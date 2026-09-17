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

    /** Throttling hard. Anything long-running should stop rather than add to it. */
    const val HOT = PowerManager.THERMAL_STATUS_SEVERE

    @Volatile private var listening = false

    /** Starts watching. Safe to call repeatedly; only the first call does anything. */
    fun watch(context: Context) {
        if (listening || Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        val manager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
        listening = true
        _status.value = runCatching { manager.currentThermalStatus }.getOrDefault(NONE)
        runCatching {
            manager.addThermalStatusListener { status -> _status.value = status }
        }
    }

    /** True once maik should stop generating rather than make things worse. */
    fun isHot(status: Int = _status.value): Boolean = status >= HOT

    /**
     * How many CPU threads to decode with.
     *
     * Four is the runtime's own default and the practical ceiling: writing a reply is
     * limited by how fast memory can be read, not by arithmetic, so more threads add
     * heat and contention without adding words. Three keeps the work off the one huge
     * core, which costs the most energy per word of any core on the chip.
     */
    fun threadsFor(keepCool: Boolean): Int = if (keepCool) 3 else 4
}
