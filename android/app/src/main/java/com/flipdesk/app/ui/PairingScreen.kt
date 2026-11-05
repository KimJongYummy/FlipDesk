package com.flipdesk.app.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Computer
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.flipdesk.app.net.ConnState
import com.flipdesk.app.net.DiscoveredServer
import com.flipdesk.app.net.Discovery
import com.flipdesk.app.net.Hub
import com.flipdesk.app.net.PairingInfo
import com.flipdesk.app.util.Haptics
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.NotFoundException
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import kotlinx.coroutines.launch
import java.util.concurrent.Executors

@Composable
fun PairingScreen(onBack: (() -> Unit)?) {
    val scope = rememberCoroutineScope()
    val state by Hub.state.collectAsStateWithLifecycle()
    var usePin by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun pair(info: PairingInfo, pin: String? = null) {
        if (busy) return
        busy = true
        error = null
        scope.launch {
            val err = Hub.pair(info, pin)
            busy = false
            if (err != null) { error = err; Haptics.heavy() } else Haptics.click()
        }
    }

    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (onBack != null) IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
            Text("Pair with your PC", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        }
        if (state is ConnState.AuthFailed) {
            Surface(color = Danger.copy(alpha = 0.15f), shape = RoundedCornerShape(14.dp)) {
                Text("The PC no longer recognises this phone. Pair again.", Modifier.padding(14.dp))
            }
        }
        Text("1. Start the FlipDesk server on your computer.\n2. Click its tray icon › Show pairing code.\n3. Scan the QR code, or type the PIN.",
            color = Muted)
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            SegmentedButton(!usePin, { usePin = false; error = null }, SegmentedButtonDefaults.itemShape(0, 2)) { Text("Scan QR") }
            SegmentedButton(usePin, { usePin = true; error = null }, SegmentedButtonDefaults.itemShape(1, 2)) { Text("Enter PIN") }
        }
        if (!usePin) QrPane(enabled = !busy) { text ->
            val info = PairingInfo.parse(text)
            if (info == null || info.secret == null) error = "That isn't a FlipDesk pairing code" else pair(info)
        } else PinPane(busy) { server, pin ->
            pair(PairingInfo(listOf(server.host), server.port, server.fingerprint, null, server.serverId, server.name), pin)
        }
        if (busy) Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
            HSpace(12.dp)
            Text("Pairing…")
        }
        error?.let { Text(it, color = Danger) }
    }
}

@Composable
private fun QrPane(enabled: Boolean, onCode: (String) -> Unit) {
    val context = LocalContext.current
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    Box(Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(28.dp)).background(Pad)
        .border(2.dp, Accent.copy(alpha = 0.4f), RoundedCornerShape(28.dp)), contentAlignment = Alignment.Center) {
        if (granted) {
            QrScanner(Modifier.fillMaxSize(), enabled, onCode)
        } else {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(Icons.Rounded.QrCodeScanner, null, modifier = Modifier.size(56.dp), tint = Accent)
                Text("Camera access is needed to scan the code.\nImages never leave the phone.", textAlign = TextAlign.Center, color = Muted)
                Button(onClick = { ask.launch(Manifest.permission.CAMERA) }) { Text("Allow camera") }
            }
        }
    }
}

@Composable
private fun PinPane(busy: Boolean, onPair: (DiscoveredServer, String) -> Unit) {
    val scope = rememberCoroutineScope()
    var servers by remember { mutableStateOf<List<DiscoveredServer>?>(null) }
    var searching by remember { mutableStateOf(false) }
    var chosen by remember { mutableStateOf<DiscoveredServer?>(null) }
    var pin by remember { mutableStateOf("") }
    fun search() {
        searching = true
        scope.launch {
            servers = Discovery.find(2500, Hub.lanNetwork)
            if (servers?.size == 1) chosen = servers!!.first()
            searching = false
        }
    }
    androidx.compose.runtime.LaunchedEffect(Unit) { search() }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Computers on this Wi-Fi", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
            if (searching) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            else OutlinedButton(onClick = { search() }) { Text("Search again") }
        }
        val list = servers
        if (list != null && list.isEmpty() && !searching) {
            Text("None found. Make sure the server is running, the phone is on the same Wi-Fi, and the PC firewall allows FlipDesk on private networks.",
                color = Muted, fontSize = 13.sp)
        }
        list?.forEach { s ->
            Surface(onClick = { chosen = s }, shape = RoundedCornerShape(16.dp),
                color = if (chosen == s) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh) {
                Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Computer, null)
                    HSpace(12.dp)
                    Column(Modifier.weight(1f)) {
                        Text(s.name, fontWeight = FontWeight.Medium)
                        Text("${s.host} · security code ${s.securityCode}", color = Muted, fontSize = 12.sp)
                    }
                }
            }
        }
        chosen?.let { s ->
            Text("Check that the PC's pairing window shows security code ${s.securityCode}.", color = Warn, fontSize = 13.sp)
            OutlinedTextField(
                value = pin, onValueChange = { v -> pin = v.filter(Char::isDigit).take(6) },
                label = { Text("6-digit PIN") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                textStyle = MaterialTheme.typography.headlineSmall.copy(letterSpacing = 6.sp),
                modifier = Modifier.fillMaxWidth(),
            )
            Button(onClick = { onPair(s, pin) }, enabled = pin.length == 6 && !busy, modifier = Modifier.fillMaxWidth()) { Text("Pair") }
        }
    }
}

/** CameraX preview + on-device ZXing QR decoding of the luminance plane. */
@Composable
fun QrScanner(modifier: Modifier, enabled: Boolean, onCode: (String) -> Unit) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val latest by rememberUpdatedState(onCode)
    val active by rememberUpdatedState(enabled)
    val executor = remember { Executors.newSingleThreadExecutor() }
    val reader = remember {
        MultiFormatReader().apply {
            setHints(mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE), DecodeHintType.TRY_HARDER to true))
        }
    }
    var lastText by remember { mutableStateOf<String?>(null) }
    val previewView = remember { PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER } }
    DisposableEffect(owner) {
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            val provider = future.get()
            val preview = Preview.Builder().build().also { it.surfaceProvider = previewView.surfaceProvider }
            val analysis = ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
            analysis.setAnalyzer(executor) { img ->
                val text = decode(reader, img)
                img.close()
                if (text != null && active && text != lastText) {
                    lastText = text
                    previewView.post { latest(text) }
                }
            }
            runCatching {
                provider.unbindAll()
                provider.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
            }
        }, ContextCompat.getMainExecutor(context))
        onDispose {
            runCatching { future.get().unbindAll() }
            executor.shutdown()
        }
    }
    AndroidView({ previewView }, modifier)
}

private fun decode(reader: MultiFormatReader, img: ImageProxy): String? {
    val plane = img.planes[0]
    val buf = plane.buffer
    val w = img.width
    val h = img.height
    val stride = plane.rowStride
    val data = ByteArray(w * h)
    for (row in 0 until h) {
        buf.position(row * stride)
        buf.get(data, row * w, w)
    }
    return try {
        reader.decodeWithState(BinaryBitmap(HybridBinarizer(PlanarYUVLuminanceSource(data, w, h, 0, 0, w, h, false)))).text
    } catch (_: NotFoundException) {
        null
    } catch (_: Exception) {
        null
    } finally {
        reader.reset()
    }
}
