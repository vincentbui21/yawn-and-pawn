package com.yawnandpawn.app.core.config

import com.yawnandpawn.app.core.alarm.AlarmRepository
import com.yawnandpawn.app.core.alarm.AlarmWriteLock
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
}

/**
 * A saved setting: applied at once ([pendingUntil] null), or stored as a pending change that takes effect after the
 * occurrence [pendingUntil] ("Saved. Takes effect after tomorrow's 7:30 alarm.", Stories 4.5 and 4.6).
 */
data class Saved(
    val pendingUntil: Occurrence?,
)

/**
 * Saves a global locked setting ([SettingValue.BaseFeeTier] or [SettingValue.MaxSnoozes]) through the commitment lock
 * ([CommitmentRules.decide]): the window is any enabled alarm's, and the change waits for the latest occurrence inside
 * it. Writes the live value first, then the pending change (a crash between leaves the stronger setting). Invalid input
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

    private suspend fun store(
        decision: LockDecision,
        pending: PendingChange?,
    ): Outcome<Saved, DomainError> {
        val live =
            when (val value = decision.live) {
                is SettingValue.BaseFeeTier -> settingsRepository.setBaseFeeTier(value.tier)
                is SettingValue.MaxSnoozes -> settingsRepository.setMaxSnoozes(value.count)
                is SettingValue.GraceSeconds, is SettingValue.Checks -> Outcome.Success(Unit)
            }
        return live.flatMap {
            when (decision) {
                is LockDecision.ApplyNow -> {
                    if (pending ==
                        null
                    ) {
                        Outcome.Success(Saved(null))
                    } else {
                        pendingRepository.remove(null, decision.live.field).map { Saved(null) }
                    }
                }

                is LockDecision.Defer -> {
                    pendingRepository.put(decision.pending).map { Saved(decision.pending.effectiveAfter) }
                }
            }
        }
    }

    private fun invalidField(value: SettingValue): SettingField? =
        when (value) {
            is SettingValue.BaseFeeTier -> SettingField.BaseFee.takeUnless { value.tier in BASE_FEE_TIERS }
            is SettingValue.MaxSnoozes -> SettingField.MaxSnoozes.takeUnless { value.count in MAX_SNOOZES }
            is SettingValue.GraceSeconds, is SettingValue.Checks -> null
        }

    companion object {
        /** Base fee tiers $1–$10 (FR-SET-1). */
        val BASE_FEE_TIERS: IntRange = 1..10

        /** Max snoozes per session (FR-SET-1, default 5). */
        val MAX_SNOOZES: IntRange = 1..5
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

/** The live value of the global [field]; an alarm field's live value is on its alarm, so it has none here. */
internal fun GlobalSettings.valueOf(field: LockedField): SettingValue {
    require(field.global) { "$field is an alarm setting" }
    return if (field == LockedField.BaseFee) SettingValue.BaseFeeTier(baseFeeTier) else SettingValue.MaxSnoozes(maxSnoozes)
}
