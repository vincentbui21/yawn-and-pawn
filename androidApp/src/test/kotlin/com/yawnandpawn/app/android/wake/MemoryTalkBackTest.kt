package com.yawnandpawn.app.android.wake

import android.content.Intent
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import com.yawnandpawn.app.StopAppRule
import com.yawnandpawn.app.core.alarm.AlarmFired
import com.yawnandpawn.app.core.alarm.AlarmRepository
import com.yawnandpawn.app.core.alarm.CheckConfigRepository
import com.yawnandpawn.app.core.checks.CheckEntry
import com.yawnandpawn.app.core.checks.CheckType
import com.yawnandpawn.app.core.checks.Difficulty
import com.yawnandpawn.app.core.error.Outcome
import com.yawnandpawn.app.core.session.SessionState
import com.yawnandpawn.app.testing.FakeAccessibilityState
import com.yawnandpawn.app.testing.anAlarm
import com.yawnandpawn.app.testing.checkConfigsOf
import com.yawnandpawn.app.ui.TalkBackRules
import com.yawnandpawn.app.ui.TalkBackRules.readingOrder
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * Story 3.12: the numbered Memory Sequence is chosen by itself when TalkBack is on at the fire (`FakeAccessibilityState`
 * through the real wake service): on Hard it stays 3x3 and announces each round's sequence as numbers, politely, before
 * the user's turn. With TalkBack off the same alarm rings the plain 4x4 grid with no announcement. Found by label only.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class MemoryTalkBackTest {
    @get:Rule(order = 0)
    val stopApp = StopAppRule()

    @get:Rule(order = 1)
    val composeRule = createEmptyComposeRule()

    private val alarm = anAlarm(id = "alarm-memory", requestCode = 1300)

    /**
     * Rings a Memory Sequence · Hard alarm with TalkBack [on], taps "I'm up" and runs [block] with the frozen entry's type
     * while the sequence plays.
     */
    private fun ringMemory(
        on: Boolean,
        block: (CheckType) -> Unit,
    ) {
        val app = WakeApp(accessibility = FakeAccessibilityState(screenReaderOn = on))
        assertEquals(Outcome.Success(Unit), runBlocking { app.koin.get<AlarmRepository>().upsert(alarm) })
        val memory = CheckEntry(CheckType.MemorySequence(), Difficulty.Hard, count = 2)
        val checks = app.koin.get<CheckConfigRepository>()
        assertEquals(Outcome.Success(Unit), runBlocking { checks.saveWithAlarm(alarm, checkConfigsOf(alarm.id, listOf(memory))) })
        app.ring(AlarmFired(alarm.id, Instant.parse("2027-03-08T06:00:00Z")))
        app.awaitRinging()
        ActivityScenario.launch<WakeActivity>(Intent(app.app, WakeActivity::class.java)).use {
            composeRule.onNode(hasText("I'm up") and hasClickAction()).performClick()
            composeRule.mainClock.autoAdvance = false // The sequence stays on its first step while the test reads it.
            // The engine moves on its own; the screen's clock moves only frame by frame, so the sequence does not play on.
            app.awaitUntil("Grace") { app.engine.state.value is SessionState.Grace }
            repeat(FRAMES_TO_SHOW) { composeRule.mainClock.advanceTimeByFrame() }
            composeRule.onNode(hasText("Watch the sequence")).assertExists()
            val type =
                (app.engine.state.value as SessionState.Grace)
                    .session.config.checkPlan.entries
                    .single()
                    .type
            block(type)
            composeRule.mainClock.autoAdvance = true
        }
    }

    private fun announcements() = composeRule.readingOrder().filter { SEQUENCE.matches(TalkBackRules.label(it)) }

    @Test
    fun `with TalkBack on the Memory alarm rings numbered, 3x3, with the sequence announced politely`() =
        ringMemory(on = true) { type ->
            assertEquals(CheckType.MemorySequence(numbered = true), type)
            composeRule.onNode(hasContentDescription("Tile 9")).assertExists()
            composeRule.onNode(hasContentDescription("Tile 10")).assertDoesNotExist()
            val announced = announcements().single()
            assertEquals(LiveRegionMode.Polite, announced.config.getOrNull(SemanticsProperties.LiveRegion))
        }

    @Test
    fun `with TalkBack off the same alarm rings the plain 4x4 grid with no announcement`() =
        ringMemory(on = false) { type ->
            assertEquals(CheckType.MemorySequence(numbered = false), type)
            composeRule.onNode(hasContentDescription("Tile 16")).assertExists()
            assertTrue(announcements().isEmpty(), "no sequence announced")
        }

    private companion object {
        /** A few frames (well under the 350 ms first step) to draw the Check screen. */
        const val FRAMES_TO_SHOW = 3

        /** "3, 7, 1, 9": the round's tiles as numbers. */
        val SEQUENCE = Regex("""^\d+(, \d+)+$""")
    }
}
