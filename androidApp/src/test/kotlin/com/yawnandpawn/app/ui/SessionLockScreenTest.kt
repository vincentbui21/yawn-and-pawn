package com.yawnandpawn.app.ui

import android.app.Application
import android.content.ComponentName
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHeightIsAtLeast
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
import com.yawnandpawn.app.android.wake.WakeActivity
import com.yawnandpawn.app.awaitChildren
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
import com.yawnandpawn.app.testing.FakeSoundPreview
import com.yawnandpawn.app.testing.aSession
import com.yawnandpawn.app.testing.aSessionConfig
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

    /** Starts the app with the session [stored] at "now" on its time ports (none: Idle) and restores it. */
    private fun start(stored: ((TimeSnapshot) -> SessionState)? = null) {
        val store = FakeActiveSessionStore()
        restartKoin(
            app,
            module {
                single<ActiveSessionStore> { store }
                single<SoundPreview> { preview }
            },
        )
        val koin = GlobalContext.get()
        stored?.let { store.row = SessionJson.encode(it(TimeSnapshot.of(koin.get(), koin.get(), koin.get()))) }
        runBlocking { engine.restore() }
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
}
