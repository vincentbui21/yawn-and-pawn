package com.yawnandpawn.app.android.wake

import android.app.NotificationManager
import android.os.Looper
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.yawnandpawn.app.StopAppRule
import com.yawnandpawn.app.android.AlarmFiredReceiver
import com.yawnandpawn.app.android.ApplicationScope
import com.yawnandpawn.app.awaitChildren
import com.yawnandpawn.app.core.alarm.AlarmDraft
import com.yawnandpawn.app.core.alarm.RequestCodes
import com.yawnandpawn.app.core.error.Outcome
import com.yawnandpawn.app.core.history.SessionOutcome
import com.yawnandpawn.app.core.session.ScheduleTestAlarm
import com.yawnandpawn.app.core.session.SessionEvent
import com.yawnandpawn.app.core.session.SessionState
import com.yawnandpawn.app.testing.FakeBilling
import com.yawnandpawn.app.testing.FakeSessionHistoryRepository
import com.yawnandpawn.app.testing.FakeTestAlarmStore
import com.yawnandpawn.app.testing.aSessionConfig
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalTime
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Story 1.18: "Test alarm" end to end. The editor's (unsaved) values are stored as a pending test and the test alarm is
 * armed; its fire goes through the receiver and the wake service into a `testMode` session that runs the whole Epic 1
 * flow, can never charge, and is logged Test.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class TestAlarmFlowTest {
    @get:Rule(order = 0)
    val stopApp = StopAppRule()

    @get:Rule(order = 1)
    val composeRule = createEmptyComposeRule()

    private val history = FakeSessionHistoryRepository()
    private val billing = FakeBilling()
    private val testAlarms = FakeTestAlarmStore()

    private fun app() = WakeApp(history = history, billing = billing, testAlarms = testAlarms)

    /** The test alarm's system alarm fires: the receiver starts the service, which the test then runs. */
    private fun fireTestAlarm(app: WakeApp) {
        app.app.sendBroadcast(AlarmFiredReceiver.intent(app.app, AlarmFiredReceiver.ACTION_TEST_ALARM))
        shadowOf(Looper.getMainLooper()).idle()
        app.koin.get<ApplicationScope>().awaitChildren()
        val intent = assertNotNull(shadowOf(app.app).nextStartedService, "the receiver started the wake service")
        assertEquals(WakeService.ACTION_TEST, intent.action)
        app.startService(intent)
    }

    @Test
    fun `the editor's values ring as a test session that can never charge and is logged Test`() {
        val app = app()
        val draft = AlarmDraft(time = LocalTime(7, 0), label = "Gym", volumePercent = 70, vibration = true)
        val armed = runBlocking { app.koin.get<ScheduleTestAlarm>()(draft) }
        assertIs<Outcome.Success<*>>(armed)
        val armedCodes = shadowOf(app.app.getSystemService(android.app.AlarmManager::class.java)).scheduledAlarms
        assertTrue(armedCodes.any { shadowOf(it.operation).requestCode == RequestCodes.TEST_ALARM }, "the test alarm is armed")

        fireTestAlarm(app)
        app.awaitRinging()

        val ringing = app.engine.state.value as SessionState.Ringing
        assertTrue(ringing.session.config.testMode)
        assertEquals("Gym", ringing.session.config.label)
        assertTrue(app.lastMediaPlayer().isReallyPlaying, "the sound plays")
        assertTrue(app.vibrator.isVibrating, "it vibrates")
        assertEquals(1, shadowOf(app.app.getSystemService(NotificationManager::class.java)).size(), "the ringing notification")
        assertNull(testAlarms.pending, "the pending test is used once")

        // The ringing screen: snooze reads "Test · no charge" and cannot be tapped.
        val screen = Robolectric.buildActivity(WakeActivity::class.java).setup().get()
        composeRule
            .onNodeWithContentDescription("Snooze unavailable, Test · no charge")
            .assertExists()
            .assertHasNoClickAction()
        // Even a snooze event reaching the engine charges nothing in test mode.
        app.dispatch(SessionEvent.SnoozeTapped)
        assertIs<SessionState.Ringing>(app.engine.state.value)

        composeRule.onNodeWithText("I'm up").performClick()
        composeRule.waitUntil(timeoutMillis = 10_000) { app.engine.state.value == SessionState.Idle && screen.isFinishing }
        app.awaitUntil("the ring stops") { app.player.sound == null }

        assertEquals(SessionOutcome.Test, history.rows.single().outcome)
        assertEquals(emptyList(), billing.launched, "no billing call in test mode")
    }

    @Test
    fun `a test fire while a session rings is ignored and logged, and the session is unchanged`() {
        val app = app()
        app.dispatch(SessionEvent.AlarmFired("session-1", aSessionConfig(), listOf(1L), beforeFirstUnlock = false))
        val before = app.engine.state.value
        testAlarms.pending = aSessionConfig(label = "test", testMode = true)

        app.startService(WakeService.intent(app.app, WakeService.ACTION_TEST))
        app.awaitUntil("the test fire is handled") { testAlarms.pending == null && app.logs().any { "TestAlarmFired" in it } }

        assertEquals(before, app.engine.state.value)
        assertTrue(
            app.logs().any { it.startsWith("SessionEventIgnored type=TestAlarmFired") },
            "${app.logs()}",
        )
    }

    @Test
    fun `a test fire with nothing pending rings nothing and stops the service`() {
        val app = app()

        val service = app.startService(WakeService.intent(app.app, WakeService.ACTION_TEST)).get()
        app.awaitUntil("the service stops") { shadowOf(service).isStoppedBySelf }

        assertEquals(SessionState.Idle, app.engine.state.value)
        assertTrue(app.mediaPlayers.isEmpty())
        assertTrue(app.logs().any { it.startsWith("FireIgnored kind=TestAlarm alarmId=null reason=no test pending") }, "${app.logs()}")
    }
}
