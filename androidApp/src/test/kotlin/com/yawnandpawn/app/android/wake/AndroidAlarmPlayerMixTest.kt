package com.yawnandpawn.app.android.wake

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.yawnandpawn.app.StopAppRule
import com.yawnandpawn.app.android.ApplicationScope
import com.yawnandpawn.app.awaitChildren
import com.yawnandpawn.app.core.alarm.Alarm
import com.yawnandpawn.app.core.log.LogEvent
import com.yawnandpawn.app.testing.FakeLogger
import com.yawnandpawn.app.testing.FakeMonotonicClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowMediaPlayer
import org.robolectric.shadows.util.DataSource
import java.io.File
import java.io.IOException
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Story 7.5: "Mix into alarm". The player alternates 30 s of alarm sound with the session's recording, over the fake
 * playback seams; the alarm sound never stops (gain 0 while the recording plays), and a failing recording leaves the
 * alarm sound alone for the rest of the session.
 */
@RunWith(RobolectricTestRunner::class)
class AndroidAlarmPlayerMixTest {
    @get:Rule(order = 0)
    val stopApp = StopAppRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val logger = FakeLogger()
    private val clock = FakeMonotonicClock(elapsedMillis = 1_000)
    private val playbacks = FakePlaybackFactory()
    private val recordings = FakeRecordingPlaybackFactory(durationMs = 8_000)
    private val volume = AlarmVolume(context, logger)
    private val messageFile = File(context.cacheDir, "recordings/r1.m4a")
    private var sourceFile: File? = messageFile
    private var sourceError: Exception? = null
    private val sourceReads = mutableListOf<String>()
    private val resolver = SoundResolver { ref -> if (ref == Alarm.DEFAULT_SOUND_REF) AlarmSound.Default else null }
    private val dispatcher = StandardTestDispatcher()
    private val player =
        AndroidAlarmPlayer(
            playbacks,
            resolver,
            volume,
            clock,
            CoroutineScope(dispatcher),
            logger,
            recordingPlaybacks = recordings,
            recordingSource = { id ->
                sourceReads += id
                sourceError?.let { throw it }
                sourceFile
            },
        )
    private val mix = MixRequest("session-1", "r1")

    @Before
    fun awaitAppStart() = GlobalContext.get().get<ApplicationScope>().awaitChildren()

    /** Advances time in the ramp loop's 250 ms steps, so every step sees its own time. */
    private fun advance(duration: Duration) {
        var left = duration
        while (left > Duration.ZERO) {
            val step = minOf(left, STEP)
            clock.advanceBy(step)
            dispatcher.scheduler.advanceTimeBy(step)
            dispatcher.scheduler.runCurrent()
            left -= step
        }
    }

    private fun play(
        gradual: Boolean = true,
        request: MixRequest? = mix,
    ) {
        player.play(Alarm.DEFAULT_SOUND_REF, volumePercent = 80, gradual = gradual, rampStartPercent = 20, mix = request)
        dispatcher.scheduler.runCurrent()
    }

    private val alarm: FakePlayback
        get() = checkNotNull(playbacks.current)

    private val message: FakeRecordingPlayback
        get() = checkNotNull(recordings.current)

    private fun mixFailures() = logger.events.filterIsInstance<LogEvent.OperationFailed>().filter { it.operation == "mix motivation" }

    @Test
    fun `the alarm ramps for 30 s, then the message plays once, then 30 s of alarm, repeating`() {
        play()
        assertEquals(listOf("r1"), sourceReads, "read once, off the caller's thread")
        assertEquals(messageFile, message.file)
        assertEquals(0.2f, player.gain, 1e-6f, "the ramp starts as without a message")

        advance(15.seconds)
        assertEquals(0.6f, player.gain, 0.02f, "the ramp is unchanged")
        advance(14.75.seconds)
        assertFalse(player.isMixPlaying, "29.75 s: still the alarm sound")
        assertEquals(0, message.started)

        advance(STEP)
        assertTrue(player.isMixPlaying, "30 s: the message")
        assertEquals(1, message.started)
        assertTrue(message.playing)
        assertEquals(1f, message.gains.last(), "the message at full gain on the ring's alarm-stream volume")
        assertEquals(0f, alarm.gains.last(), "the alarm sound goes to 0 in the same step: no gap, no overlap")
        assertTrue(alarm.playing, "the alarm sound keeps looping silently")

        advance(7.75.seconds)
        assertTrue(player.isMixPlaying)
        message.complete()
        assertFalse(player.isMixPlaying, "38 s: the message ended")
        assertEquals(1f, alarm.gains.last(), "the alarm sound is back at full gain at once")

        advance(29.75.seconds)
        assertEquals(1, message.started, "30 s of alarm sound after the message")
        advance(STEP)
        assertEquals(2, message.started, "then the message again, from the start")
        assertEquals(0f, alarm.gains.last())

        // Without a completion report, the schedule switches back 1 s after the message's length (start latency never
        // cuts its end).
        advance(8.seconds)
        assertTrue(player.isMixPlaying, "the slot lasts the message's length plus 1 s")
        assertEquals(0f, alarm.gains.last())
        advance(1.seconds)
        assertFalse(player.isMixPlaying)
        assertFalse(message.playing)
        assertEquals(1f, alarm.gains.last())
        assertEquals(1, recordings.opened.size, "prepared once for the ring")
        assertEquals(1, playbacks.opened.size, "the alarm sound never restarted")
        player.stop(restoreVolume = true)
    }

    @Test
    fun `without gradual volume the alarm plays at full gain and the message still waits 30 s`() {
        play(gradual = false)
        assertEquals(1f, player.gain)

        advance(29.75.seconds)
        assertFalse(player.isMixPlaying)
        advance(STEP)
        assertTrue(player.isMixPlaying)
        assertEquals(0f, player.gain)
        player.stop(restoreVolume = true)
    }

    @Test
    fun `a snooze re-ring of the session starts over with 30 s of alarm sound, then the message`() {
        play()
        advance(31.seconds)
        assertTrue(player.isMixPlaying)
        player.stop(restoreVolume = false)

        play()
        assertFalse(player.isMixPlaying, "the new ring starts with the alarm sound")
        advance(29.75.seconds)
        assertFalse(player.isMixPlaying, "29.75 s into the new ring: still the alarm sound")
        advance(STEP)
        assertTrue(player.isMixPlaying, "30 s: the message")
        assertEquals(2, recordings.opened.size, "each ring opens its own message")
        player.stop(restoreVolume = true)
    }

    @Test
    fun `grace mutes both and pauses the message, and when it ends the alarm comes first at full set volume`() {
        play()
        advance(32.seconds)
        assertTrue(player.isMixPlaying)

        player.mute()
        assertFalse(message.playing, "the message pauses")
        assertFalse(player.isMixPlaying)
        assertEquals(0f, alarm.gains.last(), "the alarm sound stays silent")
        advance(20.seconds)
        assertEquals(1, message.started, "nothing starts during grace")
        assertEquals(0f, alarm.gains.last())

        player.unmuteTo(80)
        assertEquals(1f, alarm.gains.last(), "full set volume, no ramp")
        advance(29.75.seconds)
        assertEquals(1, message.started, "30 s of alarm sound first")
        advance(STEP)
        assertEquals(2, message.started, "then the message, from the start")
        player.stop(restoreVolume = true)
    }

    @Test
    fun `a call pauses both, and after it the alarm sound comes first at full gain`() {
        play()
        advance(31.seconds)
        assertTrue(player.isMixPlaying)

        player.pause()
        assertFalse(message.playing)
        assertFalse(alarm.playing)
        advance(60.seconds)
        assertEquals(1, message.started, "nothing plays during the call")

        player.resume()
        assertTrue(alarm.playing)
        assertEquals(1f, alarm.gains.last())
        assertFalse(message.playing)
        advance(29.75.seconds)
        assertEquals(1, message.started)
        advance(STEP)
        assertEquals(2, message.started)
        player.stop(restoreVolume = true)
    }

    @Test
    fun `the same request again (a heartbeat) changes nothing, and after grace through play it starts over with the alarm`() {
        play()
        advance(31.seconds)
        play()
        assertTrue(player.isMixPlaying, "the same request keeps the message playing")
        assertEquals(1, recordings.opened.size)

        player.mute()
        play()
        assertFalse(player.isMixPlaying)
        assertEquals(1f, alarm.gains.last())
        advance(29.75.seconds)
        assertEquals(1, message.started)
        advance(STEP)
        assertEquals(2, message.started)
        player.stop(restoreVolume = true)
    }

    @Test
    fun `a message that fails while playing brings the alarm back at once, alone for the rest of the session`() {
        play()
        advance(31.seconds)
        assertTrue(player.isMixPlaying)

        message.fail()

        assertFalse(player.isMixing)
        assertTrue(message.released)
        assertEquals(1f, alarm.gains.last(), "the alarm sound is back at full gain at once")
        assertEquals(listOf(LogEvent.OperationFailed("mix motivation", "the message failed while playing")), mixFailures())
        advance(60.seconds)
        assertEquals(1f, alarm.gains.last())

        // The snooze ends: the same session's next ring plays the alarm sound alone.
        player.stop(restoreVolume = false)
        play()
        advance(40.seconds)
        assertFalse(player.isMixing)
        assertEquals(1, recordings.opened.size, "never opened again for that session")
        assertEquals(1, sourceReads.size)

        // Another session mixes again.
        player.stop(restoreVolume = false)
        play(request = MixRequest("session-2", "r1"))
        assertTrue(player.isMixing)
        player.stop(restoreVolume = true)
    }

    @Test
    fun `a missing recording plays the alarm sound alone, logged by code only`() {
        sourceFile = null

        play()

        assertFalse(player.isMixing)
        assertTrue(recordings.opened.isEmpty())
        assertEquals(listOf(LogEvent.OperationFailed("mix motivation", "the message is missing")), mixFailures())
        advance(40.seconds)
        assertEquals(1f, alarm.gains.last())
        assertTrue(alarm.playing)
        player.stop(restoreVolume = true)
    }

    @Test
    fun `a recording source that throws, an open that throws or a prepare error plays the alarm sound alone`() {
        sourceError = IllegalStateException("locked")
        play(request = MixRequest("session-a", "r1"))
        assertFalse(player.isMixing)
        player.stop(restoreVolume = false)

        sourceError = null
        recordings.failing = true
        play(request = MixRequest("session-b", "r1"))
        assertFalse(player.isMixing)
        player.stop(restoreVolume = false)

        recordings.failing = false
        recordings.failOnPrepare = true
        play(gradual = false, request = MixRequest("session-c", "r1"))
        assertFalse(player.isMixing)
        assertTrue(message.released)
        assertEquals(1f, player.gain)

        val causes = mixFailures().map { it.cause }
        assertEquals(4, causes.size, "$causes")
        assertTrue(causes.none { "/" in it || "r1" in it }, "never a name or a path: $causes")
        player.stop(restoreVolume = true)
    }

    @Test
    fun `a recording not prepared within 5 s plays the alarm sound alone`() {
        recordings.stalling = true
        play(gradual = false)

        advance(4.75.seconds)
        assertTrue(player.isMixing)
        advance(STEP)

        assertFalse(player.isMixing)
        assertTrue(message.released)
        assertEquals(listOf(LogEvent.OperationFailed("mix motivation", "the message did not prepare in time")), mixFailures())
        advance(40.seconds)
        assertEquals(1f, alarm.gains.last())
        player.stop(restoreVolume = true)
    }

    @Test
    fun `a recording with no length never plays`() {
        recordings.durationMs = 0
        play()

        assertFalse(player.isMixing)
        advance(40.seconds)
        assertEquals(1f, alarm.gains.last())
        player.stop(restoreVolume = true)
    }

    @Test
    fun `stop, a new request, the default switch and the emergency ring release the message`() {
        play()
        val first = message
        player.stop(restoreVolume = false)
        assertTrue(first.released)

        play()
        val second = message
        player.play(Alarm.DEFAULT_SOUND_REF, volumePercent = 60, gradual = true, rampStartPercent = 20, mix = mix)
        dispatcher.scheduler.runCurrent()
        assertTrue(second.released, "a new request starts the ring over")
        assertTrue(player.isMixing, "with its own message")

        val third = message
        advance(31.seconds)
        assertTrue(player.isMixPlaying)
        player.switchToDefault()
        assertTrue(third.released, "the default switch keeps the ring, not the message")
        assertFalse(player.isMixing)
        assertEquals(1f, alarm.gains.last(), "the default plays alone at full gain")
        assertTrue(player.isRinging)

        player.playDefault(80)
        assertFalse(player.isMixing)
        assertTrue(recordings.opened.all { it.released })
        player.stop(restoreVolume = true)
    }

    @Test
    fun `a ring without a mix request plays the alarm sound only and never reads a recording`() {
        play(request = null)

        advance(40.seconds)

        assertFalse(player.isMixing)
        assertTrue(sourceReads.isEmpty())
        assertTrue(recordings.opened.isEmpty())
        player.stop(restoreVolume = true)
    }

    @Test
    fun `the alarm sound's fallback keeps working while the message holds its gain at 0`() {
        play()
        advance(31.seconds)
        assertTrue(player.isMixPlaying)

        alarm.failWhilePlaying()

        val fallback = alarm
        assertEquals(AlarmSound.SystemAlarm, fallback.sound)
        assertEquals(0f, fallback.gains.last(), "the new sound joins silently while the message plays")
        assertTrue(player.isMixPlaying)
        message.complete()
        assertEquals(1f, fallback.gains.last())
        player.stop(restoreVolume = true)
    }

    @Test
    fun `a ring that opens during a call waits for its end, then the alarm sound comes first`() {
        player.play(Alarm.DEFAULT_SOUND_REF, 80, gradual = true, rampStartPercent = 20, paused = true, mix = mix)
        dispatcher.scheduler.runCurrent()

        advance(45.seconds)
        assertEquals(0, message.started, "silent during the call")
        player.resume()
        advance(29.75.seconds)
        assertEquals(0, message.started)
        advance(STEP)
        assertEquals(1, message.started)
        player.stop(restoreVolume = true)
    }

    @Test
    fun `the MediaPlayer recording adapter plays the file once on the alarm usage, from the start each time`() {
        ShadowMediaPlayer.setMediaInfoProvider { ShadowMediaPlayer.MediaInfo(8_000, 0) }
        val created = mutableListOf<MediaPlayer>()
        ShadowMediaPlayer.setCreateListener { mediaPlayer, _ -> created += mediaPlayer }
        var prepared = 0
        var completed = 0
        var errors = 0

        val playback = MediaPlayerRecordingPlaybackFactory(context).open(messageFile, { prepared++ }, { completed++ }) { errors++ }
        assertEquals(0L, playback.durationMs, "unknown before it is prepared")
        shadowOf(Looper.getMainLooper()).idle()

        val shadow: ShadowMediaPlayer = Shadow.extract(created.single())
        assertEquals(1, prepared)
        assertEquals(AudioAttributes.USAGE_ALARM, shadow.audioAttributes.usage)
        assertFalse(created.single().isLooping, "once, never looping")
        assertEquals(DataSource.toDataSource(messageFile.absolutePath), shadow.dataSource)
        assertEquals(8_000L, playback.durationMs)
        playback.setGain(1f)
        playback.start()
        assertTrue(shadow.isReallyPlaying)
        playback.pause()
        playback.start()
        assertEquals(0, created.single().currentPosition, "from the start")
        shadow.invokeErrorListener(MediaPlayer.MEDIA_ERROR_UNKNOWN, 0)
        assertEquals(1, errors)
        playback.release()
        assertEquals(0L, playback.durationMs)
        assertEquals(0, completed, "it never played to its end here")
    }

    @Test
    fun `the MediaPlayer recording adapter releases a player it cannot open`() {
        val created = mutableListOf<MediaPlayer>()
        ShadowMediaPlayer.setCreateListener { mediaPlayer, _ -> created += mediaPlayer }
        ShadowMediaPlayer.addException(DataSource.toDataSource(messageFile.absolutePath), IOException("broken"))

        val failed = runCatching { MediaPlayerRecordingPlaybackFactory(context).open(messageFile, {}, {}, {}) }

        assertTrue(failed.exceptionOrNull() is IOException)
        val shadow: ShadowMediaPlayer = Shadow.extract(created.single())
        assertEquals(ShadowMediaPlayer.State.END, shadow.state, "released")
    }

    @Test
    fun `a message prepared after open returned survives the 5 s watchdog and starts at 30 s`() {
        recordings.deferPrepare = true
        play()
        assertTrue(player.isMixing)

        advance(2.seconds)
        message.prepare()
        advance(4.seconds)
        assertTrue(player.isMixing, "prepared at 2 s: the watchdog never fires")
        assertTrue(mixFailures().isEmpty(), "${mixFailures()}")

        advance(23.75.seconds)
        assertEquals(0, message.started)
        advance(STEP)
        assertEquals(1, message.started, "30 s after the ring's start")
        assertTrue(player.isMixPlaying)
        player.stop(restoreVolume = true)
    }

    @Test
    fun `a message whose start throws fails the mix, the alarm sound stays at full gain and the ramp loop goes on`() {
        play()
        message.throwOnStart = true

        advance(30.seconds)

        assertFalse(player.isMixing)
        assertFalse(player.isMixPlaying)
        assertEquals(1f, alarm.gains.last(), "never left at gain 0")
        assertTrue(message.released)
        assertEquals(listOf(LogEvent.OperationFailed("mix motivation", "the message failed: IllegalStateException")), mixFailures())
        player.stop(restoreVolume = true)
    }

    @Test
    fun `a message whose pause throws in grace fails the mix, and grace still mutes the alarm sound`() {
        play()
        advance(31.seconds)
        assertTrue(player.isMixPlaying)
        message.throwOnPause = true

        player.mute()

        assertFalse(player.isMixing)
        assertTrue(message.released, "released, so it no longer plays")
        assertEquals(0f, alarm.gains.last(), "grace is silent")
        player.unmuteTo(80)
        assertEquals(1f, alarm.gains.last())
        advance(40.seconds)
        assertFalse(player.isMixing, "alarm sound alone for the rest of the session")
        player.stop(restoreVolume = true)
    }

    @Test
    fun `a message whose pause throws during a call fails the mix and the call still pauses the alarm sound`() {
        play()
        advance(31.seconds)
        message.throwOnPause = true

        player.pause()

        assertFalse(player.isMixing)
        assertFalse(alarm.playing)
        player.resume()
        assertEquals(1f, alarm.gains.last())
        player.stop(restoreVolume = true)
    }

    @Test
    fun `a recording longer than the longest message is held at most 60 s plus the slack`() {
        recordings.durationMs = 10 * 60_000L
        play(gradual = false)
        advance(30.seconds)
        assertTrue(player.isMixPlaying)

        advance(60.seconds)
        assertTrue(player.isMixPlaying, "up to 60 s")
        advance(1.seconds)

        assertFalse(player.isMixPlaying, "the alarm sound is back")
        assertEquals(1f, alarm.gains.last())
        player.stop(restoreVolume = true)
    }

    private companion object {
        val STEP = 250.milliseconds
    }
}
