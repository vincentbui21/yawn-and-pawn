package com.yawnandpawn.app.ui.editor

import com.yawnandpawn.app.core.alarm.Alarm
import com.yawnandpawn.app.core.alarm.AlarmField
import com.yawnandpawn.app.core.alarm.CheckConfig
import com.yawnandpawn.app.ui.checks.CheckType
import com.yawnandpawn.app.ui.checks.Difficulty
import com.yawnandpawn.app.ui.checks.defaultCount
import com.yawnandpawn.app.ui.checks.toUi
import com.yawnandpawn.app.ui.checksetup.CheckSetupIntent
import com.yawnandpawn.app.ui.format.Countdown
import com.yawnandpawn.app.ui.format.Money
import com.yawnandpawn.app.ui.format.Weekdays
import com.yawnandpawn.app.ui.sound.SoundPickerIntent
import com.yawnandpawn.app.ui.sound.SoundPickerUiState
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalTime
import org.jetbrains.compose.resources.StringResource

/** Every field the editor shows, as the user has set it. New alarms start from these defaults (Story 1.7 `Alarm`). */
data class EditorForm(
    val time: LocalTime = DEFAULT_TIME,
    val repeatDays: Set<DayOfWeek> = emptySet(),
    val label: String = "",
    val snoozeLengthMinutes: Int = Alarm.DEFAULT_SNOOZE_LENGTH_MINUTES,
    val volumePercent: Int = Alarm.DEFAULT_VOLUME_PERCENT,
    val gradualVolume: Boolean = true,
    /**
     * Where the gradual ramp starts, as a percentage of the set volume (not of the stream). Not editable (owner decision
     * 2026-09-27): with "Gradually increase volume" on, the ramp always starts at 20% of the set volume and rises to the
     * set volume over 30 s. The editor saves [Alarm.DEFAULT_RAMP_START_PERCENT].
     */
    val rampStartPercent: Int = Alarm.DEFAULT_RAMP_START_PERCENT,
    val vibration: Boolean = true,
    /** The chosen sound, an encoded `SoundRef` (Story 1.17), saved with the alarm. */
    val soundRef: String = Alarm.DEFAULT_SOUND_REF,
    /** The quiet time ("Quiet time", internally the grace window), 15 to 30 s (Story 3.4: edited and saved). */
    val graceSeconds: Int = Alarm.DEFAULT_GRACE_SECONDS,
    /** "Vibrate during quiet time" (Story 3.4: per alarm, on by default). */
    val vibrateInGrace: Boolean = Alarm.DEFAULT_VIBRATE_IN_GRACE,
    /** The checks in the order All mode runs them (Story 3.5); a new alarm starts with Math · Medium · 3. */
    val checks: List<CheckChip> = DEFAULT_CHECKS,
    val checkMode: CheckMode = CheckMode.Random,
) {
    companion object {
        /** The time a new alarm opens with. */
        val DEFAULT_TIME: LocalTime = LocalTime(hour = 7, minute = 0)

        /** The checks of a new alarm: core's `CheckConfig.DEFAULT_ENTRIES`. */
        val DEFAULT_CHECKS: List<CheckChip> =
            CheckConfig.DEFAULT_ENTRIES.mapNotNull { entry ->
                entry.type.toUi()?.let { CheckChip(it, entry.difficulty.toUi(), entry.count) }
            }

        /** The snooze-length options in the order the Snooze sub-screen lists them. */
        val SNOOZE_OPTIONS: List<Int> = Alarm.SNOOZE_LENGTHS_MINUTES.sorted()
    }
}

/**
 * The editor screen that is showing (progressive disclosure, owner decision 2026-09-27): the main card list, or the
 * sub-screen one of its rows opened. Back on a sub-screen returns to [Main]; from [CheckSetup] (one check's setup,
 * opened from [WakeCheck], Story 3.5) it returns to [WakeCheck].
 */
enum class EditorPane {
    Main,
    Sound,
    Snooze,
    WakeCheck,
    QuietTime,
    Motivation,
    CheckSetup,
    ;

    /** How deep the pane is: Back goes up one level, and the slide runs forward when going deeper. */
    val depth: Int
        get() =
            when (this) {
                Main -> 0
                CheckSetup -> 2
                else -> 1
            }
}

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
     * The rows of the full editor (EXPERIENCE.md Information Architecture) that later stories wire: wake-up check, fee
     * ladder, motivation and "Test alarm" (the sound list is [sound]; quiet time is in [form] since Story 3.4). `null`
     * hides them (the Story 1.8 editor).
     */
    val full: FullEditorSections? = null,
    /** Editing a stored alarm: the header's overflow menu offers Duplicate and Delete (also for TalkBack). */
    val hasOverflowMenu: Boolean = false,
    /** Delete was chosen in the overflow menu: "Delete your {time} alarm? This is logged." for the stored time. */
    val deleteDialogTime: LocalTime? = null,
    /**
     * The Sound row's value and the Sound sub-screen's list (Story 1.17); `null` shows the default sound's name and no
     * list (the Story 1.8 editor).
     */
    val sound: EditorSound? = null,
    /** The check whose setup [EditorPane.CheckSetup] shows. */
    val setupType: CheckType? = null,
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

/** The chosen sound as the editor shows it, and the Sound sub-screen's list. */
data class EditorSound(
    /** The chosen sound's name: [nameRes] for a built-in sound, else this (a ringtone's or file's own name). */
    val name: String = "",
    val nameRes: StringResource? = null,
    /** The chosen sound cannot be played: "File missing. Default sound will play." */
    val missing: Boolean = false,
    /** Built-in sounds, system ringtones and (Story 7.4) the user's files. */
    val picker: SoundPickerUiState = SoundPickerUiState(),
)

/** How the selected checks run: one picked at random each morning, or all of them in order. */
enum class CheckMode { Random, All }

/** One selected check, its difficulty and (Math, Word Unscramble, Memory Sequence) how many problems, words or rounds. */
data class CheckChip(
    val type: CheckType,
    val difficulty: Difficulty,
    val count: Int = type.defaultCount,
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
    /** A QR/Barcode code is registered (its Check setup row says "Code saved"). */
    val qrCodeSaved: Boolean = false,
    /** House Hunt reference photos taken, 0 to 3. */
    val houseHuntPhotos: Int = 0,
    /** The first three snooze prices of the fee ladder, or `null` while prices are not known. */
    val feeLadder: List<Money>? = null,
    /** Names of the recorded motivation messages ("Message 1"). */
    val recordings: List<String> = emptyList(),
    val motivation: MotivationChoice = MotivationChoice.None,
    val motivationTiming: MotivationTiming = MotivationTiming.AfterImUp,
    /** A weakening change was saved under the commitment lock; it applies after the alarm at this time. */
    val weakeningAppliesAfter: LocalTime? = null,
    /**
     * The full-editor rows shown on the main screen ([EditorPane.WakeCheck], [EditorPane.QuietTime],
     * [EditorPane.Motivation]): all of them in the design preview; the app shows each once its story wires it (Story 3.5:
     * the Wake-up check only).
     */
    val rows: Set<EditorPane> = setOf(EditorPane.WakeCheck, EditorPane.QuietTime, EditorPane.Motivation),
    /** The checks the Wake-up check sub-screen lists: every one in the preview, the pickable ones in the app. */
    val types: List<CheckType> = CheckType.entries,
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

    /** The editor left the screen (the app went to the background, or another screen covered it): a preview stops. */
    data object Backgrounded : EditorIntent

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

    /** A selected check's row in the Wake-up check sub-screen: opens its Check setup (design-preview round 3). */
    data class CheckSetupClicked(
        val type: CheckType,
    ) : EditorIntent

    /** A change in the open Check setup ([EditorUiState.setupType]): its difficulty or count; its Back (Story 3.5). */
    data class CheckSetup(
        val intent: CheckSetupIntent,
    ) : EditorIntent

    /** All mode: move a selected check one place up or down in the order the checks run. */
    data class CheckMoved(
        val type: CheckType,
        val up: Boolean,
    ) : EditorIntent

    data object TestAlarmClicked : EditorIntent

    /**
     * "Duplicate" in the overflow menu: opens a new, unsaved alarm prefilled from the stored alarm (unsaved changes ask
     * "Discard changes?" first).
     */
    data object DuplicateClicked : EditorIntent

    /** "Delete" in the overflow menu: asks first. */
    data object DeleteClicked : EditorIntent

    data object DeleteConfirmed : EditorIntent

    /** "Keep it", Back or a tap outside the delete dialog. */
    data object DeleteCancelled : EditorIntent
}

/** One-shot events for the screen. */
sealed interface EditorEffect {
    /** Leave the editor (saved, discarded, or nothing to lose). */
    data object Close : EditorEffect

    /** Show "Couldn't save the alarm. Try again." (the storage error itself is never shown). */
    data object ShowSaveFailed : EditorEffect

    /** A test ring is armed: show "Lock your phone. We'll ring in 10 seconds." (Story 1.18). */
    data object ShowTestScheduled : EditorEffect

    /** The alarm could not be read: leave the editor, and Home shows "Couldn't open this alarm.". */
    data object OpenFailed : EditorEffect

    /** Replace this editor with one on a new, unsaved alarm prefilled from the stored alarm [sourceId] (Duplicate). */
    data class OpenCopy(
        val sourceId: String,
    ) : EditorEffect
}
