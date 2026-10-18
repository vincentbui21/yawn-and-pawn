package com.yawnandpawn.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import com.yawnandpawn.app.StopAppRule
import com.yawnandpawn.app.ui.TalkBackRules.spokenOrder
import com.yawnandpawn.app.ui.payments.PaymentsIntent
import com.yawnandpawn.app.ui.payments.PaymentsScreen
import com.yawnandpawn.app.ui.theme.PpsThemeMode
import com.yawnandpawn.app.ui.you.BuiltYouRows
import com.yawnandpawn.app.ui.you.YouIntent
import com.yawnandpawn.app.ui.you.YouScreen
import com.yawnandpawn.app.ui.you.YouUiState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Story 5.8: what TalkBack reads on the You tab and on "How payments & refunds work", and their targets. The screen
 * title and the card titles are headings; every row is a button at least 48 dp tall that sends its intent; the
 * no-email-app note and the snackbar are announced. Payments reads its six paragraphs (the per-snooze cap), the
 * disclosure verbatim and the purchase authentication tip before its two links.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-mdpi")
class YouSemanticsTest {
    @get:Rule(order = 0)
    val appTeardown = StopAppRule()

    @get:Rule(order = 1)
    val composeRule = createEmptyComposeRule()

    private val youIntents = mutableListOf<YouIntent>()
    private val paymentsIntents = mutableListOf<PaymentsIntent>()

    private fun show(
        content: @Composable () -> Unit,
        block: () -> Unit,
    ) = withScreen(PpsThemeMode.Light, content = content, block = block)

    private fun you(
        state: YouUiState = YouUiState(appVersion = ""),
        block: () -> Unit,
    ) = show({ YouScreen(state = state, onIntent = { youIntents += it }, rows = BuiltYouRows) }, block)

    private fun payments(
        cap: String? = "€59.99",
        block: () -> Unit,
    ) = show({ PaymentsScreen(cap = cap, onIntent = { paymentsIntents += it }) }, block)

    private fun button(label: String) =
        hasText(label) and hasClickAction() and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button)

    /** [matcher]'s node is at least 48 dp tall; [scroll] first brings it into view (not for the fixed top bar). */
    private fun assertTallEnough(
        matcher: SemanticsMatcher,
        scroll: Boolean = true,
    ) {
        val node = composeRule.onNode(matcher)
        val bounds = (if (scroll) node.performScrollTo() else node.assertExists()).getBoundsInRoot()
        assertTrue(bounds.bottom - bounds.top >= 48.dp, "$matcher is ${bounds.bottom - bounds.top} tall")
    }

    @Test
    fun `the title and card titles are headings, the rows read in card order, never Delete all data or About`() =
        you {
            listOf("You", "Money", "Privacy and your data", "Help").forEach { title ->
                composeRule.onNode(hasText(title) and isHeading()).assertExists()
            }
            assertEquals(
                listOf(
                    "You",
                    "Money",
                    "Purchase history",
                    "How payments & refunds work",
                    "Privacy and your data",
                    "Privacy policy",
                    "Help",
                    "Support",
                    "Email support",
                    "Terms",
                ),
                composeRule.spokenOrder(),
            )
        }

    @Test
    fun `every row is a 48 dp button that sends its intent`() =
        you {
            val rows =
                listOf(
                    "Purchase history" to YouIntent.PurchaseHistoryClicked,
                    "How payments & refunds work" to YouIntent.PaymentsClicked,
                    "Privacy policy" to YouIntent.PrivacyClicked,
                    "Support" to YouIntent.SupportClicked,
                    "Email support" to YouIntent.EmailSupportClicked,
                    "Terms" to YouIntent.TermsClicked,
                )
            rows.forEach { (label, _) ->
                assertTallEnough(button(label))
                composeRule.onNode(button(label)).performScrollTo().performClick()
            }

            assertEquals(rows.map { it.second }, youIntents)
        }

    @Test
    fun `the no-email-app note reads its line and the address as one polite announcement, with a 48 dp Copy address`() =
        you(YouUiState(appVersion = "", noEmailAppAddress = "support@example.com")) {
            composeRule
                .onNode(
                    SemanticsMatcher("note texts") { node ->
                        node.config.getOrElseNullable(SemanticsProperties.Text) { null }?.map { it.text } ==
                            listOf("No email app found. Write to:", "support@example.com")
                    },
                ).assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
            assertTallEnough(hasText("Copy address") and hasClickAction())
            composeRule.onNode(hasText("Copy address") and hasClickAction()).performScrollTo().performClick()

            assertEquals(listOf<YouIntent>(YouIntent.CopyAddressClicked), youIntents)
        }

    @Test
    fun `No browser found is announced`() =
        you(YouUiState(appVersion = "", noBrowser = true)) {
            composeRule
                .onNodeWithText("No browser found.")
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
        }

    @Test
    fun `Address copied is announced`() =
        you(YouUiState(appVersion = "", addressCopied = true)) {
            composeRule
                .onNodeWithText("Address copied.")
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
        }

    @Test
    fun `Payments reads its title, the six paragraphs, the disclosure and the tip, then its two 48 dp links`() =
        payments {
            composeRule.onNode(hasText("How payments & refunds work") and isHeading()).assertExists()
            assertEquals(
                listOf(
                    "Back",
                    "How payments & refunds work",
                    "Each snooze costs your base fee times the snooze number. Google Play shows the price in your local currency.",
                    // Google Play's cached local price of the top tier, as is (review fix 2).
                    "You can snooze up to your max snoozes per session. A single snooze never costs more than €59.99.",
                    "Your fee is locked for the morning. You can raise it anytime. Lowering it waits until after your next alarm.",
                    "A pending payment only starts your snooze if it goes through before you finish. " +
                        "Otherwise, finish the check to stop the alarm.",
                    "Payments that weren't used are refunded automatically by Google.",
                    "Google lets you request a refund yourself within 48 hours of a charge.",
                    "Your alarm keeps ringing until you finish your check or pay to snooze.",
                    "Your phone stays usable: calls, other apps and emergency calls all work.",
                    "Other ways to stop it: force-stop or uninstall the app, turn off the phone, or leave it 30 minutes.",
                    "Turn on purchase authentication in Google Play so every snooze needs your fingerprint or password.",
                    "Problem with a charge?",
                    "Purchase history",
                ),
                composeRule.spokenOrder(),
            )
            assertTallEnough(button("Problem with a charge?"))
            assertTallEnough(button("Purchase history"))
            assertTallEnough(hasContentDescription("Back") and hasClickAction(), scroll = false)

            composeRule.onNode(button("Problem with a charge?")).performScrollTo().performClick()
            composeRule.onNode(button("Purchase history")).performScrollTo().performClick()
            composeRule.onNode(hasContentDescription("Back") and hasClickAction()).performClick()

            assertEquals(
                listOf(PaymentsIntent.ProblemWithChargeClicked, PaymentsIntent.PurchaseHistoryClicked, PaymentsIntent.Back),
                paymentsIntents,
            )
        }

    @Test
    fun `with no known Play price Payments names the 50 dollar price tier and says Play sets the local price`() =
        payments(cap = null) {
            val spoken = composeRule.spokenOrder()
            val tier =
                "You can snooze up to your max snoozes per session. A single snooze never costs more than the $50.00 price tier."
            val fee = spoken.indexOfFirst { it.startsWith("Each snooze costs your base fee times the snooze number.") }

            assertEquals(listOf(tier, "Google Play sets the local price, including any tax."), spoken.subList(fee + 1, fee + 3))
            assertTrue(spoken.none { it.endsWith("never costs more than $50.00.") }, spoken.toString())
        }
}
