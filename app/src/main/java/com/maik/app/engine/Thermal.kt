package com.maik.app.engine

import android.content.Context
import android.os.Build
import android.os.PowerManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * How hot the phone is, as Android sees it.
 *
 * Decoding a language model pins several cores for as long as the reply takes, which
 * is exactly the load that makes a phone hot. Android already knows when the hardware
 * has started throttling; listening is far better than guessing from a timer.
 */
object Thermal {

    /** One of [PowerManager]'s THERMAL_STATUS_* values; NONE when the phone is cool. */
    private val _status = MutableStateFlow(NONE)
    val status: StateFlow<Int> = _status

    const val NONE = 0

    /** The phone has started throttling: still usable, but it is warm in the hand. */
    const val WARM = PowerManager.THERMAL_STATUS_MODERATE

    /** Throttling hard. Anything long-running should stop rather than add to it. */
    const val HOT = PowerManager.THERMAL_STATUS_SEVERE

    private var listening = false

    /** Starts watching. Safe to call repeatedly; only the first call does anything. */
    fun watch(context: Context) {
        if (listening || Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        val power = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
        listening = true
        _status.value = runCatching { power.currentThermalStatus }.getOrDefault(NONE)
        runCatching {
            power.addThermalStatusListener { status -> _status.value = status }
        }
    }

    /** True once the phone is warm enough that maik should ease off. */
    fun isWarm(status: Int = _status.value): Boolean = status >= WARM

    /** True once maik should stop generating rather than make things worse. */
    fun isHot(status: Int = _status.value): Boolean = status >= HOT

    /**
     * How many CPU threads to decode with.
     *
     * All of them is fastest and hottest. Half the cores keeps the phone usable and
     * the fans — which phones don't have — out of the equation, at a cost of maybe a
     * fifth of the speed. Four is the practical ceiling: past that, phones of this
     * class gain little and heat a lot.
     */
    fun threadsFor(keepCool: Boolean): Int {
        val cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(2)
        return if (keepCool) (cores / 2).coerceIn(2, 4) else (cores - 2).coerceIn(2, 6)
    }
}
