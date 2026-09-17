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

    @Volatile private var listening = false
    @Volatile private var power: PowerManager? = null
    @Volatile private var lastHeadroomAt = 0L
    @Volatile private var lastHeadroom = Float.NaN

    /** Starts watching. Safe to call repeatedly; only the first call does anything. */
    fun watch(context: Context) {
        if (listening || Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        val manager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
        listening = true
        power = manager
        _status.value = runCatching { manager.currentThermalStatus }.getOrDefault(NONE)
        runCatching {
            manager.addThermalStatusListener { status -> _status.value = status }
        }
    }

    /**
     * How close the phone is to throttling: 0 is cold, 1.0 is the throttling point.
     *
     * Thermal status only changes once throttling has already started, which is too
     * late — the phone is hot by then. Headroom is a forecast, so maik can ease off
     * before the hardware forces it to. NaN means the phone doesn't offer the figure;
     * Android also returns NaN if asked too often, hence the ten-second cache.
     */
    fun headroom(): Float {
        val manager = power ?: return Float.NaN
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return Float.NaN
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastHeadroomAt < POLL_EVERY_MS) return lastHeadroom
        lastHeadroomAt = now
        lastHeadroom = runCatching { manager.getThermalHeadroom(FORECAST_SECONDS) }
            .getOrDefault(Float.NaN)
        return lastHeadroom
    }

    // Android's own guidance: asking more often than this can simply return nothing.
    private const val POLL_EVERY_MS = 10_000L

    /** Far enough ahead to see heat coming, near enough for the forecast to mean anything. */
    private const val FORECAST_SECONDS = 10

    /** True once the phone is warm enough that maik should ease off. */
    fun isWarm(status: Int = _status.value): Boolean = status >= WARM

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
