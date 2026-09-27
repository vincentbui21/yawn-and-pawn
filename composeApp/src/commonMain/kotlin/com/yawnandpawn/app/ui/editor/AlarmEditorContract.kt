package com.yawnandpawn.app.ui.editor

import com.yawnandpawn.app.core.alarm.Alarm
import com.yawnandpawn.app.core.alarm.AlarmField
import com.yawnandpawn.app.ui.checks.CheckType
import com.yawnandpawn.app.ui.checks.Difficulty
import com.yawnandpawn.app.ui.format.Money
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalTime

/** Every field the editor shows, as the user has set it. New alarms start from these defaults (Story 1.7 `Alarm`). */
data class EditorForm(
    val time: LocalTime = DEFAULT_TIME,
    val repeatDays: Set<DayOfWeek> = emptySet(),
    val label: String = "",
    val snoozeLengthMinutes: Int = Alarm.DEFAULT_SNOOZE_LENGTH_MINUTES,
    val volumePercent: Int = Alarm.DEFAULT_VOLUME_PERCENT,
    val gradualVolume: Boolean = true,
    val rampStartPercent: Int = Alarm.DEFAULT_RAMP_START_PERCENT,
    val vibration: Boolean = true,
) {
    companion object {
        /** The time a new alarm opens with. */
        val DEFAULT_TIME: LocalTime = LocalTime(hour = 7, minute = 0)

        /** The snooze-length options in the order the segmented control shows them. */
        val SNOOZE_OPTIONS: List<Int> = Alarm.SNOOZE_LENGTHS_MINUTES.sorted()
    }
}

/** What the Alarm editor screen renders. */
data class EditorUiState(
    /** `true` for a new alarm ("New alarm"), `false` when editing a stored one ("Edit alarm"). */
    val isNew: Boolean = true,
    /** An existing alarm is still being read; the form is not shown yet. */
    val isLoading: Boolean = false,
    val form: EditorForm = EditorForm(),
    /**
     * One-time alarm whose time has passed today: when it actually rings tomorrow (the chosen time, or later in a DST
     * gap), shown as "Rings tomorrow at {time}."; `null` shows no note.
     */
    val ringsTomorrowAt: LocalTime? = null,
    /** The field `SaveAlarm` rejected; [AlarmField.Label] shows "Keep the label under 40 characters." on the field. */
    val fieldError: AlarmField? = null,
    val showDiscardDialog: Boolean = false,
    val isSaving: Boolean = false,
    /**
     * The sections of the full editor (EXPERIENCE.md Information Architecture) that later stories wire: checks, grace
     * window, fee ladder, sound picker, motivation and "Test alarm". `null` hides them (the Story 1.8 editor).
     */
    val full: FullEditorSections? = null,
)

/** How the selected checks run: one picked at random each morning, or all of them in order. */
enum class CheckMode { Random, All }

/** One selected check as its `chip-check` shows it. */
data class CheckChip(
    val type: CheckType,
    val difficulty: Difficulty,
)

/** The motivation message the alarm plays: none, a random one of the recordings, or one named recording. */
sealed interface MotivationChoice {
    data object None : MotivationChoice

    data object Random : MotivationChoice

    data class Recording(
        val name: String,
    ) : MotivationChoice
}

/** When the motivation message plays. */
enum class MotivationTiming { AfterImUp, MixIntoAlarm }

/** The full-editor sections beyond the Story 1.8 fields. */
data class FullEditorSections(
    val checks: List<CheckChip> = listOf(CheckChip(CheckType.Math, Difficulty.Easy)),
    val checkMode: CheckMode = CheckMode.Random,
    /** Save was blocked because no check is selected: "Pick at least one check." */
    val noCheckError: Boolean = false,
    val graceSeconds: Int = Alarm.DEFAULT_GRACE_SECONDS,
    val vibrateInGrace: Boolean = true,
    /** The first three snooze prices of the fee ladder, or `null` while prices are not known. */
    val feeLadder: List<Money>? = null,
    val soundName: String = "",
    /** The chosen custom file is gone: "File missing. Default sound will play." */
    val soundMissing: Boolean = false,
    val motivation: MotivationChoice = MotivationChoice.None,
    val motivationTiming: MotivationTiming = MotivationTiming.AfterImUp,
    /** A weakening change was saved under the commitment lock; it applies after the alarm at this time. */
    val weakeningAppliesAfter: LocalTime? = null,
)

/** Everything the user can do in the editor. */
sealed interface EditorIntent {
    data class TimeChanged(
        val time: LocalTime,
    ) : EditorIntent

    data class DayToggled(
        val day: DayOfWeek,
    ) : EditorIntent

    data class LabelChanged(
        val label: String,
    ) : EditorIntent

    data class SnoozeLengthSelected(
        val minutes: Int,
    ) : EditorIntent

    data class VolumeChanged(
        val percent: Int,
    ) : EditorIntent

    data class GradualVolumeToggled(
        val enabled: Boolean,
    ) : EditorIntent

    data class RampStartChanged(
        val percent: Int,
    ) : EditorIntent

    data class VibrationToggled(
        val enabled: Boolean,
    ) : EditorIntent

    data object SaveClicked : EditorIntent

    /** System Back or the top-app-bar back arrow. */
    data object BackRequested : EditorIntent

    data object DiscardConfirmed : EditorIntent

    data object KeepEditing : EditorIntent

    // Full-editor sections (FullEditorSections); the Story 1.8 ViewModel ignores them until their stories land.

    data class CheckChipClicked(
        val type: CheckType,
    ) : EditorIntent

    data class CheckModeSelected(
        val mode: CheckMode,
    ) : EditorIntent

    data class GraceChanged(
        val seconds: Int,
    ) : EditorIntent

    data class VibrateInGraceToggled(
        val enabled: Boolean,
    ) : EditorIntent

    data object SoundClicked : EditorIntent

    data object MotivationClicked : EditorIntent

    data class MotivationTimingSelected(
        val timing: MotivationTiming,
    ) : EditorIntent

    data object TestAlarmClicked : EditorIntent
}

/** One-shot events for the screen. */
sealed interface EditorEffect {
    /** Leave the editor (saved, discarded, or nothing to lose). */
    data object Close : EditorEffect

    /** Show "Couldn't save the alarm. Try again." (the storage error itself is never shown). */
    data object ShowSaveFailed : EditorEffect
}
