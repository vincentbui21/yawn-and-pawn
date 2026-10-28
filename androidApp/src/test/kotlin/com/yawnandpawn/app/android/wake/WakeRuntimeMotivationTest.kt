package com.yawnandpawn.app.android.wake

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.yawnandpawn.app.StopAppRule
import com.yawnandpawn.app.android.ApplicationScope
import com.yawnandpawn.app.android.sound.LibrarySoundResolver
import com.yawnandpawn.app.awaitChildren
import com.yawnandpawn.app.core.alarm.Motivation
import com.yawnandpawn.app.core.alarm.MotivationMode
import com.yawnandpawn.app.core.log.LogEvent
import com.yawnandpawn.app.core.session.SessionData
import com.yawnandpawn.app.core.session.SessionEffect
import com.yawnandpawn.app.core.session.SessionState
import com.yawnandpawn.app.core.session.entryEffects
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
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Story 7.3: the wake runtime's `PlayMotivation`, with the real adapters over a fake playback; Story 7.5: the ring's
 * mix request.
 */
@RunWith(RobolectricTestRunner::class)
class WakeRuntimeMotivationTest {
    @get:Rule(order = 0)
    val stopApp = StopAppRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val logger = FakeLogger()
    private val dispatcher = StandardTestDispatcher()
    private val volume = AlarmVolume(context, logger)
    private val recordings = FakeRecordingPlaybackFactory()
    private val clock = FakeMonotonicClock(elapsedMillis = 1_000)
    private val sourceReads = mutableListOf<String>()
    private val player =
        AndroidAlarmPlayer(
            FakePlaybackFactory(),
            LibrarySoundResolver(),
            volume,
            clock,
            CoroutineScope(dispatcher),
            logger,
            recordingPlaybacks = recordings,
            recordingSource = { id -> File(context.cacheDir, "$id.m4a").also { sourceReads += id } },
        )
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
    private val mixed =
        aSession(config = aSessionConfig().copy(motivation = Motivation.Recording("r1"), motivationMode = MotivationMode.MixIntoAlarm))

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

    /** [ringing] rings: its entry effects run, then the player's scope finds the recording. */
    private fun ring(ringing: SessionState) {
        state = ringing
        runBlocking { entryEffects(ringing).forEach { runtime.apply(it) } }
        dispatcher.scheduler.runCurrent()
    }

    @Test
    fun `a Mix session with a frozen message mixes it into the ring (Story 7-5)`() {
        ring(SessionState.Ringing(mixed))

        assertTrue(player.isMixing)
        assertEquals(listOf("r1"), sourceReads)
        assertEquals(1, recordings.opened.size)
        player.stop(restoreVolume = true)
    }

    @Test
    fun `no mix for After I'm up, no message, an unresolved Random, or a Direct Boot ring (Story 7-5)`() {
        val mix = aSessionConfig().copy(motivationMode = MotivationMode.MixIntoAlarm)
        listOf(
            SessionState.Ringing(message),
            SessionState.Ringing(aSession(config = mix)),
            SessionState.Ringing(aSession(config = mix.copy(motivation = Motivation.Random))),
            SessionState.Ringing(mixed.copy(beforeFirstUnlock = true, directBootRing = true, startedBeforeUnlock = true)),
            // Unlocked during the ring: a Direct Boot ring keeps its default sound alone for the rest of the ring.
            SessionState.Ringing(mixed.copy(beforeFirstUnlock = false, directBootRing = true)),
        ).forEach {
            ring(it)
            assertTrue(player.isRinging, "$it rings")
            assertFalse(player.isMixing, "$it")
            player.stop(restoreVolume = true)
        }

        assertTrue(sourceReads.isEmpty(), "no recording is ever looked for")
        assertTrue(recordings.opened.isEmpty())
    }

    @Test
    fun `a Mix session that completes plays nothing on Success (Story 7-5)`() {
        state = SessionState.Completed(mixed)

        playMotivation()

        assertTrue(motivations.isEmpty())
        assertEquals(listOf<LogEvent>(LogEvent.SessionEffectLogged("PlayMotivation", entry = false)), logger.events)
    }

    /** Time passes in the ramp loop's 250 ms steps. */
    private fun advance(duration: Duration) {
        repeat((duration / STEP).toInt()) {
            clock.advanceBy(STEP)
            dispatcher.scheduler.advanceTimeBy(STEP)
            dispatcher.scheduler.runCurrent()
        }
    }

    private fun runEffect(effect: SessionEffect) = runBlocking { runtime.run(effect) }

    @Test
    fun `through the runtime, grace pauses the message and its end brings the alarm sound first (Story 7-5)`() {
        ring(SessionState.Ringing(mixed))
        advance(31.seconds)
        val message = checkNotNull(recordings.current)
        assertTrue(player.isMixPlaying)

        runEffect(SessionEffect.Mute)
        ring(SessionState.Grace(mixed.copy(graceEnd = mixed.interactionDeadline)))
        assertFalse(message.playing, "paused in grace")
        assertTrue(player.isMuted)
        advance(20.seconds)
        assertEquals(1, message.started, "nothing starts in grace")

        runEffect(SessionEffect.UnmuteToVolume(80))
        ring(SessionState.Loud(mixed))
        assertEquals(1f, player.gain, "the alarm sound first, at full gain")
        advance(29.seconds)
        assertEquals(1, message.started)
        advance(1.seconds)
        assertEquals(2, message.started, "30 s after grace ended")
        assertEquals(1, recordings.opened.size, "the same recording, never reopened")
        player.stop(restoreVolume = true)
    }

    @Test
    fun `a repeated sound effect for the same session keeps the ring and its recording (Story 7-5)`() {
        ring(SessionState.Ringing(mixed))
        advance(31.seconds)
        assertTrue(player.isMixPlaying)

        // The heartbeat re-applies the ring's entry effects.
        ring(SessionState.Ringing(mixed))
        advance(1.seconds)

        assertTrue(player.isMixPlaying, "the message plays on")
        assertEquals(1, recordings.opened.size, "never reopened")
        assertEquals(listOf("r1"), sourceReads, "never read again")
        player.stop(restoreVolume = true)
    }

    private companion object {
        val STEP = 250.milliseconds
    }
}
