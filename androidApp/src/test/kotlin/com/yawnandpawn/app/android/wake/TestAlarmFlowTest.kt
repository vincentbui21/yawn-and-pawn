package com.yawnandpawn.app.android.wake

import android.app.NotificationManager
import android.os.Looper
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.yawnandpawn.app.APP_WORK_TIMEOUT_MILLIS
import com.yawnandpawn.app.StopAppRule
import com.yawnandpawn.app.android.AlarmFiredReceiver
import com.yawnandpawn.app.android.ApplicationScope
import com.yawnandpawn.app.awaitChildren
import com.yawnandpawn.app.buildActivity
import com.yawnandpawn.app.core.alarm.AlarmDraft
import com.yawnandpawn.app.core.alarm.AlarmFired
import com.yawnandpawn.app.core.alarm.AlarmRepository
import com.yawnandpawn.app.core.alarm.RequestCodes
import com.yawnandpawn.app.core.checks.CheckEntry
import com.yawnandpawn.app.core.checks.CheckMode
import com.yawnandpawn.app.core.checks.CheckPlan
import com.yawnandpawn.app.core.checks.CheckType
import com.yawnandpawn.app.core.checks.Difficulty
import com.yawnandpawn.app.core.error.DomainError
import com.yawnandpawn.app.core.error.Outcome
import com.yawnandpawn.app.core.history.SessionOutcome
import com.yawnandpawn.app.core.session.ScheduleTestAlarm
import com.yawnandpawn.app.core.session.SessionEvent
import com.yawnandpawn.app.core.session.SessionState
import com.yawnandpawn.app.testing.FakeActiveSessionStore
import com.yawnandpawn.app.testing.FakeBilling
import com.yawnandpawn.app.testing.FakeSessionHistoryRepository
import com.yawnandpawn.app.testing.FakeTestAlarmStore
import com.yawnandpawn.app.testing.aRegisteredCode
import com.yawnandpawn.app.testing.aSessionConfig
import com.yawnandpawn.app.testing.anAlarm
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalTime
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

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
        val screen = buildActivity(WakeActivity::class.java).setup().get()
        composeRule
            .onNodeWithContentDescription("Snooze unavailable, Test · no charge")
            .assertExists()
            .assertHasNoClickAction()
        // Even a snooze event reaching the engine charges nothing in test mode.
        app.dispatch(SessionEvent.SnoozeTapped)
        assertIs<SessionState.Ringing>(app.engine.state.value)

        composeRule.onNodeWithText("I'm up").performClick()
        app.awaitUntil("the check starts") { app.engine.state.value is SessionState.Grace }
        app.solveCheck()
        // Story 3.3: the test Success screen, then "Done" closes the wake screen.
        composeRule.awaitSuccess(app, "Test finished. Your alarm works.")
        composeRule.onNodeWithText("Done").performClick()
        composeRule.waitUntil(timeoutMillis = APP_WORK_TIMEOUT_MILLIS) { screen.isFinishing }
        app.awaitUntil("the ring stops") { app.player.sound == null }

        assertEquals(SessionOutcome.Test, history.rows.single().outcome)
        assertTrue(app.logs().none { "LaunchBilling" in it }, "no billing call in test mode")
    }

    @Test
    fun `a test fire while a session rings is ignored and logged, and the session is unchanged`() {
        val app = app()
        app.dispatch(SessionEvent.AlarmFired("session-1", aSessionConfig(), beforeFirstUnlock = false))
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

    @Test
    fun `a pending test that cannot be read is logged, rings nothing and stops the service`() {
        val app = app()
        testAlarms.takeFailure = DomainError.StorageFailure("disk unreadable")

        val service = app.startService(WakeService.intent(app.app, WakeService.ACTION_TEST)).get()
        app.awaitUntil("the service stops") { shadowOf(service).isStoppedBySelf }

        assertEquals(SessionState.Idle, app.engine.state.value)
        assertTrue(app.mediaPlayers.isEmpty(), "nothing rings")
        assertTrue(app.logs().any { it.startsWith("OperationFailed operation=read pending test alarm") }, "${app.logs()}")
    }

    @Test
    fun `a test session that cannot be saved is logged, rings nothing, and its config is put back`() {
        val broken = FakeActiveSessionStore().apply { commitFailure = DomainError.StorageFailure("disk full") }
        val app = WakeApp(store = broken, history = history, billing = billing, testAlarms = testAlarms)
        val config = aSessionConfig(label = "test", testMode = true)
        testAlarms.pending = config

        val service = app.startService(WakeService.intent(app.app, WakeService.ACTION_TEST)).get()
        app.awaitUntil("the service stops") { shadowOf(service).isStoppedBySelf }

        assertEquals(SessionState.Idle, app.engine.state.value)
        assertTrue(app.mediaPlayers.isEmpty(), "nothing rings")
        assertNull(app.runtime.emergency.value, "a test never starts the emergency ring")
        assertTrue(app.logs().any { it.startsWith("OperationFailed operation=start test session") }, "${app.logs()}")
        assertEquals(config, testAlarms.pending, "the config is put back")
    }

    @Test
    fun `a real alarm during a test ends the test as Test and rings as a real session`() {
        val app = app()
        val alarm = anAlarm(id = "alarm-a", requestCode = 1000)
        assertEquals(Outcome.Success(Unit), runBlocking { app.koin.get<AlarmRepository>().upsert(alarm) })
        testAlarms.pending = aSessionConfig(label = "test", testMode = true)
        val controller = app.startService(WakeService.intent(app.app, WakeService.ACTION_TEST))
        app.awaitRinging()
        val test = app.engine.state.value as SessionState.Ringing
        assertTrue(test.session.config.testMode)

        val scheduledAt = Instant.parse("2027-03-08T06:00:00Z")
        controller.withIntent(WakeService.alarmIntent(app.app, AlarmFired(alarm.id, scheduledAt))).startCommand(0, 2)
        app.awaitUntil("the real session rings") {
            (app.engine.state.value as? SessionState.Ringing)?.session?.config?.testMode == false
        }

        val real = app.engine.state.value as SessionState.Ringing
        assertEquals(alarm.id, real.session.config.alarmId)
        assertEquals(scheduledAt, real.session.config.scheduledAt)
        assertEquals(SessionOutcome.Test, history.rows.single { it.sessionId == test.session.sessionId }.outcome)
        assertTrue(app.player.sound != null, "the real alarm rings")
        // Story 2.9 keeps this Story 1.18 rule over the AC's "merged like any other": a real morning is never logged as Test.
        assertEquals(emptyList(), history.merges, "no merge row: the real alarm got its own session")
    }

    @Test
    fun `a real alarm during a Math test answers the test's check, ends it as Test and rings as a real session (Story 3_2)`() {
        val app = app()
        val alarm = anAlarm(id = "alarm-a", requestCode = 1000)
        assertEquals(Outcome.Success(Unit), runBlocking { app.koin.get<AlarmRepository>().upsert(alarm) })
        testAlarms.pending = aSessionConfig(label = "test", testMode = true).copy(checkPlan = CheckPlan.default())
        val controller = app.startService(WakeService.intent(app.app, WakeService.ACTION_TEST))
        app.awaitRinging()
        val test = app.engine.state.value as SessionState.Ringing
        assertEquals(
            CheckType.Math,
            test.session.checkRun.currentEntry
                ?.type,
        )

        val scheduledAt = Instant.parse("2027-03-08T06:00:00Z")
        controller.withIntent(WakeService.alarmIntent(app.app, AlarmFired(alarm.id, scheduledAt))).startCommand(0, 2)
        app.awaitUntil("the real session rings") {
            (app.engine.state.value as? SessionState.Ringing)?.session?.config?.testMode == false
        }

        assertEquals(alarm.id, (app.engine.state.value as SessionState.Ringing).session.config.alarmId)
        assertEquals(SessionOutcome.Test, history.rows.single { it.sessionId == test.session.sessionId }.outcome)
        assertEquals(emptyList(), history.merges, "no merge row: the real alarm got its own session")
        assertTrue(app.logs().none { it.startsWith("SessionEventIgnored type=CheckAnswerSubmitted") }, "${app.logs()}")
        controller.destroy() // No ticking service left behind for the next test.
    }

    @Test
    fun `a real alarm during a QR test answers the test with its own code, ends it as Test and rings (Story 3_10 review)`() {
        val app = app()
        val alarm = anAlarm(id = "alarm-a", requestCode = 1000)
        assertEquals(Outcome.Success(Unit), runBlocking { app.koin.get<AlarmRepository>().upsert(alarm) })
        val qr = CheckPlan(CheckMode.All, listOf(CheckEntry(CheckType.QrBarcode, Difficulty.Medium, 1, code = aRegisteredCode())))
        testAlarms.pending = aSessionConfig(label = "test", testMode = true).copy(checkPlan = qr)
        val controller = app.startService(WakeService.intent(app.app, WakeService.ACTION_TEST))
        app.awaitRinging()
        val test = app.engine.state.value as SessionState.Ringing
        assertEquals(
            CheckType.QrBarcode,
            test.session.checkRun.currentEntry
                ?.type,
            "the QR plan rings as QR",
        )

        controller
            .withIntent(
                WakeService.alarmIntent(app.app, AlarmFired(alarm.id, Instant.parse("2027-03-08T06:00:00Z"))),
            ).startCommand(0, 2)
        app.awaitUntil("the real session rings") {
            (app.engine.state.value as? SessionState.Ringing)?.session?.config?.testMode == false
        }

        assertEquals(SessionOutcome.Test, history.rows.single { it.sessionId == test.session.sessionId }.outcome, "ended, not left ringing")
        assertTrue(app.logs().none { it.startsWith("SessionEventIgnored type=CheckAnswerSubmitted") }, "${app.logs()}")
        controller.destroy()
    }

    @Test
    fun `a Math test whose I'm up cannot be saved gets no answers when a real alarm rings, and still rings (Story 3_2 review)`() {
        val store = FakeActiveSessionStore()
        val app = WakeApp(store = store, history = history, billing = billing, testAlarms = testAlarms)
        val alarm = anAlarm(id = "alarm-a", requestCode = 1000)
        assertEquals(Outcome.Success(Unit), runBlocking { app.koin.get<AlarmRepository>().upsert(alarm) })
        testAlarms.pending = aSessionConfig(label = "test", testMode = true).copy(checkPlan = CheckPlan.default())
        val controller = app.startService(WakeService.intent(app.app, WakeService.ACTION_TEST))
        app.awaitRinging()
        store.commitFailure = DomainError.StorageFailure("disk full")

        val scheduledAt = Instant.parse("2027-03-08T06:00:00Z")
        controller.withIntent(WakeService.alarmIntent(app.app, AlarmFired(alarm.id, scheduledAt))).startCommand(0, 2)
        // After ending the test (which sends every answer first), the service merges the real alarm into the test.
        app.awaitUntil("the real alarm is merged into the test that rings on") {
            app.logs().any { "finish test session" in it } && (history.merges.isNotEmpty() || app.logs().any { "rings on" in it })
        }

        val test = assertIs<SessionState.Ringing>(app.engine.state.value)
        assertTrue(test.session.config.testMode, "the test still rings")
        assertTrue(app.player.sound != null, "never silent")
        assertTrue(app.logs().none { it.startsWith("SessionEventIgnored type=CheckAnswerSubmitted") }, "no ignored answers: ${app.logs()}")
        store.commitFailure = null
        controller.destroy() // No ticking service left behind for the next test.
    }
}
