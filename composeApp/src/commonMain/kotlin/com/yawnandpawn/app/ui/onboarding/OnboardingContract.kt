package com.yawnandpawn.app.ui.onboarding

import com.yawnandpawn.app.core.billing.FeeRules
import com.yawnandpawn.app.ui.checkpicker.CheckPickerIntent
import com.yawnandpawn.app.ui.checkpicker.CheckPickerUiState
import com.yawnandpawn.app.ui.editor.RepeatChoice
import com.yawnandpawn.app.ui.format.Weekdays
import com.yawnandpawn.app.ui.reliability.ChecklistItem
import com.yawnandpawn.app.ui.reliability.ChecklistRow
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalTime

/** The eight onboarding steps, in order (EXPERIENCE.md IA, FR-ONB-1/5/6). */
enum class OnboardingStep { Mission, Disclosure, BaseFee, FirstAlarm, Checks, Reliability, Analytics, TestAlarm }

/** The test alarm step: ready to ring, or the phone was not locked when it rang. */
enum class TestAlarmStatus { Ready, NotLocked }

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
    val checks: CheckPickerUiState = CheckPickerUiState(),
    /** The checklist rows of "Make sure it rings" (the test alarm is the last step, so it has no row here). */
    val reliability: List<ChecklistRow> = emptyList(),
    val testStatus: TestAlarmStatus = TestAlarmStatus.Ready,
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

    data class FixClicked(
        val item: ChecklistItem,
    ) : OnboardingIntent

    /** "Share" ([share] true) or "No thanks": the analytics choice (FR-ONB-6), off unless turned on. */
    data class UsageStatsChosen(
        val share: Boolean,
    ) : OnboardingIntent

    data object RingTestAlarm : OnboardingIntent

    data object SkipTestAlarm : OnboardingIntent
}
