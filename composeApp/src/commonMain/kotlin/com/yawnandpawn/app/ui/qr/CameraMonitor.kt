package com.yawnandpawn.app.ui.qr

/** The camera of the wake QR check as [CameraMonitor] sees it (Story 3.11). */
sealed interface CameraStatus {
    /** Bound, and no frame yet: the viewfinder shows and the watchdog runs. */
    data object Starting : CameraStatus

    /** Frames arrive. */
    data object Live : CameraStatus

    /** The camera cannot be used, for [problem]: "Camera isn't available. Pick a fallback check." */
    data class Unavailable(
        val problem: CameraProblem,
    ) : CameraStatus
}

/**
 * The camera's status for one entry of the wake QR check (Story 3.11). Pure: no Compose and no clock of its own; every
 * time is a `MonotonicClock` reading in milliseconds (AD-3), never the wall clock.
 * - **Watchdog:** no frame for [timeoutMillis] (5 s), counted from the moment the camera [opened] and then from the last
 *   frame, is [CameraProblem.NoFrames], so a camera that never sends a frame and one that freezes mid-scan both bring up
 *   the message. Counting from the open, not the bind, keeps a slow cold start (CameraX and the first decode on a slow
 *   phone) from latching the link on a working camera (review). A camera that never opens is caught [openLimitMillis]
 *   (15 s) after the bind.
 * - **Recovery:** after a problem that is not [sticky][CameraProblem.sticky], frames for at least [recoverMillis] (1 s,
 *   with no gap longer than that) make it [CameraStatus.Live] again, so a flaky camera does not flicker. A sticky
 *   problem ignores frames (black frames with the privacy toggle decode "fine") until the next [bound].
 * - **Latches**, for the fallback link: [everUnavailable] once any problem was seen, and [nothingRead] once the camera
 *   has gone [nothingReadMillis] (60 s) without reading any code, counted from the bind or the last code read, whichever
 *   is later (review: one wrong code must not disarm it). Neither is ever cleared: the link stays.
 */
class CameraMonitor(
    private val timeoutMillis: Long = TIMEOUT_MILLIS,
    private val recoverMillis: Long = RECOVER_MILLIS,
    private val nothingReadMillis: Long = NOTHING_READ_MILLIS,
    private val openLimitMillis: Long = OPEN_LIMIT_MILLIS,
) {
    /** The status now. */
    var status: CameraStatus = CameraStatus.Starting
        private set

    /** The camera was unavailable at some point: the link stays (reason `CameraUnavailable`). */
    var everUnavailable: Boolean = false
        private set

    /** The camera went [nothingReadMillis] without reading any code (a muted or covered camera, a damaged code). */
    var nothingRead: Boolean = false
        private set

    /** The fallback is offered for the camera: it was unavailable, or nothing was read. */
    val offersFallback: Boolean
        get() = everUnavailable || nothingRead

    private var boundAt = 0L
    private var open = false
    private var lastFrameAt = 0L
    private var lastCodeAt = 0L
    private var recoveringSince: Long? = null

    /** The camera was bound at [now] (each resume binds it again): the watchdog and the "nothing read" time start over. */
    fun bound(now: Long) {
        status = CameraStatus.Starting
        boundAt = now
        open = false
        lastFrameAt = now
        lastCodeAt = now
        recoveringSince = null
    }

    /** The camera reported itself open at [now]: while it starts, the 5 s for its first frame count from here. */
    fun opened(now: Long) {
        if (status == CameraStatus.Starting && !open) {
            open = true
            lastFrameAt = now
        }
    }

    /** A frame arrived at [now]. */
    fun frame(now: Long) {
        val current = status
        val gap = now - lastFrameAt
        open = true
        lastFrameAt = now
        when {
            current is CameraStatus.Unavailable && current.problem.sticky -> {
                Unit
            }

            current is CameraStatus.Unavailable -> {
                val since = recoveringSince?.takeIf { gap <= recoverMillis } ?: now
                recoveringSince = since
                if (now - since >= recoverMillis) {
                    status = CameraStatus.Live
                    recoveringSince = null
                }
            }

            else -> {
                status = CameraStatus.Live
            }
        }
    }

    /** A code (right or wrong) was read at [now]: the camera works, and it is a frame too. */
    fun code(now: Long) {
        lastCodeAt = now
        frame(now)
    }

    /** The scanner (or the watchdog) reported [problem]. */
    fun problem(problem: CameraProblem) {
        everUnavailable = true
        recoveringSince = null
        // A sticky problem stays until the next bind, whatever else is reported after it.
        if ((status as? CameraStatus.Unavailable)?.problem?.sticky != true) status = CameraStatus.Unavailable(problem)
    }

    /** The watchdog at [now]: no frame in time is [CameraProblem.NoFrames]; see also [nothingRead]. */
    fun tick(now: Long) {
        val current = status
        val noFrames = if (open) now - lastFrameAt >= timeoutMillis else now - boundAt >= openLimitMillis
        if (current !is CameraStatus.Unavailable && noFrames) problem(CameraProblem.NoFrames)
        if (current is CameraStatus.Unavailable && recoveringSince != null && now - lastFrameAt > recoverMillis) recoveringSince = null
        if (now - maxOf(boundAt, lastCodeAt) >= nothingReadMillis) nothingRead = true
    }

    companion object {
        /** "No frame within 5 s" (epic AC). */
        const val TIMEOUT_MILLIS = 5_000L

        /** Frames needed to call a camera back (default taken). */
        const val RECOVER_MILLIS = 1_000L

        /** A bound camera that read no code for this long offers the fallback link (default taken). */
        const val NOTHING_READ_MILLIS = 60_000L

        /** A camera that has not opened this long after the bind is unavailable (review: an upper bound for the open). */
        const val OPEN_LIMIT_MILLIS = 15_000L
    }
}
