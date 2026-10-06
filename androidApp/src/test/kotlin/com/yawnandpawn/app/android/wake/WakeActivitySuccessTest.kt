package com.yawnandpawn.app.android.wake

import android.app.Activity
import android.app.NotificationManager
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.yawnandpawn.app.StopAppRule
import com.yawnandpawn.app.android.ApplicationScope
import com.yawnandpawn.app.core.history.SessionOutcome
import com.yawnandpawn.app.core.session.SessionEvent
import com.yawnandpawn.app.core.session.SessionState
import com.yawnandpawn.app.testing.FakeActiveSessionStore
import com.yawnandpawn.app.testing.FakeSessionHistoryRepository
import com.yawnandpawn.app.testing.aSession
import com.yawnandpawn.app.testing.aSessionConfig
import kotlinx.coroutines.launch
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
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
        app.dispatch(SessionEvent.AlarmFired(sessionId, aSessionConfig(testMode = testMode), listOf(1L), beforeFirstUnlock = false))
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
        val controller = Robolectric.buildActivity(WakeActivity::class.java).setup()
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

        Robolectric.buildActivity(WakeActivity::class.java).setup()

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
        composeRule.waitUntil(timeoutMillis = 5_000) { screen.isFinishing }
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
        val controller = Robolectric.buildActivity(WakeActivity::class.java).setup()
        composeRule.onNodeWithText("I'm up").assertExists()

        controller.pause().stop()

        assertFalse(controller.get().isFinishing, "the ring goes on; the notification brings the screen back")
    }

    @Test
    fun `a new alarm while Success shows switches the screen to the new ring`() {
        val app = WakeApp()
        val screen = completed(app).get()

        ringing(app, sessionId = "session-2")

        composeRule.waitUntil(timeoutMillis = 5_000) { !composeRule.successShown(upOnTime) }
        composeRule.onNodeWithText("I'm up").assertExists()
        assertFalse(screen.isFinishing, "the same single wake screen")
        assertEquals("session-2", (app.engine.state.value as SessionState.Active).session.sessionId)
    }

    @Test
    fun `a session that ends Missed closes the screen without Success`() {
        val app = WakeApp()
        ringing(app)
        val screen = Robolectric.buildActivity(WakeActivity::class.java).setup().get()
        composeRule.waitForIdle()

        ShadowSystemClock.advanceBy(Duration.ofMinutes(31))
        val tick = app.koin.get<ApplicationScope>().launch { app.engine.tick() }
        app.awaitUntil("the tick runs") { tick.isCompleted }

        composeRule.waitUntil(timeoutMillis = 10_000) { screen.isFinishing }
        assertIs<SessionState.Missed>(app.engine.ended.value)
        assertFalse(composeRule.successShown(upOnTime), "no Success for a missed alarm")
    }

    @Test
    fun `the success haptic plays once, and not again when the screen is recreated`() {
        val controller = completed(WakeApp())
        composeRule.waitForIdle()
        assertTrue(HapticFeedbackConstants.CONFIRM in haptics(controller.get()), "the success haptic")

        controller.recreate()
        composeRule.waitForIdle()

        composeRule.onNodeWithText(upOnTime).assertExists()
        assertNull(haptics(controller.get()).firstOrNull { it == HapticFeedbackConstants.CONFIRM }, "not played again")
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
    }
}
