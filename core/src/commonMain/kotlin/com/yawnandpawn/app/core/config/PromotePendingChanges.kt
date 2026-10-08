package com.yawnandpawn.app.core.config

import com.yawnandpawn.app.core.alarm.Alarm
import com.yawnandpawn.app.core.alarm.AlarmRepository
import com.yawnandpawn.app.core.alarm.AlarmWriteLock
import com.yawnandpawn.app.core.alarm.CheckConfig
import com.yawnandpawn.app.core.alarm.CheckConfigRepository
import com.yawnandpawn.app.core.alarm.checkRowsFor
import com.yawnandpawn.app.core.alarm.nowMillis
import com.yawnandpawn.app.core.alarm.orderedEntries
import com.yawnandpawn.app.core.alarm.validateChecks
import com.yawnandpawn.app.core.error.DomainError
import com.yawnandpawn.app.core.error.Outcome
import com.yawnandpawn.app.core.error.flatMap
import com.yawnandpawn.app.core.log.LogEvent
import com.yawnandpawn.app.core.log.Logger
import com.yawnandpawn.app.core.session.SessionState
import com.yawnandpawn.app.core.session.inProgress
import com.yawnandpawn.app.core.time.Clock
import kotlin.time.Instant

/**
 * Makes the pending changes that are due the live settings (AD-16): once the occurrence they wait for is over
 * ([PendingChange.isDue]: more than [PendingChange.SETTLE] after it, review fix 9), and no
 * session in progress rings that very occurrence ([activeOccurrence]), the pending value is written as the live value
 * (the global setting, or the alarm's grace window or checks) and then the pending change is deleted, in that order (a
 * failure between leaves the change to be promoted again). A change whose alarm was turned off still takes effect; one
 * whose alarm was deleted is dropped.
 *
 * It runs in `AlarmScheduling.rescheduleAll` (app start, boot, time changes) and when the wake service shuts down
 * after a session. The settle delay keeps it from ever weakening the ring a change waited for, even when the fire
 * cold-starts the app and `rescheduleAll` runs before `AlarmFired` (review fix 9).
 * It is the one config writer allowed during a session (`SessionLockGuardScanTest`): it only touches occurrences that
 * have passed, and a running session's config is frozen (AD-16). Under the alarm write lock, so it never races a save.
 * A failed write is logged and retried on the next run.
 */
class PromotePendingChanges(
    private val pendingRepository: PendingChangeRepository,
    private val settingsRepository: GlobalSettingsRepository,
    private val alarmRepository: AlarmRepository,
    private val checkConfigRepository: CheckConfigRepository,
    private val clock: Clock,
    private val lock: AlarmWriteLock,
    private val logger: Logger,
    /** The occurrence a session in progress rings, in memory or, before the engine is restored, in the stored session. */
    private val activeOccurrence: suspend () -> Occurrence?,
) : PendingChangePromotion {
    override suspend fun promote() {
        invoke()
    }

    /** Promotes every due change; returns how many were promoted, or the failed read of the pending changes. */
    suspend operator fun invoke(): Outcome<Int, DomainError> =
        lock.withLock {
            val now = clock.nowMillis()
            when (val read = pendingRepository.all()) {
                is Outcome.Failure -> {
                    logger.log(LogEvent.OperationFailed.of(OPERATION, read.error))
                    read
                }

                is Outcome.Success -> {
                    // The session is looked up only when something is due (it may open runtime.db, review fix 9).
                    val due = read.value.filter { it.isDue(now) }
                    val active = if (due.isEmpty()) null else activeOccurrence()
                    Outcome.Success(due.filter { it.effectiveAfter != active }.count { change -> promoteOne(change, now) })
                }
            }
        }

    /** True when [change] is now live and deleted; a failure is logged. */
    private suspend fun promoteOne(
        change: PendingChange,
        now: Instant,
    ): Boolean {
        val live =
            when (val value = change.value) {
                is SettingValue.BaseFeeTier -> {
                    settingsRepository.setBaseFeeTier(value.tier)
                }

                is SettingValue.MaxSnoozes -> {
                    settingsRepository.setMaxSnoozes(value.count)
                }

                is SettingValue.GraceSeconds -> {
                    alarmField(
                        change,
                    ) { alarm -> alarmRepository.upsert(alarm.copy(graceSeconds = value.seconds)) }
                }

                is SettingValue.Checks -> {
                    alarmField(change) { alarm -> storeChecks(alarm, value, now) }
                }
            }
        val done = live.flatMap { pendingRepository.remove(change.alarmId, change.field) }
        if (done is Outcome.Failure) logger.log(LogEvent.OperationFailed.of(OPERATION, done.error))
        return done is Outcome.Success
    }

    /**
     * Runs [write] on the stored alarm of [change]. A deleted alarm has nothing to promote into, and a stored alarm the
     * change can never be applied to (an invalid value) would fail forever: both count as done, so the change is dropped.
     */
    private suspend fun alarmField(
        change: PendingChange,
        write: suspend (Alarm) -> Outcome<Unit, DomainError>,
    ): Outcome<Unit, DomainError> {
        val alarmId = change.alarmId ?: return Outcome.Success(Unit)
        return when (val stored = alarmRepository.get(alarmId)) {
            is Outcome.Failure -> {
                if (stored.error is DomainError.NotFound) Outcome.Success(Unit) else stored
            }

            is Outcome.Success -> {
                val written = write(stored.value)
                if (written is Outcome.Failure && written.error is DomainError.InvalidAlarm) {
                    logger.log(LogEvent.OperationFailed.of(OPERATION, written.error))
                    Outcome.Success(Unit)
                } else {
                    written
                }
            }
        }
    }

    /** Stores [value]'s plan on [alarm] (its mode on the alarm, its entries as the rows), keeping a re-registered code. */
    private suspend fun storeChecks(
        alarm: Alarm,
        value: SettingValue.Checks,
        now: Instant,
    ): Outcome<Unit, DomainError> =
        checkConfigRepository.forAlarm(alarm.id).flatMap { rows ->
            val plan = value.plan.withCodesFrom(rows.orderedEntries().ifEmpty { CheckConfig.DEFAULT_ENTRIES })
            val invalid = validateChecks(plan.entries)
            if (invalid != null) {
                Outcome.Failure(DomainError.InvalidAlarm(invalid))
            } else {
                checkConfigRepository.saveWithAlarm(alarm.copy(checkMode = plan.mode), checkRowsFor(alarm.id, plan.entries, rows, now))
            }
        }

    companion object {
        private const val OPERATION = "promote pending change"

        /** The occurrence the session in [state] rings for, while a ring or a snooze is in progress; otherwise null. */
        fun occurrenceOf(state: SessionState): Occurrence? =
            (state as? SessionState.Active)
                ?.takeIf { it.inProgress }
                ?.session
                ?.config
                ?.let { Occurrence(it.alarmId, it.scheduledAt) }
    }
}
