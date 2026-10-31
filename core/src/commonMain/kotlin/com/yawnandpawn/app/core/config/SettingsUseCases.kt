package com.yawnandpawn.app.core.config

import com.yawnandpawn.app.core.alarm.Alarm
import com.yawnandpawn.app.core.alarm.AlarmRepository
import com.yawnandpawn.app.core.alarm.AlarmWriteLock
import com.yawnandpawn.app.core.billing.FeeRules
import com.yawnandpawn.app.core.error.DomainError
import com.yawnandpawn.app.core.error.Outcome
import com.yawnandpawn.app.core.error.flatMap
import com.yawnandpawn.app.core.error.map
import com.yawnandpawn.app.core.session.GlobalSettings
import com.yawnandpawn.app.core.session.SessionLockGuard
import com.yawnandpawn.app.core.time.Clock
import com.yawnandpawn.app.core.time.TimeZoneProvider

/** The global settings input validation can reject, reported in `DomainError.InvalidSetting`. */
enum class SettingField {
    BaseFee,
    MaxSnoozes,

    /** "Quiet time" outside 15 to 30 s (Story 5.1; global since 0.1.7). */
    QuietTime,

    /** "Default snooze length" other than 5 / 10 / 15 / 30 min (Story 5.1). */
    DefaultSnoozeLength,
}

/**
 * A saved setting: applied at once ([pendingUntil] null), or stored as a pending change that takes effect after the
 * occurrence [pendingUntil] ("Saved. Takes effect after tomorrow's 7:30 alarm.", Stories 4.5 and 4.6).
 */
data class Saved(
    val pendingUntil: Occurrence?,
)

/**
 * Saves a global locked setting ([SettingValue.BaseFeeTier], [SettingValue.MaxSnoozes] or, since 0.1.7,
 * [SettingValue.QuietTimeSeconds]) through the commitment lock
 * ([CommitmentRules.decide]): the window is any enabled alarm's, and the change waits for the latest occurrence inside
 * it. Applying now deletes the pending change before the live write; deferring writes the live value before the pending
 * change, so a crash between never leaves a weaker setting to apply later ([store]). Invalid input
 * is `InvalidSetting` and nothing is written. Runs under the alarm write lock, so it never races a promotion or an alarm
 * save, and inside the session lock (Story 2.6).
 */
class SaveGlobalSetting(
    private val settingsRepository: GlobalSettingsRepository,
    private val pendingRepository: PendingChangeRepository,
    private val alarmRepository: AlarmRepository,
    private val clock: Clock,
    private val timeZoneProvider: TimeZoneProvider,
    private val lock: AlarmWriteLock,
    private val sessionLock: SessionLockGuard,
) {
    internal suspend operator fun invoke(value: SettingValue): Outcome<Saved, DomainError> {
        require(value.field.global) { "${value.field} is saved with its alarm" }
        invalidField(value)?.let { return Outcome.Failure(DomainError.InvalidSetting(it)) }
        return lock.withLock {
            sessionLock.whenIdle {
                val now = clock.now()
                settingsRepository.get().flatMap { settings ->
                    pendingRepository.all().flatMap { all ->
                        alarmRepository.listAll().flatMap { alarms ->
                            val pending = all.firstOrNull { it.alarmId == null && it.field == value.field }
                            val window = LockWindow.latest(alarms, now, timeZoneProvider.current())
                            val decision = CommitmentRules.decide(null, settings.valueOf(value.field), pending, value, window, now)
                            store(decision, pending)
                        }
                    }
                }
            }
        }
    }

    /**
     * Writes [decision] so that a crash or failure between two writes never leaves a weaker value behind (review fix 3):
     * - apply now: the field's pending change is deleted first, then the live value written; if the live write fails, the
     *   old live value stays and no weaker pending change survives to be promoted later;
     * - defer: the effective value is written live first, then the pending change.
     */
    private suspend fun store(
        decision: LockDecision,
        pending: PendingChange?,
    ): Outcome<Saved, DomainError> =
        when (decision) {
            is LockDecision.ApplyNow -> {
                val cleared = if (pending == null) Outcome.Success(Unit) else pendingRepository.remove(null, decision.live.field)
                cleared.flatMap { writeLive(decision.live) }.map { Saved(null) }
            }

            is LockDecision.Defer -> {
                writeLive(decision.live)
                    .flatMap { pendingRepository.put(decision.pending) }
                    .map { Saved(decision.pending.effectiveAfter) }
            }
        }

    private suspend fun writeLive(value: SettingValue): Outcome<Unit, DomainError> =
        when (value) {
            is SettingValue.BaseFeeTier -> settingsRepository.setBaseFeeTier(value.tier)
            is SettingValue.MaxSnoozes -> settingsRepository.setMaxSnoozes(value.count)
            is SettingValue.QuietTimeSeconds -> settingsRepository.setGraceSeconds(value.seconds)
            is SettingValue.GraceSeconds, is SettingValue.Checks, is SettingValue.OfferSkip -> Outcome.Success(Unit)
        }

    private fun invalidField(value: SettingValue): SettingField? =
        when (value) {
            is SettingValue.BaseFeeTier -> SettingField.BaseFee.takeUnless { value.tier in BASE_FEE_TIERS }
            is SettingValue.MaxSnoozes -> SettingField.MaxSnoozes.takeUnless { value.count in MAX_SNOOZES }
            is SettingValue.QuietTimeSeconds -> SettingField.QuietTime.takeUnless { value.seconds in GRACE_SECONDS }
            is SettingValue.GraceSeconds, is SettingValue.Checks, is SettingValue.OfferSkip -> null
        }

    companion object {
        /** Base fee tiers $1–$10 (FR-SET-1), the fee ladder's own limits (Story 4.2). */
        val BASE_FEE_TIERS: IntRange = FeeRules.BASE_FEE_TIERS

        /** Max snoozes per session (FR-SET-1, default 5). */
        val MAX_SNOOZES: IntRange = FeeRules.MAX_SNOOZES

        /** The quiet times (15 to 30 s), the same range an alarm's own stored value has. */
        val GRACE_SECONDS: IntRange = Alarm.GRACE_SECONDS_RANGE
    }
}

/** Sets the base fee tier (1–10) through the commitment lock: lowering it inside the window waits ([SaveGlobalSetting]). */
class SetBaseFee(
    private val saveGlobalSetting: SaveGlobalSetting,
) {
    suspend operator fun invoke(tier: Int): Outcome<Saved, DomainError> = saveGlobalSetting(SettingValue.BaseFeeTier(tier))
}

/** Sets max snoozes per session (1–5) through the commitment lock: raising it inside the window waits ([SaveGlobalSetting]). */
class SetMaxSnoozes(
    private val saveGlobalSetting: SaveGlobalSetting,
) {
    suspend operator fun invoke(count: Int): Outcome<Saved, DomainError> = saveGlobalSetting(SettingValue.MaxSnoozes(count))
}

/**
 * Sets the global quiet time (15 to 30 s, 0.1.7) through the commitment lock: a longer one inside the window waits until
 * that alarm has rung ([SaveGlobalSetting]). It applies to every alarm, existing ones included, and to test rings.
 */
class SetQuietTime(
    private val saveGlobalSetting: SaveGlobalSetting,
) {
    suspend operator fun invoke(seconds: Int): Outcome<Saved, DomainError> = saveGlobalSetting(SettingValue.QuietTimeSeconds(seconds))
}

/** The live value of the global [field]; an alarm field's live value is on its alarm, so it has none here. */
internal fun GlobalSettings.valueOf(field: LockedField): SettingValue =
    when (field) {
        LockedField.BaseFee -> SettingValue.BaseFeeTier(baseFeeTier)
        LockedField.MaxSnoozes -> SettingValue.MaxSnoozes(maxSnoozes)
        LockedField.QuietTime -> SettingValue.QuietTimeSeconds(graceSeconds)
        LockedField.GraceSeconds, LockedField.Checks, LockedField.OfferSkip -> throw IllegalArgumentException("$field is an alarm setting")
    }
