package dev.local.mihotspot.airdrop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 事件契约测试：钉住 Kotlin 侧对 `native/airdrop/PROGRESS.md` 的解读。
 *
 * 为什么值得单独写：Rust 侧和后端事件名/字段名是**跨语言契约**，改名不会有任何编译错误，
 * 只会在真机上表现为"界面永远停在准备中"。这些测试用的就是文档里的样例行，
 * 一旦哪边改了字段，这里先炸。
 */
class AirDropEventsTest {

    @Test
    fun `parses every documented receive event`() {
        val lines = listOf(
            """{"event":"listening","name":"Marionette 小米 13","port":8771}""",
            """{"event":"ready","name":"Marionette 小米 13","port":8771}""",
            """{"event":"discover","sender":"Andrew's MacBook Pro"}""",
            """{"event":"request","token":"1761097200123-0","sender":"Andrew's MacBook Pro","total":1048576,"files":[{"name":"photo.jpg","size":1048576}]}""",
            """{"event":"decision","token":"1761097200123-0","accepted":true}""",
            """{"event":"receiving","total":0}""",
            """{"event":"progress","received":65536,"total":0}""",
            """{"event":"received","dir":"/sdcard/AirDrop","total":1048576,"files":[{"name":"photo.jpg","size":1048576,"path":"/sdcard/AirDrop/photo.jpg"}]}""",
            """{"event":"error","message":"boom"}"""
        )
        val kinds = lines.map { AirDropEvents.parse(it)!!.getString("event") }
        assertEquals(
            listOf(
                "listening", "ready", "discover", "request", "decision", "receiving", "progress",
                "received", "error"
            ),
            kinds
        )
    }

    @Test
    fun `parses every documented send event`() {
        val lines = listOf(
            """{"event":"browsing","duration":5}""",
            """{"event":"peer","name":"Andrew's MacBook Pro._airdrop._tcp.local.","address":"fe80::1c2d:3e4f:5a6b:7c8d","port":8771}""",
            """{"event":"stage","stage":"upload"}""",
            """{"event":"progress","sent":262144,"total":1049000}""",
            """{"event":"sent","bytes":1048576,"peer":"Andrew's MacBook Pro"}""",
            """{"event":"lost","name":"Andrew's MacBook Pro._airdrop._tcp.local."}"""
        )
        val kinds = lines.map { AirDropEvents.parse(it)!!.getString("event") }
        assertEquals(listOf("browsing", "peer", "stage", "progress", "sent", "lost"), kinds)
    }

    @Test
    fun `ignores log lines and half written json`() {
        // stdout 里只应该有 JSON，但进程被杀时最后一行可能只有一半；读取循环必须能跳过。
        assertNull(AirDropEvents.parse("Send complete."))
        assertNull(AirDropEvents.parse(""))
        assertNull(AirDropEvents.parse("""{"event":"progress","rece"""))
        assertNull(AirDropEvents.parse("  "))
        // 前后空白是允许的（readLine 之后可能带 \r）。
        assertNotNull(AirDropEvents.parse("""{"event":"ready","name":"x","port":1}""" + "\r"))
    }

    @Test
    fun `approval carries per file metadata and falls back on a blank sender`() {
        val json = AirDropEvents.parse(
            """{"event":"request","token":"T","sender":"","total":300,"files":[{"name":"a.jpg","size":100},{"name":"b.mov","size":200}]}"""
        )!!
        val approval = AirDropEvents.approval(json)
        assertEquals("T", approval.token)
        assertEquals("附近设备", approval.sender)
        assertEquals(300, approval.total)
        assertEquals(2, approval.files.size)
        assertEquals("a.jpg", approval.files[0].name)
        assertEquals(100, approval.files[0].size)
        assertEquals("b.mov", approval.files[1].name)
    }

    @Test
    fun `approval tolerates a missing files array`() {
        // 后端不会发这种，但 UI 不能因为一个畸形事件崩掉。
        val json = AirDropEvents.parse("""{"event":"request","token":"T","sender":"Mac"}""")!!
        val approval = AirDropEvents.approval(json)
        assertTrue(approval.files.isEmpty())
        assertEquals(0, approval.total)
    }

    @Test
    fun `received files keep the real on disk path`() {
        // 重名文件会被后端改名成 `1-x.jpg`；界面打开必须用 path 而不是自己拼名字。
        val json = AirDropEvents.parse(
            """{"event":"received","dir":"/d","total":5,"files":[{"name":"x.jpg","size":5,"path":"/d/1-x.jpg"}]}"""
        )!!
        val files = AirDropEvents.receivedFiles(json)
        assertEquals(1, files.size)
        assertEquals("x.jpg", files[0].name)
        assertEquals("/d/1-x.jpg", files[0].path)
    }

    @Test
    fun `progress fraction is null for an unknown total`() {
        // 苹果固定用 chunked 上传，没有总长 —— 此时只能显示不确定进度条。
        assertNull(AirDropEvents.Progress(1024, 0).fraction)
        assertTrue(AirDropEvents.Progress(1024, 0).indeterminate)
        assertEquals(0.5f, AirDropEvents.Progress(512, 1024).fraction!!, 0.001f)
        assertFalse(AirDropEvents.Progress(512, 1024).indeterminate)
    }

    @Test
    fun `progress fraction clamps a lying sender`() {
        assertEquals(1f, AirDropEvents.Progress(2048, 1024).fraction!!, 0.0001f)
        assertEquals(0f, AirDropEvents.Progress(0, 1024).fraction!!, 0.0001f)
    }

    @Test
    fun `peer display name drops the mdns suffix`() {
        val peer = AirDropEvents.Peer("Andrew's MacBook Pro._airdrop._tcp.local.", "fe80::1", 8771)
        assertEquals("Andrew's MacBook Pro", peer.displayName)
        // 名字里没有服务后缀时原样显示，不能变成空串。
        assertEquals("odd name", AirDropEvents.Peer("odd name", "fe80::2", 8771).displayName)
    }

    @Test
    fun `event names match the documented contract`() {
        // 这些常量是 Rust 侧字符串的另一半；改一个就得同步改 PROGRESS.md。
        assertEquals("listening", AirDropEvents.LISTENING)
        assertEquals("ready", AirDropEvents.READY)
        assertEquals("discover", AirDropEvents.DISCOVER)
        assertEquals("request", AirDropEvents.REQUEST)
        assertEquals("decision", AirDropEvents.DECISION)
        assertEquals("receiving", AirDropEvents.RECEIVING)
        assertEquals("progress", AirDropEvents.PROGRESS)
        assertEquals("received", AirDropEvents.RECEIVED)
        assertEquals("browsing", AirDropEvents.BROWSING)
        assertEquals("peer", AirDropEvents.PEER)
        assertEquals("lost", AirDropEvents.LOST)
        assertEquals("stage", AirDropEvents.STAGE)
        assertEquals("sent", AirDropEvents.SENT)
        assertEquals("error", AirDropEvents.ERROR)
    }
}
