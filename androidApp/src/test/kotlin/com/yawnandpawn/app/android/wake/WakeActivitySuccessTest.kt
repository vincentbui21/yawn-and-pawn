package com.yawnandpawn.app.android.wake

import android.app.Activity
import android.app.NotificationManager
import android.os.Bundle
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import com.yawnandpawn.app.APP_WORK_TIMEOUT_MILLIS
import com.yawnandpawn.app.StopAppRule
import com.yawnandpawn.app.android.ApplicationScope
import com.yawnandpawn.app.awaitChildren
import com.yawnandpawn.app.buildActivity
import com.yawnandpawn.app.core.history.SessionOutcome
import com.yawnandpawn.app.core.session.SessionEvent
import com.yawnandpawn.app.core.session.SessionState
import com.yawnandpawn.app.testing.FakeActiveSessionStore
import com.yawnandpawn.app.testing.FakeClock
import com.yawnandpawn.app.testing.FakeSessionHistoryRepository
import com.yawnandpawn.app.testing.aSession
import com.yawnandpawn.app.testing.aSessionConfig
import com.yawnandpawn.app.testing.aSessionHistoryRow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/** Story 3.3: the wake screen shows the basic Success screen once the session it showed completes. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class WakeActivitySuccessTest {
    @get:Rule(order = 0)
    val stopApp = StopAppRule()

    @get:Rule(order = 1)
    val composeRule = createEmptyComposeRule()

    private val upOnTime = "Up on time."

    private fun ringing(
        app: WakeApp,
        sessionId: String = "session-1",
        testMode: Boolean = false,
    ) {
        app.dispatch(SessionEvent.AlarmFired(sessionId, aSessionConfig(testMode = testMode), beforeFirstUnlock = false))
        assertIs<SessionState.Ringing>(app.engine.state.value)
    }

    /** A ringing session, the wake screen, and "I'm up": the screen answers the placeholder check and the session ends. */
    private fun completed(
        app: WakeApp,
        headline: String = upOnTime,
        testMode: Boolean = false,
    ): ActivityController<WakeActivity> {
        ringing(app, testMode = testMode)
        app.awaitRinging()
        val controller = buildActivity(WakeActivity::class.java).setup()
        composeRule.onNodeWithText("I'm up").performClick()
        composeRule.awaitSuccess(app, headline)
        return controller
    }

    @Test
    fun `a completed session shows Success while the engine records it, goes Idle, clears the store and stops the ring`() {
        val history = FakeSessionHistoryRepository()
        val store = FakeActiveSessionStore()
        val app = WakeApp(store = store, history = history)

        val screen = completed(app).get()

        app.awaitUntil("the runtime stopped the ring") {
            app.player.sound == null && shadowOf(app.app.getSystemService(NotificationManager::class.java)).size() == 0
        }
        assertEquals(SessionState.Idle, app.engine.state.value)
        assertNull(store.row, "runtime.db cleared")
        assertTrue(store.commits.any { it is SessionState.Completed }, "the session completed")
        assertEquals(SessionOutcome.OnTime, history.rows.single().outcome)
        composeRule.onNodeWithText("Done").assertExists()
        assertFalse(screen.isFinishing, "Success stays on screen")
    }

    @Test
    fun `a test session shows Test finished`() {
        val app = WakeApp()

        completed(app, headline = "Test finished. Your alarm works.", testMode = true)

        composeRule.onNodeWithText(upOnTime).assertDoesNotExist()
    }

    @Test
    fun `after a snooze it shows You're up with no paid line before Epic 4`() {
        // A loud ring after one granted snooze (FakeBilling grants are the only way to snooze until Epic 4).
        val loud = SessionState.Loud(aSession(sessionId = "session-snoozed").copy(snoozesGranted = 1, ringIndex = 2))
        val app = WakeApp(store = FakeActiveSessionStore(loud))
        val restore = app.koin.get<ApplicationScope>().launch { app.engine.restore() }
        app.awaitUntil("the session is restored") { restore.isCompleted }

        buildActivity(WakeActivity::class.java).setup()

        composeRule.awaitSuccess(app, "You're up. That's what counts.")
        composeRule.onNodeWithText("paid this morning", substring = true).assertDoesNotExist()
        composeRule.onNodeWithText(upOnTime).assertDoesNotExist()
    }

    @Test
    fun `Done closes the screen and Back does nothing`() {
        val screen = completed(WakeApp()).get()

        screen.onBackPressedDispatcher.onBackPressed()
        composeRule.waitForIdle()
        assertFalse(screen.isFinishing, "Back does nothing")
        composeRule.onNodeWithText(upOnTime).assertExists()

        composeRule.onNodeWithText("Done").performClick()
        composeRule.waitUntil(timeoutMillis = APP_WORK_TIMEOUT_MILLIS) { screen.isFinishing }
    }

    @Test
    fun `Success closes by itself after 60 seconds without a tap`() {
        val screen = completed(WakeApp()).get()
        composeRule.mainClock.autoAdvance = false

        composeRule.mainClock.advanceTimeBy(WakeActivity.SUCCESS_TIMEOUT.inWholeMilliseconds - ONE_SECOND)
        assertFalse(screen.isFinishing, "59 s: still shown")
        composeRule.onNodeWithText(upOnTime).assertExists()

        composeRule.mainClock.advanceTimeBy(ONE_SECOND + FRAME)
        assertTrue(screen.isFinishing, "60 s: closed")
    }

    @Test
    fun `leaving Success with Home closes it, so the next app open shows Home`() {
        val controller = completed(WakeApp())

        controller.pause().stop()

        assertTrue(controller.get().isFinishing)
    }

    @Test
    fun `leaving the ringing screen with Home does not close it`() {
        val app = WakeApp()
        ringing(app)
        val controller = buildActivity(WakeActivity::class.java).setup()
        composeRule.onNodeWithText("I'm up").assertExists()

        controller.pause().stop()

        assertFalse(controller.get().isFinishing, "the ring goes on; the notification brings the screen back")
    }

    @Test
    fun `a new alarm while Success shows switches the screen to the new ring`() {
        val app = WakeApp()
        val screen = completed(app).get()

        ringing(app, sessionId = "session-2")

        composeRule.waitUntil(timeoutMillis = APP_WORK_TIMEOUT_MILLIS) { !composeRule.successShown(upOnTime) }
        composeRule.onNodeWithText("I'm up").assertExists()
        assertFalse(screen.isFinishing, "the same single wake screen")
        assertEquals("session-2", (app.engine.state.value as SessionState.Active).session.sessionId)
    }

    @Test
    fun `a session that ends Missed closes the screen without Success`() {
        val app = WakeApp()
        ringing(app)
        val screen = buildActivity(WakeActivity::class.java).setup().get()
        composeRule.waitForIdle()

        ShadowSystemClock.advanceBy(Duration.ofMinutes(31))
        val tick = app.koin.get<ApplicationScope>().launch { app.engine.tick() }
        app.awaitUntil("the tick runs") { tick.isCompleted }

        composeRule.waitUntil(timeoutMillis = APP_WORK_TIMEOUT_MILLIS) { screen.isFinishing }
        assertIs<SessionState.Missed>(app.engine.ended.value)
        assertFalse(composeRule.successShown(upOnTime), "no Success for a missed alarm")
    }

    @Test
    fun `the success haptic plays once, and not again when the screen is recreated`() {
        val controller = completed(WakeApp())
        composeRule.waitForIdle()
        assertTrue(HapticFeedbackConstants.CONFIRM in haptics(controller.get()), "the success haptic")

        val before = controller.get()

        controller.recreate()
        composeRule.waitForIdle()

        assertFalse(before.isFinishing, "a recreation does not close Success")
        assertFalse(controller.get().isFinishing, "the new screen shows it")
        composeRule.onNodeWithText(upOnTime).assertExists()
        assertNull(haptics(controller.get()).firstOrNull { it == HapticFeedbackConstants.CONFIRM }, "not played again")
    }

    /** Story 6.4: the celebration and its haptic play once per session; a recreated screen shows the final state, still. */
    @Test
    fun `a recreated Success keeps its streak and does not celebrate again`() {
        val scheduled = aSessionConfig().scheduledAt
        val history = FakeSessionHistoryRepository()
        runBlocking {
            (1..2).forEach { day ->
                history.upsert(aSessionHistoryRow(sessionId = "earlier-$day").copy(scheduledAt = scheduled - day.days))
            }
        }
        val app = WakeApp(history = history, clock = FakeClock(scheduled + 5.minutes))
        ringing(app)
        app.awaitRinging()
        val controller = buildActivity(WakeActivity::class.java).setup()
        composeRule.onNodeWithText("I'm up").assertExists()
        app.awaitUntil("the snapshot is read") {
            composeRule.waitForIdle()
            history.allCalls == 1
        }
        app.koin.get<ApplicationScope>().awaitChildren()
        composeRule.onNodeWithText("I'm up").performClick()
        composeRule.awaitSuccess(app, upOnTime)
        composeRule.onNodeWithContentDescription("3").assertExists()
        assertTrue(HapticFeedbackConstants.CONFIRM in haptics(controller.get()), "the success haptic")

        composeRule.mainClock.autoAdvance = false
        controller.recreate()
        composeRule.mainClock.advanceTimeBy(FRAME)
        val start = composeRule.onRoot().captureToImage().toPixelMap()
        composeRule.mainClock.advanceTimeBy(ONE_SECOND)
        val later = composeRule.onRoot().captureToImage().toPixelMap()

        composeRule.onNodeWithContentDescription("3").assertExists()
        composeRule.onNodeWithText("days in a row").assertExists()
        val changed = (0 until start.height).sumOf { y -> (0 until start.width).count { x -> start[x, y] != later[x, y] } }
        assertEquals(0, changed, "no count-up or confetti after the recreation")
        assertNull(haptics(controller.get()).firstOrNull { it == HapticFeedbackConstants.CONFIRM }, "no second haptic")
    }

    /** Story 6.4: Success lets the system timeout apply; a new ring keeps the screen on again. */
    @Test
    fun `Success clears keep-screen-on, and a new ring sets it again`() {
        val app = WakeApp()
        val screen = completed(app).get()
        composeRule.waitForIdle()

        assertEquals(0, screen.window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON, "cleared on Success")

        ringing(app, sessionId = "session-2")
        composeRule.waitUntil(timeoutMillis = APP_WORK_TIMEOUT_MILLIS) { !composeRule.successShown(upOnTime) }
        composeRule.waitForIdle()

        assertTrue(screen.window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON != 0, "set again for the ring")
    }

    @Test
    fun `the 60 seconds count from when Success first showed, across a recreation`() {
        val controller = completed(WakeApp())
        composeRule.mainClock.autoAdvance = false
        composeRule.mainClock.advanceTimeBy(FORTY_SECONDS)
        ShadowSystemClock.advanceBy(Duration.ofMillis(FORTY_SECONDS))

        controller.recreate()
        composeRule.mainClock.advanceTimeBy(WakeActivity.SUCCESS_TIMEOUT.inWholeMilliseconds - FORTY_SECONDS - ONE_SECOND)
        assertFalse(controller.get().isFinishing, "59 s in all: still shown")
        composeRule.onNodeWithText(upOnTime).assertExists()

        composeRule.mainClock.advanceTimeBy(ONE_SECOND + FRAME)
        assertTrue(controller.get().isFinishing, "60 s in all: closed")
    }

    @Test
    fun `an emergency ring after Success shows the ring, and when it stops the screen closes without Success`() {
        val app = WakeApp()
        val screen = completed(app).get()

        app.runtime.startEmergency(Instant.parse("2027-03-03T06:15:00Z"), volumePercent = 80, cause = "commit failed")
        composeRule.waitUntil(timeoutMillis = APP_WORK_TIMEOUT_MILLIS) { !composeRule.successShown(upOnTime) }
        composeRule.onNodeWithText("I'm up").performClick()

        composeRule.waitUntil(timeoutMillis = APP_WORK_TIMEOUT_MILLIS) { screen.isFinishing }
        assertNull(app.runtime.emergency.value)
        assertFalse(composeRule.successShown(upOnTime), "Success does not come back")
    }

    @Test
    fun `restored after a kill while Success showed, the screen closes, since the new process does not know that session`() {
        val controller = completed(WakeApp())
        val bundle = Bundle()
        controller
            .pause()
            .saveInstanceState(bundle)
            .stop()
            .destroy()

        // A new process: a fresh engine with nothing stored and no ended session.
        val app = WakeApp()
        val screen = buildActivity(WakeActivity::class.java).setup(bundle).get()

        composeRule.waitUntil(timeoutMillis = APP_WORK_TIMEOUT_MILLIS) { screen.isFinishing }
        assertNull(app.engine.ended.value)
        assertFalse(composeRule.successShown(upOnTime))
    }

    /** The last haptic each view of [activity] performed (Robolectric records the last one per view). */
    private fun haptics(activity: Activity): List<Int> =
        activity.window.decorView
            .all()
            .map { shadowOf(it).lastHapticFeedbackPerformed() }
            .toList()

    private fun View.all(): Sequence<View> =
        sequence {
            yield(this@all)
            if (this@all is ViewGroup) for (i in 0 until childCount) yieldAll(getChildAt(i).all())
        }

    private companion object {
        const val ONE_SECOND = 1_000L
        const val FRAME = 32L
        const val FORTY_SECONDS = 40_000L
    }
}
