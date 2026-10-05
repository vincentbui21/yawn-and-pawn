package com.yawnandpawn.app.core.session

import com.yawnandpawn.app.core.alarm.AlarmFired
import com.yawnandpawn.app.core.alarm.AlarmScheduler
import com.yawnandpawn.app.core.error.Outcome
import com.yawnandpawn.app.core.log.LogEvent
import com.yawnandpawn.app.core.log.Logger
import com.yawnandpawn.app.core.time.BootCounter
import com.yawnandpawn.app.core.time.Clock
import com.yawnandpawn.app.core.time.Deadline
import com.yawnandpawn.app.core.time.MonotonicClock
import com.yawnandpawn.app.core.time.TimeSnapshot
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * Arms the session slot (AD-4) from what `runtime.db` holds, without the [SessionEngine] (Story 2.1). The engine's
 * restore runs only where a foreground service may start (`WakeService`, `MainActivity`, `WakeActivity`); everywhere
 * else the slot brings the session back, because its fire may start the wake service.
 *
 * - [afterSystemEvent]: a process started for a boot, a clock or time-zone change, an app update or an exact-alarm
 *   permission change never runs the restore's entry effects and never starts a foreground service (Android 12+ start
 *   rules; no media foreground service from a boot receiver on Android 15+). It arms the slot instead.
 * - [afterRefusedStart]: the platform refused to start the wake service (or to put it in the foreground) for an alarm,
 *   a slot or a restore. The slot is armed again one [SessionReducer.HEARTBEAT] later, carrying the alarm the start was
 *   for, so the ring still comes back.
 *
 * Both return the deadline they armed (null when nothing was armed); failures are logged, never thrown.
 */
class SessionSlotRearm(
    private val store: ActiveSessionStore,
    private val scheduler: AlarmScheduler,
    private val clock: Clock,
    private val monotonicClock: MonotonicClock,
    private val bootCounter: BootCounter,
    private val logger: Logger,
) {
    /**
     * Ringing, Grace or Loud: the slot [IMMEDIATELY]. Snoozed: at its snooze end (a passed one fires at once). Nothing
     * stored, an unreadable row, Idle, Completed or Missed: nothing (the next restore finishes an ended session). A store
     * that cannot be read is logged and arms nothing: with no evidence of a session, a broken store must not ring after
     * every boot. A slot that still carries an alarm ([AlarmScheduler.sessionSlotAlarm], less than
     * [SessionReducer.NO_INTERACTION_TIMEOUT] past) keeps it and fires at once (Story 2.1 review).
     */
    suspend fun afterSystemEvent(): Deadline? {
        val now = now()
        val state = (load() as? Loaded.Found)?.state
        val carried = scheduler.sessionSlotAlarm()?.takeIf { it.isFresh(now) }
        val at =
            when (state) {
                is SessionState.Ring -> Deadline.after(now, IMMEDIATELY)
                is SessionState.Snoozed -> state.session.snoozeEnd?.takeIf { carried == null } ?: Deadline.after(now, IMMEDIATELY)
                else -> null
            }
        return at?.let { arm(it, carried, retrySince = null, SYSTEM_EVENT, state) }
    }

    /**
     * A refused wake-service start for [alarm] (null for a slot or restore start that carried none): the slot one
     * heartbeat from now carrying [alarm], while it is less than [SessionReducer.NO_INTERACTION_TIMEOUT] past its
     * scheduled time (later it would be Missed anyway, so it is dropped). With none, the alarm the armed slot still
     * carries is kept ([AlarmScheduler.sessionSlotAlarm], Story 2.1 review). Without one: for a stored Ringing, Grace or
     * Loud one heartbeat from now, for a stored Snoozed its snooze end (one heartbeat when that has passed), and also one
     * heartbeat when the store cannot be read (a start was asked for, so something may need to ring). Nothing for no
     * session or an ended one.
     *
     * Bounded (Story 2.1 review): the retries stop, logged, once starts have been refused for
     * [SessionReducer.NO_INTERACTION_TIMEOUT], counted from [retrySince] (the slot carries it), else from the alarm's
     * scheduled time, else from now (the first refusal).
     */
    suspend fun afterRefusedStart(
        alarm: AlarmFired?,
        retrySince: Instant? = null,
    ): Deadline? {
        val now = now()
        val pending = (alarm ?: scheduler.sessionSlotAlarm())?.takeIf { it.isFresh(now) }
        val since = retrySince ?: pending?.scheduledAt ?: Instant.fromEpochMilliseconds(now.wallMillis)
        if (now.wallMillis - since.toEpochMilliseconds() >= WINDOW_MILLIS) {
            logger.log(
                LogEvent.OperationFailed("re-arm session slot", "starts refused for ${SessionReducer.NO_INTERACTION_TIMEOUT}; giving up"),
            )
            return null
        }
        val loaded = load()
        val state = (loaded as? Loaded.Found)?.state
        val heartbeat = Deadline.after(now, SessionReducer.HEARTBEAT)
        val at =
            when {
                pending != null -> heartbeat
                state is SessionState.Ring -> heartbeat
                state is SessionState.Snoozed -> state.session.snoozeEnd?.takeUnless { it.isDue(now) } ?: heartbeat
                loaded == Loaded.Failed -> heartbeat
                else -> null
            }
        return at?.let { arm(it, pending, since, REFUSED_START, state) }
    }

    private fun AlarmFired.isFresh(now: TimeSnapshot): Boolean = now.wallMillis - scheduledAt.toEpochMilliseconds() < WINDOW_MILLIS

    private suspend fun load(): Loaded =
        when (val stored = store.load()) {
            is Outcome.Failure -> {
                logger.log(LogEvent.OperationFailed.of("read session for slot", stored.error))
                Loaded.Failed
            }

            is Outcome.Success -> {
                (stored.value as? StoredSession.Found)?.let { Loaded.Found(it.state) } ?: Loaded.NoSession
            }
        }

    private fun arm(
        at: Deadline,
        alarm: AlarmFired?,
        retrySince: Instant?,
        reason: String,
        state: SessionState?,
    ): Deadline? =
        when (val armed = scheduler.armSessionSlot(at, alarm, retrySince)) {
            is Outcome.Failure -> {
                logger.log(LogEvent.OperationFailed.of("arm session slot", armed.error))
                null
            }

            is Outcome.Success -> {
                val sessionId = (state as? SessionState.Active)?.session?.sessionId
                logger.log(LogEvent.SessionSlotRearmed(reason, at.remaining(now()).inWholeMilliseconds, sessionId, alarm?.alarmId))
                at
            }
        }

    private fun now(): TimeSnapshot = TimeSnapshot.of(clock, monotonicClock, bootCounter)

    private sealed interface Loaded {
        data class Found(
            val state: SessionState,
        ) : Loaded

        data object NoSession : Loaded

        data object Failed : Loaded
    }

    companion object {
        /** "At once" for the slot: the platform may still hold it back a few seconds (minimum alarm futurity). */
        val IMMEDIATELY: Duration = 1.seconds

        const val SYSTEM_EVENT = "system event"
        const val REFUSED_START = "wake service start refused"

        private val WINDOW_MILLIS = SessionReducer.NO_INTERACTION_TIMEOUT.inWholeMilliseconds
    }
}
