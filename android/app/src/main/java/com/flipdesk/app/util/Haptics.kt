package com.flipdesk.app.util

import android.content.Context
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import com.flipdesk.app.data.Prefs

/** Crisp haptics using composition primitives where the motor supports them (Galaxy phones do). */
object Haptics {
    private var vibrator: Vibrator? = null
    private var tick: VibrationEffect? = null
    private var click: VibrationEffect? = null
    private var heavy: VibrationEffect? = null

    fun init(context: Context) {
        val v = context.getSystemService(VibratorManager::class.java)?.defaultVibrator ?: return
        if (!v.hasVibrator()) return
        vibrator = v
        fun prim(p: Int, scale: Float, fallback: Int): VibrationEffect =
            if (v.areAllPrimitivesSupported(p)) VibrationEffect.startComposition().addPrimitive(p, scale).compose()
            else VibrationEffect.createPredefined(fallback)
        tick = prim(VibrationEffect.Composition.PRIMITIVE_TICK, 0.7f, VibrationEffect.EFFECT_TICK)
        click = prim(VibrationEffect.Composition.PRIMITIVE_CLICK, 0.9f, VibrationEffect.EFFECT_CLICK)
        heavy = prim(VibrationEffect.Composition.PRIMITIVE_THUD, 1f, VibrationEffect.EFFECT_HEAVY_CLICK)
    }

    private fun play(e: VibrationEffect?) {
        if (e != null && Prefs.settings.value.haptics) runCatching { vibrator?.vibrate(e) }
    }

    fun tick() = play(tick)
    fun click() = play(click)
    fun heavy() = play(heavy)
}
