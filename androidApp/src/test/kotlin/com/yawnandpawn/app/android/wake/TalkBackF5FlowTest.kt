package com.yawnandpawn.app.android.wake

import android.content.Intent
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.performClick
import com.yawnandpawn.app.StopAppRule
import com.yawnandpawn.app.android.qr.FakeCodeScanner
import com.yawnandpawn.app.core.alarm.AlarmFired
import com.yawnandpawn.app.core.alarm.AlarmRepository
import com.yawnandpawn.app.core.alarm.CheckConfigRepository
import com.yawnandpawn.app.core.checks.CheckEntry
import com.yawnandpawn.app.core.checks.CheckType
import com.yawnandpawn.app.core.checks.Difficulty
import com.yawnandpawn.app.core.checks.qr.CodeFormat
import com.yawnandpawn.app.core.checks.qr.RegisteredCode
import com.yawnandpawn.app.core.error.Outcome
import com.yawnandpawn.app.core.session.SessionState
import com.yawnandpawn.app.launchActivity
import com.yawnandpawn.app.testing.FakeAccessibilityState
import com.yawnandpawn.app.testing.anAlarm
import com.yawnandpawn.app.testing.checkConfigsOf
import com.yawnandpawn.app.ui.TalkBackRules
import com.yawnandpawn.app.ui.TalkBackRules.assertControlsLabelledWithRoles
import com.yawnandpawn.app.ui.TalkBackRules.headings
import com.yawnandpawn.app.ui.TalkBackRules.readingOrder
import com.yawnandpawn.app.ui.TalkBackRules.spokenOrder
import com.yawnandpawn.app.ui.TalkBackRules.talkBackOrder
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * Story 3.12, flow F5 on the host (the real Koin graph through [WakeApp], the real wake service and `WakeActivity`): a
 * QR/Barcode alarm fires with TalkBack on and no camera permission. Every step is found by label, role, heading and
 * TalkBack order only, never by test tag, colour or position, and every step is a tap: the clock reads first, then
 * "I'm up"; the camera message and "Can't do this check?" are there at once; the picker lists Math first; each of the 6
 * spoken problems is solved on the number pad, its answer read back; the session ends and the alarm sound is released.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class TalkBackF5FlowTest {
    @get:Rule(order = 0)
    val stopApp = StopAppRule()

    @get:Rule(order = 1)
    val composeRule = createEmptyComposeRule()

    private val alarm = anAlarm(id = "alarm-qr", requestCode = 1200)
    private val code: RegisteredCode = assertNotNull(RegisteredCode.of(CodeFormat.Ean13, "4006381333931"))

    private val scanner = FakeCodeScanner(permitted = false)

    private fun button(label: String) = hasText(label) and hasClickAction()

    private fun tap(matcher: SemanticsMatcher) {
        composeRule.onNode(matcher).performClick()
        composeRule.waitForIdle()
    }

    @Test
    fun `F5 with TalkBack and no camera stops the alarm with taps only, by labels and roles`() {
        val app = WakeApp(scanner = scanner, accessibility = FakeAccessibilityState(screenReaderOn = true))
        assertEquals(Outcome.Success(Unit), runBlocking { app.koin.get<AlarmRepository>().upsert(alarm) })
        val qr = CheckEntry(CheckType.QrBarcode, Difficulty.Medium, count = 1, code = code)
        val checks = app.koin.get<CheckConfigRepository>()
        assertEquals(Outcome.Success(Unit), runBlocking { checks.saveWithAlarm(alarm, checkConfigsOf(alarm.id, listOf(qr))) })
        app.ring(AlarmFired(alarm.id, Instant.parse("2027-03-08T06:00:00Z")))
        app.awaitRinging()

        launchActivity<WakeActivity>(Intent(app.app, WakeActivity::class.java)).use {
            composeRule.waitForIdle()
            // Ringing: the clock first (the full time), then "I'm up".
            val first = composeRule.talkBackOrder().take(2)
            assertTrue(CLOCK.matches(first[0]), "TalkBack starts on the clock: ${composeRule.talkBackOrder()}")
            assertEquals("I'm up", first[1])
            composeRule.assertControlsLabelledWithRoles(wakeActions = listOf(button("I'm up")))

            imUpShowsTheMessageAndLink(app)
            linkToMath()
            // A wrong answer, twice: each time focus goes to "Not quite. Try again." and the next swipe is "1".
            repeat(2) { attempt -> answerWrong(app, attempts = attempt + 1) }
            solveSixProblems(app)

            composeRule.awaitScreen(app, "the session ends") { app.engine.state.value !is SessionState.Ring }
            app.awaitUntil("the alarm sound is released") { !app.player.isRinging && app.player.sound == null }
            assertFalse(app.player.isRinging)
            assertNull(app.player.sound)
        }
    }

    /**
     * "I'm up", then the QR check without the camera: its heading, the polite message and a focusable "Can't do this
     * check?", known from the missing permission alone (no camera bound, no camera event awaited). The one-frame timing
     * is asserted with a paused clock in `FallbackTalkBackDeviceTest` and `QrCameraFailureTest`; here a paused clock left
     * frame state behind that broke later screenshot tests of the same JVM (Story 3.12 review, bisected), so it is not used.
     */
    private fun imUpShowsTheMessageAndLink(app: WakeApp) {
        composeRule.onNode(button("I'm up")).performClick()
        composeRule.awaitScreen(app, "Grace") { app.engine.state.value is SessionState.Grace }
        assertEquals(listOf("Scan your code"), composeRule.headings())
        composeRule
            .onNode(hasText("Camera isn't available. Pick a fallback check."))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
        composeRule.onNode(button(LINK)).assert(SemanticsMatcher.keyIsDefined(SemanticsActions.RequestFocus))
        // No camera was ever bound: the screen knew from the missing permission alone, with no camera event to wait for.
        assertEquals(0, scanner.starts, "no camera bound")
        composeRule.assertControlsLabelledWithRoles()
    }

    /** The link opens the picker: its title, the close button, then Math first, which is tapped. */
    private fun linkToMath() {
        tap(button(LINK))
        assertEquals(listOf("Pick a fallback check"), composeRule.headings())
        val cards = composeRule.readingOrder().filter { node -> SemanticsActions.OnClick in node.config }.map(TalkBackRules::label)
        assertEquals("Back to check", cards.first())
        assertTrue(cards[1].startsWith("Math"), "Math is the first card: $cards")
        composeRule.assertControlsLabelledWithRoles()
        tap(button("Math"))
    }

    /** Types the problem's answer plus one and sends it: focus goes to the feedback, and the next swipe is "1". */
    private fun answerWrong(
        app: WakeApp,
        attempts: Int,
    ) {
        awaitProblem(app, number = 1)
        (solve(composeRule.headings().single()) + 1).toString().forEach { digit -> tap(button(digit.toString())) }
        tap(button("Check"))
        composeRule.awaitScreen(app, "wrong answer $attempts counted") {
            composeRule.onAllNodes(hasText(WRONG)).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNode(hasText(WRONG)).assertIsFocused()
        val spoken = composeRule.spokenOrder()
        assertEquals("1", spoken[spoken.indexOf(WRONG) + 1], "the next swipe after the feedback: $spoken")
    }

    /** Math Hard × 6: each problem a heading TalkBack reads in words, typed by key labels, read back as the answer. */
    private fun solveSixProblems(app: WakeApp) {
        repeat(PROBLEMS) { index ->
            awaitProblem(app, number = index + 1)
            val spoken = composeRule.headings().single()
            assertTrue(SPOKEN_PROBLEM.matches(spoken), "the problem in words: $spoken")
            val answer = solve(spoken).toString()
            answer.forEach { digit -> tap(button(digit.toString())) }
            composeRule
                .onNode(hasContentDescription("Answer $answer"))
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
            tap(button("Check"))
        }
    }

    private fun awaitProblem(
        app: WakeApp,
        number: Int,
    ) = composeRule.awaitScreen(app, "problem $number") {
        composeRule.onAllNodes(hasText("Problem $number of $PROBLEMS")).fetchSemanticsNodes().isNotEmpty()
    }

    /** The value of a spoken problem ("23 times 4 plus 17"), times before plus and minus: what a user works out by ear. */
    private fun solve(spoken: String): Int {
        val words = spoken.split(" ")
        val terms = mutableListOf(words[0].toInt())
        val signs = mutableListOf<String>()
        words.drop(1).chunked(2).forEach { (word, number) ->
            if (word == "times") {
                terms[terms.lastIndex] = terms.last() * number.toInt()
            } else {
                signs += word
                terms += number.toInt()
            }
        }
        return terms.drop(1).zip(signs).fold(terms.first()) { total, (term, sign) -> if (sign == "plus") total + term else total - term }
    }

    private companion object {
        /** The fallback Math: Hard, 6 problems (CameraFallbackPolicy). */
        const val PROBLEMS = 6
        const val LINK = "Can't do this check?"
        const val WRONG = "Not quite. Try again."

        /** "6:15 AM" (any space before AM or PM) or "06:15". */
        val CLOCK = Regex("""^\d{1,2}:\d{2}(.[AP]M)?$""")

        val SPOKEN_PROBLEM = Regex("""^\d+ (plus|minus|times) \d+( (plus|minus|times) \d+)*$""")
    }
}
