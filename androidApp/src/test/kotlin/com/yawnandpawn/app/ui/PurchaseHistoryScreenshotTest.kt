package com.yawnandpawn.app.ui

import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import com.yawnandpawn.app.StopAppRule
import com.yawnandpawn.app.screenshotOptions
import com.yawnandpawn.app.ui.purchases.PURCHASE_HISTORY_SKELETON_TAG
import com.yawnandpawn.app.ui.purchases.PurchaseHistoryScreen
import com.yawnandpawn.app.ui.purchases.PurchaseHistoryUiState
import com.yawnandpawn.app.ui.theme.PpsThemeMode
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Story 4.16 screenshots of Purchase history as the app shows it (no "Problem with a charge?" row until Story 4.17):
 * mixed statuses with an unknown amount and month totals, two currencies, empty, loading (the skeleton) and the load
 * failure, in Light and Dark, the primary states also at 200% font scale.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-mdpi")
class PurchaseHistoryScreenshotTest {
    @get:Rule(order = 0)
    val appTeardown = StopAppRule()

    @get:Rule(order = 1)
    val composeRule = createEmptyComposeRule()

    private fun capture(
        name: String,
        state: PurchaseHistoryUiState,
        mode: PpsThemeMode = PpsThemeMode.Light,
    ) = withScreen(mode, content = {
        PurchaseHistoryScreen(state = state, is24Hour = false, onBack = {}, onProblemWithCharge = {}, showProblemWithCharge = false)
    }) {
        composeRule.waitForIdle()
        if (state.loading) {
            composeRule.mainClock.advanceTimeBy(SKELETON_WAIT_MILLIS)
            composeRule.onNodeWithTag(PURCHASE_HISTORY_SKELETON_TAG, useUnmergedTree = true).assertExists()
        }
        composeRule.onRoot().captureRoboImage("src/test/screenshots/$name.png", roborazziOptions = screenshotOptions)
    }

    @Test
    fun `mixed statuses light`() = capture("purchase_history_mixed_light", PurchaseHistorySamples.mixed)

    @Test
    fun `mixed statuses dark`() = capture("purchase_history_mixed_dark", PurchaseHistorySamples.mixed, PpsThemeMode.Dark)

    @Test
    @Config(fontScale = 2.0f)
    fun `mixed statuses at 200 percent`() = capture("purchase_history_mixed_light_font200", PurchaseHistorySamples.mixed)

    @Test
    fun `two currencies light`() = capture("purchase_history_two_currencies_light", PurchaseHistorySamples.twoCurrencies)

    @Test
    fun `two currencies dark`() = capture("purchase_history_two_currencies_dark", PurchaseHistorySamples.twoCurrencies, PpsThemeMode.Dark)

    @Test
    @Config(fontScale = 2.0f)
    fun `two currencies at 200 percent`() = capture("purchase_history_two_currencies_light_font200", PurchaseHistorySamples.twoCurrencies)

    @Test
    fun `empty light`() = capture("purchase_history_empty_light", PurchaseHistorySamples.empty)

    @Test
    fun `empty dark`() = capture("purchase_history_empty_dark", PurchaseHistorySamples.empty, PpsThemeMode.Dark)

    @Test
    @Config(fontScale = 2.0f)
    fun `empty at 200 percent`() = capture("purchase_history_empty_light_font200", PurchaseHistorySamples.empty)

    @Test
    fun `loading light`() = capture("purchase_history_loading_light", PurchaseHistorySamples.loading)

    @Test
    fun `loading dark`() = capture("purchase_history_loading_dark", PurchaseHistorySamples.loading, PpsThemeMode.Dark)

    @Test
    fun `load failure light`() = capture("purchase_history_load_failed_light", PurchaseHistorySamples.loadFailed)

    @Test
    fun `load failure dark`() = capture("purchase_history_load_failed_dark", PurchaseHistorySamples.loadFailed, PpsThemeMode.Dark)

    @Test
    @Config(fontScale = 2.0f)
    fun `load failure at 200 percent`() = capture("purchase_history_load_failed_light_font200", PurchaseHistorySamples.loadFailed)

    private companion object {
        /** Past the 300 ms skeleton delay. */
        const val SKELETON_WAIT_MILLIS = 400L
    }
}
