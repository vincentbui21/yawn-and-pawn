package com.yawnandpawn.app.ui

import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.yawnandpawn.app.StopAppRule
import com.yawnandpawn.app.ui.editor.AlarmEditorScreen
import com.yawnandpawn.app.ui.editor.EditorUiState
import com.yawnandpawn.app.ui.format.Countdown
import com.yawnandpawn.app.ui.home.DisableUnderLock
import com.yawnandpawn.app.ui.home.HomeIntent
import com.yawnandpawn.app.ui.home.HomeScreen
import com.yawnandpawn.app.ui.home.HomeUiState
import com.yawnandpawn.app.ui.theme.PpsThemeMode
import kotlinx.datetime.LocalTime
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertEquals

/**
 * Story 4.6 with TalkBack in mind, at 200 % font: the "Saved. Takes effect after ..." notes are polite live regions, the
 * snooze lengths and the turn-off dialog's buttons are at least 48 dp, and "Keep it on" leaves the alarm on. The dialog's
 * four "It rings in ..." bodies (0.1.7 polish).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w360dp-h640dp-xxhdpi", fontScale = 2.0f)
class EditorLockSemanticsTest {
    @get:Rule(order = 0)
    val appTeardown = StopAppRule()

    @get:Rule(order = 1)
    val composeRule = createEmptyComposeRule()

    private val polite = SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite)
    private val note = "Saved. Takes effect after tomorrow's 7:30 AM alarm."

    private fun editor(
        state: EditorUiState,
        block: () -> Unit,
    ) = withScreen(PpsThemeMode.Light, content = { AlarmEditorScreen(state = state, is24Hour = false, onIntent = {}) }, block = block)

    private fun home(
        state: HomeUiState,
        onIntent: (HomeIntent) -> Unit = {},
        block: () -> Unit,
    ) = withScreen(PpsThemeMode.Light, content = { HomeScreen(state = state, is24Hour = false, onIntent = onIntent) }, block = block)

    @Test
    fun `the pending-field notes are polite live regions`() {
        listOf(EditorLockSamples.pendingMain, EditorLockSamples.pendingQuietTime, EditorLockSamples.pendingChecks).forEach { state ->
            editor(state) { composeRule.onNode(hasText(note) and polite).assertExists() }
        }
    }

    @Test
    fun `the snooze lengths are 48 dp and the ladder reads as text`() =
        editor(EditorLockSamples.ladderApproximate) {
            composeRule.onAllNodes(isSelectable()).fetchSemanticsNodes().let { assertEquals(4, it.size) }
            listOf("5 min", "9 min", "10 min", "15 min").forEach { label ->
                composeRule.onNode(hasText(label) and isSelectable()).assertHeightIsAtLeast(48.dp)
            }
            composeRule.onNodeWithText("Snooze 1: $2.00 · 2: $4.00 · 3: $6.00", useUnmergedTree = true).assertExists()
            composeRule.onNodeWithText("Approximate. Your local price shows when you're online.", useUnmergedTree = true).assertExists()
        }

    @Test
    fun `the turn-off dialog's buttons are at least 48 dp, and Keep it on is the safe action`() {
        val intents = mutableListOf<HomeIntent>()
        home(EditorLockSamples.turnOffHours, onIntent = { intents += it }) {
            composeRule.onNodeWithText("Turn off the 7:30 AM alarm?").assertExists()
            composeRule.onNodeWithText("It rings in 7 hours.").assertExists()
            val turnOff = composeRule.onNode(hasText("Turn off") and hasClickAction())
            val keep = composeRule.onNode(hasText("Keep it on") and hasClickAction())
            listOf(turnOff, keep).forEach { it.assertHeightIsAtLeast(48.dp) }

            keep.performClick()
            assertEquals(listOf<HomeIntent>(HomeIntent.DisableCancelled), intents)
        }
    }

    @Test
    fun `the turn-off dialog says how long until the ring in hours, 1 hour, minutes or 1 minute (0_1_7 polish)`() {
        val variants =
            mapOf(
                Countdown.HoursMinutes(7, 59) to "It rings in 7 hours.",
                Countdown.HoursMinutes(1, 30) to "It rings in 1 hour.",
                Countdown.Minutes(25) to "It rings in 25 minutes.",
                Countdown.Minutes(1) to "It rings in 1 minute.",
            )
        variants.forEach { (ringsIn, body) ->
            val state = EditorLockSamples.turnOffHours.copy(disableDialog = DisableUnderLock("1", LocalTime(7, 30), ringsIn))
            home(state) {
                composeRule.onNodeWithText("Turn off the 7:30 AM alarm?").assertExists()
                composeRule.onNodeWithText(body).assertExists()
                composeRule.onNodeWithText("This is logged.", substring = true).assertDoesNotExist()
            }
        }
    }

    @Test
    fun `the saved-under-lock snackbar says the note politely`() =
        home(EditorLockSamples.savedSnackbar) {
            composeRule.onNode(hasText(note) and polite).assertExists()
        }
}
