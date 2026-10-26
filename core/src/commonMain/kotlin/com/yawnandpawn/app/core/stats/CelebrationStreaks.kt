package com.yawnandpawn.app.core.stats

import com.yawnandpawn.app.core.error.Outcome
import com.yawnandpawn.app.core.history.SessionHistoryRepository
import com.yawnandpawn.app.core.history.SessionHistoryRow
import com.yawnandpawn.app.core.log.LogEvent
import com.yawnandpawn.app.core.log.Logger
import com.yawnandpawn.app.core.log.diagnostic
import com.yawnandpawn.app.core.session.SessionData
import com.yawnandpawn.app.core.time.Clock
import com.yawnandpawn.app.core.time.TimeZoneProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.datetime.toLocalDateTime
import kotlin.coroutines.cancellation.CancellationException

/**
 * The history snapshot behind the zero-snooze celebration (Story 6.4), in memory only (AD-18: nothing derived is
 * stored). The wake screen calls [request] when it shows an ongoing real session and [prepare] after its first drawn
 * frame (NFR-7: the ring never waits for it), and the Success screen asks [streakFor] once, synchronously: it never
 * suspends and never shows a loading state. A read only: `SessionRecorder` stays the only writer of history.
 *
 * Only the latest session's snapshot is kept. Thread-safe: [prepare] runs on the app scope, [streakFor] on the main
 * thread.
 */
class CelebrationStreaks(
    private val history: SessionHistoryRepository,
    private val clock: Clock,
    private val zones: TimeZoneProvider,
    private val logger: Logger,
) {
    private val snapshot = MutableStateFlow<Snapshot>(Snapshot.NotRequested)

    /** The decision already made for a session id: Success shows the same one after a recomposition or a recreation. */
    private val decided = MutableStateFlow<Pair<String, SuccessStreak>?>(null)

    /**
     * Marks that the wake screen shows [sessionId] and will read its snapshot ([prepare]): memory only, no I/O, so the
     * screen calls it at once. A Success before the read starts then shows "Up on time." (Pending), not the
     * missing-snapshot fallback. Does nothing for the session already requested.
     */
    fun request(sessionId: String) {
        snapshot.update { current -> if (current.sessionId == sessionId) current else Snapshot.Requested(sessionId) }
    }

    /**
     * Reads history once for [sessionId]; a later call for the same id does nothing, and a new id drops the snapshot of
     * the one before. A failed read is kept as failed (Success then shows no number); it is never thrown.
     */
    suspend fun prepare(sessionId: String) {
        var claimed = false
        snapshot.update { current ->
            claimed = current.sessionId != sessionId || current is Snapshot.Requested
            if (claimed) Snapshot.Loading(sessionId) else current
        }
        if (!claimed) return
        val loaded = read(sessionId)
        snapshot.update { current -> if (current == Snapshot.Loading(sessionId)) loaded else current }
    }

    /**
     * What Success shows for the completed [session]: decided at the first call for its id and then kept, so a later
     * load never changes it. A test or a snoozed session has no streak ([SuccessStreak.NONE], not kept). Pending and
     * Unknown are logged once per session.
     */
    fun streakFor(session: SessionData): SuccessStreak =
        if (session.config.testMode || session.snoozesGranted > 0) {
            SuccessStreak.NONE
        } else {
            decided.value?.takeIf { (id, _) -> id == session.sessionId }?.second ?: decideOnce(session)
        }

    /**
     * Decides for [session] and keeps it, unless a concurrent call kept one first (then that one is returned). It runs on
     * the main thread at Success: whatever goes wrong while deciding is no streak (the plain text), never a crash.
     */
    @Suppress("TooGenericExceptionCaught")
    private fun decideOnce(session: SessionData): SuccessStreak {
        val current = snapshot.value
        var failure: String? = null
        val streak =
            try {
                decide(current, session)
            } catch (e: Exception) {
                failure = e.message ?: e::class.simpleName ?: "streak error"
                SuccessStreak.Unknown
            }
        var first = false
        val kept =
            decided.updateAndGet { memo ->
                first = memo?.first != session.sessionId
                if (first) session.sessionId to streak else memo
            }
        if (first) logMissing(current, session.sessionId, streak, failure)
        return kept?.second ?: streak
    }

    private fun decide(
        current: Snapshot,
        session: SessionData,
    ): SuccessStreak {
        if (current.sessionId != session.sessionId) return SuccessStreak.Unknown
        return when (current) {
            is Snapshot.Loaded -> {
                val zone = zones.current()
                successStreak(
                    snapshot = current.rows,
                    sessionId = session.sessionId,
                    alarmId = session.config.alarmId,
                    scheduledAt = session.config.scheduledAt,
                    today = clock.now().toLocalDateTime(zone).date,
                    zone = zone,
                )
            }

            is Snapshot.Requested, is Snapshot.Loading -> {
                SuccessStreak.Pending
            }

            is Snapshot.Failed, Snapshot.NotRequested -> {
                SuccessStreak.Unknown
            }
        }
    }

    private fun logMissing(
        current: Snapshot,
        sessionId: String,
        streak: SuccessStreak,
        failure: String?,
    ) {
        val cause =
            when {
                failure != null -> failure
                streak == SuccessStreak.Pending -> if (current is Snapshot.Requested) "not started" else "still loading"
                streak != SuccessStreak.Unknown -> return
                current is Snapshot.Failed && current.sessionId == sessionId -> current.diagnostic
                else -> "not loaded"
            }
        logger.log(LogEvent.OperationFailed(OPERATION, cause))
    }

    // The port returns storage errors; anything thrown anyway is kept as a failure too, so the snapshot never stays
    // Loading for good and nothing reaches the app scope's handler from here.
    @Suppress("TooGenericExceptionCaught")
    private suspend fun read(sessionId: String): Snapshot =
        try {
            when (val rows = history.all()) {
                is Outcome.Success -> Snapshot.Loaded(sessionId, rows.value)
                is Outcome.Failure -> Snapshot.Failed(sessionId, rows.error.diagnostic())
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Snapshot.Failed(sessionId, e.message ?: e::class.simpleName ?: "read error")
        }

    /** The snapshot of the latest session that asked for one. */
    private sealed interface Snapshot {
        val sessionId: String?

        data object NotRequested : Snapshot {
            override val sessionId: String? = null
        }

        /** The screen shows the session; the read starts after its first frames. */
        data class Requested(
            override val sessionId: String,
        ) : Snapshot

        data class Loading(
            override val sessionId: String,
        ) : Snapshot

        data class Loaded(
            override val sessionId: String,
            val rows: List<SessionHistoryRow>,
        ) : Snapshot

        data class Failed(
            override val sessionId: String,
            val diagnostic: String,
        ) : Snapshot
    }

    private companion object {
        const val OPERATION = "load celebration streak"
    }
}
