package com.yawnandpawn.app.android.wake

import android.app.NotificationManager
import android.content.Intent
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import com.yawnandpawn.app.StopAppRule
import com.yawnandpawn.app.android.ApplicationScope
import com.yawnandpawn.app.core.alarm.AlarmFired
import com.yawnandpawn.app.core.alarm.AlarmRepository
import com.yawnandpawn.app.core.checks.CheckAnswer
import com.yawnandpawn.app.core.checks.CheckPlan
import com.yawnandpawn.app.core.error.DomainError
import com.yawnandpawn.app.core.error.Outcome
import com.yawnandpawn.app.core.session.CheckRun
import com.yawnandpawn.app.core.session.SessionEvent
import com.yawnandpawn.app.core.session.SessionState
import com.yawnandpawn.app.core.session.StepPointer
import com.yawnandpawn.app.testing.FakeSessionHistoryRepository
import com.yawnandpawn.app.testing.aSessionConfig
import com.yawnandpawn.app.testing.anAlarm
import com.yawnandpawn.app.testing.rightAnswer
import com.yawnandpawn.app.testing.wrongAnswer
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowSystemClock
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.time.Instant

/**
 * Story 3.2: the wake screen shows the Math check after "I'm up" and sends the typed answer; the engine decides. Also the
 * deferred Epic 1 item: after Home, the notification (or "Back to alarm") opens the current problem again.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class MathCheckScreenTest {
    @get:Rule(order = 0)
    val stopApp = StopAppRule()

    @get:Rule(order = 1)
    val composeRule = createEmptyComposeRule()

    private val mathConfig = aSessionConfig().copy(checkPlan = CheckPlan.default())

    private fun ringMath(app: WakeApp) {
        app.dispatch(SessionEvent.AlarmFired("session-1", mathConfig, beforeFirstUnlock = false))
        assertIs<SessionState.Ringing>(app.engine.state.value)
    }

    private fun launch(app: WakeApp) = ActivityScenario.launch<WakeActivity>(Intent(app.app, WakeActivity::class.java))

    private fun run(app: WakeApp): CheckRun = assertIs<SessionState.Active>(app.engine.state.value).session.checkRun

    private fun key(label: String) = composeRule.onNode(hasText(label) and hasClickAction())

    /** Taps [answer]'s digits and "Check" on the number pad, as a user does. */
    private fun type(answer: CheckAnswer) {
        (answer as CheckAnswer.Number).digits.forEach { key(it.toString()).performClick() }
    }

    private fun answerField(value: String) = composeRule.onNode(hasContentDescription("Answer $value"))

    private fun waitFor(
        app: WakeApp,
        what: String,
        condition: () -> Boolean,
    ) = composeRule.awaitScreen(app, what, condition)

    @Test
    fun `I'm up shows problem 1 of 3 with the countdown, and solving all three on the pad ends the session`() {
        val app = WakeApp()
        ringMath(app)
        launch(app).use { scenario ->
            composeRule.onNodeWithText("I'm up").performClick()
            waitFor(app, "Grace") { app.engine.state.value is SessionState.Grace }

            composeRule.onNodeWithText("Problem 1 of 3").assertExists()
            composeRule.onNodeWithText("Quiet for 20s. Finish before it rings again.").assertExists()
            repeat(3) { item ->
                composeRule.onNodeWithText("Problem ${item + 1} of 3").assertExists()
                val answer = assertNotNull(rightAnswer(run(app)))
                type(answer)
                answerField((answer as CheckAnswer.Number).digits).assertExists()
                key("Check").performClick()
                waitFor(app, "problem ${item + 1} answered") {
                    app.engine.state.value !is SessionState.Ring || run(app).step != StepPointer(0, item)
                }
            }

            // Story 3.3: the Success screen, and "Done" closes the wake screen.
            composeRule.awaitSuccess(app, "Up on time.")
            composeRule.onNodeWithText("Done").performClick()
            composeRule.waitForIdle()
            scenario.onActivity { assertEquals(true, it.isFinishing, "the wake screen closes") }
        }
    }

    @Test
    fun `a wrong answer clears the field, says Not quite and counts a failed attempt, and an empty Check sends nothing`() {
        val app = WakeApp()
        ringMath(app)
        launch(app).use {
            composeRule.onNodeWithText("I'm up").performClick()
            waitFor(app, "Grace") { app.engine.state.value is SessionState.Grace }

            val before = assertIs<SessionState.Active>(app.engine.state.value).session.interactionDeadline
            ShadowSystemClock.advanceBy(java.time.Duration.ofSeconds(2))
            key("Check").performClick()
            waitFor(app, "the empty Check reached the engine") {
                assertIs<SessionState.Active>(app.engine.state.value).session.interactionDeadline != before
            }
            assertEquals(0, run(app).failedAttempts, "an empty field submits nothing")

            val wrong = assertNotNull(wrongAnswer(run(app)))
            type(wrong)
            key("Check").performClick()
            waitFor(app, "the wrong answer counted") { run(app).failedAttempts == 1 }

            composeRule.onNodeWithText("Not quite. Try again.").assertExists()
            answerField("").assertExists()
            assertEquals(StepPointer(0, 0), run(app).step, "the same problem")
            key("1").performClick()
            composeRule.onNodeWithText("Not quite. Try again.").assertDoesNotExist()
            answerField("1").assertExists()
            composeRule.onNode(hasContentDescription("Delete digit")).performClick()
            answerField("").assertExists()
        }
    }

    @Test
    fun `the answer takes at most 5 digits and every key resets the interaction deadline`() {
        val app = WakeApp()
        ringMath(app)
        launch(app).use {
            composeRule.onNodeWithText("I'm up").performClick()
            waitFor(app, "Grace") { app.engine.state.value is SessionState.Grace }
            val before = assertIs<SessionState.Grace>(app.engine.state.value).session.interactionDeadline
            ShadowSystemClock.advanceBy(java.time.Duration.ofMinutes(1))

            repeat(6) { key("7").performClick() }
            answerField("77777").assertExists()
            waitFor(app, "a key tap reached the engine") {
                assertIs<SessionState.Active>(app.engine.state.value).session.interactionDeadline != before
            }
        }
    }

    @Test
    fun `after Home the notification opens the current problem again, with the typed digits gone`() {
        val app = WakeApp()
        val alarm = anAlarm(id = "alarm-a", requestCode = 1000)
        assertEquals(Outcome.Success(Unit), runBlocking { app.koin.get<AlarmRepository>().upsert(alarm) })
        app.ring(AlarmFired(alarm.id, Instant.parse("2027-03-08T06:00:00Z")))
        app.awaitRinging()
        launch(app).use {
            composeRule.onNodeWithText("I'm up").performClick()
            waitFor(app, "Grace") { app.engine.state.value is SessionState.Grace }
            type(assertNotNull(rightAnswer(run(app))))
            key("Check").performClick()
            waitFor(app, "problem 1 solved") { run(app).step == StepPointer(0, 1) }
            key("4").performClick()
            answerField("4").assertExists()
        }
        // Home: the screen is gone. The ongoing notification opens it again on the current problem.
        val notification =
            assertNotNull(
                app.app
                    .getSystemService(NotificationManager::class.java)
                    .activeNotifications
                    .firstOrNull { it.id == WakeNotifier.NOTIFICATION_ID }
                    ?.notification,
            )
        val opened = shadowOf(notification.contentIntent).savedIntent
        ActivityScenario.launch<WakeActivity>(opened).use {
            composeRule.onNodeWithText("Problem 2 of 3").assertExists()
            answerField("").assertExists()
            composeRule.onNodeWithText("I'm up").assertDoesNotExist()
        }
    }

    /** The seconds of the "Quiet for {n}s. …" grace header on screen, or null when it is not shown. */
    private fun quietSeconds(): Int? =
        composeRule
            .onAllNodes(hasText("Quiet for", substring = true))
            .fetchSemanticsNodes()
            .firstNotNullOfOrNull { node ->
                node.config
                    .getOrNull(SemanticsProperties.Text)
                    ?.joinToString { it.text }
                    ?.let { QUIET.find(it) }
                    ?.groupValues
                    ?.get(1)
                    ?.toInt()
            }

    @Test
    fun `the grace countdown counts down live on the screen`() {
        val app = WakeApp()
        ringMath(app)
        launch(app).use {
            composeRule.onNodeWithText("I'm up").performClick()
            waitFor(app, "Grace") { app.engine.state.value is SessionState.Grace }
            assertEquals(20, quietSeconds())

            ShadowSystemClock.advanceBy(java.time.Duration.ofSeconds(6))
            composeRule.mainClock.advanceTimeBy(WakeCheck.GRACE_TICK.inWholeMilliseconds * 2)
            waitFor(app, "the countdown moved") { quietSeconds()?.let { it in 13..14 } == true }
            assertIs<SessionState.Grace>(app.engine.state.value)
        }
    }

    @Test
    fun `a check that ends Missed keeps its Check screen until the wake screen closes, never the Ringing screen (Story 3_2 review)`() {
        // The history row cannot be written, so the session stays Missed. No service runs to end it, so the ringing
        // notification stays posted: the moment between Missed and the service's endSession, held still.
        val history = FakeSessionHistoryRepository().apply { upsertFailure = DomainError.StorageFailure("disk full") }
        val app = WakeApp(history = history)
        ringMath(app)
        app.koin.get<WakeNotifier>().show(mathConfig.scheduledAt)
        launch(app).use {
            composeRule.onNodeWithText("I'm up").performClick()
            waitFor(app, "Grace") { app.engine.state.value is SessionState.Grace }
            composeRule.onNodeWithText("Problem 1 of 3").assertExists()

            ShadowSystemClock.advanceBy(java.time.Duration.ofMinutes(31))
            val tick = app.koin.get<ApplicationScope>().launch { app.engine.tick() }
            app.awaitUntil("the tick runs") { tick.isCompleted }
            waitFor(app, "Missed") { app.engine.state.value is SessionState.Missed }
            assertNotNull(app.runtime.shownAlarmAt(), "the ringing notification is still posted")

            composeRule.onNodeWithText("Problem 1 of 3").assertExists()
            composeRule.onNodeWithText("I'm up").assertDoesNotExist()
        }
    }

    @Test
    fun `a session stored with the placeholder check still ends with I'm up alone`() {
        val app = WakeApp()
        app.dispatch(SessionEvent.AlarmFired("session-1", aSessionConfig(), beforeFirstUnlock = false))
        launch(app).use {
            composeRule.onNodeWithText("I'm up").performClick()
            waitFor(app, "the placeholder answered") { app.engine.state.value == SessionState.Idle }
        }
    }

    private companion object {
        val QUIET = Regex("""Quiet for (\d+)s""")
    }
}
