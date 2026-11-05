package com.flipdesk.app.ui

import android.view.RoundedCorner
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.rounded.KeyboardHide
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.automirrored.rounded.ScreenShare
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.window.layout.FoldingFeature
import com.flipdesk.app.data.LayoutOverride
import com.flipdesk.app.data.Prefs
import com.flipdesk.app.display.BigScreen
import com.flipdesk.app.input.RemoteInput
import com.flipdesk.app.net.ConnState
import com.flipdesk.app.net.Hub
import com.flipdesk.app.util.Haptics
import kotlin.math.max
import kotlin.math.min

enum class Tab(val label: String, val icon: ImageVector) {
    TRACKPAD("Trackpad", Icons.Rounded.TouchApp),
    KEYS("Keys", Icons.Rounded.Keyboard),
    SCREEN("Screen", Icons.AutoMirrored.Rounded.ScreenShare),
    MEDIA("Media", Icons.Rounded.MusicNote),
    LAUNCH("Launch", Icons.Rounded.Apps),
}

enum class LayoutMode { PORTRAIT, LANDSCAPE, LAPTOP, COVER, BIG_SCREEN }

/** Fold posture as the UI needs it: is the phone half-open with a horizontal hinge, and where. */
data class Posture(val laptop: Boolean, val hingeTopPx: Int, val hingeBottomPx: Int)

fun postureOf(f: FoldingFeature?): Posture? {
    if (f == null) return null
    val laptop = f.state == FoldingFeature.State.HALF_OPENED && f.orientation == FoldingFeature.Orientation.HORIZONTAL
    return Posture(laptop, f.bounds.top, f.bounds.bottom)
}

/** Cover screen = small, nearly square window (the Flip8's FlexWindow is 1048×948 px). */
fun isCoverSize(wDp: Float, hDp: Float): Boolean {
    val big = max(wDp, hDp)
    val small = min(wDp, hDp)
    return big < 560f && big / small < 1.4f
}

@Composable
fun MainScreen(posture: Posture?, onSettings: () -> Unit) {
    val settings by Prefs.settings.collectAsStateWithLifecycle()
    val bigScreen by BigScreen.showingOn.collectAsStateWithLifecycle()
    // A new big screen always starts on the trackpad deck.
    LaunchedEffect(bigScreen) { if (bigScreen != null) Deck.hidden = false }
    BoxWithConstraints(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        val w = maxWidth.value
        val h = maxHeight.value
        val mode = when {
            settings.layout == LayoutOverride.COVER || isCoverSize(w, h) -> LayoutMode.COVER
            bigScreen != null && !Deck.hidden -> LayoutMode.BIG_SCREEN
            // Laptop mode streams a zoomed region, which would steal the big screen's full view.
            posture?.laptop == true && bigScreen == null -> LayoutMode.LAPTOP
            w > h -> LayoutMode.LANDSCAPE
            else -> LayoutMode.PORTRAIT
        }
        var tab by rememberSaveable { mutableStateOf(Tab.TRACKPAD) }
        // AnimatedContent keeps composing the outgoing layout while it fades out, after the
        // posture has already changed, so remember the last laptop posture for it.
        val lastLaptop = remember { mutableStateOf<Posture?>(null) }
        if (posture?.laptop == true) lastLaptop.value = posture
        // Leaving Flex mode: let the screen view zoom back out to the full desktop first,
        // so unfolding reverses the zoom-in instead of cutting away.
        var shown by remember { mutableStateOf(mode) }
        LaunchedEffect(mode) {
            if (shown == LayoutMode.LAPTOP && mode != LayoutMode.LAPTOP) LaptopZoom.animateOut()
            shown = mode
        }
        AnimatedContent(
            targetState = shown,
            transitionSpec = { (fadeIn(tween(240)) + scaleIn(tween(240), initialScale = 0.97f)) togetherWith fadeOut(tween(140)) },
            label = "layout",
        ) { m ->
            when (m) {
                LayoutMode.PORTRAIT -> PortraitLayout(tab, { tab = it }, onSettings)
                LayoutMode.LANDSCAPE -> LandscapeLayout(tab, { tab = it }, onSettings)
                LayoutMode.LAPTOP -> LaptopLayout(lastLaptop.value ?: posture ?: return@AnimatedContent)
                LayoutMode.COVER -> CoverRemote()
                LayoutMode.BIG_SCREEN -> BigScreenRemote(posture, w > h, { Deck.hidden = true }, onSettings)
            }
        }
    }
}

@Composable
private fun TopBar(onSettings: () -> Unit, modifier: Modifier = Modifier) {
    val state by Hub.state.collectAsStateWithLifecycle()
    val pc by Hub.pc.collectAsStateWithLifecycle()
    val context = androidx.compose.ui.platform.LocalContext.current
    Row(modifier.fillMaxWidth().height(52.dp).padding(start = 18.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        StatusDot(state)
        HSpace(10.dp)
        Column(Modifier.weight(1f)) {
            Text(state.label(), style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (state is ConnState.Connected && pc.latencyMs != null) Text("${pc.latencyMs} ms", color = Muted, fontSize = 11.sp)
        }
        if (state !is ConnState.Connected && state !is ConnState.Connecting) {
            androidx.compose.material3.TextButton(onClick = { Hub.start(context) }) { Text("Connect") }
        }
        BigScreenButton()
        IconButton(onClick = { Haptics.tick(); onSettings() }) { Icon(Icons.Rounded.Settings, "Settings") }
    }
}

@Composable
private fun TabContent(tab: Tab, modifier: Modifier = Modifier) {
    AnimatedContent(tab, transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(120)) }, label = "tab", modifier = modifier) { t ->
        when (t) {
            Tab.TRACKPAD -> TrackpadPane(Modifier.fillMaxSize())
            Tab.KEYS -> KeysPane(Modifier.fillMaxSize())
            Tab.SCREEN -> ScreenPane(Modifier.fillMaxSize())
            Tab.MEDIA -> MediaPane(Modifier.fillMaxSize())
            Tab.LAUNCH -> LaunchPane(Modifier.fillMaxSize())
        }
    }
}

/** Full-size live view with a keyboard toggle underneath. */
@Composable
fun ScreenPane(modifier: Modifier = Modifier) {
    var typing by remember { mutableStateOf(false) }
    KeepScreenOn(true)
    Column(modifier.padding(horizontal = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        StreamView(Modifier.weight(1f).fillMaxWidth())
        AnimatedVisibility(typing, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                TypeField(Modifier.fillMaxWidth(), autoFocus = true, minHeight = 48.dp)
                ModifierKeys(Modifier.fillMaxWidth(), height = 40.dp)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            KeyCap("Keyboard", { typing = !typing }, Modifier.weight(1.4f), icon = if (typing) Icons.Rounded.KeyboardHide else Icons.Rounded.Keyboard, height = 44.dp)
            KeyCap("Esc", { RemoteInput.key("esc") }, Modifier.weight(1f), height = 44.dp)
            KeyCap("Tab", { RemoteInput.key("tab") }, Modifier.weight(1f), height = 44.dp)
            KeyCap("Win", { RemoteInput.key("win") }, Modifier.weight(1f), height = 44.dp)
            KeyCap("Enter", { RemoteInput.key("enter") }, Modifier.weight(1f), height = 44.dp)
        }
    }
}

@Composable
private fun PortraitLayout(tab: Tab, onTab: (Tab) -> Unit, onSettings: () -> Unit) {
    val settings by Prefs.settings.collectAsStateWithLifecycle()
    // Tall 21:9 main screen: live view on top, controls below, nav at the thumb.
    val preview = settings.livePreview && (tab == Tab.TRACKPAD || tab == Tab.KEYS)
    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))) {
        TopBar(onSettings)
        AnimatedVisibility(preview, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
            StreamView(Modifier.padding(horizontal = 12.dp).padding(bottom = 10.dp).fillMaxWidth().aspectRatio(16f / 9f))
        }
        TabContent(tab, Modifier.weight(1f).fillMaxWidth())
        BottomNav(tab, onTab)
    }
}

@Composable
private fun BottomNav(tab: Tab, onTab: (Tab) -> Unit) {
    NavigationBar(containerColor = MaterialTheme.colorScheme.background, tonalElevation = 0.dp) {
        for (t in Tab.entries) {
            NavigationBarItem(
                selected = t == tab, onClick = { if (t != tab) Haptics.tick(); onTab(t) },
                icon = { Icon(t.icon, t.label) }, label = { Text(t.label, fontSize = 11.sp) },
                colors = NavigationBarItemDefaults.colors(indicatorColor = MaterialTheme.colorScheme.primaryContainer),
            )
        }
    }
}

@Composable
private fun LandscapeLayout(tab: Tab, onTab: (Tab) -> Unit, onSettings: () -> Unit) {
    val settings by Prefs.settings.collectAsStateWithLifecycle()
    Row(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
        NavigationRail(containerColor = MaterialTheme.colorScheme.background,
            header = {
                IconButton(onClick = { Haptics.tick(); onSettings() }) { Icon(Icons.Rounded.Settings, "Settings") }
                BigScreenButton()
            }) {
            for (t in Tab.entries) {
                NavigationRailItem(
                    selected = t == tab, onClick = { if (t != tab) Haptics.tick(); onTab(t) },
                    icon = { Icon(t.icon, t.label) }, label = { Text(t.label, fontSize = 11.sp) },
                    colors = NavigationRailItemDefaults.colors(indicatorColor = MaterialTheme.colorScheme.primaryContainer),
                )
            }
        }
        if (tab == Tab.SCREEN) {
            ScreenPane(Modifier.weight(1f).fillMaxHeight().padding(vertical = 8.dp))
        } else {
            // Split: live view on the left, the chosen controls on the right.
            if (settings.livePreview) {
                Column(Modifier.weight(1.25f).fillMaxHeight().padding(8.dp)) {
                    TopBarCompact()
                    StreamView(Modifier.weight(1f).fillMaxWidth())
                }
            }
            TabContent(tab, Modifier.weight(1f).fillMaxHeight().padding(vertical = 8.dp))
        }
    }
}

@Composable
private fun TopBarCompact() {
    val state by Hub.state.collectAsStateWithLifecycle()
    Row(Modifier.padding(start = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        StatusDot(state)
        HSpace(8.dp)
        Text(state.label(), style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * Flex Mode "mini laptop": live screen on the top half, trackpad and a slim key row on the
 * bottom half. Nothing touchable sits across the hinge.
 */
@Composable
private fun LaptopLayout(p: Posture) {
    val density = LocalDensity.current
    val topDp = with(density) { p.hingeTopPx.toDp() }
    val hingeDp = with(density) { (p.hingeBottomPx - p.hingeTopPx).toDp() }
    var typing by remember { mutableStateOf(false) }
    KeepScreenOn(true)
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxWidth().height(topDp)
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
            .padding(start = 8.dp, end = 8.dp, top = 4.dp, bottom = 10.dp)) {
            ZoomStreamView(Modifier.fillMaxSize(), corner = 16.dp)
        }
        Spacer(Modifier.height(hingeDp + 10.dp)) // dead zone over the hinge
        Column(Modifier.weight(1f).fillMaxWidth()
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal))
            .padding(horizontal = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                val h = 40.dp
                KeyCap("Esc", { RemoteInput.key("esc") }, Modifier.weight(1f), height = h)
                KeyCap("Tab", { RemoteInput.key("tab") }, Modifier.weight(1f), height = h)
                for (m in com.flipdesk.app.input.Modifiers.Mod.entries) {
                    val latch = com.flipdesk.app.input.Modifiers.state[m]
                    KeyCap(m.label.take(4), { com.flipdesk.app.input.Modifiers.tap(m) }, Modifier.weight(1f), height = h,
                        selected = latch == com.flipdesk.app.input.Modifiers.Latch.ONCE,
                        locked = latch == com.flipdesk.app.input.Modifiers.Latch.LOCKED)
                }
                KeyCap("Keys", { typing = !typing }, Modifier.weight(1f), height = h,
                    icon = if (typing) Icons.Rounded.KeyboardHide else Icons.Rounded.Keyboard, selected = typing)
            }
            AnimatedVisibility(typing) { TypeField(Modifier.fillMaxWidth(), autoFocus = true, minHeight = 44.dp) }
            ArrowKeys(Modifier.fillMaxWidth(), height = 36.dp)
            Trackpad(Modifier.weight(1f).fillMaxWidth().padding(bottom = 6.dp), hint = null, corner = 20.dp)
        }
    }
}

/** Inset that keeps content clear of the cover screen's rounded corners (radius × (1 − 1/√2)). */
@Composable
private fun roundedCornerInset(): Dp {
    val view = LocalView.current
    LocalConfiguration.current // recompute when the window changes screens
    val insets = view.rootWindowInsets ?: return 0.dp
    val r = listOf(RoundedCorner.POSITION_TOP_LEFT, RoundedCorner.POSITION_TOP_RIGHT,
        RoundedCorner.POSITION_BOTTOM_LEFT, RoundedCorner.POSITION_BOTTOM_RIGHT)
        .maxOf { insets.getRoundedCorner(it)?.radius ?: 0 }
    return with(LocalDensity.current) { (r * 0.3f).toDp() }
}

/** Compact remote for the Flip8's near-square FlexWindow cover screen. */
@Composable
fun CoverRemote() {
    val state by Hub.state.collectAsStateWithLifecycle()
    val pager = rememberPagerState { 2 }
    val inset = roundedCornerInset()
    val context = androidx.compose.ui.platform.LocalContext.current
    KeepScreenOn(pager.currentPage == 1)
    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(inset)) {
        HorizontalPager(pager, Modifier.weight(1f).fillMaxWidth()) { page ->
            if (page == 0) {
                Column(Modifier.fillMaxSize().padding(horizontal = 6.dp), horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.SpaceEvenly) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        StatusDot(state)
                        HSpace(6.dp)
                        Text(state.label(), fontSize = 12.sp, color = Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (state !is ConnState.Connected && state !is ConnState.Connecting) {
                            androidx.compose.material3.TextButton(onClick = { Hub.start(context) }) { Text("Connect", fontSize = 12.sp) }
                        }
                    }
                    NowPlayingText(Modifier.fillMaxWidth(), big = false)
                    TransportControls(Modifier.fillMaxWidth(), big = 80.dp, small = 60.dp)
                    VolumeRow(Modifier.fillMaxWidth(), compact = true)
                    KeyCap("Lock PC", { Hub.send("power", "action" to "lock"); Haptics.heavy() },
                        Modifier.fillMaxWidth(0.6f), icon = null, height = 44.dp)
                }
            } else {
                Column(Modifier.fillMaxSize().padding(horizontal = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Trackpad(Modifier.weight(1f).fillMaxWidth(), hint = "Mini trackpad", corner = 22.dp)
                    MouseButtonsRow(Modifier.fillMaxWidth(), height = 44.dp)
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.Center) {
            repeat(2) { i ->
                Box(Modifier.padding(3.dp).size(if (pager.currentPage == i) 8.dp else 6.dp).clip(CircleShape)
                    .background(if (pager.currentPage == i) MaterialTheme.colorScheme.primary else Muted.copy(alpha = 0.5f)))
            }
        }
    }
}
