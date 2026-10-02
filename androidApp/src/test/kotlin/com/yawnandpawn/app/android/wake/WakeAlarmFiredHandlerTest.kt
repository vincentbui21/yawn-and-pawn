package com.yawnandpawn.app.android.wake

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.yawnandpawn.app.StopAppRule
import com.yawnandpawn.app.core.alarm.AlarmFired
import com.yawnandpawn.app.core.alarm.AlarmFiredHandler
import com.yawnandpawn.app.core.error.DomainError
import com.yawnandpawn.app.core.log.LogEvent
import com.yawnandpawn.app.testing.FakeAlarmRepository
import com.yawnandpawn.app.testing.FakeLogger
import com.yawnandpawn.app.testing.anAlarm
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant

/** Story 1.14: the fire handler starts the wake service whatever happens to the scheduling part. */
@RunWith(RobolectricTestRunner::class)
class WakeAlarmFiredHandlerTest {
    @get:Rule(order = 0)
    val stopApp = StopAppRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val started = mutableListOf<Intent>()
    private val starter = WakeServiceStarter(context, FakeLogger()) { started += it }
    private val fired = AlarmFired("alarm-a", Instant.fromEpochMilliseconds(1_000))
    private val repository = FakeAlarmRepository(listOf(anAlarm(id = "alarm-a")))

    private val logger = FakeLogger()

    private fun handler(
        starter: WakeServiceStarter = this.starter,
        schedule: suspend (AlarmFired) -> Unit = {},
    ) = WakeAlarmFiredHandler(
        repository,
        object : AlarmFiredHandler {
            override suspend fun onAlarmFired(fired: AlarmFired) = schedule(fired)

            override suspend fun onSessionSlotFired() = Unit

            override suspend fun onTestAlarmFired() = Unit
        },
        starter,
        logger,
    )

    private fun startedAlarms() = started.map { it.action to it.getStringExtra("alarmId") }

    @Test
    fun `a re-arm that throws still starts the service`() {
        assertFailsWith<IllegalStateException> { runBlocking { handler { error("scheduler broke") }.onAlarmFired(fired) } }

        assertEquals(listOf(WakeService.ACTION_ALARM to "alarm-a"), startedAlarms())
    }

    @Test
    fun `a re-arm that overruns the receiver budget and is cancelled still starts the service`() {
        val finished = runBlocking { withTimeoutOrNull(50.milliseconds) { handler { awaitCancellation() }.onAlarmFired(fired) } }

        assertNull(finished, "timed out")
        assertEquals(listOf(WakeService.ACTION_ALARM to "alarm-a"), startedAlarms())
    }

    @Test
    fun `an alarm that cannot be read still rings, a deleted or disabled one does not`() {
        repository.failure = DomainError.StorageFailure("disk I/O error")
        runBlocking { handler {}.onAlarmFired(fired) }
        assertEquals(1, started.size, "unreadable rings the emergency default")

        repository.failure = null
        runBlocking { handler {}.onAlarmFired(AlarmFired("gone", fired.scheduledAt)) }
        runBlocking { repository.upsert(anAlarm(id = "alarm-a", enabled = false)) }
        runBlocking { handler {}.onAlarmFired(fired) }
        assertEquals(1, started.size)
    }

    @Test
    fun `a test fire starts the service with the test action, and a refused start is logged`() {
        runBlocking { handler().onTestAlarmFired() }
        assertEquals(listOf(WakeService.ACTION_TEST), started.map { it.action })

        val refusing = WakeServiceStarter(context, FakeLogger()) { throw IllegalStateException("background start not allowed") }
        runBlocking { handler(starter = refusing).onTestAlarmFired() }

        assertEquals(
            listOf<LogEvent>(LogEvent.OperationFailed("start test alarm", "service start refused; the test does not ring")),
            logger.events,
        )
    }
}
