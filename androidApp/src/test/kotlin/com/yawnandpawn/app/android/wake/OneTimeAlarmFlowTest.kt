package com.yawnandpawn.app.android.wake

import android.app.AlarmManager
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.yawnandpawn.app.StopAppRule
import com.yawnandpawn.app.android.AlarmFiredReceiver
import com.yawnandpawn.app.android.ApplicationScope
import com.yawnandpawn.app.awaitChildren
import com.yawnandpawn.app.core.alarm.AlarmRepository
import com.yawnandpawn.app.core.alarm.RequestCodes
import com.yawnandpawn.app.core.alarm.SetAlarmEnabled
import com.yawnandpawn.app.core.error.Outcome
import com.yawnandpawn.app.core.error.valueOrNull
import com.yawnandpawn.app.core.history.SessionOutcome
import com.yawnandpawn.app.core.log.WakeStage
import com.yawnandpawn.app.core.session.SessionState
import com.yawnandpawn.app.testing.FakeSessionHistoryRepository
import com.yawnandpawn.app.testing.anAlarm
import kotlinx.coroutines.runBlocking
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
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Device test round 1: a one-time alarm end to end. Its system alarm fires through the real PendingIntent and receiver,
 * the receiver starts the wake service and keeps its broadcast open until the service took the start, the alarm rings,
 * "I'm up" ends the session, and afterwards the alarm is off with nothing armed for it (logged by `RearmOnFire`).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class OneTimeAlarmFlowTest {
    @get:Rule(order = 0)
    val stopApp = StopAppRule()

    @get:Rule(order = 1)
    val composeRule = createEmptyComposeRule()

    private val history = FakeSessionHistoryRepository()

    private fun armedCodes(app: WakeApp): List<Int> =
        shadowOf(app.app.getSystemService(AlarmManager::class.java)).scheduledAlarms.map { shadowOf(it.operation).requestCode }

    @Test
    fun `a one-time alarm rings once, and after I'm up it is off and nothing is armed for it`() {
        val app = WakeApp(history = history, serviceStartWait = 6.seconds)
        val repository = app.koin.get<AlarmRepository>()
        val once = anAlarm(id = "once", requestCode = 1000, enabled = false)
        assertEquals(Outcome.Success(Unit), runBlocking { repository.upsert(once) })
        // Switched on in the app: stored enabled and armed for its next 07:00. The alarms are editable once the stored
        // session is restored (the session lock, Story 2.6).
        runBlocking { app.engine.restore() }
        assertIs<Outcome.Success<*>>(runBlocking { app.koin.get<SetAlarmEnabled>()(once.id, enabled = true) })
        assertEquals(listOf(once.requestCode), armedCodes(app))

        // The system fires it: the receiver starts the service, which the test runs as the system would.
        val alarmManager = shadowOf(app.app.getSystemService(AlarmManager::class.java))
        alarmManager.fireAlarm(alarmManager.scheduledAlarms.single())
        app.awaitUntil("the receiver starts the wake service") { shadowOf(app.app).peekNextStartedService() != null }
        val intent = shadowOf(app.app).nextStartedService
        assertEquals(WakeService.ACTION_ALARM, intent.action)
        assertEquals(once.id, intent.getStringExtra(AlarmFiredReceiver.EXTRA_ALARM_ID))
        app.startService(intent)
        app.awaitRinging()
        // The broadcast finished once the service took the start (no wait timed out).
        app.koin.get<ApplicationScope>().awaitChildren()
        assertTrue(app.logs().none { it.startsWith("OperationFailed operation=wait for wake service") }, "${app.logs()}")

        val ringing = app.engine.state.value as SessionState.Ringing
        assertEquals(once.id, ringing.session.config.alarmId)
        assertTrue(app.lastMediaPlayer().isReallyPlaying, "the sound plays")

        // "I'm up" on the wake screen ends the Epic 1 session.
        val screen = Robolectric.buildActivity(WakeActivity::class.java).setup().get()
        composeRule.onNodeWithText("I'm up").performClick()
        // Story 3.3: the Success screen for the session, then "Done" closes the wake screen.
        composeRule.awaitSuccess(app, "Up on time.")
        composeRule.onNodeWithText("Done").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) { screen.isFinishing }
        app.awaitUntil("the ring stops") { app.player.sound == null }

        assertEquals(false, runBlocking { repository.get(once.id).valueOrNull()?.enabled }, "the one-time alarm is off")
        assertTrue(once.requestCode !in armedCodes(app), "nothing is armed for it: ${armedCodes(app)}")
        assertTrue(RequestCodes.SESSION_SLOT !in armedCodes(app), "no session slot is left")
        assertEquals(SessionOutcome.OnTime, history.rows.single().outcome)
        assertTrue(app.logs().any { it.startsWith("OneTimeAlarmDisabled alarmId=once") }, "${app.logs()}")
        // Each stage of the ring start is in the log, timed from the scheduled time.
        val stages = app.logs().filter { it.startsWith("WakeTiming") }.map { it.substringAfter("stage=").substringBefore(" ") }
        assertEquals(WakeStage.entries.map { it.name }.toSet(), stages.toSet(), "every stage is logged")
        assertEquals(WakeStage.ReceiverReceived.name, stages.first())
    }
}
