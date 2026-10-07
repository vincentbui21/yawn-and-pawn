package com.yawnandpawn.app.android.wake

import android.content.Intent
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
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
import com.yawnandpawn.app.testing.FakeAccessibilityState
import com.yawnandpawn.app.testing.anAlarm
import com.yawnandpawn.app.testing.checkConfigsOf
import com.yawnandpawn.app.ui.TalkBackRules
import com.yawnandpawn.app.ui.TalkBackRules.assertControlsLabelledWithRoles
import com.yawnandpawn.app.ui.TalkBackRules.headings
import com.yawnandpawn.app.ui.TalkBackRules.readingOrder
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

    private fun button(label: String) = hasText(label) and hasClickAction()

    private fun tap(matcher: SemanticsMatcher) {
        composeRule.onNode(matcher).performClick()
        composeRule.waitForIdle()
    }

    @Test
    fun `F5 with TalkBack and no camera stops the alarm with taps only, by labels and roles`() {
        val app = WakeApp(scanner = FakeCodeScanner(permitted = false), accessibility = FakeAccessibilityState(screenReaderOn = true))
        assertEquals(Outcome.Success(Unit), runBlocking { app.koin.get<AlarmRepository>().upsert(alarm) })
        val qr = CheckEntry(CheckType.QrBarcode, Difficulty.Medium, count = 1, code = code)
        val checks = app.koin.get<CheckConfigRepository>()
        assertEquals(Outcome.Success(Unit), runBlocking { checks.saveWithAlarm(alarm, checkConfigsOf(alarm.id, listOf(qr))) })
        app.ring(AlarmFired(alarm.id, Instant.parse("2027-03-08T06:00:00Z")))
        app.awaitRinging()

        ActivityScenario.launch<WakeActivity>(Intent(app.app, WakeActivity::class.java)).use {
            composeRule.waitForIdle()
            // Ringing: the clock first (the full time), then "I'm up".
            val first = composeRule.talkBackOrder().take(2)
            assertTrue(CLOCK.matches(first[0]), "TalkBack starts on the clock: ${composeRule.talkBackOrder()}")
            assertEquals("I'm up", first[1])
            composeRule.assertControlsLabelledWithRoles()

            tap(button("I'm up"))
            app.awaitUntil("Grace") {
                composeRule.waitForIdle()
                app.engine.state.value is SessionState.Grace
            }

            // The QR check without the camera: its heading, the message and the link, focusable at once.
            assertEquals(listOf("Scan your code"), composeRule.headings())
            composeRule.onNode(hasText("Camera isn't available. Pick a fallback check.")).assertExists()
            composeRule
                .onNode(button("Can't do this check?"))
                .assert(SemanticsMatcher.keyIsDefined(SemanticsActions.RequestFocus))
            composeRule.assertControlsLabelledWithRoles()

            // The picker: its title, the close button, then Math first.
            tap(button("Can't do this check?"))
            assertEquals(listOf("Pick a fallback check"), composeRule.headings())
            val cards = composeRule.readingOrder().filter { node -> SemanticsActions.OnClick in node.config }.map(TalkBackRules::label)
            assertEquals("Back to check", cards.first())
            assertTrue(cards[1].startsWith("Math"), "Math is the first card: $cards")
            composeRule.assertControlsLabelledWithRoles()
            tap(button("Math"))

            // Math Hard x 6: each problem a heading TalkBack reads in words, typed by key labels, read back as the answer.
            repeat(PROBLEMS) { index ->
                app.awaitUntil("problem ${index + 1}") {
                    composeRule.waitForIdle()
                    composeRule.onAllNodes(hasText("Problem ${index + 1} of $PROBLEMS")).fetchSemanticsNodes().isNotEmpty()
                }
                val spoken = composeRule.headings().single()
                assertTrue(SPOKEN_PROBLEM.matches(spoken), "the problem in words: $spoken")
                val answer = solve(spoken).toString()
                answer.forEach { digit -> tap(button(digit.toString())) }
                composeRule.onNode(hasContentDescription("Answer $answer")).assert(
                    SemanticsMatcher("a polite live region") { it.config.getOrNull(SemanticsProperties.LiveRegion) != null },
                )
                tap(button("Check"))
            }

            app.awaitUntil("the session ends") { app.engine.state.value !is SessionState.Ring }
            app.awaitUntil("the alarm sound is released") { !app.player.isRinging && app.player.sound == null }
            assertFalse(app.player.isRinging)
            assertNull(app.player.sound)
        }
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

        /** "6:15 AM" (any space before AM or PM) or "06:15". */
        val CLOCK = Regex("""^\d{1,2}:\d{2}(.[AP]M)?$""")

        val SPOKEN_PROBLEM = Regex("""^\d+ (plus|minus|times) \d+( (plus|minus|times) \d+)*$""")
    }
}
