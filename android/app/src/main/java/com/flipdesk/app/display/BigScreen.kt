package com.flipdesk.app.display

import android.app.Activity
import android.app.ActivityOptions
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Display
import android.view.WindowManager
import com.flipdesk.app.data.Prefs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.max

/**
 * "Big screen" mode: when the phone drives an external display (Samsung DeX, HDMI mirroring,
 * Smart View / Miracast), the PC stream goes full screen there and the phone becomes the
 * trackpad and keyboard. Tracks external displays and what's currently shown on them.
 */
object BigScreen {
    private lateinit var dm: DisplayManager

    private val _external = MutableStateFlow<Display?>(null)
    /** The external display we'd show the PC on, if one is connected. */
    val external: StateFlow<Display?> = _external.asStateFlow()

    private val _showingOn = MutableStateFlow<Int?>(null)
    /** Display id the PC stream is currently full screen on (activity or presentation). */
    val showingOn: StateFlow<Int?> = _showingOn.asStateFlow()

    private val _dismissed = MutableStateFlow<Int?>(null)
    /** The user closed the big screen on this display; stays closed until it's re-plugged. */
    val dismissed: StateFlow<Int?> = _dismissed.asStateFlow()

    private val _closeRequests = MutableStateFlow(0)
    /** Bumped when the phone asks the big-screen view to close. */
    val closeRequests: StateFlow<Int> = _closeRequests.asStateFlow()

    private val _mouseActive = MutableStateFlow(false)
    /** A real mouse is moving over the big screen: show its own pointer instead of the drawn PC cursor. */
    val mouseActive: StateFlow<Boolean> = _mouseActive.asStateFlow()

    /** Displays that won't host our activity (some mirroring modes); they get a Presentation instead. */
    private val refused = mutableSetOf<Int>()
    private var launchedAt = 0L

    fun init(context: Context) {
        dm = context.getSystemService(DisplayManager::class.java)
        dm.registerDisplayListener(object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) = refresh()
            override fun onDisplayRemoved(displayId: Int) = refresh()
            override fun onDisplayChanged(displayId: Int) = refresh()
        }, Handler(Looper.getMainLooper()))
        refresh()
    }

    /**
     * An external screen, not the phone's own panels. The Flip's cover screen can be a separate
     * display, but it's never a presentation display and is far smaller than any monitor or TV.
     */
    fun isExternal(d: Display): Boolean {
        if (d.displayId == Display.DEFAULT_DISPLAY || d.state == Display.STATE_OFF) return false
        if (d.flags and Display.FLAG_PRIVATE != 0) return false
        if (d.flags and Display.FLAG_PRESENTATION != 0) return true
        val m = d.mode
        return max(m.physicalWidth, m.physicalHeight) >= 1600 // a DeX desktop that isn't flagged as presentation
    }

    private fun refresh() {
        val ext = dm.displays.firstOrNull(::isExternal)
        _external.value = ext
        val live = dm.displays.map { it.displayId }.toSet()
        refused.retainAll(live)
        if (_dismissed.value != null && _dismissed.value !in live) _dismissed.value = null
    }

    /** Should the PC be put on [d] right now? */
    fun wanted(d: Display): Boolean =
        Prefs.settings.value.bigScreen && Prefs.server.value != null && _dismissed.value != d.displayId

    fun activityRefused(displayId: Int) = displayId in refused

    /**
     * From a foreground activity: open the full-screen PC view on [d]. Returns false if the
     * display won't take activities, so the caller can fall back to a Presentation.
     */
    fun launch(activity: Activity, d: Display): Boolean {
        if (d.displayId in refused) return false
        val now = android.os.SystemClock.uptimeMillis()
        if (now - launchedAt < 3_000) return true // already on its way
        val opts = ActivityOptions.makeBasic().setLaunchDisplayId(d.displayId)
        // In DeX / desktop windowing, open as big as the display instead of a small window.
        runCatching {
            activity.createDisplayContext(d).getSystemService(WindowManager::class.java).maximumWindowMetrics.bounds
        }.getOrNull()?.let { opts.setLaunchBounds(it) }
        val intent = Intent(activity, BigScreenActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra(BigScreenActivity.EXTRA_DISPLAY, d.displayId)
        return try {
            activity.startActivity(intent, opts.toBundle())
            launchedAt = now
            true
        } catch (e: SecurityException) {
            Log.i("FlipDesk", "display ${d.displayId} refused the big-screen activity", e)
            refused += d.displayId
            false
        } catch (e: ActivityNotFoundException) {
            refused += d.displayId
            false
        }
    }

    /** The activity ended up somewhere else (e.g. the phone screen): that display needs a Presentation. */
    internal fun misplaced(displayId: Int) {
        if (displayId >= 0) refused += displayId
        launchedAt = 0L
        _retry.value++
    }

    private val _retry = MutableStateFlow(0)
    /** Bumped when observers should re-check what to show (e.g. after a misplaced launch). */
    val retry: StateFlow<Int> = _retry.asStateFlow()

    internal fun shown(displayId: Int) { _showingOn.value = displayId }

    internal fun hidden(displayId: Int) {
        if (_showingOn.value == displayId) _showingOn.value = null
        _mouseActive.value = false
    }

    /** Phone's "stop" button: close the big screen until the display is plugged in again. */
    fun close() {
        _dismissed.value = _showingOn.value ?: _external.value?.displayId
        _closeRequests.value++
    }

    internal fun closedOn(displayId: Int) {
        if (_dismissed.value == null) _dismissed.value = displayId
    }

    /** Phone's "show on big screen" button after a close. */
    fun reopen() { _dismissed.value = null; launchedAt = 0L }

    internal fun setMouseActive(active: Boolean) { _mouseActive.value = active }
}
