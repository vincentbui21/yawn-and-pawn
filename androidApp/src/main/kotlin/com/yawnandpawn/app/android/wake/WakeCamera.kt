package com.yawnandpawn.app.android.wake

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.yawnandpawn.app.core.checks.CheckType
import com.yawnandpawn.app.core.log.LogEvent
import com.yawnandpawn.app.core.log.Logger
import com.yawnandpawn.app.core.session.SessionState
import com.yawnandpawn.app.core.time.MonotonicClock
import com.yawnandpawn.app.ui.qr.CameraMonitor
import com.yawnandpawn.app.ui.qr.CameraStatus
import com.yawnandpawn.app.ui.qr.ScanEvent

/** The QR/Barcode entry a camera is followed for: its session, ring and entry, and whether it is a fallback (Story 3.11). */
internal data class CameraEntry(
    val sessionId: String,
    val ringIndex: Int,
    val entry: Int,
    val fallback: Boolean,
) {
    companion object {
        /** The QR/Barcode entry [state] waits on in Grace or Loud, or null. */
        fun of(state: SessionState): CameraEntry? {
            val session = (state as? SessionState.Grace)?.session ?: (state as? SessionState.Loud)?.session ?: return null
            val run = session.checkRun
            return if (run.currentEntry?.type == CheckType.QrBarcode) {
                CameraEntry(session.sessionId, session.ringIndex, run.step.entry, run.fallbackUsed)
            } else {
                null
            }
        }
    }
}

/**
 * The camera of the wake QR check as the screen sees it (Story 3.11, part of [WakeQr]): one [CameraMonitor] per entry,
 * fed at the [monotonicClock]'s now, its status as snapshot state for the screen, and the entry whose camera offers the
 * fallback (latched). Each change into an unavailable status is logged once, as `OperationFailed("camera", problem)`.
 */
internal class WakeCamera(
    private val monotonicClock: MonotonicClock,
    private val logger: Logger,
) {
    /** The entry followed and its monitor; replaced on each new entry. Not snapshot state: [status] is. */
    private var entry: CameraEntry? = null
    private var monitor = CameraMonitor()

    /** The [monitor]'s status for its entry, observed by the screen. */
    private var status by mutableStateOf<Pair<CameraEntry?, CameraStatus>>(null to CameraStatus.Starting)

    /** The entry whose camera offers the fallback (unavailable once, nothing read, or no permission). */
    private var fallbackOn by mutableStateOf<CameraEntry?>(null)

    /** The scan is in composition and the screen resumed: the monitor is fed and the watchdog runs. */
    var watching = false
        private set

    /** Follows [next]: a new entry gets a new monitor, bound at once while watching. */
    fun follow(next: CameraEntry?) {
        if (next == entry) return
        entry = next
        monitor = CameraMonitor()
        if (watching) monitor.bound(monotonicClock.elapsedMillis())
        publish()
    }

    /** The fallback is offered for [key] whatever the monitor says (the permission is missing). */
    fun latch(key: CameraEntry) {
        fallbackOn = key
    }

    /**
     * The screen resumed with the scan ([resumed]: bound again, the watchdog from 0), or paused or the scan left (the
     * watchdog stops).
     */
    fun watch(resumed: Boolean) {
        watching = resumed
        if (resumed) update { monitor.bound(it) }
    }

    /** What the scanner reported: a code (the camera works), a heartbeat or a problem. */
    fun onEvent(event: ScanEvent) =
        when (event) {
            is ScanEvent.Detected -> update { monitor.code(it) }
            ScanEvent.Frame -> update { monitor.frame(it) }
            is ScanEvent.CameraUnavailable -> update { monitor.problem(event.problem) }
        }

    /** The watchdog's tick, only while [watching]. */
    fun tick() {
        if (watching) update { monitor.tick(it) }
    }

    /** The status of [key]'s camera: Starting until its monitor says otherwise. */
    fun statusOf(key: CameraEntry?): CameraStatus {
        val (shownFor, shown) = status
        return if (key != null && shownFor == key) shown else CameraStatus.Starting
    }

    /** Whether [key]'s camera offers the fallback (latched for the entry). */
    fun offersFallback(key: CameraEntry?): Boolean = key != null && fallbackOn == key

    private inline fun update(change: (Long) -> Unit) {
        change(monotonicClock.elapsedMillis())
        publish()
    }

    private fun publish() {
        val now = monitor.status
        val (shownFor, shown) = status
        if (shownFor != entry || shown != now) {
            if (now is CameraStatus.Unavailable) logger.log(LogEvent.OperationFailed("camera", now.problem.name))
            status = entry to now
        }
        entry?.takeIf { monitor.offersFallback }?.let { fallbackOn = it }
    }
}
