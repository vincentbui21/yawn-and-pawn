package com.yawnandpawn.app.ui

import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import com.yawnandpawn.app.StopAppRule
import com.yawnandpawn.app.ui.TalkBackRules.spokenOrder
import com.yawnandpawn.app.ui.payments.NO_EMAIL_APP_NOTE_TAG
import com.yawnandpawn.app.ui.payments.PaymentsIntent
import com.yawnandpawn.app.ui.payments.ProblemWithChargeFlow
import com.yawnandpawn.app.ui.payments.ProblemWithChargeUiState
import com.yawnandpawn.app.ui.theme.PpsThemeMode
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Story 4.17: what TalkBack reads on "Problem with a charge?" and its picker, and their targets. The three paragraphs
 * read before the two actions; each picker row is one radio button (date and time, price and status) with its
 * selected state, at least 48 dp; "Email support" is a 48 dp button that stays on screen at 200%; the snackbar is
 * announced.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-mdpi")
class ProblemWithChargeSemanticsTest {
    @get:Rule(order = 0)
    val appTeardown = StopAppRule()

    @get:Rule(order = 1)
    val composeRule = createEmptyComposeRule()

    private val intents = mutableListOf<PaymentsIntent>()

    private fun show(
        state: ProblemWithChargeUiState,
        block: () -> Unit,
    ) = withScreen(PpsThemeMode.Light, content = {
        ProblemWithChargeFlow(state = state, is24Hour = false, onIntent = { intents += it })
    }, block = block)

    private fun assertTallEnough(
        matcher: SemanticsMatcher,
        min: androidx.compose.ui.unit.Dp = 48.dp,
    ) {
        val bounds = composeRule.onNode(matcher).assertExists().getBoundsInRoot()
        assertTrue(bounds.bottom - bounds.top >= min, "$matcher is ${bounds.bottom - bounds.top} tall")
    }

    private fun radio(vararg texts: String) =
        SemanticsMatcher("radio button ${texts.toList()}") { node ->
            node.config.getOrElseNullable(SemanticsProperties.Role) { null } == Role.RadioButton &&
                node.config.getOrElseNullable(SemanticsProperties.Text) { null }?.map { it.text } == texts.toList()
        }

    @Test
    fun `the screen reads its paragraphs, then the two actions, each a 48 dp button`() =
        show(ProblemWithChargeSamples.withCharges) {
            assertEquals(
                listOf(
                    "Back",
                    "Problem with a charge?",
                    "Google lets you request a refund yourself within 48 hours of a charge.",
                    "Payments that weren't used are refunded automatically by Google.",
                    "A pending payment only charges you if it goes through.",
                    "Open Google Play order history",
                    "Email support",
                ),
                composeRule.spokenOrder(),
            )
            assertTallEnough(hasText("Open Google Play order history") and hasClickAction())
            assertTallEnough(hasText("Email support") and hasClickAction())

            composeRule.onNode(hasText("Open Google Play order history") and hasClickAction()).performClick()
            composeRule.onNode(hasText("Email support") and hasClickAction()).performClick()

            assertEquals(listOf(PaymentsIntent.OpenOrderHistoryClicked, PaymentsIntent.EmailSupportClicked), intents)
        }

    @Test
    fun `no browser is announced as No browser found`() =
        show(ProblemWithChargeSamples.noBrowser) {
            composeRule
                .onNodeWithText("No browser found.")
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
        }

    @Test
    fun `each picker row is one radio button with date, time, price and status, the newest selected, 48 dp tall`() =
        show(ProblemWithChargeSamples.picker) {
            val newest = radio("Wed, Sep 23 · 7:41 AM", "$2.00 · Snooze 2")
            composeRule.onNode(newest).assertIsSelected()
            assertTallEnough(newest)
            listOf(
                radio("Wed, Sep 23 · 7:32 AM", "$1.00 · Snooze 1"),
                // The amount is not known: no price.
                radio("Tue, Sep 22 · 7:31 AM", "Snooze 1"),
                radio("Sun, Sep 6 · 7:33 AM", "$1.00 · Not used, refunded automatically by Google"),
            ).forEach { row ->
                composeRule.onNode(row).assertIsNotSelected()
                assertTallEnough(row)
            }
            composeRule.onNodeWithText("Which charge?").assertExists()

            composeRule.onNode(radio("Tue, Sep 22 · 7:31 AM", "Snooze 1")).performClick()
            composeRule.onNode(hasText("Email support") and hasClickAction()).performClick()
            composeRule
                .onNode(hasClickAction() and SemanticsMatcher.expectValue(SemanticsProperties.ContentDescription, listOf("Back")))
                .performClick()

            assertEquals(listOf(PaymentsIntent.ChargePicked("2"), PaymentsIntent.SendEmailClicked, PaymentsIntent.Back), intents)
        }

    private fun noteBounds() = composeRule.onNodeWithTag(NO_EMAIL_APP_NOTE_TAG, useUnmergedTree = true).assertExists().getBoundsInRoot()

    /** The no-email-app note reads its line and the plain address as one polite announcement. */
    private fun assertNoteAnnounced() {
        composeRule
            .onNode(
                SemanticsMatcher("note texts") { node ->
                    node.config.getOrElseNullable(SemanticsProperties.Text) { null }?.map { it.text } ==
                        listOf("No email app found. Write to:", "support@example.com")
                },
            ).assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
    }

    @Test
    @Config(qualifiers = "w411dp-h780dp-mdpi", fontScale = 2.0f)
    fun `at 200 percent on a short screen the picker's note sits between the list and Email support, overlapping neither`() =
        show(ProblemWithChargeSamples.pickerNoEmailApp) {
            val root = composeRule.onRoot().getBoundsInRoot()
            val button = composeRule.onNode(hasText("Email support") and hasClickAction()).getBoundsInRoot()
            val note = noteBounds()
            assertTrue(button.bottom <= root.bottom && button.top >= root.top, "Email support at $button in $root")
            assertTrue(button.top >= note.bottom, "Email support at $button under the note at $note")
            val rows =
                composeRule
                    .onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton))
                    .fetchSemanticsNodes()
                    .map { it.boundsInRoot }
                    .filter { it.height > 0f }
            // Rows are compared in px, like the semantics nodes report them (clipped to the scrolling list).
            val notePx = composeRule.onNodeWithTag(NO_EMAIL_APP_NOTE_TAG, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            assertTrue(rows.isNotEmpty(), "some rows show")
            rows.forEach { row -> assertTrue(row.bottom <= notePx.top, "row at $row overlaps the note at $notePx") }
            assertTallEnough(hasText("Email support") and hasClickAction())
            assertTallEnough(hasText("Copy address") and hasClickAction())
            assertNoteAnnounced()

            composeRule.onNode(hasText("Copy address") and hasClickAction()).performClick()
            assertEquals(listOf<PaymentsIntent>(PaymentsIntent.CopyAddressClicked), intents)
        }

    @Test
    @Config(fontScale = 2.0f)
    fun `at 200 percent the main screen's note follows Email support without overlapping it, with a 48 dp Copy address`() =
        show(ProblemWithChargeSamples.noChargesNoEmailApp) {
            val emailSupport = composeRule.onNode(hasText("Email support") and hasClickAction()).getBoundsInRoot()
            composeRule.onNode(hasText("Copy address") and hasClickAction()).performScrollTo()
            val row = composeRule.onNode(hasText("Email support") and hasClickAction()).getBoundsInRoot()
            val note = noteBounds()
            assertTrue(note.top >= row.bottom, "note at $note overlaps Email support at $row (before scrolling: $emailSupport)")
            assertTallEnough(hasText("Copy address") and hasClickAction())
            assertNoteAnnounced()
        }
}
