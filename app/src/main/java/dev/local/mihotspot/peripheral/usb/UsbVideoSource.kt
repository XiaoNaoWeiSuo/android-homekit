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
        // The UVC function is MJPEG 1280x720 (uncompressed 720p exceeds the
        // ~24.6 MB/s this high-speed port can carry), so sources are captured
        // at that size and always emitted as JPEG.
        private const val UVC_WIDTH = 1280
        private const val UVC_HEIGHT = 720
        private const val CAMERA_WIDTH = 1280
        private const val CAMERA_HEIGHT = 720
        // The panel is 20:9; mirror it at the same aspect and letterbox to 16:9.
        private const val SCREEN_WIDTH = 1280
        private const val SCREEN_HEIGHT = 576
        private const val JPEG_QUALITY = 85
        private const val FRAME_INTERVAL_NANOS = 1_000_000_000L / 30L
        private const val DEFAULT_SCREEN_DPI = 320
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
        releaseAllOnWorker()
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

        val (cameraId, targetSize) = try {
            val id = findCameraId(manager, facing)
            if (id == null) {
                fail(
                    gen,
                    if (facing == CameraMetadata.LENS_FACING_FRONT) "未找到前置摄像头"
                    else "未找到后置摄像头"
                )
                return
            }
            val map = manager.getCameraCharacteristics(id)
                .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            id to chooseSize(map)
        } catch (e: Exception) {
            fail(gen, "枚举相机失败: ${e.message ?: e.javaClass.simpleName}")
            return
        }

        val reader = try {
            ImageReader.newInstance(
                targetSize.width,
                targetSize.height,
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

        try {
            manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(device: CameraDevice) {
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
                    if (gen == generation.get()) {
                        if (cameraDevice === device) cameraDevice = null
                        fail(gen, "相机错误 ($error)")
                    }
                }
            }, handler)
        } catch (e: SecurityException) {
            fail(gen, "没有相机权限")
        } catch (e: Exception) {
            fail(gen, "打开相机失败: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    private fun findCameraId(manager: CameraManager, facing: Int): String? {
        for (id in manager.cameraIdList) {
            val idFacing = manager.getCameraCharacteristics(id)
                .get(CameraCharacteristics.LENS_FACING)
            if (idFacing == facing) return id
        }
        return null
    }

    private fun chooseSize(map: StreamConfigurationMap?): Size {
        val sizes = map?.getOutputSizes(ImageFormat.YUV_420_888)?.toList().orEmpty()
        val evenSizes = sizes.filter { it.width % 2 == 0 && it.height % 2 == 0 }
        val candidates = if (evenSizes.isNotEmpty()) evenSizes else sizes
        if (candidates.isEmpty()) return Size(CAMERA_WIDTH, CAMERA_HEIGHT)

        return candidates.minByOrNull {
            abs(it.width - CAMERA_WIDTH) + abs(it.height - CAMERA_HEIGHT)
        } ?: Size(CAMERA_WIDTH, CAMERA_HEIGHT)
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
                        try {
                            val request = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                            request.addTarget(reader.surface)
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

            val now = SystemClock.elapsedRealtimeNanos()
            if (now - lastFrameNanos < FRAME_INTERVAL_NANOS) return
            lastFrameNanos = now

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
            val jpeg = nv21ToJpeg(nv21, width, height)
            emitFrame(jpeg, UVC_WIDTH, UVC_HEIGHT, FORMAT_JPEG, pts)
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
            emitFrame(letterboxJpeg(bmp), UVC_WIDTH, UVC_HEIGHT, FORMAT_JPEG, now)
        } finally {
            image.close()
        }
    }

    /** Camera frames arrive as NV21; UVC wants a JPEG at exactly 1280x720. */
    private fun nv21ToJpeg(nv21: ByteArray, width: Int, height: Int): ByteArray {
        val yuv = YuvImage(nv21, ImageFormat.NV21, width, height, null)
        val out = ByteArrayOutputStream()
        yuv.compressToJpeg(Rect(0, 0, width, height), JPEG_QUALITY, out)
        val jpeg = out.toByteArray()
        if (width == UVC_WIDTH && height == UVC_HEIGHT) return jpeg
        val bmp = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size) ?: return jpeg
        return letterboxJpeg(bmp)
    }

    /** Fits `src` into 1280x720 preserving aspect, padding with black. */
    private fun letterboxJpeg(src: Bitmap): ByteArray {
        val out = Bitmap.createBitmap(UVC_WIDTH, UVC_HEIGHT, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(Color.BLACK)
        val scale = minOf(
            UVC_WIDTH.toFloat() / src.width,
            UVC_HEIGHT.toFloat() / src.height
        )
        val w = (src.width * scale).toInt().coerceAtLeast(2)
        val h = (src.height * scale).toInt().coerceAtLeast(2)
        val left = (UVC_WIDTH - w) / 2
        val top = (UVC_HEIGHT - h) / 2
        canvas.drawBitmap(src, null, Rect(left, top, left + w, top + h), null)
        val baos = ByteArrayOutputStream()
        out.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, baos)
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
        failedGeneration = gen
        running = false
        releaseAllOnWorker()
        reportError(message)
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
