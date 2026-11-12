package dev.local.mihotspot.peripheral.usb

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageFormat
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.StreamConfigurationMap
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import android.util.Range
import android.util.Size
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs
import kotlin.math.min

/**
 * Produces raw video payloads for the MRV1 USB video stream described in
 * `docs/USB_PERIPHERAL.md`.
 *
 * MRV1 puts a header in front of every payload:
 * `"MRV1"`, width (u16 LE), height (u16 LE), format (u8), flags (u8),
 * length (u32 LE), pts (u64 LE), then the payload. This class supplies only
 * the payload, dimensions, format byte and pts.
 *
 * [onFrame] is invoked on the internal capture [HandlerThread]. Every payload
 * is a freshly allocated `ByteArray`, so it stays valid after the callback
 * returns and may be queued for asynchronous delivery.
 */
class UsbVideoSource(
    private val context: Context,
    private val onFrame: (data: ByteArray, width: Int, height: Int, format: Int, ptsNanos: Long) -> Unit
) {

    enum class Source { FRONT, BACK, SCREEN }

    companion object {
        /** MRV1 `format` byte: NV21 (full Y plane followed by interleaved VU). */
        const val FORMAT_NV21 = 0

        /** MRV1 `format` byte: tightly packed RGBA8888, four bytes per pixel. */
        const val FORMAT_RGBA8888 = 1

        /** MRV1 `format` byte: a complete JPEG, forwarded to UVC unchanged. */
        const val FORMAT_JPEG = 2

        private const val TAG = "UsbVideoSource"
        // The UVC gadget advertises an MJPEG ladder (see assets/usb/start.sh);
        // the daemon reports the rung the host committed and the source encodes
        // to exactly that size. Until that arrives, portrait 1080p is assumed:
        // the phone is normally held upright, and a landscape canvas would show
        // the scene rotated by the sensor orientation.
        private const val DEFAULT_WIDTH = 1080
        private const val DEFAULT_HEIGHT = 1920
        // Mirror at the panel's own aspect; MediaProjection would otherwise
        // rotate a portrait screen into a landscape surface.
        private const val SCREEN_WIDTH = 1080
        private const val SCREEN_HEIGHT = 2400
        private const val JPEG_QUALITY_START = 85
        private const val JPEG_QUALITY_MIN = 55
        private const val JPEG_QUALITY_MAX = 88
        private const val FRAME_INTERVAL_NANOS = 1_000_000_000L / 30L
        /* The camera delivers on a 30 fps clock with jitter, so a strict 33.3 ms
         * gate rejects every frame that lands a millisecond early and the stream
         * settles at every second frame (~20 fps). Allow 25% early. */
        private const val MIN_FRAME_GAP_NANOS = FRAME_INTERVAL_NANOS * 3 / 4
        private const val DEFAULT_SCREEN_DPI = 320
        /* How long to wait before retrying a camera open that failed because
         * another session had not finished letting go of it. */
        private const val CAMERA_RETRY_DELAY_MILLIS = 400L
    }

    /** Receives fatal capture errors. Invoked on the internal capture thread. */
    @Volatile
    var onError: ((String) -> Unit)? = null

    /** True between a successful start request and [stop] or a fatal error. */
    @Volatile
    private var running = false

    val isRunning: Boolean
        get() = running

    private val lock = Any()
    private val generation = AtomicLong(0L)

    private var failedGeneration = -1L
    private var thread: HandlerThread? = null
    private var worker: Handler? = null

    // The fields below are only touched on the worker thread.
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var cameraReader: ImageReader? = null
    private var mediaProjection: MediaProjection? = null
    private var projectionCallback: MediaProjection.Callback? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var screenReader: ImageReader? = null
    private var lastFrameNanos = 0L

    /* Frame size the host committed, and the capture rotation it implies. */
    @Volatile private var targetWidth = DEFAULT_WIDTH
    @Volatile private var targetHeight = DEFAULT_HEIGHT
    private var sensorOrientation = 90
    private var activeSource: Source? = null
    /* Pinned to 30 fps: the default 15-30 range lets the AE settle at 15. */
    private var fpsRange: Range<Int>? = null

    /* Compression ladder: quality follows the measured encode cost so the
     * stream can hold 30 fps instead of dropping frames. */
    private var jpegQuality = JPEG_QUALITY_START
    private var statsSince = 0L
    private var statsFrames = 0
    private var statsCostMs = 0L
    private var statsBytes = 0L
    /* Diagnostics: how fast the camera actually hands frames over. */
    private var lastArrivalNanos = 0L
    private var arrivalLogs = 0
    /* Switch diagnostics: elapsed time of the start request that is running,
     * plus the first-frame counters used to show where a source change slows
     * down (release, open, configure, first payload). */
    private var startAtMillis = 0L
    private var firstFrameLogged = false
    @Volatile private var lastLuma = 0

    /**
     * Applies the frame size the host committed in the UVC handshake. A camera
     * capture size is chosen when the source starts, so a change restarts it.
     */
    fun setTargetSize(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        val restart: Boolean
        synchronized(lock) {
            if (width == targetWidth && height == targetHeight) return
            targetWidth = width
            targetHeight = height
            restart = activeSource != null
        }
        if (!restart) return
        val handler = synchronized(lock) { worker } ?: return
        handler.post {
            val source = activeSource ?: return@post
            val gen = generation.incrementAndGet()
            Log.i(TAG, "capture size changed, restarting $source at ${targetWidth}x${targetHeight}")
            lastFrameNanos = 0L
            startCameraOnWorker(source, gen)
        }
    }

    /**
     * Starts (or switches to) the front or back camera.
     *
     * [Source.SCREEN] is not valid here because it requires the result of the
     * MediaProjection consent flow; use [startScreen] instead.
     */
    fun startCamera(source: Source) {
        if (source == Source.SCREEN) {
            val handler = synchronized(lock) { ensureWorkerLocked() }
            handler.post {
                reportError("SCREEN 源需要通过 startScreen(resultCode, data) 启动")
            }
            return
        }

        val (handler, gen) = synchronized(lock) {
            val workerHandler = ensureWorkerLocked()
            running = true
            workerHandler to generation.incrementAndGet()
        }

        handler.post {
            if (gen != generation.get()) return@post
            startCameraOnWorker(source, gen)
        }
    }

    /**
     * Starts screen capture using the result of
     * `MediaProjectionManager.createScreenCaptureIntent()`.
     */
    fun startScreen(resultCode: Int, data: Intent) {
        val (handler, gen) = synchronized(lock) {
            val workerHandler = ensureWorkerLocked()
            running = true
            workerHandler to generation.incrementAndGet()
        }

        handler.post {
            if (gen != generation.get()) return@post
            startScreenOnWorker(resultCode, data, gen)
        }
    }

    /** Stops capture. Safe to call repeatedly and when nothing is running. */
    fun stop() {
        val handler = synchronized(lock) {
            running = false
            generation.incrementAndGet()
            worker
        }
        handler?.post { releaseAllOnWorker() }
    }

    private fun ensureWorkerLocked(): Handler {
        val currentThread = thread
        if (currentThread != null && currentThread.isAlive) {
            val currentHandler = worker
            if (currentHandler != null) return currentHandler
        }

        val newThread = HandlerThread("UsbVideoSource")
        newThread.start()
        thread = newThread
        return Handler(newThread.looper).also { worker = it }
    }

    private fun requireWorkerHandler(): Handler =
        checkNotNull(worker) { "UsbVideoSource worker handler is missing" }

    private fun startCameraOnWorker(source: Source, gen: Long) {
        startAtMillis = SystemClock.elapsedRealtime()
        firstFrameLogged = false
        lastLuma = 0
        Log.i(TAG, "USB_CAMERA_START source=$source gen=$gen target=${targetWidth}x${targetHeight}")
        releaseAllOnWorker()
        Log.i(TAG, "USB_CAMERA_RELEASED after ${sinceStart()}ms")
        if (gen != generation.get()) return
        lastFrameNanos = 0L

        val manager = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
        if (manager == null) {
            fail(gen, "相机服务不可用")
            return
        }

        val facing =
            if (source == Source.FRONT) CameraMetadata.LENS_FACING_FRONT
            else CameraMetadata.LENS_FACING_BACK

        val (cameraId, captureSize) = try {
            val id = findCameraId(manager, facing)
            if (id == null) {
                fail(
                    gen,
                    if (facing == CameraMetadata.LENS_FACING_FRONT) "未找到前置摄像头"
                    else "未找到后置摄像头"
                )
                return
            }
            val characteristics = manager.getCameraCharacteristics(id)
            sensorOrientation = characteristics
                .get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
            val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            // Ask the camera for a steady 30 fps. Without a pinned range the
            // default 15-30 range lets auto exposure sit at 15 in dim light,
            // which is what made the camera side of the stream look slow.
            fpsRange = characteristics
                .get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
                ?.filter { it.upper == 30 }
                ?.maxByOrNull { it.lower }
                ?: Range(30, 30)
            // Sensors hand out landscape buffers; capture the size whose swap
            // matches the portrait canvas the host asked for.
            val portrait = targetHeight > targetWidth
            val wantW = if (portrait) targetHeight else targetWidth
            val wantH = if (portrait) targetWidth else targetHeight
            id to chooseSize(map, wantW, wantH)
        } catch (e: Exception) {
            fail(gen, "枚举相机失败: ${e.message ?: e.javaClass.simpleName}")
            return
        }
        activeSource = source
        Log.i(
            TAG,
            "camera $cameraId capture ${captureSize.width}x${captureSize.height} " +
                "rotation=$sensorOrientation fps=${fpsRange?.lower}-${fpsRange?.upper} " +
                "target ${targetWidth}x${targetHeight}"
        )
        arrivalLogs = 0
        lastArrivalNanos = 0L

        val reader = try {
            ImageReader.newInstance(
                captureSize.width,
                captureSize.height,
                ImageFormat.YUV_420_888,
                2
            )
        } catch (e: Exception) {
            fail(gen, "创建相机帧缓冲失败: ${e.message ?: e.javaClass.simpleName}")
            return
        }
        cameraReader = reader

        val handler = requireWorkerHandler()
        reader.setOnImageAvailableListener({ r -> onCameraImage(r, gen) }, handler)

        /*
         * Opening can fail with "camera in use" when the previous session's
         * release has not landed yet (a rapid switch, or a stale session left
         * behind by an earlier crash). One delayed retry recovers that state;
         * without it the host keeps the last frame and the picture looks
         * frozen until the app is restarted.
         */
        fun open(attempt: Int) {
            if (gen != generation.get()) return
            val callback = object : CameraDevice.StateCallback() {
                override fun onOpened(device: CameraDevice) {
                    Log.i(TAG, "USB_CAMERA_OPENED after ${sinceStart()}ms")
                    if (gen != generation.get()) {
                        device.close()
                        return
                    }
                    cameraDevice = device
                    createCameraSession(device, reader, gen)
                }

                override fun onDisconnected(device: CameraDevice) {
                    device.close()
                    if (gen == generation.get()) {
                        if (cameraDevice === device) cameraDevice = null
                        fail(gen, "相机连接已断开")
                    }
                }

                override fun onError(device: CameraDevice, error: Int) {
                    device.close()
                    if (gen != generation.get()) return
                    if (cameraDevice === device) cameraDevice = null
                    val busy = error == ERROR_CAMERA_IN_USE || error == ERROR_MAX_CAMERAS_IN_USE
                    if (busy && attempt == 0) {
                        retryOpen(handler, gen, "busy($error)") { open(1) }
                        return
                    }
                    fail(gen, "相机错误 ($error)")
                }
            }

            try {
                manager.openCamera(cameraId, callback, handler)
            } catch (e: SecurityException) {
                fail(gen, "没有相机权限")
            } catch (e: Exception) {
                if (attempt == 0) {
                    retryOpen(handler, gen, e.javaClass.simpleName) { open(1) }
                } else {
                    fail(gen, "打开相机失败: ${e.message ?: e.javaClass.simpleName}")
                }
            }
        }
        open(0)
    }

    /** Retries a camera open once, after releasing whatever is still holding it. */
    private fun retryOpen(handler: Handler, gen: Long, reason: String, open: () -> Unit) {
        Log.i(TAG, "USB_CAMERA_OPEN_RETRY reason=$reason")
        handler.postDelayed(
            {
                if (gen != generation.get()) return@postDelayed
                releaseCameraOnWorker()
                open()
            },
            CAMERA_RETRY_DELAY_MILLIS
        )
    }

    private fun findCameraId(manager: CameraManager, facing: Int): String? {
        for (id in manager.cameraIdList) {
            val idFacing = manager.getCameraCharacteristics(id)
                .get(CameraCharacteristics.LENS_FACING)
            if (idFacing == facing) return id
        }
        return null
    }

    private fun chooseSize(map: StreamConfigurationMap?, wantW: Int, wantH: Int): Size {
        val sizes = map?.getOutputSizes(ImageFormat.YUV_420_888)?.toList().orEmpty()
        val evenSizes = sizes.filter { it.width % 2 == 0 && it.height % 2 == 0 }
        val candidates = if (evenSizes.isNotEmpty()) evenSizes else sizes
        if (candidates.isEmpty()) return Size(wantW, wantH)

        // Prefer the same aspect ratio, then the closest resolution: a 9:16
        // canvas must not be fed from a 4:3 sensor mode.
        val wantAspect = wantW.toDouble() / wantH
        val sameAspect = candidates.filter {
            abs(it.width.toDouble() / it.height - wantAspect) < 0.02 * wantAspect
        }
        val pool = if (sameAspect.isNotEmpty()) sameAspect else candidates
        return pool.minByOrNull {
            abs(it.width - wantW) + abs(it.height - wantH)
        } ?: Size(wantW, wantH)
    }

    @Suppress("DEPRECATION")
    private fun createCameraSession(device: CameraDevice, reader: ImageReader, gen: Long) {
        val handler = requireWorkerHandler()
        try {
            device.createCaptureSession(
                listOf(reader.surface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        if (gen != generation.get() || cameraDevice !== device) {
                            runCatching { session.close() }
                            return
                        }
                        captureSession = session
                        Log.i(TAG, "USB_CAMERA_SESSION_READY after ${sinceStart()}ms")
                        try {
                            val request = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                            request.addTarget(reader.surface)
                            fpsRange?.let {
                                request.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it)
                            }
                            session.setRepeatingRequest(request.build(), null, handler)
                        } catch (e: Exception) {
                            fail(gen, "启动相机预览失败: ${e.message ?: e.javaClass.simpleName}")
                        }
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        runCatching { session.close() }
                        fail(gen, "相机会话配置失败")
                    }
                },
                handler
            )
        } catch (e: Exception) {
            fail(gen, "创建相机会话失败: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    private fun onCameraImage(reader: ImageReader, gen: Long) {
        val image = try {
            reader.acquireLatestImage()
        } catch (e: Exception) {
            return
        } ?: return

        try {
            if (gen != generation.get()) return

            val arrival = SystemClock.elapsedRealtimeNanos()
            if (arrivalLogs < 12) {
                val dt = if (lastArrivalNanos == 0L) 0L else
                    (arrival - lastArrivalNanos) / 1_000_000
                Log.i(TAG, "USB_VIDEO_ARRIVAL dt=${dt}ms ${image.width}x${image.height}")
                arrivalLogs++
            }
            lastArrivalNanos = arrival

            if (!firstFrameLogged) {
                firstFrameLogged = true
                Log.i(TAG, "USB_CAMERA_FRAME1 after ${sinceStart()}ms luma=${meanLuma(image)}")
            }

            val now = arrival
            if (now - lastFrameNanos < MIN_FRAME_GAP_NANOS) return
            lastFrameNanos = now
            /* Steady-state brightness, reported once a second with the stats. A
             * source that keeps sending frames but stays dark (auto exposure
             * still settling, or a covered lens) is visible here. */
            lastLuma = meanLuma(image)

            val width = image.width
            val height = image.height
            val nv21 = try {
                yuv420ToNv21(image, width, height)
            } catch (e: Exception) {
                Log.e(TAG, "NV21 conversion failed", e)
                return
            }

            if (gen != generation.get()) return
            val pts = image.timestamp.takeIf { it > 0L } ?: now
            val tw = targetWidth
            val th = targetHeight
            val started = SystemClock.elapsedRealtime()
            val jpeg = cameraJpeg(nv21, width, height, tw, th)
            val cost = (SystemClock.elapsedRealtime() - started).toInt()
            adaptQuality(cost)
            emitFrame(jpeg, tw, th, FORMAT_JPEG, pts)
            recordStats(jpeg.size, cost)
        } finally {
            image.close()
        }
    }

    private fun startScreenOnWorker(resultCode: Int, data: Intent, gen: Long) {
        releaseAllOnWorker()
        if (gen != generation.get()) return
        lastFrameNanos = 0L

        if (resultCode != Activity.RESULT_OK) {
            fail(gen, "屏幕采集授权无效 (resultCode=$resultCode)")
            return
        }

        val manager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE)
            as? MediaProjectionManager
        if (manager == null) {
            fail(gen, "屏幕采集服务不可用")
            return
        }

        val projection = try {
            manager.getMediaProjection(resultCode, data)
        } catch (e: Exception) {
            fail(gen, "获取屏幕采集授权失败: ${e.message ?: e.javaClass.simpleName}")
            return
        } ?: run {
            fail(gen, "获取屏幕采集授权失败")
            return
        }

        val callback = object : MediaProjection.Callback() {
            override fun onStop() {
                if (gen != generation.get()) return
                if (mediaProjection !== projection) return
                running = false
                releaseScreenOnWorker()
            }
        }

        mediaProjection = projection
        projectionCallback = callback
        try {
            projection.registerCallback(callback, requireWorkerHandler())
        } catch (e: Exception) {
            fail(gen, "注册屏幕采集回调失败: ${e.message ?: e.javaClass.simpleName}")
            return
        }

        val reader = try {
            ImageReader.newInstance(
                SCREEN_WIDTH,
                SCREEN_HEIGHT,
                PixelFormat.RGBA_8888,
                2
            )
        } catch (e: Exception) {
            fail(gen, "创建屏幕帧缓冲失败: ${e.message ?: e.javaClass.simpleName}")
            return
        }
        screenReader = reader

        val handler = requireWorkerHandler()
        reader.setOnImageAvailableListener({ r -> onScreenImage(r, gen) }, handler)

        val display = try {
            projection.createVirtualDisplay(
                "UsbVideoSource",
                SCREEN_WIDTH,
                SCREEN_HEIGHT,
                screenDensityDpi(),
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.surface,
                null,
                handler
            )
        } catch (e: Exception) {
            fail(gen, "创建虚拟屏幕失败: ${e.message ?: e.javaClass.simpleName}")
            return
        }

        if (display == null) {
            fail(gen, "创建虚拟屏幕失败")
            return
        }
        virtualDisplay = display
        activeSource = Source.SCREEN
    }

    private fun screenDensityDpi(): Int {
        val dpi = context.resources.displayMetrics.densityDpi
        return if (dpi > 0) dpi else DEFAULT_SCREEN_DPI
    }

    private fun onScreenImage(reader: ImageReader, gen: Long) {
        val image = try {
            reader.acquireLatestImage()
        } catch (e: Exception) {
            return
        } ?: return

        try {
            if (gen != generation.get()) return

            val now = SystemClock.elapsedRealtimeNanos()
            if (now - lastFrameNanos < FRAME_INTERVAL_NANOS) return
            lastFrameNanos = now

            val width = image.width
            val height = image.height
            val plane = image.planes.firstOrNull() ?: return
            val rgba = copyRgba(plane, width, height)

            if (gen != generation.get()) return
            val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            bmp.copyPixelsFromBuffer(ByteBuffer.wrap(rgba))
            val tw = targetWidth
            val th = targetHeight
            emitFrame(letterboxJpeg(bmp, tw, th), tw, th, FORMAT_JPEG, now)
        } finally {
            image.close()
        }
    }

    /**
     * Rotates the sensor buffer upright and encodes it at `tw`x`th`.
     *
     * A portrait canvas needs `SENSOR_ORIENTATION` degrees of clockwise
     * rotation; a landscape canvas needs that minus 90, because the sensor's
     * native orientation is already landscape. Getting this wrong is what made
     * both cameras look like a landscape picture tilted to the right.
     */
    private fun cameraJpeg(
        nv21: ByteArray,
        width: Int,
        height: Int,
        tw: Int,
        th: Int
    ): ByteArray {
        val degrees = if (th > tw) sensorOrientation else (sensorOrientation - 90 + 360) % 360
        val (src, sw, sh) = rotateNv21(nv21, width, height, degrees)
        val yuv = YuvImage(src, ImageFormat.NV21, sw, sh, null)
        val out = ByteArrayOutputStream()
        yuv.compressToJpeg(Rect(0, 0, sw, sh), jpegQuality, out)
        val jpeg = out.toByteArray()
        if (sw == tw && sh == th) return jpeg
        val bmp = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size) ?: return jpeg
        return letterboxJpeg(bmp, tw, th)
    }

    private fun rotateNv21(
        src: ByteArray,
        width: Int,
        height: Int,
        degrees: Int
    ): Triple<ByteArray, Int, Int> = when (degrees) {
        90 -> Triple(rotateNv21Cw(src, width, height, 1), height, width)
        270 -> Triple(rotateNv21Cw(src, width, height, -1), height, width)
        180 -> Triple(rotateNv21Cw(src, width, height, 2), width, height)
        else -> Triple(src, width, height)
    }

    /**
     * Rotates NV21 in place-free fashion. `turns` is clockwise quarter turns:
     * 1 = 90 CW, -1 = 90 CCW, 2 = 180. The destination is only allocated when
     * a rotation is actually needed, and the chroma plane is copied in VU pairs
     * so the interleaving stays intact.
     */
    private fun rotateNv21Cw(src: ByteArray, width: Int, height: Int, turns: Int): ByteArray {
        val out = ByteArray(src.size)
        val lumaBytes = width * height
        val dstW = if (turns == 2) width else height
        val dstH = if (turns == 2) height else width

        for (y in 0 until height) {
            val row = y * width
            for (x in 0 until width) {
                val dst = when (turns) {
                    1 -> x * dstW + (height - 1 - y)
                    -1 -> (width - 1 - x) * dstW + y
                    else -> (height - 1 - y) * dstW + (width - 1 - x)
                }
                out[dst] = src[row + x]
            }
        }

        val cw = width / 2
        val ch = height / 2
        val dstCw = dstW / 2
        for (y in 0 until ch) {
            val row = lumaBytes + y * cw * 2
            for (x in 0 until cw) {
                val dst = when (turns) {
                    1 -> lumaBytes + x * dstCw * 2 + (ch - 1 - y) * 2
                    -1 -> lumaBytes + (cw - 1 - x) * dstCw * 2 + y * 2
                    else -> lumaBytes + (ch - 1 - y) * dstCw * 2 + (cw - 1 - x) * 2
                }
                out[dst] = src[row + x * 2]
                out[dst + 1] = src[row + x * 2 + 1]
            }
        }
        return out
    }

    /** Lowers quality when encoding is too slow, raises it when there is slack. */
    private fun adaptQuality(costMs: Int) {
        when {
            costMs > 26 && jpegQuality > JPEG_QUALITY_MIN -> jpegQuality -= 5
            costMs < 12 && jpegQuality < JPEG_QUALITY_MAX -> jpegQuality += 2
        }
    }

    private fun recordStats(bytes: Int, costMs: Int) {
        val now = SystemClock.elapsedRealtime()
        if (statsSince == 0L) {
            statsSince = now
            return
        }
        statsFrames++
        statsCostMs += costMs
        statsBytes += bytes
        val elapsed = now - statsSince
        if (elapsed < 2_000) return
        val fps = statsFrames * 1_000.0 / elapsed
        val luma = lastLuma
        Log.i(
            TAG,
            "USB_VIDEO_STATS ${targetWidth}x${targetHeight} fps=${String.format("%.1f", fps)} " +
                "q=$jpegQuality avg=${statsBytes / statsFrames / 1024}KB " +
                "enc=${String.format("%.1f", statsCostMs.toDouble() / statsFrames)}ms" +
                if (luma > 0) " luma=$luma" else ""
        )
        statsSince = now
        statsFrames = 0
        statsCostMs = 0
        statsBytes = 0
    }

    /** Fits `src` into `tw`x`th` preserving aspect, padding with black. */
    private fun letterboxJpeg(src: Bitmap, tw: Int, th: Int): ByteArray {
        val out = Bitmap.createBitmap(tw, th, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(Color.BLACK)
        val scale = minOf(
            tw.toFloat() / src.width,
            th.toFloat() / src.height
        )
        val w = (src.width * scale).toInt().coerceAtLeast(2)
        val h = (src.height * scale).toInt().coerceAtLeast(2)
        val left = (tw - w) / 2
        val top = (th - h) / 2
        canvas.drawBitmap(src, null, Rect(left, top, left + w, top + h), null)
        val baos = ByteArrayOutputStream()
        out.compress(Bitmap.CompressFormat.JPEG, jpegQuality, baos)
        return baos.toByteArray()
    }

    private fun emitFrame(
        data: ByteArray,
        width: Int,
        height: Int,
        format: Int,
        ptsNanos: Long
    ) {
        try {
            onFrame(data, width, height, format, ptsNanos)
        } catch (t: Throwable) {
            Log.e(TAG, "onFrame callback threw", t)
        }
    }

    private fun yuv420ToNv21(image: Image, width: Int, height: Int): ByteArray {
        val planes = image.planes
        require(planes.size >= 3) { "YUV_420_888 image must have three planes" }

        val chromaWidth = width / 2
        val chromaHeight = height / 2
        val out = ByteArray(width * height + chromaWidth * chromaHeight * 2)

        val yPlane = planes[0]
        val yBuffer = yPlane.buffer
        val yBase = yBuffer.position()
        val yLimit = yBuffer.limit()
        val yRowBuffer = yBuffer.duplicate()
        var yOut = 0

        for (row in 0 until height) {
            val rowStart = yBase + row * yPlane.rowStride
            if (rowStart < yLimit) {
                if (yPlane.pixelStride == 1) {
                    val count = min(width, yLimit - rowStart)
                    yRowBuffer.position(rowStart)
                    yRowBuffer.get(out, yOut, count)
                } else {
                    for (col in 0 until width) {
                        val src = rowStart + col * yPlane.pixelStride
                        if (src < yLimit) out[yOut + col] = yBuffer.get(src)
                    }
                }
            }
            yOut += width
        }

        val uPlane = planes[1]
        val vPlane = planes[2]
        val uBuffer = uPlane.buffer
        val vBuffer = vPlane.buffer
        val uBase = uBuffer.position()
        val vBase = vBuffer.position()
        val uLimit = uBuffer.limit()
        val vLimit = vBuffer.limit()

        var chromaOut = width * height
        for (row in 0 until chromaHeight) {
            val uRow = uBase + row * uPlane.rowStride
            val vRow = vBase + row * vPlane.rowStride
            for (col in 0 until chromaWidth) {
                val uIndex = uRow + col * uPlane.pixelStride
                val vIndex = vRow + col * vPlane.pixelStride
                val uValue = if (uIndex < uLimit) uBuffer.get(uIndex) else 0
                val vValue = if (vIndex < vLimit) vBuffer.get(vIndex) else 0
                // NV21 chroma order is V then U.
                out[chromaOut++] = vValue
                out[chromaOut++] = uValue
            }
        }

        return out
    }

    private fun copyRgba(plane: Image.Plane, width: Int, height: Int): ByteArray {
        val out = ByteArray(width * height * 4)
        val buffer = plane.buffer
        val base = buffer.position()
        val limit = buffer.limit()
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        val rowBuffer = buffer.duplicate()

        for (row in 0 until height) {
            val rowStart = base + row * rowStride
            if (rowStart >= limit) continue
            val dstRow = row * width * 4

            if (pixelStride == 4) {
                val count = min(width * 4, limit - rowStart)
                rowBuffer.position(rowStart)
                rowBuffer.get(out, dstRow, count)
            } else {
                for (col in 0 until width) {
                    val src = rowStart + col * pixelStride
                    if (src + 3 < limit) {
                        val dst = dstRow + col * 4
                        out[dst] = buffer.get(src)
                        out[dst + 1] = buffer.get(src + 1)
                        out[dst + 2] = buffer.get(src + 2)
                        out[dst + 3] = buffer.get(src + 3)
                    }
                }
            }
        }

        return out
    }

    private fun fail(gen: Long, message: String) {
        if (gen != generation.get() || failedGeneration == gen) return
        Log.w(TAG, "USB_CAMERA_FAILED after ${sinceStart()}ms: $message")
        failedGeneration = gen
        running = false
        releaseAllOnWorker()
        reportError(message)
    }

    /** Milliseconds since the start request that is currently running. */
    private fun sinceStart(): Long =
        if (startAtMillis == 0L) -1L else SystemClock.elapsedRealtime() - startAtMillis

    /**
     * Mean luma of a captured frame, sampled rather than summed over every
     * pixel. A source that is producing frames but black (auto exposure still
     * settling) reads near 0, which separates "no frames" from "dark frames"
     * when diagnosing a slow switch.
     */
    private fun meanLuma(image: Image): Int {
        return try {
            val plane = image.planes[0]
            val buffer = plane.buffer
            val step = maxOf(1, buffer.remaining() / 4096)
            var sum = 0L
            var samples = 0
            var index = 0
            val limit = buffer.limit()
            while (index < limit) {
                sum += buffer.get(index).toInt() and 0xFF
                samples++
                index += step
            }
            if (samples == 0) -1 else (sum / samples).toInt()
        } catch (e: Exception) {
            -1
        }
    }

    private fun reportError(message: String) {
        Log.w(TAG, message)
        try {
            onError?.invoke(message)
        } catch (t: Throwable) {
            Log.e(TAG, "onError callback threw", t)
        }
    }

    private fun releaseAllOnWorker() {
        lastFrameNanos = 0L
        activeSource = null
        releaseScreenOnWorker()
        releaseCameraOnWorker()
    }

    private fun releaseCameraOnWorker() {
        val session = captureSession
        captureSession = null
        if (session != null) {
            runCatching { session.stopRepeating() }
            runCatching { session.close() }
        }

        val reader = cameraReader
        cameraReader = null
        if (reader != null) {
            reader.setOnImageAvailableListener(null, null)
            runCatching { reader.close() }
        }

        val device = cameraDevice
        cameraDevice = null
        if (device != null) {
            runCatching { device.close() }
        }
    }

    private fun releaseScreenOnWorker() {
        val display = virtualDisplay
        virtualDisplay = null
        if (display != null) {
            runCatching { display.release() }
        }

        val reader = screenReader
        screenReader = null
        if (reader != null) {
            reader.setOnImageAvailableListener(null, null)
            runCatching { reader.close() }
        }

        val projection = mediaProjection
        mediaProjection = null
        val callback = projectionCallback
        projectionCallback = null
        if (projection != null) {
            if (callback != null) {
                runCatching { projection.unregisterCallback(callback) }
            }
            runCatching { projection.stop() }
        }
    }
}
