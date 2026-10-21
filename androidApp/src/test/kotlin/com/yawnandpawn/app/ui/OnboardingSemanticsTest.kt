package com.yawnandpawn.app.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.unit.dp
import com.yawnandpawn.app.StopAppRule
import com.yawnandpawn.app.ui.TalkBackRules.assertControlsLabelledWithRoles
import com.yawnandpawn.app.ui.TalkBackRules.talkBackOrder
import com.yawnandpawn.app.ui.onboarding.OnboardingScreen
import com.yawnandpawn.app.ui.onboarding.OnboardingUiState
import com.yawnandpawn.app.ui.theme.PpsThemeMode
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertEquals

/**
 * Story 5.10 with TalkBack in mind, at 200 % font on a small phone: each of steps 1 to 3 reads its headline first, as a
 * heading; the dots read "Step N of 8"; every control is labelled, has a role and is at least 48 dp; step 1 has no
 * in-app Back; the stepper ends follow the tier.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w360dp-h640dp-xxhdpi", fontScale = 2.0f)
class OnboardingSemanticsTest {
    @get:Rule(order = 0)
    val appTeardown = StopAppRule()

    @get:Rule(order = 1)
    val composeRule = createEmptyComposeRule()

    private fun show(
        state: () -> OnboardingUiState,
        block: () -> Unit,
    ) = withScreen(
        PpsThemeMode.Light,
        content = { OnboardingScreen(state = state(), is24Hour = false, onIntent = {}) },
        block = {
            composeRule.waitForIdle()
            block()
        },
    )

    /** TalkBack starts on [headline], which is a heading, and the dots read "Step [step] of 8". */
    private fun assertStartsOnHeadline(
        headline: String,
        step: Int,
    ) {
        assertEquals(headline, composeRule.talkBackOrder().first(), "TalkBack starts on the step's headline")
        composeRule
            .onNode(hasText(headline) and SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
            .assertExists()
        composeRule.onNode(hasContentDescription("Step $step of 8")).assertExists()
    }

    @Test
    fun `step 1 reads the app name first, as a heading, and has no in-app Back`() =
        show({ OnboardingSamples.mission }) {
            assertStartsOnHeadline("Yawn & Pawn", step = 1)
            composeRule.onNode(hasContentDescription("Back")).assertDoesNotExist()
            composeRule.assertControlsLabelledWithRoles()
        }

    @Test
    fun `step 2 reads its headline first, and Back and I understand are 48 dp`() =
        show({ OnboardingSamples.disclosure }) {
            assertStartsOnHeadline("How your alarm works", step = 2)
            composeRule.onNodeWithContentDescription("Back").assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp)
            composeRule.onNode(hasText("I understand") and hasClickAction()).assertHeightIsAtLeast(48.dp)
            composeRule.assertControlsLabelledWithRoles()
        }

    @Test
    fun `step 3 reads its headline first, and the stepper is named, 48 dp and bounded by the tier`() =
        show({ OnboardingSamples.baseFeeApproximate }) {
            assertStartsOnHeadline("Choose your snooze fee", step = 3)
            val lower = composeRule.onNodeWithContentDescription("Lower Base fee")
            val raise = composeRule.onNodeWithContentDescription("Raise Base fee")
            listOf(lower, raise).forEach { it.assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp) }
            lower.assertIsNotEnabled()
            raise.assertIsEnabled()
            composeRule.assertControlsLabelledWithRoles()
        }

    @Test
    fun `a step change carries the new headline as the pane title, so TalkBack moves to the new step`() {
        var state by mutableStateOf<OnboardingUiState?>(null)
        withScreen(PpsThemeMode.Light, content = { state?.let { OnboardingScreen(state = it, is24Hour = false, onIntent = {}) } }) {
            composeRule.runOnIdle { state = OnboardingSamples.mission }
            composeRule.waitForIdle()
            composeRule.onNode(paneTitled("Yawn & Pawn")).assertExists()

            composeRule.runOnIdle { state = OnboardingSamples.disclosure }
            composeRule.waitForIdle()

            composeRule.onNode(paneTitled("How your alarm works")).assertExists()
            composeRule.onNode(paneTitled("Yawn & Pawn")).assertDoesNotExist()
        }
    }

    private fun paneTitled(title: String) = SemanticsMatcher.expectValue(SemanticsProperties.PaneTitle, title)

    @Test
    fun `step 3 with Play's prices enables both stepper ends`() =
        show({ OnboardingSamples.baseFee }) {
            composeRule.onNodeWithContentDescription("Lower Base fee").assertIsEnabled()
            composeRule.onNodeWithContentDescription("Raise Base fee").assertIsEnabled()
        }
}
