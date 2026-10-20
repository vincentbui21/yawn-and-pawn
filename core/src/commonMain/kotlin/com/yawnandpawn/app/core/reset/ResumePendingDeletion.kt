package com.yawnandpawn.app.core.reset

import com.yawnandpawn.app.core.error.DomainError
import com.yawnandpawn.app.core.error.Outcome
import com.yawnandpawn.app.core.log.LogEvent
import com.yawnandpawn.app.core.log.Logger
import com.yawnandpawn.app.core.session.SessionLockGuard
import com.yawnandpawn.app.core.session.SessionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Finishes an unfinished "Delete all data" (Story 5.9 and its review): a crash left the marker, or a run failed.
 *
 * - [onAppStart] runs once per process from `Application.onCreate`, before the alarms are re-armed: with the marker
 *   there it counts the start ([DeletionMarker.countStart]) and holds the app ([PendingDeletion.hold]), so nothing is
 *   created on top of the half-done delete and no background start re-arms its alarms. After [MAX_STARTS] starts that
 *   found the marker (a deletion that keeps failing, or a resume that crashes the app) it gives up instead.
 * - [ensureRunning] starts the resume when a deletion is pending and none runs (the app start, and the restoring screen
 *   after a failed delete).
 *
 * The resume first restores the session when nothing is stored ([restoreSession], so a start with no activity, such as
 * a boot, can finish too), then waits for the session lock to open (a ring or snooze is never held up: it waits as long
 * as they last) and for Idle. A session left Completed or Missed (its history row not written) longer than [idleWait]
 * makes it give up. Each failed run is tried again after [retryDelay], [MAX_TRIES] times in all.
 *
 * Giving up releases the app ([PendingDeletion.release]: the app goes on to Home and writes again) and is logged; the
 * marker stays, so the next start tries again until [MAX_STARTS].
 */
@Suppress("LongParameterList")
class ResumePendingDeletion(
    private val deleteAllData: DeleteAllData,
    private val pendingDeletion: PendingDeletion,
    private val marker: DeletionMarker,
    private val sessionLock: SessionLockGuard,
    private val scope: CoroutineScope,
    private val logger: Logger,
    /** Restores the stored session when nothing is stored; the app wires `SessionEngine.restore`. */
    private val restoreSession: suspend () -> Unit = {},
    private val idleWait: Duration = IDLE_WAIT,
    private val retryDelay: Duration = RETRY_DELAY,
) {
    private val running = MutableStateFlow(false)

    /** The app start (see the class); the resume's job, or null when nothing is pending or it gave up. */
    fun onAppStart(): Job? {
        val starts = if (marker.exists()) marker.countStart() else 0
        when {
            starts == 0 -> pendingDeletion.release()
            starts > MAX_STARTS -> giveUp("$starts starts found the marker")
            else -> pendingDeletion.hold()
        }
        return ensureRunning()
    }

    /** Starts the resume when a deletion is pending and none runs; its job, or null. */
    fun ensureRunning(): Job? {
        if (!pendingDeletion.pending.value || !running.compareAndSet(expect = false, update = true)) return null
        return scope.launch {
            try {
                resume()
            } finally {
                running.value = false
            }
        }
    }

    private suspend fun resume() {
        restoreSession()
        var failures = 0
        var finished = false
        while (!finished) {
            when (val attempt = runOnce()) {
                Attempt.Done -> {
                    finished = true
                }

                Attempt.Refused -> {
                    Unit
                }

                is Attempt.GaveUp -> {
                    giveUp(attempt.reason)
                    finished = true
                }

                Attempt.Failed -> {
                    failures++
                    finished = failures >= MAX_TRIES
                    if (finished) giveUp("$failures runs failed") else delay(retryDelay)
                }
            }
        }
    }

    /** One wait and run: done (deleted, or nothing left to delete), refused (a session started first), failed or given up. */
    private suspend fun runOnce(): Attempt =
        when {
            !awaitIdle() -> {
                Attempt.GaveUp("the session stayed unfinished for $idleWait")
            }

            !marker.exists() -> {
                pendingDeletion.release()
                Attempt.Done
            }

            else -> {
                when (deleteAllData.resume()) {
                    is Outcome.Success -> Attempt.Done
                    Outcome.Failure(DomainError.SessionActive) -> Attempt.Refused
                    else -> Attempt.Failed
                }
            }
        }

    /**
     * Waits until the session lock is open and the session is Idle: true then, false once a session stays Completed or
     * Missed for [idleWait] (the wait for a ring or snooze has no limit, and starts the [idleWait] again after it).
     */
    private suspend fun awaitIdle(): Boolean {
        var idle: Boolean? = false
        while (idle == false) {
            sessionLock.locked.first { !it }
            idle =
                withTimeoutOrNull(idleWait) {
                    combine(sessionLock.locked, sessionLock.state) { locked, state ->
                        when {
                            locked -> false
                            state == SessionState.Idle -> true
                            else -> null
                        }
                    }.filterNotNull().first()
                }
        }
        return idle == true
    }

    private fun giveUp(reason: String) {
        pendingDeletion.release()
        logger.log(LogEvent.OperationFailed(OPERATION, "gave up: $reason"))
    }

    private sealed interface Attempt {
        data object Done : Attempt

        data object Refused : Attempt

        data object Failed : Attempt

        data class GaveUp(
            val reason: String,
        ) : Attempt
    }

    companion object {
        /** The operation logged when the resume gives up. */
        const val OPERATION = "resume delete all data"

        /** App starts that may find the marker before the resume gives up. */
        const val MAX_STARTS = 3

        /** Runs per process before the resume gives up. */
        const val MAX_TRIES = 3

        val IDLE_WAIT: Duration = 10.seconds
        val RETRY_DELAY: Duration = 2.seconds
    }
}
