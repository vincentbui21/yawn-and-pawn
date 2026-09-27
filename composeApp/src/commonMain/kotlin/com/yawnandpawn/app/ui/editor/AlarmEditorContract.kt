package com.yawnandpawn.app.ui.editor

import com.yawnandpawn.app.core.alarm.Alarm
import com.yawnandpawn.app.core.alarm.AlarmField
import com.yawnandpawn.app.ui.checks.CheckType
import com.yawnandpawn.app.ui.checks.Difficulty
import com.yawnandpawn.app.ui.format.Countdown
import com.yawnandpawn.app.ui.format.Money
import com.yawnandpawn.app.ui.format.Weekdays
import com.yawnandpawn.app.ui.sound.SoundPickerIntent
import com.yawnandpawn.app.ui.sound.SoundPickerUiState
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
    /**
     * Where the gradual ramp starts. Not editable (owner decision 2026-09-27): with "Gradually increase volume" on, the
     * ramp always starts at 20% and rises to the set volume over 30 s. Kept so a stored value round-trips.
     */
    val rampStartPercent: Int = Alarm.DEFAULT_RAMP_START_PERCENT,
    val vibration: Boolean = true,
) {
    companion object {
        /** The time a new alarm opens with. */
        val DEFAULT_TIME: LocalTime = LocalTime(hour = 7, minute = 0)

        /** The snooze-length options in the order the Snooze sub-screen lists them. */
        val SNOOZE_OPTIONS: List<Int> = Alarm.SNOOZE_LENGTHS_MINUTES.sorted()
    }
}

/**
 * The editor screen that is showing (progressive disclosure, owner decision 2026-09-27): the main card list, or the
 * sub-screen one of its rows opened. Back on a sub-screen returns to [Main].
 */
enum class EditorPane { Main, Sound, Snooze, WakeCheck, QuietTime, Motivation }

/** The repeat quick choices "Once" · "Weekdays" · "Custom"; Custom reveals the day chips. */
enum class RepeatChoice { Once, Weekdays, Custom }

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
    val pane: EditorPane = EditorPane.Main,
    /** "Custom" was chosen, so the day chips show even while the days match "Once" or "Weekdays". */
    val customRepeat: Boolean = false,
    /** How far away the next ring is, for the header "Rings in {...}"; `null` shows no countdown. */
    val ringsIn: Countdown? = null,
    /**
     * The rows of the full editor (EXPERIENCE.md Information Architecture) that later stories wire: wake-up check, quiet
     * time, fee ladder, sound list, motivation and "Test alarm". `null` hides them (the Story 1.8 editor).
     */
    val full: FullEditorSections? = null,
) {
    /** The highlighted repeat quick choice. */
    val repeatChoice: RepeatChoice
        get() =
            when {
                customRepeat -> RepeatChoice.Custom
                form.repeatDays.isEmpty() -> RepeatChoice.Once
                form.repeatDays == Weekdays -> RepeatChoice.Weekdays
                else -> RepeatChoice.Custom
            }
}

/** How the selected checks run: one picked at random each morning, or all of them in order. */
enum class CheckMode { Random, All }

/** One selected check and its difficulty. */
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

/** The full-editor rows beyond the Story 1.8 fields. */
data class FullEditorSections(
    val checks: List<CheckChip> = listOf(CheckChip(CheckType.Math, Difficulty.Easy)),
    val checkMode: CheckMode = CheckMode.Random,
    /** Save was blocked because no check is selected: "Pick at least one check." */
    val noCheckError: Boolean = false,
    /** The quiet time ("Quiet time", internally the grace window), 15 to 30 s. */
    val graceSeconds: Int = Alarm.DEFAULT_GRACE_SECONDS,
    /** "Vibrate during quiet time". */
    val vibrateInGrace: Boolean = true,
    /** The first three snooze prices of the fee ladder, or `null` while prices are not known. */
    val feeLadder: List<Money>? = null,
    val soundName: String = "",
    /** The chosen custom file is gone: "File missing. Default sound will play." */
    val soundMissing: Boolean = false,
    /** The Sound sub-screen's list: built-in sounds, system ringtones and the user's files. */
    val sounds: SoundPickerUiState = SoundPickerUiState(),
    /** Names of the recorded motivation messages ("Message 1"). */
    val recordings: List<String> = emptyList(),
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

    data class RepeatChosen(
        val choice: RepeatChoice,
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

    data class VibrationToggled(
        val enabled: Boolean,
    ) : EditorIntent

    /** A row of the main screen opened its sub-screen. */
    data class PaneOpened(
        val pane: EditorPane,
    ) : EditorIntent

    data object SaveClicked : EditorIntent

    /** System Back, "Cancel" in the bottom pill, or the back arrow of a sub-screen (which returns to the main screen). */
    data object BackRequested : EditorIntent

    data object DiscardConfirmed : EditorIntent

    data object KeepEditing : EditorIntent

    // Full-editor rows (FullEditorSections); the Story 1.8 ViewModel ignores them until their stories land.

    data class CheckToggled(
        val type: CheckType,
        val selected: Boolean,
    ) : EditorIntent

    /** The difficulty of every selected check. */
    data class DifficultySelected(
        val difficulty: Difficulty,
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

    /** A tap in the Sound sub-screen's list (select, preview, pick a file). */
    data class Sound(
        val intent: SoundPickerIntent,
    ) : EditorIntent

    data class MotivationChosen(
        val choice: MotivationChoice,
    ) : EditorIntent

    data class MotivationTimingSelected(
        val timing: MotivationTiming,
    ) : EditorIntent

    /** "Record a message": opens Recordings (design-preview round 3). */
    data object RecordMessageClicked : EditorIntent

    data object TestAlarmClicked : EditorIntent
}

/** One-shot events for the screen. */
sealed interface EditorEffect {
    /** Leave the editor (saved, discarded, or nothing to lose). */
    data object Close : EditorEffect

    /** Show "Couldn't save the alarm. Try again." (the storage error itself is never shown). */
    data object ShowSaveFailed : EditorEffect
}
