package com.yawnandpawn.app.core.config

import com.yawnandpawn.app.core.alarm.Alarm
import com.yawnandpawn.app.core.error.DomainError
import com.yawnandpawn.app.core.error.Outcome
import com.yawnandpawn.app.core.session.GlobalSettings
import com.yawnandpawn.app.core.session.SessionLockGuard
import com.yawnandpawn.app.core.session.ThemeMode

/**
 * A Settings value with no commitment (Story 5.1): a default for alarms created later, or the app theme. Changing one
 * never changes a stored alarm or a running session (AD-16).
 */
sealed interface DefaultSetting {
    /** "Default quiet time", 15 to 30 s. */
    data class GraceSeconds(
        val seconds: Int,
    ) : DefaultSetting

    /** "Vibrate during quiet time" for new alarms. */
    data class VibrateInGrace(
        val on: Boolean,
    ) : DefaultSetting

    /** "Default snooze length", 5 / 9 / 10 / 15 min. */
    data class SnoozeLength(
        val minutes: Int,
    ) : DefaultSetting

    /** Appearance: System / Light / Dark. */
    data class Theme(
        val mode: ThemeMode,
    ) : DefaultSetting
}

/** These settings with [value] set, valid or not (the ViewModel shows a requested value at once with it). */
fun GlobalSettings.with(value: DefaultSetting): GlobalSettings =
    when (value) {
        is DefaultSetting.GraceSeconds -> copy(defaultGraceSeconds = value.seconds)
        is DefaultSetting.VibrateInGrace -> copy(defaultVibrateInGrace = value.on)
        is DefaultSetting.SnoozeLength -> copy(defaultSnoozeLengthMinutes = value.minutes)
        is DefaultSetting.Theme -> copy(themeMode = value.mode)
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
            is DefaultSetting.GraceSeconds -> settingsRepository.setDefaultGraceSeconds(value.seconds)
            is DefaultSetting.VibrateInGrace -> settingsRepository.setDefaultVibrateInGrace(value.on)
            is DefaultSetting.SnoozeLength -> settingsRepository.setDefaultSnoozeLength(value.minutes)
            is DefaultSetting.Theme -> settingsRepository.setThemeMode(value.mode)
        }

    private fun invalidField(value: DefaultSetting): SettingField? =
        when (value) {
            is DefaultSetting.GraceSeconds -> SettingField.DefaultGrace.takeUnless { value.seconds in GRACE_SECONDS }
            is DefaultSetting.SnoozeLength -> SettingField.DefaultSnoozeLength.takeUnless { value.minutes in SNOOZE_LENGTHS }
            is DefaultSetting.VibrateInGrace, is DefaultSetting.Theme -> null
        }

    companion object {
        /** The quiet times an alarm may have (15 to 30 s), so a default is always one a new alarm can save. */
        val GRACE_SECONDS: IntRange = Alarm.GRACE_SECONDS_RANGE

        /** The snooze lengths an alarm may have (5 / 9 / 10 / 15 min). */
        val SNOOZE_LENGTHS: Set<Int> = Alarm.SNOOZE_LENGTHS_MINUTES
    }
}

/** Sets "Default quiet time" (15 to 30 s) for alarms created later ([SaveDefaultSetting]). */
class SetDefaultGrace(
    private val saveDefaultSetting: SaveDefaultSetting,
) {
    suspend operator fun invoke(seconds: Int): Outcome<Unit, DomainError> = saveDefaultSetting(DefaultSetting.GraceSeconds(seconds))
}

/** Sets "Vibrate during quiet time" for alarms created later ([SaveDefaultSetting]). */
class SetDefaultVibrateInGrace(
    private val saveDefaultSetting: SaveDefaultSetting,
) {
    suspend operator fun invoke(on: Boolean): Outcome<Unit, DomainError> = saveDefaultSetting(DefaultSetting.VibrateInGrace(on))
}

/** Sets "Default snooze length" (5 / 9 / 10 / 15 min) for alarms created later ([SaveDefaultSetting]). */
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
