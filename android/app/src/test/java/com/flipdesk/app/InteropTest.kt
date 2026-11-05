package com.flipdesk.app

import com.flipdesk.app.net.PairingInfo
import com.flipdesk.app.net.PinnedClient
import com.flipdesk.app.net.Protocol
import com.flipdesk.app.net.VideoHeader
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Talks to the real Python server using the app's own TLS pinning + protocol code.
 * Opt-in: start `python server/tests/interop_server.py <dir>` and set FLIPDESK_INTEROP_DIR=<dir>.
 */
class InteropTest {
    private class Client(info: PairingInfo, fp: String = info.fingerprint) : WebSocketListener() {
        val inbox = LinkedBlockingQueue<Any>()
        val ws: WebSocket = PinnedClient.forFingerprint(fp)
            .newWebSocket(Request.Builder().url("wss://${info.hosts[0]}:${info.port}/").build(), this)

        override fun onMessage(webSocket: WebSocket, text: String) { inbox.put(JSONObject(text)) }
        override fun onMessage(webSocket: WebSocket, bytes: ByteString) { inbox.put(bytes.toByteArray()) }
        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { inbox.put(t) }
        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { inbox.put("closed:$code") }

        fun next(type: String? = null, timeoutS: Long = 5): Any {
            while (true) {
                val m = inbox.poll(timeoutS, TimeUnit.SECONDS) ?: error("timeout waiting for ${type ?: "message"}")
                if (type == null) return m
                if (type == "binary" && m is ByteArray) return m
                if (m is JSONObject && m.optString("t") == type) return m
                if (m is Throwable || (m is String && m.startsWith("closed"))) error("connection ended: $m")
            }
        }

        fun send(type: String, vararg f: Pair<String, Any?>) = ws.send(Protocol.msg(type, *f))
    }

    @Test
    fun endToEndAgainstPythonServer() {
        val dir = System.getenv("FLIPDESK_INTEROP_DIR")
        assumeTrue("set FLIPDESK_INTEROP_DIR to run", dir != null)
        val info = PairingInfo.parse(File(dir!!, "qr.txt").readText())!!

        // 1. Wrong fingerprint must be refused by our pinning trust manager.
        val evil = Client(info, fp = "00".repeat(32))
        assertTrue(evil.next() is javax.net.ssl.SSLException)

        // 2. Pair with the QR secret.
        val c = Client(info)
        val deviceId = "interop-" + System.nanoTime()
        c.send("pair", "v" to 1, "device_id" to deviceId, "device_name" to "JVM Flip", "secret" to info.secret)
        val paired = c.next("paired") as JSONObject
        val token = paired.getString("token")
        val welcome = c.next("auth_ok") as JSONObject
        assertTrue(welcome.getJSONArray("launchers").length() > 0)

        // 3. Input + ping.
        c.send("mouse_move", "dx" to 5, "dy" to -3)
        c.send("mouse_button", "button" to "left", "action" to "click")
        c.send("key", "key" to "c", "mods" to listOf("ctrl"))
        c.send("scroll", "dx" to 0.0, "dy" to 1.5)
        c.send("ping", "ts" to 123L)
        assertEquals(123L, (c.next("pong") as JSONObject).getLong("ts"))

        // 4. A video frame parses with our header reader, then ack it.
        c.send("stream_start", "monitor" to 0, "max_w" to 800, "max_h" to 800, "fps" to 30, "quality" to 60)
        val frame = c.next("binary") as ByteArray
        val h = VideoHeader.parse(frame)
        assertNotNull(h)
        assertEquals(1920, h!!.monW)
        assertEquals(0xFF.toByte(), frame[Protocol.VIDEO_HEADER_SIZE])
        c.send("frame_ack", "seq" to h.seq)

        // 4b. Zoomed region at native resolution, decoded with our region-aware header parser.
        c.send("stream_region", "x" to 400, "y" to 300, "w" to 640, "h" to 360, "max_w" to 1280, "max_h" to 720)
        var rh: VideoHeader
        do {
            val f = c.next("binary") as ByteArray
            rh = VideoHeader.parse(f)!!
            c.send("frame_ack", "seq" to rh.seq)
        } while (rh.region == null)
        assertEquals(com.flipdesk.app.net.StreamRegion(400, 300, 640, 360), rh.region)
        assertEquals(24, rh.jpegOffset)
        c.send("stream_stop")

        // 5. File chunk framing.
        val data = ByteArray(300_000) { (it % 251).toByte() }
        c.send("file_begin", "id" to 9, "name" to "interop.bin", "size" to data.size)
        c.next("file_ready")
        c.ws.send(ByteString.of(*Protocol.fileChunk(9, data)))
        c.send("file_end", "id" to 9)
        assertEquals("interop.bin", (c.next("file_saved") as JSONObject).getString("name"))
        c.ws.close(1000, null)

        // 6. Reconnect with the stored token.
        val again = Client(info)
        again.send("hello", "v" to 1, "device_id" to deviceId, "token" to token)
        assertEquals("auth_ok", (again.next() as JSONObject).getString("t"))
        again.ws.close(1000, null)

        // 7. A bad token is rejected.
        val bad = Client(info)
        bad.send("hello", "v" to 1, "device_id" to deviceId, "token" to "nope")
        assertEquals("auth_fail", (bad.next() as JSONObject).getString("t"))
    }
}
