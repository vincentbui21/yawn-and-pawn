package com.yawnandpawn.app.ui.editor

import com.yawnandpawn.app.core.alarm.Alarm
import com.yawnandpawn.app.core.alarm.AlarmField
import com.yawnandpawn.app.core.alarm.CheckConfig
import com.yawnandpawn.app.core.billing.Money
import com.yawnandpawn.app.core.checks.qr.RegisteredCode
import com.yawnandpawn.app.ui.checks.CheckType
import com.yawnandpawn.app.ui.checks.Difficulty
import com.yawnandpawn.app.ui.checks.defaultCount
import com.yawnandpawn.app.ui.checks.toUi
import com.yawnandpawn.app.ui.checksetup.CheckPreviewUiState
import com.yawnandpawn.app.ui.checksetup.CheckSetupIntent
import com.yawnandpawn.app.ui.format.Countdown
import com.yawnandpawn.app.ui.format.Weekdays
import com.yawnandpawn.app.ui.qr.ScanEvent
import com.yawnandpawn.app.ui.settings.WeakeningNote
import com.yawnandpawn.app.ui.sound.SoundPickerIntent
import com.yawnandpawn.app.ui.sound.SoundPickerUiState
import com.yawnandpawn.app.ui.wake.WakeIntent
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
    /**
     * The alarm's own stored quiet time (Story 3.4), passed through unchanged: since 0.1.7 the editor no longer shows or
     * edits it, and sessions use the global quiet time in Settings.
     */
    val graceSeconds: Int = Alarm.DEFAULT_GRACE_SECONDS,
    /** The alarm's own stored "Vibrate during quiet time", passed through unchanged like [graceSeconds] (0.1.7). */
    val vibrateInGrace: Boolean = Alarm.DEFAULT_VIBRATE_IN_GRACE,
    /** The checks in the order All mode runs them (Story 3.5); a new alarm starts with Math · Easy · 3. */
    val checks: List<CheckChip> = DEFAULT_CHECKS,
    val checkMode: CheckMode = CheckMode.Random,
    /** The motivation message the alarm plays (Story 7.2), saved with the alarm; never a locked field. */
    val motivation: MotivationChoice = MotivationChoice.None,
    /** "When it plays" (Story 7.5), saved with the alarm; kept when the message is None, never a locked field. */
    val motivationTiming: MotivationTiming = MotivationTiming.AfterImUp,
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
 * opened from [WakeCheck], Story 3.5) it returns to [WakeCheck], and from [TryIt] (its "Try it" preview, Story 3.6) to
 * [CheckSetup].
 */
enum class EditorPane {
    Main,
    Sound,
    Snooze,
    WakeCheck,
    Motivation,
    CheckSetup,
    TryIt,

    /** QR registration (Story 3.10), opened from Check setup's "Your code" or Home's "Re-register". */
    ScanCode,

    /** Recordings (Story 7.2), opened from the Motivation sub-screen's "Record a message"; Back returns there. */
    Recordings,

    /**
     * The printable QR (Story 7.10), opened from Check setup's or QR registration's "Make a printable QR"; Back returns to
     * the pane it was opened from. It comes after [ScanCode], so opening it from there slides forward ([isDeeperThan]).
     */
    PrintableQr,
    ;

    /** How deep the pane is: Back goes up one level, and the slide runs forward when going deeper. */
    val depth: Int
        get() =
            when (this) {
                Main -> 0
                CheckSetup, Recordings -> 2
                TryIt, ScanCode, PrintableQr -> 3
                else -> 1
            }

    /** Going from [other] to this pane slides forward: deeper, or as deep and later (QR registration to the printable QR). */
    fun isDeeperThan(other: EditorPane): Boolean = depth > other.depth || (depth == other.depth && ordinal > other.ordinal)
}

/** The repeat quick choices "Once" · "Weekdays" · "Custom"; Custom reveals the day chips. */
enum class RepeatChoice { Once, Weekdays, Custom }

/**
 * The next ring as the time card's line says it: [daysAhead] days after today (0 today, 1 tomorrow, more on its
 * [dayOfWeek]) at [time].
 */
data class RingsAt(
    val daysAhead: Int,
    val dayOfWeek: DayOfWeek,
    val time: LocalTime,
)

/** What the Alarm editor screen renders. */
data class EditorUiState(
    /** `true` for a new alarm ("New alarm"), `false` when editing a stored one ("Edit alarm"). */
    val isNew: Boolean = true,
    /** An existing alarm is still being read; the form is not shown yet. */
    val isLoading: Boolean = false,
    val form: EditorForm = EditorForm(),
    /**
     * When the alarm next rings (the chosen time, or later in a DST gap), always shown under the wheels as "Rings today at
     * {time}.", "Rings tomorrow at {time}." or "Rings on {weekday} at {time}." (0.1.7 polish: the line never comes and
     * goes, so the layout does not jump); `null` (not computed yet) keeps the line's space empty.
     */
    val ringsAt: RingsAt? = null,
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
     * ladder and motivation (the sound list is [sound]; quiet time is in [form] since Story 3.4). `null` hides them (the
     * Story 1.8 editor). "Test alarm" is not one of them: it is in the header's "More options" menu in every editor
     * (owner decision 2026-10-31, 0.1.8).
     */
    val full: FullEditorSections? = null,
    /**
     * Editing a stored alarm: the header's overflow menu offers Duplicate and Delete after "Test alarm" (also for
     * TalkBack); a new alarm or a duplicate gets "Test alarm" only (owner decision 2026-10-31, 0.1.8).
     */
    val hasOverflowMenu: Boolean = false,
    /** Delete was chosen in the overflow menu: "Delete the {time} alarm?" for the stored time. */
    val deleteDialogTime: LocalTime? = null,
    /**
     * The Sound row's value and the Sound sub-screen's list (Story 1.17); `null` shows the default sound's name and no
     * list (the Story 1.8 editor).
     */
    val sound: EditorSound? = null,
    /** The check whose setup [EditorPane.CheckSetup] shows. */
    val setupType: CheckType? = null,
    /** The "Try it" preview [EditorPane.TryIt] shows (Story 3.6); never part of the form. */
    val tryIt: CheckPreviewUiState? = null,
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
    /** QR/Barcode: the registered code (Story 3.10), saved with the alarm; null until one is scanned. */
    val code: RegisteredCode? = null,
)

/**
 * The motivation message the alarm plays: none, a random one of the recordings, or one recording by its id (Story 7.2;
 * the core `Motivation`).
 */
sealed interface MotivationChoice {
    data object None : MotivationChoice

    data object Random : MotivationChoice

    data class Recording(
        val id: String,
    ) : MotivationChoice
}

/** One recorded message as the Motivation sub-screen lists it: its [name], and whether its file is [missing]. */
data class MotivationRecording(
    val id: String,
    val name: String,
    val missing: Boolean = false,
)

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
    /** Picking a camera check was refused the camera: "Camera isn't available." with "Fix" (Story 3.10). */
    val cameraUnavailable: Boolean = false,
    /** House Hunt reference photos taken, 0 to 3. */
    val houseHuntPhotos: Int = 0,
    /**
     * The fee ladder under the snooze length (Story 4.6): snoozes 1 to min(3, max snoozes) at the base fee the alarm's
     * next ring will charge; `null` shows no ladder.
     */
    val feeLadder: List<LadderPrice>? = null,
    /** Prices never loaded: the ladder is in USD with "Approximate. Your local price shows when you're online.". */
    val pricesApproximate: Boolean = false,
    /** The recorded motivation messages, oldest first (Story 7.2: the radio rows of the Motivation sub-screen). */
    val recordings: List<MotivationRecording> = emptyList(),
    val motivation: MotivationChoice = MotivationChoice.None,
    val motivationTiming: MotivationTiming = MotivationTiming.AfterImUp,
    /** "When it plays" shows (with a message chosen): in the design preview and, since Story 7.5, in the app. */
    val motivationTimingShown: Boolean = true,
    /**
     * A weakening change of this alarm waits under the commitment lock (Story 4.6): "Saved. Takes effect after
     * tomorrow's {time} alarm." (or "today's") under the rows card. It shows while a field holds its pending value.
     */
    val weakeningNote: WeakeningNote? = null,
    /** The checks show their pending (easier) plan: the same note under them in the Wake-up check sub-screen. */
    val checksNote: WeakeningNote? = null,
    /**
     * The full-editor rows shown on the main screen ([EditorPane.WakeCheck], [EditorPane.Motivation]): all of them in the
     * design preview; the app shows each once its story wires it (Story 3.5: the Wake-up check only). The quiet time is a
     * Settings row since 0.1.7.
     */
    val rows: Set<EditorPane> = setOf(EditorPane.WakeCheck, EditorPane.Motivation),
    /** The checks the Wake-up check sub-screen lists: every one in the preview, the pickable ones in the app. */
    val types: List<CheckType> = CheckType.entries,
    /** TalkBack is on (Story 3.8): Memory Sequence says "Uses numbered tiles with TalkBack." in the picker and its setup. */
    val talkBackOn: Boolean = false,
)

/**
 * One price of the fee ladder: [Text] as shown (Play's own `formattedPrice`, or a USD approximation already formatted by
 * the `MoneyFormatter`), or an [Amount] the screen formats in the phone's locale (the design preview's samples).
 */
sealed interface LadderPrice {
    data class Text(
        val text: String,
    ) : LadderPrice

    data class Amount(
        val money: Money,
    ) : LadderPrice
}

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

    /** "Record a message": opens Recordings (design-preview round 3; Story 7.2). */
    data object RecordMessageClicked : EditorIntent

    /** Recordings saved a new message [id] (Story 7.2): it is listed; the choice stays the user's. */
    data class RecordingSaved(
        val id: String,
    ) : EditorIntent

    /** Recordings deleted the message [id] (Story 7.2): a choice of it becomes None, as the stored alarms did. */
    data class RecordingDeleted(
        val id: String,
    ) : EditorIntent

    /** A selected check's row in the Wake-up check sub-screen: opens its Check setup (design-preview round 3). */
    data class CheckSetupClicked(
        val type: CheckType,
    ) : EditorIntent

    /** A change in the open Check setup ([EditorUiState.setupType]): its difficulty or count; its Back (Story 3.5). */
    data class CheckSetup(
        val intent: CheckSetupIntent,
    ) : EditorIntent

    /** A tap in the "Try it" preview (Story 3.6): the pad, "Check" or "Done". */
    data class TryIt(
        val intent: WakeIntent,
    ) : EditorIntent

    /** What the camera of a QR/Barcode "Try it" reported (Story 3.10). */
    data class TryItScanned(
        val event: ScanEvent,
    ) : EditorIntent

    /** QR registration's "Use this code" (Story 3.10): the code of the QR/Barcode check. */
    data class CodeRegistered(
        val code: RegisteredCode,
    ) : EditorIntent

    /** "Fix" on "Camera isn't available.": the app's page in the system settings (Story 3.10). */
    data object FixCamera : EditorIntent

    /** QR registration's "Make a printable QR" (Story 7.10): opens the printable QR, whose Back returns there. */
    data object PrintableQrClicked : EditorIntent

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

    /**
     * Leave the editor after a save that left a weakening change waiting under the commitment lock (Story 4.6): Home
     * shows [note] as "Saved. Takes effect after tomorrow's {time} alarm." (or "today's").
     */
    data class CloseSavedUnderLock(
        val note: WeakeningNote,
    ) : EditorEffect

    /** Show "Couldn't save the alarm. Try again." (the storage error itself is never shown). */
    data object ShowSaveFailed : EditorEffect

    /** A test ring is armed: show "Lock your phone. We'll ring in 10 seconds." (Story 1.18). */
    data object ShowTestScheduled : EditorEffect

    /** A picked file was refused (Story 7.4): show "This file can't be played. Pick another." */
    data object ShowSoundFileNotPlayable : EditorEffect

    /** A picked file is over 20 MB (Story 7.4): show "This file is too big. Pick one under 20 MB." */
    data object ShowSoundFileTooBig : EditorEffect

    /** The alarm could not be read: leave the editor, and Home shows "Couldn't open this alarm.". */
    data object OpenFailed : EditorEffect

    /** Replace this editor with one on a new, unsaved alarm prefilled from the stored alarm [sourceId] (Duplicate). */
    data class OpenCopy(
        val sourceId: String,
    ) : EditorEffect
}
