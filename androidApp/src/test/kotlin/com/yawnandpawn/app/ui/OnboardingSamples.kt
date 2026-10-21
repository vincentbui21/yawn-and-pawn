package com.yawnandpawn.app.ui

import com.yawnandpawn.app.core.billing.Money
import com.yawnandpawn.app.ui.checkpicker.CheckPickerUiState
import com.yawnandpawn.app.ui.checks.CheckType
import com.yawnandpawn.app.ui.checks.Difficulty
import com.yawnandpawn.app.ui.checks.PickableCheckTypes
import com.yawnandpawn.app.ui.editor.CheckChip
import com.yawnandpawn.app.ui.format.formatMoney
import com.yawnandpawn.app.ui.onboarding.OnboardingStep
import com.yawnandpawn.app.ui.onboarding.OnboardingUiState
import com.yawnandpawn.app.ui.reliability.ChecklistItem
import com.yawnandpawn.app.ui.reliability.ChecklistRow
import com.yawnandpawn.app.ui.reliability.ItemStatus
import kotlinx.datetime.LocalTime

/**
 * Onboarding steps 1 to 7 as the app shows them (Stories 5.10 and 5.11). Getters, because they format prices with the
 * phone's `MoneyFormatter` (ICU), which only runs inside a Robolectric test.
 */
internal object OnboardingSamples {
    /** Play's prices loaded (a euro phone): tier 2, so both stepper ends are enabled. */
    private fun loaded(step: OnboardingStep): OnboardingUiState =
        OnboardingUiState(
            step = step,
            baseFeeTier = 2,
            baseFee = formatMoney(Money.of(2, "EUR")),
            feeLadder = listOf(2, 4, 6).map { formatMoney(Money.of(it, "EUR")) },
        )

    val mission: OnboardingUiState get() = loaded(OnboardingStep.Mission)

    val disclosure: OnboardingUiState get() = loaded(OnboardingStep.Disclosure)

    val baseFee: OnboardingUiState get() = loaded(OnboardingStep.BaseFee)

    /** Never online: US dollar tiers with "Approximate. Your local price shows when you're online.", at tier 1. */
    val baseFeeApproximate: OnboardingUiState get() =
        OnboardingUiState(
            step = OnboardingStep.BaseFee,
            baseFeeTier = 1,
            baseFee = formatMoney(Money.of(1, "USD")),
            feeLadder = listOf(1, 2, 3).map { formatMoney(Money.of(it, "USD")) },
            pricesApproximate = true,
        )

    /** The editor's new alarm in the evening: a one-time 7:00 that rings tomorrow, with the default check. */
    private fun firstAlarm(step: OnboardingStep): OnboardingUiState =
        OnboardingUiState(
            step = step,
            alarmTime = LocalTime(7, 0),
            repeatDays = emptySet(),
            ringsTomorrowAt = LocalTime(7, 0),
            checks = CheckPickerUiState(checks = listOf(CheckChip(CheckType.Math, Difficulty.Easy, count = 3)), types = PickableCheckTypes),
        )

    /** Step 4: "When should it ring?" with "Rings tomorrow at 7:00 AM.". */
    val firstAlarm: OnboardingUiState get() = firstAlarm(OnboardingStep.FirstAlarm)

    /** Step 5 with Math and QR/Barcode selected, no code yet ("Scan a code to use this check."). */
    val checksQr: OnboardingUiState get() =
        firstAlarm(OnboardingStep.Checks).let { state ->
            state.copy(
                checks =
                    state.checks.copy(
                        checks = state.checks.checks + CheckChip(CheckType.QrBarcode, Difficulty.Medium),
                    ),
            )
        }

    /** Step 5 with no check: "Pick at least one check." and "Continue" disabled. */
    val checksNone: OnboardingUiState get() =
        firstAlarm(OnboardingStep.Checks).let { state ->
            state.copy(checks = state.checks.copy(checks = emptyList(), noCheckError = true))
        }

    /** Step 6 with notifications and battery to fix (two "Fix" rows). */
    val checklist: OnboardingUiState get() =
        firstAlarm(OnboardingStep.Reliability).copy(
            reliability =
                listOf(
                    ChecklistRow(ChecklistItem.Notifications, ItemStatus.Missing),
                    ChecklistRow(ChecklistItem.FullScreen, ItemStatus.Ok),
                    ChecklistRow(ChecklistItem.DoNotDisturb, ItemStatus.Ok),
                    ChecklistRow(ChecklistItem.Battery, ItemStatus.Missing),
                ),
        )

    /** Step 7: "Share anonymous usage stats?" with "Share" and "No thanks". */
    val usageStats: OnboardingUiState get() = firstAlarm(OnboardingStep.Analytics)
}
