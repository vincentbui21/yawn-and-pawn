package com.yawnandpawn.app.ui

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import com.yawnandpawn.app.StopAppRule
import com.yawnandpawn.app.ui.TalkBackRules.assertReadsInOrder
import com.yawnandpawn.app.ui.reliability.ChecklistItem
import com.yawnandpawn.app.ui.reliability.ReliabilityIntent
import com.yawnandpawn.app.ui.reliability.ReliabilityScreen
import com.yawnandpawn.app.ui.reliability.ReliabilityUiState
import com.yawnandpawn.app.ui.theme.PpsThemeMode
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertEquals

/**
 * Story 5.4 with TalkBack in mind, at 200 % font on a 360 dp phone: each `checklist-row` is one TalkBack stop of at
 * least 64 dp that reads its title, its reason (or the revoked line) and "OK", with "Fix" its own 48 dp button read
 * right after as "<title>, Fix"; the Test alarm row's hollow ring reads "Ring a test alarm" as its state; Back and "Ring
 * a test alarm" are 48 dp buttons.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w360dp-h640dp-xxhdpi", fontScale = 2.0f)
class ReliabilitySemanticsTest {
    @get:Rule(order = 0)
    val appTeardown = StopAppRule()

    @get:Rule(order = 1)
    val composeRule = createEmptyComposeRule()

    private fun show(
        state: ReliabilityUiState,
        onIntent: (ReliabilityIntent) -> Unit = {},
        block: () -> Unit,
    ) = withScreen(PpsThemeMode.Light, content = { ReliabilityScreen(state = state, onIntent = onIntent) }, block = block)

    /** The merged row node reading [title] and [reason]. */
    private fun row(
        title: String,
        reason: String,
    ) = composeRule.onNode(hasText(title) and hasText(reason))

    @Test
    fun `each row is one stop of at least 64 dp reading title, reason and OK`() =
        show(ReliabilitySamples.allOk) {
            listOf(
                "Notifications" to "Needed to show your alarm.",
                "Full-screen alarm" to "Shows the alarm over the lock screen.",
                "Do Not Disturb" to "Do Not Disturb must allow alarms.",
                "Battery" to "Stops Android from pausing your alarm.",
                "Test alarm" to "Ring a test with your phone locked.",
            ).forEach { (title, reason) ->
                row(title, reason).performScrollTo().assertHeightIsAtLeast(64.dp).assert(hasText("OK"))
            }
            assertEquals(0, composeRule.onAllNodesWithText("Fix").fetchSemanticsNodes().size, "nothing to fix")
        }

    @Test
    fun `a row to fix reads title and reason, then its own 48 dp Fix button, which sends that row`() {
        val intents = mutableListOf<ReliabilityIntent>()
        show(ReliabilitySamples.severalNeedFix, onIntent = { intents += it }) {
            val notifications = row("Notifications", "Needed to show your alarm.")
            notifications.performScrollTo().assertHeightIsAtLeast(64.dp)
            notifications.assert(!hasText("OK") and !hasText("Fix") and !hasClickAction())
            composeRule.assertReadsInOrder(
                "Notifications",
                "Notifications, Fix",
                "Full-screen alarm",
                "Do Not Disturb",
                "Do Not Disturb, Fix",
                "Battery",
                "Test alarm",
            )

            val fixes = composeRule.onAllNodes(hasText("Fix") and hasClickAction())
            assertEquals(2, fixes.fetchSemanticsNodes().size, "the Test alarm row has no Fix: the button below rings it")
            repeat(2) { index ->
                fixes[index]
                    .performScrollTo()
                    .assertHeightIsAtLeast(48.dp)
                    .assertWidthIsAtLeast(48.dp)
                    .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
            }
            fixes[1].performClick()
            fixes[0].performClick()

            assertEquals(
                listOf<ReliabilityIntent>(
                    ReliabilityIntent.FixClicked(ChecklistItem.DoNotDisturb),
                    ReliabilityIntent.FixClicked(ChecklistItem.Notifications),
                ),
                intents,
            )
        }
    }

    @Test
    fun `each Fix names its row, and the Test alarm row not yet rung reads its state (review item 6)`() {
        show(ReliabilitySamples.severalNeedFix) {
            composeRule.onNode(hasContentDescription("Notifications, Fix") and hasClickAction()).assertExists()
            composeRule.onNode(hasContentDescription("Do Not Disturb, Fix") and hasClickAction()).assertExists()
            row("Test alarm", "Ring a test with your phone locked.")
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Ring a test alarm"))
        }
        show(ReliabilitySamples.allOk) {
            row("Test alarm", "Ring a test with your phone locked.")
                .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.StateDescription))
                .assert(hasText("OK"))
        }
    }

    @Test
    fun `a revoked row reads the revoked line instead of its reason`() =
        show(ReliabilitySamples.revokedBattery) {
            row("Battery", "Alarm may not ring: battery optimization turned back on").performScrollTo().assertHeightIsAtLeast(64.dp)
            composeRule.onNode(hasText("Stops Android from pausing your alarm.")).assertDoesNotExist()
        }

    @Test
    fun `the camera and API 31 exact alarm rows read like the others`() {
        show(ReliabilitySamples.cameraVisible) {
            row("Camera", "Needed for your QR/Barcode check.").performScrollTo().assertHeightIsAtLeast(64.dp)
            row("Manufacturer settings", "Your phone may stop apps in the background.").assert(hasText("OK"))
        }
        show(ReliabilitySamples.api31Exact) {
            row("Exact alarms", "Lets the alarm ring on time.").performScrollTo().assertHeightIsAtLeast(64.dp)
        }
    }

    @Test
    fun `Back and Ring a test alarm are 48 dp buttons that send their intents`() {
        val intents = mutableListOf<ReliabilityIntent>()
        show(ReliabilitySamples.allOk, onIntent = { intents += it }) {
            composeRule
                .onNodeWithContentDescription("Back")
                .assertHeightIsAtLeast(48.dp)
                .assertWidthIsAtLeast(48.dp)
                .performClick()
            composeRule
                .onNode(hasText("Ring a test alarm") and hasClickAction())
                .performScrollTo()
                .assertHeightIsAtLeast(48.dp)
                .performClick()

            assertEquals(listOf(ReliabilityIntent.Back, ReliabilityIntent.RingTestAlarm), intents)
        }
    }
}
