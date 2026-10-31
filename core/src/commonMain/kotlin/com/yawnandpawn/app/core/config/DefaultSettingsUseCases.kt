package com.yawnandpawn.app.core.config

import com.yawnandpawn.app.core.alarm.Alarm
import com.yawnandpawn.app.core.error.DomainError
import com.yawnandpawn.app.core.error.Outcome
import com.yawnandpawn.app.core.session.GlobalSettings
import com.yawnandpawn.app.core.session.SessionLockGuard
import com.yawnandpawn.app.core.session.ThemeMode

/**
 * A Settings value with no commitment (Story 5.1): a default for alarms created later, the app theme, or (0.1.7) "Vibrate
 * during quiet time". Changing one never changes a running session (AD-16). The quiet time itself is locked
 * ([SetQuietTime]).
 */
sealed interface DefaultSetting {
    /** "Vibrate during quiet time" (0.1.7: for every alarm, existing ones included); never locked. */
    data class VibrateInGrace(
        val on: Boolean,
    ) : DefaultSetting

    /** "Default snooze length", 5 / 10 / 15 / 30 min. */
    data class SnoozeLength(
        val minutes: Int,
    ) : DefaultSetting

    /** Appearance: System / Light / Dark. */
    data class Theme(
        val mode: ThemeMode,
    ) : DefaultSetting

    /** "Bright wake screen" (Story 5.2): frozen into a session at its fire, so a change mid-session has no effect. */
    data class BrightWakeScreen(
        val on: Boolean,
    ) : DefaultSetting
}

/** These settings with [value] set, valid or not (the ViewModel shows a requested value at once with it). */
fun GlobalSettings.with(value: DefaultSetting): GlobalSettings =
    when (value) {
        is DefaultSetting.VibrateInGrace -> copy(vibrateInGrace = value.on)
        is DefaultSetting.SnoozeLength -> copy(defaultSnoozeLengthMinutes = value.minutes)
        is DefaultSetting.Theme -> copy(themeMode = value.mode)
        is DefaultSetting.BrightWakeScreen -> copy(brightWakeScreen = value.on)
    }

/**
 * Saves a [DefaultSetting] (Story 5.1): applied at once, with no commitment lock and no pending change (the defaults are
 * not locked, owner-approved). Input outside its range is `InvalidSetting` and nothing is written. Runs inside the
 * session lock (Story 2.6), like every user-facing write: during a session it is `SessionActive`.
 */
class SaveDefaultSetting(
    private val settingsRepository: GlobalSettingsRepository,
    private val sessionLock: SessionLockGuard,
) {
    internal suspend operator fun invoke(value: DefaultSetting): Outcome<Unit, DomainError> {
        invalidField(value)?.let { return Outcome.Failure(DomainError.InvalidSetting(it)) }
        return sessionLock.whenIdle { write(value) }
    }

    private suspend fun write(value: DefaultSetting): Outcome<Unit, DomainError> =
        when (value) {
            is DefaultSetting.VibrateInGrace -> settingsRepository.setVibrateInGrace(value.on)
            is DefaultSetting.SnoozeLength -> settingsRepository.setDefaultSnoozeLength(value.minutes)
            is DefaultSetting.Theme -> settingsRepository.setThemeMode(value.mode)
            is DefaultSetting.BrightWakeScreen -> settingsRepository.setBrightWakeScreen(value.on)
        }

    private fun invalidField(value: DefaultSetting): SettingField? =
        when (value) {
            is DefaultSetting.SnoozeLength -> SettingField.DefaultSnoozeLength.takeUnless { value.minutes in SNOOZE_LENGTHS }
            is DefaultSetting.VibrateInGrace, is DefaultSetting.Theme, is DefaultSetting.BrightWakeScreen -> null
        }

    companion object {
        /** The snooze lengths an alarm may have (5 / 10 / 15 / 30 min). */
        val SNOOZE_LENGTHS: Set<Int> = Alarm.SNOOZE_LENGTHS_MINUTES
    }
}

/** Sets "Vibrate during quiet time" for every alarm (0.1.7), applied at once with no lock ([SaveDefaultSetting]). */
class SetVibrateInGrace(
    private val saveDefaultSetting: SaveDefaultSetting,
) {
    suspend operator fun invoke(on: Boolean): Outcome<Unit, DomainError> = saveDefaultSetting(DefaultSetting.VibrateInGrace(on))
}

/** Sets "Default snooze length" (5 / 10 / 15 / 30 min) for alarms created later ([SaveDefaultSetting]). */
class SetDefaultSnoozeLength(
    private val saveDefaultSetting: SaveDefaultSetting,
) {
    suspend operator fun invoke(minutes: Int): Outcome<Unit, DomainError> = saveDefaultSetting(DefaultSetting.SnoozeLength(minutes))
}

/** Sets the app screens' theme (Appearance), applied at once ([SaveDefaultSetting]). */
class SetThemeMode(
    private val saveDefaultSetting: SaveDefaultSetting,
) {
    suspend operator fun invoke(mode: ThemeMode): Outcome<Unit, DomainError> = saveDefaultSetting(DefaultSetting.Theme(mode))
}

/** Sets "Bright wake screen" (Story 5.2), used from the next fire on ([SaveDefaultSetting]). */
class SetBrightWakeScreen(
    private val saveDefaultSetting: SaveDefaultSetting,
) {
    suspend operator fun invoke(on: Boolean): Outcome<Unit, DomainError> = saveDefaultSetting(DefaultSetting.BrightWakeScreen(on))
}
