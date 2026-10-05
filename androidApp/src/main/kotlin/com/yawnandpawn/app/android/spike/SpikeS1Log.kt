// SPIKE S1 (branch spike/s1-billing-lockscreen only, never merged to main). Throwaway prototype code.
package com.yawnandpawn.app.android.spike

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log

/**
 * The spike's evidence log: every line goes to logcat under [TAG] (`adb logcat -s SpikeS1`) and into an in-memory
 * buffer the spike screen shows and shares. Each line starts with `t=<elapsedRealtime ms>` and, after a "Pay" tap,
 * `+<ms since Pay>`, so the timings for question 4 can be read straight from the log.
 */
object SpikeS1Log {
    const val TAG = "SpikeS1"
    private const val MAX_LINES = 3000

    private val main = Handler(Looper.getMainLooper())
    private val lines = ArrayDeque<String>()

    /** Called on the main thread after every new line (the open spike screen refreshes). */
    @Volatile
    var onChange: (() -> Unit)? = null

    /** elapsedRealtime of the last "Pay" / "Pay without unlock" tap; null before the first one. */
    @Volatile
    var payAt: Long? = null

    fun now(): Long = SystemClock.elapsedRealtime()

    /** Milliseconds since the last Pay tap, or null before the first one. */
    fun sincePay(): Long? = payAt?.let { now() - it }

    fun log(message: String) {
        val t = now()
        val since = payAt?.let { " +${t - it}ms" } ?: ""
        val line = "t=$t$since | $message"
        Log.i(TAG, line)
        synchronized(lines) {
            lines.addLast(line)
            while (lines.size > MAX_LINES) lines.removeFirst()
        }
        main.post { onChange?.invoke() }
    }

    /** The newest [count] lines, newest first. */
    fun newest(count: Int): List<String> = synchronized(lines) { lines.toList().asReversed().take(count) }

    /** The whole buffer, oldest first (for "Share log"). */
    fun all(): String = synchronized(lines) { lines.joinToString("\n") }

    fun clear() {
        synchronized(lines) { lines.clear() }
        log("log cleared")
    }
}
