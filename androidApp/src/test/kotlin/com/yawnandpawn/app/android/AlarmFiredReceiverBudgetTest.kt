package com.yawnandpawn.app.android

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.yawnandpawn.app.StopAppRule
import com.yawnandpawn.app.YawnAndPawnApp
import com.yawnandpawn.app.awaitChildren
import com.yawnandpawn.app.core.alarm.AlarmFired
import com.yawnandpawn.app.core.alarm.AlarmFiredHandler
import com.yawnandpawn.app.core.log.LogEvent
import com.yawnandpawn.app.core.log.Logger
import com.yawnandpawn.app.restartKoin
import com.yawnandpawn.app.testing.FakeLogger
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext
import org.koin.dsl.module
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Deferred from Stories 1.10 and 1.12: the alarm receiver finishes its `goAsync()` pending result in every case. The
 * fire is an ordered broadcast whose final result receiver runs only once the pending result is finished.
 */
@RunWith(RobolectricTestRunner::class)
class AlarmFiredReceiverBudgetTest {
    @get:Rule(order = 0)
    val stopApp = StopAppRule()

    private val app = ApplicationProvider.getApplicationContext<YawnAndPawnApp>()
    private val dispatcher = StandardTestDispatcher()
    private val fakeLogger = FakeLogger()

    private fun bind(onSlot: suspend () -> Unit) {
        GlobalContext.get().get<ApplicationScope>().awaitChildren()
        val handler =
            object : AlarmFiredHandler {
                override suspend fun onAlarmFired(fired: AlarmFired) = Unit

                override suspend fun onSessionSlotFired() = onSlot()

                override suspend fun onTestAlarmFired() = Unit
            }
        restartKoin(
            app,
            module {
                single<Logger> { fakeLogger }
                single { ApplicationScope(fakeLogger, dispatcher) }
                single<AlarmFiredHandler> { handler }
            },
        )
    }

    /** Sends a session-slot fire; the returned flag turns true once the receiver finished its pending result. */
    private fun fireSlot(): AtomicBoolean {
        val finished = AtomicBoolean(false)
        val last =
            object : BroadcastReceiver() {
                override fun onReceive(
                    context: Context,
                    intent: Intent,
                ) {
                    finished.set(true)
                }
            }
        app.sendOrderedBroadcast(AlarmFiredReceiver.intent(app, AlarmFiredReceiver.ACTION_SESSION_SLOT), null, last, null, 0, null, null)
        idleMain()
        return finished
    }

    private fun idleMain() = shadowOf(Looper.getMainLooper()).idle()

    private fun runWork(seconds: Int) {
        dispatcher.scheduler.advanceTimeBy(seconds.seconds)
        dispatcher.scheduler.runCurrent()
        idleMain()
    }

    @Test
    fun `a handler that overruns the work budget is logged as a timeout and the pending result still finishes`() {
        bind { awaitCancellation() }

        val finished = fireSlot()
        runWork(seconds = 7)
        assertFalse(finished.get(), "still inside the 8 s budget")
        runWork(seconds = 2)

        assertTrue(finished.get(), "the pending result is finished after the timeout")
        assertEquals(listOf<LogEvent>(LogEvent.OperationFailed("handle alarm fire", "took longer than 8s")), fakeLogger.events)
    }

    @Test
    fun `a throwing handler is logged by the scope and the pending result still finishes`() {
        bind { error("handler broke") }

        val finished = fireSlot()
        runWork(seconds = 1)

        assertTrue(finished.get(), "the pending result is finished in the finally block")
        assertEquals(listOf<LogEvent>(LogEvent.OperationFailed("background work", "handler broke")), fakeLogger.events)
    }
}
