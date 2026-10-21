package com.yawnandpawn.app.ui

import com.yawnandpawn.app.core.billing.Money
import com.yawnandpawn.app.ui.format.formatMoney
import com.yawnandpawn.app.ui.onboarding.OnboardingStep
import com.yawnandpawn.app.ui.onboarding.OnboardingUiState

/**
 * Onboarding steps 1 to 3 as the app shows them (Story 5.10). Getters, because they format prices with the phone's
 * `MoneyFormatter` (ICU), which only runs inside a Robolectric test.
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
}
