package com.yawnandpawn.app.ui.editor

import com.yawnandpawn.app.core.alarm.Alarm
import com.yawnandpawn.app.core.config.GlobalSettingsRepository
import com.yawnandpawn.app.core.error.DomainError
import com.yawnandpawn.app.core.error.Outcome
import com.yawnandpawn.app.core.session.GlobalSettings

/** The operation logged when a new alarm cannot read the Settings defaults (Story 5.1). */
internal const val LOAD_DEFAULTS = "load new-alarm defaults"

/** The operation logged when "Test alarm" cannot read the Settings it rings with (0.1.7: the quiet time). */
internal const val LOAD_TEST_SETTINGS = "load settings for test alarm"

/**
 * The form of a new alarm with the Settings defaults in [settings] (Story 5.1): the snooze length, and the global quiet
 * time and its vibration for the alarm's own (unused since 0.1.7) columns; the rest is the built-in new alarm. A default
 * the form cannot take (a store that bypassed its use case) opens as the built-in one.
 */
internal fun newAlarmForm(settings: GlobalSettings): EditorForm {
    val builtIn = EditorForm()
    return builtIn.copy(
        snoozeLengthMinutes =
            settings.defaultSnoozeLengthMinutes.takeIf { it in Alarm.SNOOZE_LENGTHS_MINUTES } ?: builtIn.snoozeLengthMinutes,
        graceSeconds = settings.graceSeconds.takeIf { it in Alarm.GRACE_SECONDS_RANGE } ?: builtIn.graceSeconds,
        vibrateInGrace = settings.vibrateInGrace,
    )
}

/**
 * The form of a new alarm with the stored Settings defaults (Story 5.1). A store that cannot be read is reported to
 * [onFailure] and the built-in defaults are used, so "+" always opens an editor.
 */
internal suspend fun GlobalSettingsRepository.newAlarmForm(onFailure: (DomainError) -> Unit): EditorForm =
    when (val read = get()) {
        is Outcome.Success -> {
            newAlarmForm(read.value)
        }

        is Outcome.Failure -> {
            onFailure(read.error)
            newAlarmForm(GlobalSettings())
        }
    }
