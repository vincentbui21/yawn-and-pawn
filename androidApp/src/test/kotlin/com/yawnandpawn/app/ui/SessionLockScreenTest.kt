package com.yawnandpawn.app.ui

import android.app.Application
import android.content.ComponentName
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.yawnandpawn.app.MainActivity
import com.yawnandpawn.app.StopAppRule
import com.yawnandpawn.app.android.ApplicationScope
import com.yawnandpawn.app.android.screen.AndroidWakeScreenOpener
import com.yawnandpawn.app.android.wake.WakeActivity
import com.yawnandpawn.app.awaitChildren
import com.yawnandpawn.app.core.error.DomainError
import com.yawnandpawn.app.core.history.SessionHistoryRepository
import com.yawnandpawn.app.core.reliability.ReliabilityProbe
import com.yawnandpawn.app.core.session.ActiveSessionStore
import com.yawnandpawn.app.core.session.CheckAnswer
import com.yawnandpawn.app.core.session.SessionEngine
import com.yawnandpawn.app.core.session.SessionEvent
import com.yawnandpawn.app.core.session.SessionJson
import com.yawnandpawn.app.core.session.SessionState
import com.yawnandpawn.app.core.sound.SoundPreview
import com.yawnandpawn.app.core.sound.SoundRef
import com.yawnandpawn.app.core.time.Deadline
import com.yawnandpawn.app.core.time.TimeSnapshot
import com.yawnandpawn.app.restartKoin
import com.yawnandpawn.app.testing.FakeActiveSessionStore
import com.yawnandpawn.app.testing.FakeReliabilityProbe
import com.yawnandpawn.app.testing.FakeSessionHistoryRepository
import com.yawnandpawn.app.testing.FakeSoundPreview
import com.yawnandpawn.app.testing.aSession
import com.yawnandpawn.app.testing.aSessionConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext
import org.koin.dsl.module
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.minutes

/**
 * Story 2.6 (FR-SES-3) in the real app: while a session is active the app shows only "Alarm in progress" with "Back to
 * alarm" (no nav capsule, no other screen), an open editor closes without a dialog and its preview stops, and Home
 * returns once the session is over.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class SessionLockScreenTest {
    @get:Rule(order = 0)
    val stopApp = StopAppRule()

    @get:Rule(order = 1)
    val composeRule = createEmptyComposeRule()

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val preview = FakeSoundPreview()

    private val engine: SessionEngine
        get() = GlobalContext.get().get()

    /** Counts its checks: HomeViewModel checks once when it is created. */
    private val probe = FakeReliabilityProbe()
    private val history = FakeSessionHistoryRepository()

    /**
     * Starts the app with the session [stored] at "now" on its time ports (none: Idle) and restores it unless [restore]
     * is false (the restore that Story 2.1 runs from the activities has not finished yet).
     */
    private fun start(
        restore: Boolean = true,
        stored: ((TimeSnapshot) -> SessionState)? = null,
    ) {
        val store = FakeActiveSessionStore()
        restartKoin(
            app,
            module {
                single<ActiveSessionStore> { store }
                single<SoundPreview> { preview }
                single<ReliabilityProbe> { probe }
                single<SessionHistoryRepository> { history }
            },
        )
        val koin = GlobalContext.get()
        stored?.let { store.row = SessionJson.encode(it(TimeSnapshot.of(koin.get(), koin.get(), koin.get()))) }
        if (restore) runBlocking { engine.restore() }
    }

    private fun dispatch(vararg events: SessionEvent) {
        runBlocking { events.forEach { engine.dispatch(it) } }
        GlobalContext.get().get<ApplicationScope>().awaitChildren()
        composeRule.waitForIdle()
    }

    private fun startedActivities() = generateSequence { shadowOf(app).nextStartedActivity }.toList()

    private fun assertLocked() {
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodes(hasText("Alarm in progress")).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNode(hasText("Alarm in progress") and isHeading()).assertExists()
        composeRule.onNodeWithContentDescription("Add alarm").assertDoesNotExist()
        composeRule.onNodeWithText("Save").assertDoesNotExist()
    }

    @Test
    fun `during a snooze the app shows only Alarm in progress, and Back to alarm opens the wake screen`() {
        // A paid snooze that ends in 9 minutes.
        start { now -> SessionState.Snoozed(aSession().copy(snoozeEnd = Deadline.after(now, 9.minutes))) }
        assertEquals(SessionState.Snoozed::class, engine.state.value::class)

        ActivityScenario.launch(MainActivity::class.java).use {
            assertLocked()
            assertEquals(emptyList(), startedActivities(), "a snooze is not forwarded to the wake screen")
            val button = composeRule.onNodeWithText("Back to alarm")
            button.assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
            button.assertHeightIsAtLeast(48.dp)

            button.performClick()

            assertEquals(listOf(ComponentName(app, WakeActivity::class.java)), startedActivities().map { it.component })
        }
    }

    @Test
    fun `an open editor closes without a dialog and its preview stops when a session starts, and Home returns after`() {
        start()
        ActivityScenario.launch(MainActivity::class.java).use {
            composeRule.onNodeWithContentDescription("Add alarm").performClick()
            composeRule.onNodeWithText("Save").assertExists()
            preview.play(SoundRef.BuiltIn("bell"), volumePercent = 50)

            dispatch(SessionEvent.AlarmFired("session-1", aSessionConfig(), listOf(1L), beforeFirstUnlock = false))

            assertLocked()
            assertEquals(1, preview.stops, "the editor's preview stopped with it")
            assertNull(preview.previewing.value)

            dispatch(SessionEvent.ImUpTapped, SessionEvent.CheckAnswerSubmitted(CheckAnswer.Placeholder))
            composeRule.waitUntil(timeoutMillis = 5_000) { engine.state.value == SessionState.Idle }
            composeRule.waitForIdle()

            composeRule.onNodeWithText("Alarm in progress").assertDoesNotExist()
            composeRule.onNodeWithContentDescription("Add alarm").assertExists()
            composeRule.onNodeWithText("Save").assertDoesNotExist()
        }
    }

    @Test
    fun `a cold start into a running session never composes Home, so its ViewModel is never created`() {
        start { SessionState.Ringing(aSession()) }

        ActivityScenario.launch(MainActivity::class.java).use {
            assertLocked()
            composeRule.mainClock.advanceTimeBy(1_000)
            composeRule.waitForIdle()

            assertEquals(0, probe.checks, "HomeViewModel checks the reliability settings when it is created")
            composeRule.onNodeWithContentDescription("Add alarm").assertDoesNotExist()
        }
    }

    @Test
    fun `before the stored session is restored the app shows the lock, not Home, and stays locked on a ring`() {
        start(restore = false) { SessionState.Ringing(aSession()) }
        assertEquals(SessionState.Idle, engine.state.value, "not restored yet")

        ActivityScenario.launch(MainActivity::class.java).use {
            assertLocked()
            assertEquals(0, probe.checks, "Home never composed")

            runBlocking { engine.restore() }
            composeRule.waitForIdle()

            assertLocked()
            assertEquals(0, probe.checks)
        }
    }

    @Test
    fun `before the restore of an empty store the app is locked, then shows Home`() {
        start(restore = false)

        ActivityScenario.launch(MainActivity::class.java).use {
            assertLocked()

            runBlocking { engine.restore() }
            composeRule.waitUntil(timeoutMillis = 5_000) {
                composeRule.onAllNodes(hasContentDescription("Add alarm")).fetchSemanticsNodes().isNotEmpty()
            }

            composeRule.onNodeWithText("Alarm in progress").assertDoesNotExist()
        }
    }

    @Test
    fun `a session stuck in Completed because its history row cannot be written does not lock the app`() {
        history.upsertFailure = DomainError.StorageFailure("disk full")
        start { SessionState.Completed(aSession().copy(interactionDeadline = null)) }
        assertEquals(SessionState.Completed::class, engine.state.value::class, "the history write keeps failing")

        ActivityScenario.launch(MainActivity::class.java).use {
            composeRule.waitUntil(timeoutMillis = 5_000) {
                composeRule.onAllNodes(hasContentDescription("Add alarm")).fetchSemanticsNodes().isNotEmpty()
            }

            composeRule.onNodeWithText("Alarm in progress").assertDoesNotExist()
        }
    }

    @Test
    fun `Back to alarm opens the wake screen only during a ring, a snooze or the emergency ring`() {
        val session = MutableStateFlow<SessionState>(SessionState.Idle)
        val emergency = MutableStateFlow(false)
        val opener = AndroidWakeScreenOpener(app, session, emergency)
        val ring = aSession()
        val wakeScreen = listOf(ComponentName(app, WakeActivity::class.java))

        // Ended (history row pending), or Idle: the wake screen would never see a ring and never close.
        listOf(SessionState.Idle, SessionState.Completed(ring), SessionState.Missed(ring)).forEach { state ->
            session.value = state
            opener.open()
            assertEquals(emptyList(), startedActivities().map { it.component }, "$state")
        }

        listOf(SessionState.Ringing(ring), SessionState.Loud(ring), SessionState.Snoozed(ring)).forEach { state ->
            session.value = state
            opener.open()
            assertEquals(wakeScreen, startedActivities().map { it.component }, "$state")
        }

        session.value = SessionState.Idle
        emergency.value = true
        opener.open()
        assertEquals(wakeScreen, startedActivities().map { it.component }, "emergency ring")
    }
}
