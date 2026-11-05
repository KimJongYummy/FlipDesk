package com.flipdesk.app

import com.flipdesk.app.input.PointerAccel
import com.flipdesk.app.input.TypingDiff
import com.flipdesk.app.input.TypingEdit
import com.flipdesk.app.net.DiscoveredServer
import com.flipdesk.app.net.PairingInfo
import com.flipdesk.app.net.Protocol
import com.flipdesk.app.net.VideoHeader
import com.flipdesk.app.net.shortFingerprint
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer

class LogicTest {
    private val fp = "ab".repeat(32)

    @Test fun parsesPairingQr() {
        val p = PairingInfo.parse("flipdesk://pair?v=1&h=192.168.1.5%2C10.0.0.2&p=8765&fp=$fp&s=se_cr-et&id=abc&n=My+PC")!!
        assertEquals(listOf("192.168.1.5", "10.0.0.2"), p.hosts)
        assertEquals(8765, p.port)
        assertEquals(fp, p.fingerprint)
        assertEquals("se_cr-et", p.secret)
        assertEquals("My PC", p.name)
    }

    @Test fun rejectsBadPairingQr() {
        assertNull(PairingInfo.parse("https://evil.example/pair?h=1&p=1&fp=$fp"))
        assertNull(PairingInfo.parse("flipdesk://pair?h=1.2.3.4&p=8765&fp=nothex"))
        assertNull(PairingInfo.parse("flipdesk://pair?h=1.2.3.4&p=99999&fp=$fp"))
        assertNull(PairingInfo.parse("garbage"))
    }

    @Test fun parsesDiscoveryReply() {
        val s = DiscoveredServer.parse("""{"t":"flipdesk","v":1,"id":"x1","name":"PC","port":8765,"fp":"${fp.uppercase()}"}""", "10.0.0.9")!!
        assertEquals("10.0.0.9", s.host)
        assertEquals(fp, s.fingerprint)
        assertEquals("ABAB-ABAB", s.securityCode)
        assertNull(DiscoveredServer.parse("""{"t":"other"}""", "1.1.1.1"))
        assertEquals("1234-5678", shortFingerprint("12345678ffff"))
    }

    @Test fun parsesVideoHeader() {
        val b = ByteBuffer.allocate(20)
        b.put(1).putInt(-2).putShort(1920.toShort()).putShort(1080.toShort()).putShort((-1).toShort())
            .putShort(500).put(2).put(70).put(0).put(byteArrayOf(-1, -40, 1, 2))
        val h = VideoHeader.parse(b.array())!!
        assertEquals(4294967294L, h.seq)
        assertEquals(1920, h.monW); assertEquals(1080, h.monH)
        assertEquals(-1, h.cursorX); assertEquals(500, h.cursorY)
        assertEquals(2, h.monitor); assertEquals(70, h.quality)
        assertNull(VideoHeader.parse(byteArrayOf(2, 0, 0)))
    }

    @Test fun buildsMessagesAndChunks() {
        val o = JSONObject(Protocol.msg("key", "key" to "c", "mods" to listOf("ctrl"), "skip" to null))
        assertEquals("key", o.getString("t"))
        assertEquals("ctrl", o.getJSONArray("mods").getString(0))
        assertTrue(!o.has("skip"))
        assertArrayEquals(byteArrayOf(2, 0, 0, 1, 2, 9, 9), Protocol.fileChunk(258, byteArrayOf(9, 9, 7), 2))
    }

    @Test fun typingDiff() {
        assertEquals(TypingEdit(0, "o"), TypingDiff.diff("hell", "hello"))
        assertEquals(TypingEdit(1, ""), TypingDiff.diff("hello", "hell"))
        // autocorrect "teh" -> "the": back up over "eh", type "he"
        assertEquals(TypingEdit(2, "he"), TypingDiff.diff("teh", "the"))
        // emoji counts as one backspace
        assertEquals(TypingEdit(1, ""), TypingDiff.diff("hi😀", "hi"))
        assertTrue(TypingDiff.diff("same", "same").isEmpty)
    }

    @Test fun pointerAccelIsPreciseSlowAndFastWhenFlicked() {
        val a = PointerAccel(sensitivity = 1f, acceleration = 1f)
        val (slow, _) = a.apply(1f, 0f, 16)      // 0.06 dp/ms
        val (fast, _) = a.apply(40f, 0f, 16)     // 2.5 dp/ms
        assertEquals(PointerAccel.BASE_GAIN, slow, 0.001f)
        assertEquals(40f * PointerAccel.BASE_GAIN * 4f, fast, 0.01f)
        val linear = PointerAccel(acceleration = 0f)
        assertEquals(40f * PointerAccel.BASE_GAIN, linear.apply(40f, 0f, 16).first, 0.01f)
        // sub-pixel remainders are kept
        val r = PointerAccel(sensitivity = 0.25f, acceleration = 0f)
        var sum = 0
        repeat(10) { sum += r.applyInt(1f, 0f, 16).first }  // 10 x 0.4 px
        assertTrue(sum in 3..4)
    }
}
