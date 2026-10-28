package com.yawnandpawn.app.android.wake

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.yawnandpawn.app.StopAppRule
import com.yawnandpawn.app.android.ApplicationScope
import com.yawnandpawn.app.android.sound.LibrarySoundResolver
import com.yawnandpawn.app.awaitChildren
import com.yawnandpawn.app.core.alarm.Motivation
import com.yawnandpawn.app.core.log.LogEvent
import com.yawnandpawn.app.core.session.SessionData
import com.yawnandpawn.app.core.session.SessionEffect
import com.yawnandpawn.app.core.session.SessionState
import com.yawnandpawn.app.core.time.TimeSnapshot
import com.yawnandpawn.app.testing.FakeAlarmScheduler
import com.yawnandpawn.app.testing.FakeCrashReporter
import com.yawnandpawn.app.testing.FakeLogger
import com.yawnandpawn.app.testing.FakeMonotonicClock
import com.yawnandpawn.app.testing.FakeTimeZoneProvider
import com.yawnandpawn.app.testing.aSession
import com.yawnandpawn.app.testing.aSessionConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.datetime.TimeZone
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Story 7.3: the wake runtime's `PlayMotivation`, with the real adapters over a fake playback. */
@RunWith(RobolectricTestRunner::class)
class WakeRuntimeMotivationTest {
    @get:Rule(order = 0)
    val stopApp = StopAppRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val logger = FakeLogger()
    private val dispatcher = StandardTestDispatcher()
    private val volume = AlarmVolume(context, logger)
    private val player =
        AndroidAlarmPlayer(FakePlaybackFactory(), LibrarySoundResolver(), volume, FakeMonotonicClock(), CoroutineScope(dispatcher), logger)
    private var state: SessionState = SessionState.Idle
    private val motivations = mutableListOf<SessionData>()
    private val runtime =
        WakeRuntime(
            WakeOutputs(
                player,
                AlarmVibrator(context),
                WakeNotifier(context, FakeTimeZoneProvider(TimeZone.UTC)),
                volume,
                FakeAlarmScheduler(),
                FakeCrashReporter(),
            ),
            WakeServiceStarter(context, logger) {},
            CoroutineScope(dispatcher),
            logger,
            { TimeSnapshot(wallMillis = 1_000_000, elapsedMillis = 5_000, bootCount = 1) },
            { state },
            onPlayMotivation = { motivations += it },
        )

    private val message = aSession(config = aSessionConfig().copy(motivation = Motivation.Recording("r1")))

    /** As `WakeRuntimeTest`: the app's start runs on ApplicationScope threads and shares the volume preferences. */
    @Before
    fun awaitAppStart() = GlobalContext.get().get<ApplicationScope>().awaitChildren()

    private fun playMotivation() = runBlocking { runtime.run(SessionEffect.PlayMotivation) }

    @Test
    fun `PlayMotivation plays the completed session's frozen message, and is still logged by type name`() {
        state = SessionState.Completed(message)

        playMotivation()

        assertEquals(listOf(message), motivations)
        assertEquals(listOf<LogEvent>(LogEvent.SessionEffectLogged("PlayMotivation", entry = false)), logger.events)
    }

    @Test
    fun `PlayMotivation plays nothing without a frozen message, before the first unlock, or outside Completed`() {
        listOf(
            SessionState.Completed(aSession()),
            SessionState.Completed(aSession(config = aSessionConfig().copy(motivation = Motivation.Random))),
            SessionState.Completed(message.copy(startedBeforeUnlock = true)),
            SessionState.Missed(message),
            SessionState.Loud(message),
            SessionState.Idle,
        ).forEach {
            state = it
            playMotivation()
        }

        assertTrue(motivations.isEmpty())
    }
}
