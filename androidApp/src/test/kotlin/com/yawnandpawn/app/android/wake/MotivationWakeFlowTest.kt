package com.yawnandpawn.app.android.wake

import android.os.Looper
import com.yawnandpawn.app.StopAppRule
import com.yawnandpawn.app.core.alarm.Alarm
import com.yawnandpawn.app.core.alarm.AlarmFired
import com.yawnandpawn.app.core.alarm.AlarmRepository
import com.yawnandpawn.app.core.alarm.Motivation
import com.yawnandpawn.app.core.error.DomainError
import com.yawnandpawn.app.core.error.Outcome
import com.yawnandpawn.app.core.recording.MotivationResolver
import com.yawnandpawn.app.core.recording.RecordedTake
import com.yawnandpawn.app.core.recording.Recording
import com.yawnandpawn.app.core.recording.RecordingRepository
import com.yawnandpawn.app.core.session.SessionEvent
import com.yawnandpawn.app.core.session.SessionState
import com.yawnandpawn.app.testing.FakeMotivationPlayer
import com.yawnandpawn.app.testing.FakeRecordingRepository
import com.yawnandpawn.app.testing.FakeTestAlarmStore
import com.yawnandpawn.app.testing.aRecording
import com.yawnandpawn.app.testing.aSessionConfig
import com.yawnandpawn.app.testing.anAlarm
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

/**
 * Story 7.3 through the wake service: a fire freezes its motivation (Random picked from the session seed), the completed
 * session plays the frozen message, and a slow recordings read never holds the ring.
 */
@RunWith(RobolectricTestRunner::class)
class MotivationWakeFlowTest {
    @get:Rule
    val stopApp = StopAppRule()

    private val scheduledAt = Instant.parse("2027-03-08T06:00:00Z")
    private val recordings =
        listOf("r1", "r2", "r3").mapIndexed { day, id -> aRecording(id = id, createdAt = scheduledAt - (day + 1).days) }
    private val player = FakeMotivationPlayer()

    private fun upsert(
        app: WakeApp,
        alarm: Alarm,
    ) = assertEquals(Outcome.Success(Unit), runBlocking { app.koin.get<AlarmRepository>().upsert(alarm) })

    private fun ringing(app: WakeApp): SessionState.Ringing {
        app.awaitRinging()
        return assertIs<SessionState.Ringing>(app.engine.state.value)
    }

    private fun complete(app: WakeApp) {
        app.dispatch(SessionEvent.ImUpTapped)
        app.solveCheck()
        app.awaitUntil("the session ends") { app.engine.state.value == SessionState.Idle }
    }

    @Test
    fun `a real alarm with Random freezes one message from the session seed and plays that one when it completes`() {
        val app = WakeApp(recordings = FakeRecordingRepository(recordings), motivationPlayer = player)
        val alarm = anAlarm(id = "alarm-a", requestCode = 1000).copy(motivation = Motivation.Random)
        upsert(app, alarm)
        app.ring(AlarmFired(alarm.id, scheduledAt))

        val session = ringing(app).session
        val expected = MotivationResolver.resolve(Motivation.Random, session.sessionId, recordings, beforeFirstUnlock = false)
        assertEquals(expected, session.config.motivation, "frozen at the fire")
        assertIs<Motivation.Recording>(expected)
        complete(app)

        assertEquals(listOf(session.sessionId to expected.id), player.played)
    }

    @Test
    fun `a test alarm with a message plays it once it completes`() {
        val testAlarms = FakeTestAlarmStore()
        testAlarms.pending = aSessionConfig(label = "test", testMode = true).copy(motivation = Motivation.Recording("r2"))
        val app = WakeApp(testAlarms = testAlarms, recordings = FakeRecordingRepository(recordings), motivationPlayer = player)
        app.startService(WakeService.intent(app.app, WakeService.ACTION_TEST))

        val session = ringing(app).session
        complete(app)

        assertEquals(listOf(session.sessionId to "r2"), player.played)
    }

    @Test
    fun `a test alarm with Random freezes one message from the session seed and plays it`() {
        val testAlarms = FakeTestAlarmStore()
        testAlarms.pending = aSessionConfig(label = "test", testMode = true).copy(motivation = Motivation.Random)
        val app = WakeApp(testAlarms = testAlarms, recordings = FakeRecordingRepository(recordings), motivationPlayer = player)
        app.startService(WakeService.intent(app.app, WakeService.ACTION_TEST))

        val session = ringing(app).session
        val expected = MotivationResolver.resolve(Motivation.Random, session.sessionId, recordings, beforeFirstUnlock = false)
        assertIs<Motivation.Recording>(expected)
        assertEquals(expected, session.config.motivation, "frozen at the test fire")
        complete(app)

        assertEquals(listOf(session.sessionId to expected.id), player.played)
    }

    @Test
    fun `a recordings read that never answers freezes no message after 500 ms, and the alarm rings`() {
        val app = WakeApp(recordings = Silent(), motivationPlayer = player)
        val alarm = anAlarm(id = "alarm-a", requestCode = 1000).copy(motivation = Motivation.Random)
        upsert(app, alarm)
        app.ring(AlarmFired(alarm.id, scheduledAt))
        // The main looper's clock moves only when told: the read's 500 ms pass here, a tenth of a second at a time.
        app.awaitUntil("the alarm rings") {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
            app.engine.state.value is SessionState.Ringing
        }

        val session = ringing(app).session
        assertEquals(Motivation.None, session.config.motivation)
        assertEquals(true, app.logs().any { MotivationResolver.OPERATION in it && "timed out" in it }, app.logs().toString())
        complete(app)
        assertEquals(emptyList(), player.played)
    }

    /** A repository whose list never comes. */
    private class Silent : RecordingRepository {
        override fun observeAll(): Flow<List<Recording>> = flow { awaitCancellation() }

        override suspend fun get(id: String): Outcome<Recording?, DomainError> = Outcome.Success(null)

        override suspend fun save(
            recording: Recording,
            take: RecordedTake,
        ): Outcome<Unit, DomainError> = Outcome.Success(Unit)

        override suspend fun delete(id: String): Outcome<Unit, DomainError> = Outcome.Success(Unit)
    }
}
