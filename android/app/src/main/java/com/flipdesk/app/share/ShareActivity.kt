package com.flipdesk.app.share

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Patterns
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.IntentCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.flipdesk.app.MainActivity
import com.flipdesk.app.data.Prefs
import com.flipdesk.app.net.Hub
import com.flipdesk.app.ui.AccentGreen
import com.flipdesk.app.ui.Danger
import com.flipdesk.app.ui.FlipDeskTheme
import com.flipdesk.app.ui.Muted
import com.flipdesk.app.util.Haptics
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * "Send to PC" in Android's share sheet. Links open in the PC browser, other text goes to
 * the PC clipboard, and files/photos are saved to the PC's Downloads folder.
 * Stays open (as a small card) until the upload finishes, so URI read grants stay valid.
 */
class ShareActivity : ComponentActivity() {
    private var status by mutableStateOf("Connecting to PC…")
    private var done by mutableStateOf<Boolean?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Prefs.server.value == null) {
            startActivity(Intent(this, MainActivity::class.java))
            finish()
            return
        }
        setContent {
            FlipDeskTheme {
                val transfer by Hub.transfer.collectAsStateWithLifecycle()
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
                    Surface(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.navigationBars).padding(16.dp),
                        shape = RoundedCornerShape(26.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                        Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            when (done) {
                                null -> CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 3.dp)
                                true -> Icon(Icons.Rounded.CheckCircle, null, tint = AccentGreen, modifier = Modifier.size(32.dp))
                                false -> Icon(Icons.Rounded.ErrorOutline, null, tint = Danger, modifier = Modifier.size(32.dp))
                            }
                            Column {
                                Text("FlipDesk", style = MaterialTheme.typography.titleMedium)
                                Text(transfer ?: status, color = Muted)
                            }
                        }
                    }
                }
            }
        }
        lifecycleScope.launch { handle(intent) }
    }

    private suspend fun handle(intent: Intent) {
        Hub.start(this)
        if (!Hub.awaitConnected(12_000)) return finish(false, "Couldn't reach the PC")
        val uris: List<Uri> = when (intent.action) {
            Intent.ACTION_SEND -> listOfNotNull(IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java))
            Intent.ACTION_SEND_MULTIPLE -> IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java) ?: emptyList()
            else -> emptyList()
        }
        if (uris.isNotEmpty()) {
            status = "Sending ${uris.size} file(s)…"
            val n = Hub.sendFiles(this, uris)
            return if (n == uris.size) finish(true, "Saved to PC Downloads") else finish(false, "Sent $n of ${uris.size} files")
        }
        val text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()?.trim().orEmpty()
        if (text.isEmpty()) return finish(false, "Nothing to send")
        val url = Regex("https?://\\S+").find(text)?.value
        if (url != null && Patterns.WEB_URL.matcher(url).matches() && text.length < url.length + 120) {
            status = "Opening link on PC…"
            if (Hub.openUrl(url)) finish(true, "Opened in the PC browser") else finish(false, "PC couldn't open the link")
        } else {
            status = "Copying to PC clipboard…"
            if (Hub.pushClipboard(text)) finish(true, "Copied to PC clipboard") else finish(false, "Couldn't copy to the PC")
        }
    }

    private suspend fun finish(ok: Boolean, msg: String) {
        status = msg
        done = ok
        if (ok) Haptics.click() else Haptics.heavy()
        delay(if (ok) 900 else 2200)
        finish()
    }
}
