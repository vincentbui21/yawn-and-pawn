package com.yawnandpawn.app.ui.onboarding

import com.yawnandpawn.app.core.billing.FeeRules
import com.yawnandpawn.app.ui.checkpicker.CheckPickerIntent
import com.yawnandpawn.app.ui.checkpicker.CheckPickerUiState
import com.yawnandpawn.app.ui.editor.EditorIntent
import com.yawnandpawn.app.ui.editor.EditorUiState
import com.yawnandpawn.app.ui.editor.RepeatChoice
import com.yawnandpawn.app.ui.editor.RingsAt
import com.yawnandpawn.app.ui.format.Weekdays
import com.yawnandpawn.app.ui.reliability.ChecklistItem
import com.yawnandpawn.app.ui.reliability.ChecklistRow
import com.yawnandpawn.app.ui.settings.WeakeningNote
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalTime

/** The eight onboarding steps, in order (EXPERIENCE.md IA, FR-ONB-1/5/6). */
enum class OnboardingStep { Mission, Disclosure, BaseFee, FirstAlarm, Checks, Reliability, Analytics, TestAlarm }

/**
 * The test alarm step (Story 5.12): ready to ring; a test is pending ("Ring a test alarm" disabled until its ring is
 * over); or the ring is over without a locked pass ("Your phone wasn't locked. Try again with it locked.").
 */
enum class TestAlarmStatus { Ready, Waiting, NotLocked }

/** What onboarding renders. */
data class OnboardingUiState(
    val step: OnboardingStep = OnboardingStep.Mission,
    /**
     * The base fee tier B the user chose (1 to 10; the requested one while it is being saved): snooze 1 costs B, snooze N
     * costs B x N (FR-SET-1).
     */
    val baseFeeTier: Int = FeeRules.BASE_FEE_TIERS.first,
    /** The price of [baseFeeTier] as shown: Play's own `formattedPrice`, or a USD approximation ([pricesApproximate]). */
    val baseFee: String = "",
    /** The fee ladder preview: the prices of snoozes 1 to min(3, max snoozes) as shown. */
    val feeLadder: List<String> = emptyList(),
    /** Prices never loaded: USD tiers with "Approximate. Your local price shows when you're online.". */
    val pricesApproximate: Boolean = false,
    val alarmTime: LocalTime = DEFAULT_ALARM_TIME,
    val repeatDays: Set<DayOfWeek> = Weekdays,
    /** "Custom" was chosen, so the day chips show even while the days match "Once" or "Weekdays". */
    val customRepeat: Boolean = false,
    /**
     * When the first alarm next rings, always shown on the time card as "Rings today / tomorrow / on {weekday} at {time}."
     * (like the editor); `null` (not computed yet) keeps the line's space empty.
     */
    val ringsAt: RingsAt? = null,
    /**
     * The stored first alarm, edited after Back close to its ring, keeps a weaker change pending under the commitment lock
     * (Story 4.6): "Saved. Takes effect after tomorrow's {time} alarm." under step 4's cards, as in the editor.
     */
    val firstAlarmNote: WeakeningNote? = null,
    /** The same note under step 5's Check picker while the easier checks shown wait under the lock. */
    val checksNote: WeakeningNote? = null,
    val checks: CheckPickerUiState = CheckPickerUiState(),
    /**
     * The editor's Check setup, "Try it" or QR registration over step 5 (Story 5.11), opened from a selected check's row,
     * or by "Continue" with QR/Barcode and no code; `null` shows none.
     */
    val checkSetup: EditorUiState? = null,
    /** The checklist rows of "Make sure it rings" (the test alarm is the last step, so it has no row here). */
    val reliability: List<ChecklistRow> = emptyList(),
    /** "Fix" on the Manufacturer row opened the maker guidance over step 6 (Story 5.5's screen). */
    val manufacturerGuidance: Boolean = false,
    val testStatus: TestAlarmStatus = TestAlarmStatus.Ready,
    /**
     * Step 8's Bright wake screen note, shown only while that setting is on. The setting arrives with Story 5.2, so the
     * app renders false until then (DEFAULT TAKEN, Story 5.12); true keeps the approved preview.
     */
    val brightWakeNote: Boolean = true,
    /** A lowered fee waits for an alarm under the commitment lock: "Saved. Takes effect after tomorrow's {time} alarm.". */
    val baseFeeNote: WeakeningNote? = null,
    /**
     * The step's action could not be stored ("I understand", "Continue" with the first alarm or the completion, the usage
     * stats choice): "Couldn't save. Try again." above the action.
     */
    val saveFailed: Boolean = false,
) {
    /** − is enabled above tier 1. */
    val canLowerFee: Boolean get() = baseFeeTier > FeeRules.BASE_FEE_TIERS.first

    /** + is enabled below tier 10. */
    val canRaiseFee: Boolean get() = baseFeeTier < FeeRules.BASE_FEE_TIERS.last

    val repeatChoice: RepeatChoice
        get() =
            when {
                customRepeat -> RepeatChoice.Custom
                repeatDays.isEmpty() -> RepeatChoice.Once
                repeatDays == Weekdays -> RepeatChoice.Weekdays
                else -> RepeatChoice.Custom
            }

    companion object {
        /** The design preview's sample time (flow F1); the app's first alarm opens at the editor's new-alarm time. */
        val DEFAULT_ALARM_TIME = LocalTime(hour = 7, minute = 30)
    }
}

/** Everything the user can do in onboarding. */
sealed interface OnboardingIntent {
    /**
     * The step's primary action on the step [from]: "Let's set it up", "I understand", "Continue". A tap that lands on a
     * step already left (a double tap, or a tap on the outgoing step during the slide) is ignored, so no step is skipped.
     */
    data class Next(
        val from: OnboardingStep,
    ) : OnboardingIntent

    /** The back arrow or system Back: the step before (the first step leaves onboarding alone). */
    data object Back : OnboardingIntent

    data object LowerBaseFee : OnboardingIntent

    data object RaiseBaseFee : OnboardingIntent

    data class TimeChanged(
        val time: LocalTime,
    ) : OnboardingIntent

    data class RepeatChosen(
        val choice: RepeatChoice,
    ) : OnboardingIntent

    data class DayToggled(
        val day: DayOfWeek,
    ) : OnboardingIntent

    data class Checks(
        val intent: CheckPickerIntent,
    ) : OnboardingIntent

    /** A tap in the editor's Check setup, "Try it" or QR registration shown over step 5 ([OnboardingUiState.checkSetup]). */
    data class CheckSetup(
        val intent: EditorIntent,
    ) : OnboardingIntent

    data class FixClicked(
        val item: ChecklistItem,
    ) : OnboardingIntent

    /** The maker guidance over step 6 closed (its back arrow, system Back, or a stored "I've done this"). */
    data object GuidanceClosed : OnboardingIntent

    /** The screen came back to the front (`ON_RESUME`): the checklist is evaluated again, e.g. after a "Fix". */
    data object Resumed : OnboardingIntent

    /** "Share" ([share] true) or "No thanks": the analytics choice (FR-ONB-6), off unless turned on. */
    data class UsageStatsChosen(
        val share: Boolean,
    ) : OnboardingIntent

    /** "Ring a test alarm" on step 8: a test of the first alarm in 10 seconds (Story 5.12). */
    data object RingTestAlarm : OnboardingIntent

    /** "Skip for now" on step 8: onboarding completes, and Home shows the test note (Story 5.12). */
    data object SkipTestAlarm : OnboardingIntent
}
