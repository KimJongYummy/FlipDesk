package com.flipdesk.app.net

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.net.URLDecoder
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** FlipDesk protocol v1 — see docs/PROTOCOL.md. */
object Protocol {
    const val VERSION = 1
    const val DEFAULT_PORT = 8765
    const val DISCOVERY_PORT = 8766
    const val DISCOVERY_PROBE = "FLIPDESK?{\"v\":1}"
    const val BIN_VIDEO: Byte = 0x01
    const val BIN_FILE_CHUNK: Byte = 0x02
    const val VIDEO_HEADER_SIZE = 16
    const val VIDEO_REGION_SIZE = 8
    const val VIDEO_FLAG_REGION = 0x01
    const val CLOSE_UNAUTHORIZED = 4401

    fun msg(type: String, vararg fields: Pair<String, Any?>): String {
        val o = JSONObject().put("t", type)
        for ((k, v) in fields) {
            when (v) {
                null -> {}
                is List<*> -> o.put(k, JSONArray(v))
                else -> o.put(k, v)
            }
        }
        return o.toString()
    }

    fun fileChunk(transferId: Int, payload: ByteArray, len: Int = payload.size): ByteArray {
        val out = ByteArray(5 + len)
        out[0] = BIN_FILE_CHUNK
        ByteBuffer.wrap(out, 1, 4).order(ByteOrder.BIG_ENDIAN).putInt(transferId)
        System.arraycopy(payload, 0, out, 5, len)
        return out
    }
}

/** Part of a monitor, in monitor pixels. */
data class StreamRegion(val x: Int, val y: Int, val w: Int, val h: Int) {
    fun contains(x0: Float, y0: Float, x1: Float, y1: Float) = x0 >= x && y0 >= y && x1 <= x + w && y1 <= y + h
}

/**
 * Parsed binary video frame header. The JPEG starts at [jpegOffset]; if [region] is set it
 * covers only that rectangle of the monitor (zoomed views), otherwise the whole monitor.
 */
data class VideoHeader(
    val seq: Long,
    val monW: Int,
    val monH: Int,
    val cursorX: Int,
    val cursorY: Int,
    val monitor: Int,
    val quality: Int,
    val region: StreamRegion? = null,
    val jpegOffset: Int = Protocol.VIDEO_HEADER_SIZE,
) {
    companion object {
        fun parse(data: ByteArray): VideoHeader? {
            if (data.size < Protocol.VIDEO_HEADER_SIZE || data[0] != Protocol.BIN_VIDEO) return null
            val b = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN)
            b.get()
            val seq = b.int.toLong() and 0xFFFFFFFFL
            val w = b.short.toInt() and 0xFFFF
            val h = b.short.toInt() and 0xFFFF
            val cx = b.short.toInt()
            val cy = b.short.toInt()
            val mon = b.get().toInt() and 0xFF
            val q = b.get().toInt() and 0xFF
            val flags = b.get().toInt() and 0xFF
            if (flags and Protocol.VIDEO_FLAG_REGION == 0) return VideoHeader(seq, w, h, cx, cy, mon, q)
            if (data.size < Protocol.VIDEO_HEADER_SIZE + Protocol.VIDEO_REGION_SIZE) return null
            fun u16() = b.short.toInt() and 0xFFFF
            val r = StreamRegion(u16(), u16(), u16(), u16())
            return VideoHeader(seq, w, h, cx, cy, mon, q, r, Protocol.VIDEO_HEADER_SIZE + Protocol.VIDEO_REGION_SIZE)
        }
    }
}

/** Contents of the pairing QR code: flipdesk://pair?v=1&h=ip[,ip]&p=port&fp=sha256&s=secret&id=..&n=.. */
data class PairingInfo(
    val hosts: List<String>,
    val port: Int,
    val fingerprint: String,
    val secret: String?,
    val serverId: String?,
    val name: String?,
) {
    companion object {
        fun parse(text: String): PairingInfo? {
            val uri = runCatching { URI(text.trim()) }.getOrNull() ?: return null
            if (uri.scheme != "flipdesk" || uri.host != "pair") return null
            val q = (uri.rawQuery ?: return null).split('&').mapNotNull {
                val i = it.indexOf('=')
                if (i <= 0) null
                else it.substring(0, i) to URLDecoder.decode(it.substring(i + 1), "UTF-8")
            }.toMap()
            val hosts = q["h"]?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() } ?: return null
            val port = q["p"]?.toIntOrNull() ?: return null
            val fp = q["fp"]?.lowercase()?.takeIf { it.length == 64 && it.all { c -> c in "0123456789abcdef" } }
                ?: return null
            if (hosts.isEmpty() || port !in 1..65535) return null
            return PairingInfo(hosts, port, fp, q["s"], q["id"], q["n"])
        }
    }
}

/** A server found by UDP discovery. */
data class DiscoveredServer(
    val host: String,
    val port: Int,
    val fingerprint: String,
    val serverId: String,
    val name: String,
) {
    /** Same short form the PC shows under the PIN, e.g. "A1B2-C3D4". */
    val securityCode: String get() = shortFingerprint(fingerprint)

    companion object {
        fun parse(json: String, host: String): DiscoveredServer? = runCatching {
            val o = JSONObject(json)
            if (o.optString("t") != "flipdesk") return null
            DiscoveredServer(host, o.getInt("port"), o.getString("fp").lowercase(), o.getString("id"),
                o.optString("name", host))
        }.getOrNull()
    }
}

fun shortFingerprint(fp: String): String =
    if (fp.length < 8) fp.uppercase() else "${fp.substring(0, 4)}-${fp.substring(4, 8)}".uppercase()
