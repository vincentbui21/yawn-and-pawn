package com.yawnandpawn.app.android.telemetry

import com.yawnandpawn.app.StopAppRule
import com.yawnandpawn.app.YawnAndPawnApp
import com.yawnandpawn.app.android.crash.FirebaseConfiguration
import com.yawnandpawn.app.android.crash.FirebaseStartup
import com.yawnandpawn.app.android.wake.WakeApp
import com.yawnandpawn.app.core.alarm.Alarm
import com.yawnandpawn.app.core.alarm.AlarmDraft
import com.yawnandpawn.app.core.alarm.AlarmFired
import com.yawnandpawn.app.core.alarm.SaveAlarm
import com.yawnandpawn.app.core.error.Outcome
import com.yawnandpawn.app.core.session.SessionEngine
import com.yawnandpawn.app.core.session.SessionEvent
import com.yawnandpawn.app.core.session.SessionState
import com.yawnandpawn.app.core.telemetry.ConsentGatedTelemetry
import com.yawnandpawn.app.core.telemetry.SetAnalyticsConsent
import com.yawnandpawn.app.core.telemetry.Telemetry
import com.yawnandpawn.app.stopApp
import com.yawnandpawn.app.testAppModule
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalTime
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext
import org.koin.core.module.Module
import org.koin.dsl.module
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * Story 5.6 review fix 6: the production wiring of usage stats, run as a Firebase-configured build on a recording
 * Analytics facade (no real Firebase). The app start turns collection off as soon as Firebase is ready and the gate turns
 * it on only after a yes; a real session completed with consent on reaches Analytics as the three events.
 */
@RunWith(RobolectricTestRunner::class)
class TelemetryWiringTest {
    @get:Rule(order = 0)
    val stopAppRule = StopAppRule()

    /** The real app start, as a configured build whose Analytics facade is [sink]. */
    class ConfiguredStartApp : YawnAndPawnApp() {
        override val overrideModules: List<Module> =
            listOf(
                testAppModule,
                module {
                    single { FirebaseConfiguration(present = true) }
                    single<AnalyticsSink> { sink }
                },
            )

        override fun onCreate() {
            stopApp()
            super.onCreate()
        }

        companion object {
            internal val sink = RecordingAnalyticsSink()
        }
    }

    @Test
    @Config(application = ConfiguredStartApp::class)
    fun `the app start turns collection off when Firebase is ready, and the gate turns it on only after a yes`() {
        val koin = GlobalContext.get()
        val startup = koin.get<FirebaseStartup>()
        val sink = ConfiguredStartApp.sink

        assertTrue(startup.ready.value)
        assertTrue(startup.startedCollectionOff)
        assertEquals(listOf("collection false"), sink.calls.toList(), "off first, as Firebase became ready")
        assertIs<ConsentGatedTelemetry>(koin.get<Telemetry>())

        // The choice is stored only once the session lock knows no session runs (the stored session is loaded).
        runBlocking { koin.get<SessionEngine>().restore() }
        assertIs<Outcome.Success<*>>(runBlocking { koin.get<SetAnalyticsConsent>()(true) })

        awaitUntil { sink.calls.size >= 3 }
        assertEquals(listOf("collection false", "collection true", "grant analytics storage only"), sink.calls.toList())
    }

    @Test
    fun `a real session completed with consent on reaches Analytics as its three events`() {
        val sink = RecordingAnalyticsSink()
        val app = WakeApp(analytics = sink, firebase = FirebaseConfiguration(present = true))
        val koin = app.koin
        runBlocking { app.engine.restore() }
        assertIs<Outcome.Success<*>>(runBlocking { koin.get<SetAnalyticsConsent>()(true) })
        val saved = assertIs<Outcome.Success<*>>(runBlocking { koin.get<SaveAlarm>()(AlarmDraft(time = LocalTime(7, 0))) })
        val alarmId = (saved.value as Alarm).id

        app.ring(AlarmFired(alarmId, Instant.parse("2027-03-08T06:00:00Z")))
        app.awaitRinging()
        app.dispatch(SessionEvent.ImUpTapped)
        app.solveCheck()
        app.awaitUntil("the session is recorded and Idle") { app.engine.state.value == SessionState.Idle }
        app.awaitUntil("the usage stats reach Analytics") { sink.events.size >= 3 }

        assertEquals(listOf("session_outcome", "snooze_count", "check_type"), sink.events.toList().map { it.first })
        assertEquals(3, sink.calls.count { it.startsWith("log ") }, "${sink.calls}")
    }

    /** Waits (real time, bounded) for the gate, which runs on its own app-wide scope. */
    private fun awaitUntil(condition: () -> Boolean) {
        repeat(MAX_ROUNDS) {
            if (condition()) return
            Thread.sleep(ROUND_MILLIS)
        }
        kotlin.test.fail("timed out")
    }

    private companion object {
        const val MAX_ROUNDS = 3_000
        const val ROUND_MILLIS = 10L
    }
}
