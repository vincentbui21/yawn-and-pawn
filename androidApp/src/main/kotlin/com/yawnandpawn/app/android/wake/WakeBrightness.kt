package com.yawnandpawn.app.android.wake

import android.content.ContentResolver
import android.os.Build
import android.provider.Settings
import android.view.Window
import android.view.WindowManager
import java.util.concurrent.atomic.AtomicBoolean

/**
 * "Bright wake screen" (Story 5.2, owner decision 2026-10-22, option C "Ringing only"): the one owner of the wake
 * window's `screenBrightness`. The window is at full brightness only while all of these hold:
 * - the shown screen is a session's Ringing screen (with or without the snooze confirm sheet over it) and that
 *   session's frozen config has the setting on ([show]);
 * - the activity is started ([onStart] to [onStop]);
 * - the system Extra dim is not known to be on ([extraDim], read only when full brightness could apply, at most once per
 *   start; an unreadable Extra dim counts as off, see [extraDimOn]).
 *
 * Everything else is at the phone's own level (`BRIGHTNESS_OVERRIDE_NONE`): the Check screen, the Fallback check
 * picker, Snoozed, Success, the emergency ring, the wait before the session shows, and after Home, Recents, the screen
 * off or finish. The override is window-scoped (no `WRITE_SETTINGS`), so leaving the window ends it anyway.
 *
 * The window attribute is written only when the wanted level changes, and never while the full level was never applied:
 * with the setting off it is not touched at all. Nothing here suspends or throws; the only read is the Extra dim setting,
 * at most once per start and only on a bright Ringing screen, so brightness never delays or breaks a ring.
 */
internal class WakeBrightness(
    private val window: () -> Window,
    private val extraDim: () -> Boolean,
) {
    /** The screen shown wants full brightness ([show]). */
    private var wanted = false

    /** Between [onStart] and [onStop]. */
    private var started = false

    /** This helper wrote `BRIGHTNESS_OVERRIDE_FULL` and has not taken it back yet. */
    private var applied = false

    /** Extra dim as read since the last [onStart]; null until full brightness first applies in that start. */
    private var dim: Boolean? = null

    /** The screen now shown: [full] for a session's Ringing screen whose frozen config has "Bright wake screen" on. */
    fun show(full: Boolean) {
        wanted = full
        update()
    }

    /** Visible again (back from Home or Recents, the screen on): full again while the Ringing screen shows. */
    fun onStart() {
        started = true
        dim = null
        update()
    }

    /** Left (Home, Recents, the screen off, finish): back to the phone's own level. */
    fun onStop() {
        started = false
        update()
    }

    private fun update() {
        // Extra dim is read only when full brightness could apply, at most once per start.
        val full = wanted && started && !(dim ?: extraDim().also { dim = it })
        if (full == applied) return
        applied = full
        val target = window()
        target.attributes = target.attributes.apply { screenBrightness = if (full) FULL else NONE }
    }

    companion object {
        /** Full brightness, 1f. */
        const val FULL: Float = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_FULL

        /** The phone's own level, -1f. */
        const val NONE: Float = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE

        /**
         * The `Settings.Secure` key of the system Extra dim (API 31+), a hidden constant. It is not `@Readable`, so on a
         * real phone the settings provider throws `SecurityException` for an installed app like this one (only system,
         * platform-signed or `testOnly` apps can read it); no public API exposes Extra dim (`ColorDisplayManager` is
         * hidden and system-only). Robolectric does not enforce this, so tests can set it.
         */
        const val EXTRA_DIM_KEY = "reduce_bright_colors_activated"

        /** A failed Extra dim read was already passed to an `onFailure` in this process. */
        private val failureReported = AtomicBoolean(false)

        /**
         * Whether the system Extra dim is on (API 31+; never before). An unreadable value (a `SecurityException`, which
         * the Story 5.13 check found on every start on the Oppo A96, or any other failure) counts as off, so the Ringing
         * screen still turns bright; its cause is passed to [onFailure] once per process, so the log is not spammed.
         */
        fun extraDimOn(
            resolver: ContentResolver,
            onFailure: (cause: String) -> Unit = {},
        ): Boolean = extraDimOn(Build.VERSION.SDK_INT, onFailure) { Settings.Secure.getInt(resolver, EXTRA_DIM_KEY, 0) }

        /**
         * [extraDimOn] on API [sdk] with the setting's value from [read]; [reported] marks a failure as already reported
         * (tests give it a failing read and their own flag).
         */
        internal fun extraDimOn(
            sdk: Int,
            onFailure: (cause: String) -> Unit,
            reported: AtomicBoolean = failureReported,
            read: () -> Int,
        ): Boolean {
            if (sdk < Build.VERSION_CODES.S) return false
            return runCatching { read() == 1 }
                .onFailure { if (reported.compareAndSet(false, true)) onFailure(it::class.simpleName ?: "exception") }
                .getOrDefault(false)
        }
    }
}
