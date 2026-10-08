package com.yawnandpawn.app.ui.onboarding

import com.yawnandpawn.app.core.billing.Money
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
    /** Snooze 1 costs the base fee B; snooze N costs B x N (FR-SET-1). */
    val baseFee: Money,
    val lowerFee: Money? = null,
    val higherFee: Money? = null,
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
    /** The step's primary action: "Let's set it up", "I understand", "Continue". */
    data object Next : OnboardingIntent

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
