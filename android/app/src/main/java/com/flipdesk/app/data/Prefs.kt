package com.flipdesk.app.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

enum class FoldCloseAction { NOTHING, PAUSE_MEDIA, LOCK_PC }
enum class StreamQuality { AUTO, SAVER, SHARP }
enum class LayoutOverride { AUTO, COVER }

data class Settings(
    val sensitivity: Float = 1f,
    val acceleration: Float = 1f,
    val naturalScroll: Boolean = true,
    val scrollSpeed: Float = 1f,
    val haptics: Boolean = true,
    val volumeKeys: Boolean = true,
    val foldClose: FoldCloseAction = FoldCloseAction.NOTHING,
    val keepAwake: Boolean = true,
    val livePreview: Boolean = true,
    val directTouch: Boolean = true,
    val quality: StreamQuality = StreamQuality.AUTO,
    val maxFps: Int = 30,
    val airSensitivity: Float = 1f,
    val monitor: Int = -1, // -1 = the PC's primary monitor
    val layout: LayoutOverride = LayoutOverride.AUTO,
    /** Last zoom level used in laptop (Flex) mode. */
    val laptopZoom: Float = 2.5f,
    val miniMap: Boolean = true,
    /** DeX / HDMI / Smart View: show the PC full screen on the external display. */
    val bigScreen: Boolean = true,
)

/** The PC this phone is paired with. */
data class PairedServer(
    val serverId: String,
    val name: String,
    val hosts: List<String>,
    val port: Int,
    val fingerprint: String,
    val token: String,
    val lastHost: String? = null,
) {
    fun toJson(): String = JSONObject()
        .put("id", serverId).put("name", name).put("hosts", JSONArray(hosts)).put("port", port)
        .put("fp", fingerprint).put("token", token).put("last", lastHost ?: "").toString()

    companion object {
        fun fromJson(s: String): PairedServer? = runCatching {
            val o = JSONObject(s)
            val hosts = o.getJSONArray("hosts").let { a -> List(a.length()) { a.getString(it) } }
            PairedServer(o.getString("id"), o.getString("name"), hosts, o.getInt("port"),
                o.getString("fp"), o.getString("token"), o.optString("last").ifEmpty { null })
        }.getOrNull()
    }
}

object Prefs {
    private lateinit var sp: SharedPreferences
    private val _settings = MutableStateFlow(Settings())
    val settings: StateFlow<Settings> = _settings.asStateFlow()
    private val _server = MutableStateFlow<PairedServer?>(null)
    val server: StateFlow<PairedServer?> = _server.asStateFlow()

    lateinit var deviceId: String
        private set

    fun init(context: Context) {
        sp = context.getSharedPreferences("flipdesk", Context.MODE_PRIVATE)
        deviceId = sp.getString("device_id", null) ?: UUID.randomUUID().toString().also {
            sp.edit { putString("device_id", it) }
        }
        _settings.value = load()
        _server.value = sp.getString("server", null)?.let(PairedServer::fromJson)
    }

    fun update(f: (Settings) -> Settings) {
        val s = f(_settings.value)
        _settings.value = s
        sp.edit {
            putFloat("sensitivity", s.sensitivity)
            putFloat("acceleration", s.acceleration)
            putBoolean("naturalScroll", s.naturalScroll)
            putFloat("scrollSpeed", s.scrollSpeed)
            putBoolean("haptics", s.haptics)
            putBoolean("volumeKeys", s.volumeKeys)
            putString("foldClose", s.foldClose.name)
            putBoolean("keepAwake", s.keepAwake)
            putBoolean("livePreview", s.livePreview)
            putBoolean("directTouch", s.directTouch)
            putString("quality", s.quality.name)
            putInt("maxFps", s.maxFps)
            putFloat("airSensitivity", s.airSensitivity)
            putInt("monitor", s.monitor)
            putString("layout", s.layout.name)
            putFloat("laptopZoom", s.laptopZoom)
            putBoolean("miniMap", s.miniMap)
            putBoolean("bigScreen", s.bigScreen)
        }
    }

    private fun load(): Settings {
        val d = Settings()
        return Settings(
            sensitivity = sp.getFloat("sensitivity", d.sensitivity),
            acceleration = sp.getFloat("acceleration", d.acceleration),
            naturalScroll = sp.getBoolean("naturalScroll", d.naturalScroll),
            scrollSpeed = sp.getFloat("scrollSpeed", d.scrollSpeed),
            haptics = sp.getBoolean("haptics", d.haptics),
            volumeKeys = sp.getBoolean("volumeKeys", d.volumeKeys),
            foldClose = enumOr(sp.getString("foldClose", null), d.foldClose),
            keepAwake = sp.getBoolean("keepAwake", d.keepAwake),
            livePreview = sp.getBoolean("livePreview", d.livePreview),
            directTouch = sp.getBoolean("directTouch", d.directTouch),
            quality = enumOr(sp.getString("quality", null), d.quality),
            maxFps = sp.getInt("maxFps", d.maxFps),
            airSensitivity = sp.getFloat("airSensitivity", d.airSensitivity),
            monitor = sp.getInt("monitor", d.monitor),
            layout = enumOr(sp.getString("layout", null), d.layout),
            laptopZoom = sp.getFloat("laptopZoom", d.laptopZoom),
            miniMap = sp.getBoolean("miniMap", d.miniMap),
            bigScreen = sp.getBoolean("bigScreen", d.bigScreen),
        )
    }

    private inline fun <reified E : Enum<E>> enumOr(name: String?, default: E): E =
        name?.let { n -> enumValues<E>().firstOrNull { it.name == n } } ?: default

    /** False only after the user taps Disconnect; then nothing reconnects on its own (not even after a reboot). */
    val stayConnected: Boolean get() = sp.getBoolean("stayConnected", true)

    fun setStayConnected(v: Boolean) {
        if (v != stayConnected) sp.edit { putBoolean("stayConnected", v) }
    }

    /** We ask once to be exempt from battery optimization (One UI otherwise puts idle apps to sleep). */
    var askedBatteryExemption: Boolean
        get() = sp.getBoolean("askedBattery", false)
        set(v) = sp.edit { putBoolean("askedBattery", v) }

    fun saveServer(s: PairedServer?) {
        _server.value = s
        sp.edit { if (s == null) remove("server") else putString("server", s.toJson()) }
    }
}
