package com.flipdesk.app.net

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.content.ContextCompat
import com.flipdesk.app.data.PairedServer
import com.flipdesk.app.data.Prefs
import com.flipdesk.app.data.StreamQuality
import com.flipdesk.app.service.ConnectionService
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

sealed interface ConnState {
    /** No PC paired yet. */
    data object Unpaired : ConnState
    data object Offline : ConnState
    /** Not on Wi-Fi (or Ethernet): the PC can't be reached, so nothing is tried until Wi-Fi is back. */
    data object Away : ConnState
    data class Connecting(val host: String, val attempt: Int) : ConnState
    data class Waiting(val retryInMs: Long, val reason: String) : ConnState
    data class Connected(val serverName: String, val host: String) : ConnState
    data class AuthFailed(val reason: String) : ConnState
}

data class NowPlaying(
    val title: String, val artist: String, val album: String, val app: String,
    val playing: Boolean, val durationMs: Long, val positionMs: Long, val receivedAt: Long,
)

data class Monitor(val index: Int, val w: Int, val h: Int, val primary: Boolean, val name: String)
data class Launcher(val id: String, val name: String, val type: String, val icon: String)

data class PcState(
    val serverName: String = "",
    val volume: Float = 0f,
    val muted: Boolean = false,
    val nowPlaying: NowPlaying? = null,
    val monitors: List<Monitor> = emptyList(),
    val launchers: List<Launcher> = emptyList(),
    val latencyMs: Int? = null,
)

class Frame(val bitmap: Bitmap, val header: VideoHeader) {
    val image: ImageBitmap = bitmap.asImageBitmap()
}
data class Cursor(val x: Int, val y: Int, val monW: Int, val monH: Int)

/** Process-wide connection to the paired PC. Lives outside any Activity so folds and rotations never drop it. */
@SuppressLint("StaticFieldLeak") // holds only the Application context
object Hub {
    private lateinit var app: Context
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _state = MutableStateFlow<ConnState>(ConnState.Offline)
    val state: StateFlow<ConnState> = _state.asStateFlow()
    private val _pc = MutableStateFlow(PcState())
    val pc: StateFlow<PcState> = _pc.asStateFlow()
    private val _frame = MutableStateFlow<Frame?>(null)
    /** Latest frame of any kind (whole monitor or a zoomed region). */
    val frame: StateFlow<Frame?> = _frame.asStateFlow()
    private val _fullFrame = MutableStateFlow<Frame?>(null)
    /** Latest whole-monitor frame. */
    val fullFrame: StateFlow<Frame?> = _fullFrame.asStateFlow()
    private val _cursor = MutableStateFlow<Cursor?>(null)
    val cursor: StateFlow<Cursor?> = _cursor.asStateFlow()
    private val _toasts = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val toasts: SharedFlow<String> = _toasts.asSharedFlow()
    private val _transfer = MutableStateFlow<String?>(null)
    /** Human-readable progress of a running file upload, or null. */
    val transfer: StateFlow<String?> = _transfer.asStateFlow()

    val isConnected get() = _state.value is ConnState.Connected

    fun toastLocal(msg: String) { _toasts.tryEmit(msg) }

    @Volatile private var ws: WebSocket? = null
    private var loopJob: Job? = null
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private val frames = Channel<ByteArray>(2, BufferOverflow.DROP_OLDEST)
    private val pending = ArrayDeque<Pair<Long, String>>()
    private val waiters = HashMap<String, CompletableDeferred<JSONObject>>()
    private val transferIds = AtomicInteger(1)

    // Wi-Fi / Ethernet networks that are up right now (the PC is only reachable over these).
    private val lanNetworks = LinkedHashSet<Network>()
    @Volatile private var lan: Network? = null
    /** The Wi-Fi/Ethernet network the PC is reached over, or null when away from it. */
    val lanNetwork: Network? get() = lan
    @Volatile private var attemptSocket: WebSocket? = null
    @Volatile private var attemptNetwork: Network? = null
    @Volatile private var lastPongAt = 0L

    fun init(context: Context) {
        app = context.applicationContext
        _state.value = if (Prefs.server.value == null) ConnState.Unpaired else ConnState.Offline
        scope.launch(Dispatchers.Default) { decodeLoop() }
        val cm = app.getSystemService(ConnectivityManager::class.java)
        // Seed with the current network so the first connect doesn't wait for the callback below.
        cm.activeNetwork?.let { n ->
            val caps = cm.getNetworkCapabilities(n)
            if (caps != null && (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))) {
                lanNetworks += n
                lan = n
            }
        }
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) // home Wi-Fi without internet still counts
            .build()
        cm.registerNetworkCallback(request, object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                scope.launch {
                    lanNetworks += network
                    lan = network
                    // Back on Wi-Fi: drop any attempt that was stuck on the old network and retry now.
                    if (!isConnected) attemptSocket?.cancel()
                    wake.trySend(Unit)
                }
            }

            override fun onLost(network: Network) {
                scope.launch {
                    lanNetworks -= network
                    lan = lanNetworks.lastOrNull()
                    if (attemptNetwork == network) attemptSocket?.cancel() // that link is gone; don't wait for a timeout
                    wake.trySend(Unit)
                }
            }
        })
    }

    // --- lifecycle ------------------------------------------------------------------------
    /** Start the foreground service, which keeps the connection alive. */
    fun start(context: Context) {
        if (Prefs.server.value == null) return
        Prefs.setStayConnected(true)
        ContextCompat.startForegroundService(context, Intent(context, ConnectionService::class.java)
            .setAction(ConnectionService.ACTION_CONNECT))
    }

    /**
     * The app came to the front: connect if needed, and if we think we're connected, check the
     * link is really alive (after the phone slept it may be dead without anyone noticing yet).
     */
    fun onForeground(context: Context) {
        if (Prefs.server.value == null) return
        val s = ws
        if (s == null || !isConnected) { start(context); return }
        val sentAt = System.currentTimeMillis()
        s.send(Protocol.msg("ping", "ts" to sentAt))
        scope.launch {
            delay(3_000)
            if (ws === s && lastPongAt < sentAt) s.cancel() // no answer: reconnect right away
        }
    }

    fun stop(context: Context) {
        Prefs.setStayConnected(false)
        disconnect()
        if (ConnectionService.running) {
            runCatching {
                context.startService(Intent(context, ConnectionService::class.java).setAction(ConnectionService.ACTION_DISCONNECT))
            }
        }
    }

    /** Called by the service. Idempotent. */
    fun connect() {
        if (loopJob?.isActive == true) {
            wake.trySend(Unit)
            return
        }
        loopJob = scope.launch { connectLoop() }
    }

    fun disconnect() {
        loopJob?.cancel()
        loopJob = null
        ws?.close(1000, "bye")
        ws = null
        clearFrames()
        _state.value = if (Prefs.server.value == null) ConnState.Unpaired else ConnState.Offline
    }

    fun forget(context: Context) {
        stop(context)
        disconnect()
        Prefs.saveServer(null)
        _pc.value = PcState()
        _state.value = ConnState.Unpaired
    }

    private suspend fun connectLoop() {
        var backoff = MIN_BACKOFF_MS
        var attempt = 0
        while (true) {
            val srv = Prefs.server.value ?: run { _state.value = ConnState.Unpaired; return }
            val net = lan
            if (net == null) {
                // Away from home: stay idle (no battery use) until a Wi-Fi network comes up.
                _state.value = ConnState.Away
                wake.receive()
                backoff = MIN_BACKOFF_MS; attempt = 0
                continue
            }
            attempt++
            var everAuthed = false
            val hosts = (listOfNotNull(srv.lastHost) + srv.hosts).distinct()
            for (h in hosts) {
                _state.value = ConnState.Connecting(h, attempt)
                val r = session(h, srv, net)
                if (r == Result.AUTH_FAILED) return
                if (r == Result.WAS_CONNECTED) { everAuthed = true; break }
            }
            if (!everAuthed && lan == net) {
                // The PC may have a new IP: look for it by server id + fingerprint.
                val found = Discovery.find(network = net).firstOrNull { it.serverId == srv.serverId && it.fingerprint == srv.fingerprint }
                if (found != null) {
                    val updated = srv.copy(hosts = (listOf(found.host) + srv.hosts).distinct().take(6), port = found.port)
                    Prefs.saveServer(updated)
                    _state.value = ConnState.Connecting(found.host, attempt)
                    val r = session(found.host, updated, net)
                    if (r == Result.AUTH_FAILED) return
                    if (r == Result.WAS_CONNECTED) everAuthed = true
                }
            }
            if (everAuthed) { backoff = MIN_BACKOFF_MS; attempt = 0 }
            if (lan == null) continue // Wi-Fi went away meanwhile: go idle right away
            _state.value = ConnState.Waiting(backoff, if (everAuthed) "Connection lost" else "PC not reachable")
            // At home the PC should come back quickly (restart, wake from sleep), so retry often;
            // opening the app, tapping Connect or a network change retries immediately.
            val woken = withTimeoutOrNull(backoff) { wake.receive() } != null
            backoff = if (woken) MIN_BACKOFF_MS else (backoff * 2).coerceAtMost(MAX_BACKOFF_MS)
        }
    }

    private const val MIN_BACKOFF_MS = 500L
    private const val MAX_BACKOFF_MS = 10_000L

    private enum class Result { NEVER_CONNECTED, WAS_CONNECTED, AUTH_FAILED }

    /** One WebSocket session; suspends until it closes. */
    private suspend fun session(host: String, srv: PairedServer, network: Network?): Result {
        val done = CompletableDeferred<Result>()
        var authed = false
        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send(Protocol.msg("hello", "v" to Protocol.VERSION, "device_id" to Prefs.deviceId, "token" to srv.token))
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val m = runCatching { JSONObject(text) }.getOrNull() ?: return
                when (m.optString("t")) {
                    "auth_ok" -> {
                        authed = true
                        scope.launch { onAuthed(webSocket, host, srv, m) }
                    }
                    "auth_fail" -> {
                        done.complete(Result.AUTH_FAILED)
                        _state.value = ConnState.AuthFailed(m.optString("reason", "The PC no longer knows this phone"))
                    }
                    else -> scope.launch { onMessage(m) }
                }
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                val data = bytes.toByteArray()
                if (data.isNotEmpty() && data[0] == Protocol.BIN_VIDEO) frames.trySend(data)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
                // Only a real "you're not paired" means pairing is lost. The PC also uses this code
                // when the hello arrives too late (slow Wi-Fi); that's just a retry, not a reason to re-pair.
                if (code == Protocol.CLOSE_UNAUTHORIZED && !authed && (reason == "not paired" || reason == "unpaired")) {
                    _state.value = ConnState.AuthFailed(reason.ifEmpty { "Not paired" })
                    done.complete(Result.AUTH_FAILED)
                }
                done.complete(if (authed) Result.WAS_CONNECTED else Result.NEVER_CONNECTED)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                done.complete(if (authed) Result.WAS_CONNECTED else Result.NEVER_CONNECTED)
            }
        }
        val req = Request.Builder().url("wss://$host:${srv.port}/").build()
        val socket = PinnedClient.forFingerprint(srv.fingerprint, network).newWebSocket(req, listener)
        attemptSocket = socket
        attemptNetwork = network
        return try {
            val r = done.await()
            if (ws === socket) ws = null
            clearFrames()
            failWaiters()
            r
        } finally {
            if (attemptSocket === socket) { attemptSocket = null; attemptNetwork = null }
            socket.cancel()
        }
    }

    private fun onAuthed(socket: WebSocket, host: String, srv: PairedServer, m: JSONObject) {
        ws = socket
        if (srv.lastHost != host) Prefs.server.value?.let { Prefs.saveServer(it.copy(lastHost = host)) }
        applyState(m)
        _state.value = ConnState.Connected(m.optString("server_name", srv.name), host)
        // Flush commands queued while offline (e.g. a widget tap that started the service).
        val now = System.currentTimeMillis()
        while (pending.isNotEmpty()) {
            val (at, msg) = pending.removeFirst()
            if (now - at < 15_000) socket.send(msg)
        }
        if (streamUsers > 0) sendStreamStart()
        scope.launch { pingLoop(socket) }
    }

    private suspend fun pingLoop(socket: WebSocket) {
        while (ws === socket) {
            socket.send(Protocol.msg("ping", "ts" to System.currentTimeMillis()))
            delay(5_000)
        }
    }

    private fun failWaiters() {
        waiters.values.forEach { it.completeExceptionally(IllegalStateException("disconnected")) }
        waiters.clear()
    }

    // --- incoming -------------------------------------------------------------------------
    private fun onMessage(m: JSONObject) {
        when (m.optString("t")) {
            "state" -> applyState(m)
            "volume" -> _pc.value = _pc.value.copy(volume = m.optDouble("level", 0.0).toFloat(), muted = m.optBoolean("muted"))
            "now_playing" -> _pc.value = _pc.value.copy(nowPlaying = parseNowPlaying(m.optJSONObject("info")))
            "cursor" -> _frame.value?.header?.let { h -> _cursor.value = Cursor(m.optInt("x"), m.optInt("y"), h.monW, h.monH) }
            "stream_started" -> m.optJSONArray("monitors")?.let { _pc.value = _pc.value.copy(monitors = parseMonitors(m)) }
            "clipboard" -> {
                val text = m.optString("text")
                app.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("PC clipboard", text))
                // Android 13+ shows its own "copied" confirmation.
                if (Build.VERSION.SDK_INT < 33) _toasts.tryEmit("Copied PC clipboard to phone")
            }
            "pong" -> {
                lastPongAt = System.currentTimeMillis()
                val ts = m.optLong("ts", 0)
                if (ts > 0) _pc.value = _pc.value.copy(latencyMs = (System.currentTimeMillis() - ts).toInt())
            }
            "error" -> _toasts.tryEmit(m.optString("msg", "Error"))
            "file_ready" -> waiters.remove("file_ready:${m.optInt("id")}")?.complete(m)
            "file_saved" -> waiters.remove("file_saved:${m.optInt("id")}")?.complete(m)
            "ok" -> waiters.remove("ok:${m.optString("for_t")}")?.complete(m)
        }
    }

    private fun applyState(m: JSONObject) {
        val launchers = m.optJSONArray("launchers")?.let { a ->
            (0 until a.length()).map { a.getJSONObject(it) }.map {
                Launcher(it.optString("id"), it.optString("name"), it.optString("type"), it.optString("icon"))
            }
        } ?: emptyList()
        _pc.value = _pc.value.copy(
            serverName = m.optString("server_name"),
            volume = m.optDouble("volume", 0.0).toFloat(),
            muted = m.optBoolean("muted"),
            nowPlaying = parseNowPlaying(m.optJSONObject("now_playing")),
            monitors = parseMonitors(m),
            launchers = launchers,
        )
    }

    private fun parseMonitors(m: JSONObject): List<Monitor> = m.optJSONArray("monitors")?.let { a ->
        (0 until a.length()).map { a.getJSONObject(it) }.map {
            Monitor(it.optInt("index"), it.optInt("w"), it.optInt("h"), it.optBoolean("primary"), it.optString("name"))
        }
    } ?: emptyList()

    private fun parseNowPlaying(o: JSONObject?): NowPlaying? {
        if (o == null || (o.optString("title").isEmpty() && o.optString("artist").isEmpty())) return null
        return NowPlaying(o.optString("title"), o.optString("artist"), o.optString("album"),
            o.optString("app"), o.optBoolean("playing"), o.optLong("duration_ms"), o.optLong("position_ms"),
            System.currentTimeMillis())
    }

    // --- outgoing -------------------------------------------------------------------------
    fun send(type: String, vararg fields: Pair<String, Any?>): Boolean {
        val s = ws ?: return false
        return s.send(Protocol.msg(type, *fields))
    }

    /** Send now, or queue (≤15 s) and bring the connection up; used by widgets/notifications. */
    fun sendOrQueue(context: Context, type: String, vararg fields: Pair<String, Any?>) {
        if (!send(type, *fields)) {
            pending.addLast(System.currentTimeMillis() to Protocol.msg(type, *fields))
            while (pending.size > 20) pending.removeFirst()
            start(context)
        }
    }

    private suspend fun request(type: String, key: String, timeoutMs: Long, vararg fields: Pair<String, Any?>): JSONObject? {
        val d = CompletableDeferred<JSONObject>()
        waiters[key] = d
        if (!send(type, *fields)) { waiters.remove(key); return null }
        return runCatching { withTimeoutOrNull(timeoutMs) { d.await() } }.getOrNull().also { waiters.remove(key) }
    }

    // --- pairing --------------------------------------------------------------------------
    /** Pair using a scanned QR code, or a discovered server + PIN. Returns null on success, else an error. */
    suspend fun pair(info: PairingInfo, pin: String? = null): String? {
        disconnect()
        var lastError = "Could not reach the PC"
        for (host in info.hosts) {
            val result = CompletableDeferred<Pair<PairedServer?, String>>()
            val listener = object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    webSocket.send(Protocol.msg("pair", "v" to Protocol.VERSION, "device_id" to Prefs.deviceId,
                        "device_name" to deviceName(), "secret" to (if (pin == null) info.secret else null), "pin" to pin))
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    val m = runCatching { JSONObject(text) }.getOrNull() ?: return
                    when (m.optString("t")) {
                        "paired" -> result.complete(PairedServer(
                            serverId = m.optString("server_id", info.serverId ?: ""),
                            name = m.optString("server_name", info.name ?: "PC"),
                            hosts = info.hosts, port = info.port, fingerprint = info.fingerprint,
                            token = m.optString("token"), lastHost = host) to "")
                        "pair_fail" -> result.complete(null to m.optString("reason", "Pairing refused"))
                    }
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    val msg = if (t is javax.net.ssl.SSLException || t.cause is java.security.cert.CertificateException)
                        "Security check failed: this isn't the PC from the QR code"
                    else "Could not reach $host (${t.javaClass.simpleName})"
                    result.complete(null to msg)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    result.complete(null to reason.ifEmpty { "Connection closed" })
                }
            }
            val sock = PinnedClient.forFingerprint(info.fingerprint, lan)
                .newWebSocket(Request.Builder().url("wss://$host:${info.port}/").build(), listener)
            val (server, err) = withTimeoutOrNull(10_000) { result.await() } ?: (null to "Timed out reaching $host")
            sock.close(1000, null)
            if (server != null) {
                Prefs.saveServer(server)
                _state.value = ConnState.Offline
                start(app)
                return null
            }
            lastError = err
            if (err.startsWith("Security") || err.contains("code") || err.contains("pairing")) break
        }
        return lastError
    }

    private fun deviceName(): String {
        val model = Build.MODEL ?: "Android"
        return if (model.startsWith("SM-F7")) "Galaxy Z Flip ($model)" else model
    }

    // --- screen stream --------------------------------------------------------------------
    private var streamUsers = 0
    private var bigUsers = 0 // views on an external display: allow a sharper stream
    private var streamW = 1280
    private var streamH = 1280
    private var stopJob: Job? = null

    /** Each visible stream view acquires the stream; it stops shortly after the last one leaves. */
    fun acquireStream(viewW: Int, viewH: Int, big: Boolean = false) {
        streamUsers++
        if (big) bigUsers++
        stopJob?.cancel()
        val changed = viewW > streamW + 64 || viewH > streamH + 64 || (big && bigUsers == 1)
        streamW = maxOf(viewW, 320)
        streamH = maxOf(viewH, 320)
        if (streamUsers == 1 || changed) sendStreamStart()
    }

    fun releaseStream(big: Boolean = false) {
        if (big) bigUsers = (bigUsers - 1).coerceAtLeast(0)
        streamUsers = (streamUsers - 1).coerceAtLeast(0)
        if (streamUsers == 0) {
            stopJob?.cancel()
            stopJob = scope.launch {
                delay(1_200) // layouts swap during fold animations; don't flap the stream
                if (streamUsers == 0) {
                    send("stream_stop")
                    region = null
                    clearFrames()
                }
            }
        }
    }

    fun restartStream() {
        if (streamUsers > 0) sendStreamStart()
    }

    private fun sendStreamStart() {
        val s = Prefs.settings.value
        val mons = _pc.value.monitors
        val monitor = if (s.monitor in mons.indices) s.monitor else mons.indexOfFirst { it.primary }.coerceAtLeast(0)
        val (cap, q) = when (s.quality) {
            StreamQuality.SAVER -> (if (bigUsers > 0) 1280 else 960) to 50
            StreamQuality.AUTO -> (if (bigUsers > 0) 1920 else 1600) to 70
            StreamQuality.SHARP -> 2560 to 82
        }
        // Landscape-ish PC screens: ask for the longer side to cover the view's longer side.
        val side = maxOf(streamW, streamH).coerceAtMost(cap)
        send("stream_start", "monitor" to monitor, "max_w" to side, "max_h" to side,
            "fps" to s.maxFps, "quality" to q)
        region?.let { sendRegion(it) } // stream_start resets the region on the PC
    }

    private var region: Pair<StreamRegion, Pair<Int, Int>>? = null

    /**
     * Zoomed views ask for just the visible part of the monitor, fitted to maxW x maxH phone
     * pixels (the PC never upscales, so small regions arrive at native resolution). null = whole monitor.
     */
    fun setRegion(r: StreamRegion?, maxW: Int = 0, maxH: Int = 0) {
        val next = r?.let { it to (maxW to maxH) }
        if (next == region) return
        region = next
        if (next == null) send("stream_region") else sendRegion(next)
    }

    private fun sendRegion(r: Pair<StreamRegion, Pair<Int, Int>>) {
        val (g, max) = r
        send("stream_region", "x" to g.x, "y" to g.y, "w" to g.w, "h" to g.h,
            "max_w" to max.first.coerceIn(160, 2560), "max_h" to max.second.coerceIn(160, 2560))
    }

    private fun clearFrames() {
        _frame.value = null
        _fullFrame.value = null
    }

    private suspend fun decodeLoop() {
        for (data in frames) {
            val h = VideoHeader.parse(data) ?: continue
            // A fresh bitmap per frame: zoomed views keep older frames on screen as layers,
            // so recycling buffers would overwrite pixels that are still being drawn.
            val bmp = runCatching {
                BitmapFactory.decodeByteArray(data, h.jpegOffset, data.size - h.jpegOffset)
            }.getOrNull() ?: continue
            val frame = Frame(bmp, h)
            withContext(Dispatchers.Main) {
                _frame.value = frame
                if (h.region == null) _fullFrame.value = frame
                _cursor.value = Cursor(h.cursorX, h.cursorY, h.monW, h.monH)
                send("frame_ack", "seq" to h.seq)
            }
        }
    }

    // --- file upload ----------------------------------------------------------------------
    /** Upload files to the PC's Downloads folder. Returns the number saved. */
    suspend fun sendFiles(context: Context, uris: List<Uri>): Int {
        var ok = 0
        for ((n, uri) in uris.withIndex()) {
            val label = if (uris.size > 1) " (${n + 1}/${uris.size})" else ""
            if (sendFile(context, uri, label)) ok++
        }
        _transfer.value = null
        return ok
    }

    private suspend fun sendFile(context: Context, uri: Uri, label: String): Boolean {
        val cr = context.contentResolver
        var name = "shared_file"
        var size = -1L
        runCatching {
            cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    c.getString(0)?.let { name = it }
                    if (!c.isNull(1)) size = c.getLong(1)
                }
            }
        }
        var tmp: File? = null
        val source: Uri = if (size < 0) {
            // Unknown size: copy to cache first, the protocol announces the size up front.
            tmp = withContext(Dispatchers.IO) {
                File.createTempFile("share", null, context.cacheDir).also { f ->
                    cr.openInputStream(uri)?.use { i -> f.outputStream().use { i.copyTo(it) } }
                }
            }
            size = tmp.length()
            Uri.fromFile(tmp)
        } else uri
        val mime = cr.getType(uri)
        try {
            val id = transferIds.getAndIncrement()
            request("file_begin", "file_ready:$id", 10_000, "id" to id, "name" to name, "size" to size, "mime" to mime)
                ?: return false.also { _toasts.tryEmit("PC didn't accept $name") }
            val socket = ws ?: return false
            var sent = 0L
            val buf = ByteArray(256 * 1024)
            val input = (if (source.scheme == "file") File(source.path!!).inputStream() else cr.openInputStream(source))
                ?: return false.also { send("file_cancel", "id" to id) }
            input.use {
                while (true) {
                    val r = withContext(Dispatchers.IO) { it.read(buf) }
                    if (r <= 0) break
                    if (!socket.send(Protocol.fileChunk(id, buf, r).toByteString())) return false
                    sent += r
                    while (socket.queueSize() > 4L * 1024 * 1024) delay(15) // don't flood OkHttp's 16 MB buffer
                    _transfer.value = "Sending $name$label · ${if (size > 0) sent * 100 / size else 100}%"
                }
            }
            val saved = request("file_end", "file_saved:$id", 60_000, "id" to id)
            return saved != null
        } finally {
            tmp?.delete()
        }
    }

    /** Waits (up to [timeoutMs]) for the connection; used by the share sheet. */
    suspend fun awaitConnected(timeoutMs: Long): Boolean =
        withTimeoutOrNull(timeoutMs) { state.first { it is ConnState.Connected } } != null

    suspend fun openUrl(url: String): Boolean = request("open_url", "ok:open_url", 5_000, "url" to url) != null
    suspend fun pushClipboard(text: String): Boolean = request("clipboard_push", "ok:clipboard_push", 5_000, "text" to text) != null
}
