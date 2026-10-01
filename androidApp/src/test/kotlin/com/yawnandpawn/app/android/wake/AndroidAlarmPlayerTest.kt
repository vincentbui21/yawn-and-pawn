package com.yawnandpawn.app.android.wake

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.yawnandpawn.app.R
import com.yawnandpawn.app.StopAppRule
import com.yawnandpawn.app.android.ApplicationScope
import com.yawnandpawn.app.android.sound.LibrarySoundResolver
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
import java.io.IOException
import kotlin.math.roundToInt
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/** Story 1.14: the one alarm player, over a fake playback seam (and the real `MediaPlayer` adapter once). */
@RunWith(RobolectricTestRunner::class)
class AndroidAlarmPlayerTest {
    @get:Rule(order = 0)
    val stopApp = StopAppRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val audio = context.getSystemService(AudioManager::class.java)
    private val logger = FakeLogger()
    private val clock = FakeMonotonicClock(elapsedMillis = 1_000)
    private val playbacks = FakePlaybackFactory()
    private val volume = AlarmVolume(context, logger)
    private val chosen = AlarmSound.File("content://sounds/rain")
    private val resolver =
        SoundResolver { ref ->
            when (ref) {
                Alarm.DEFAULT_SOUND_REF -> AlarmSound.Default
                "test:rain" -> chosen
                else -> null
            }
        }

    // The ramp loop and the retry run on this dispatcher only when a test advances it.
    private val dispatcher = StandardTestDispatcher()
    private val player = AndroidAlarmPlayer(playbacks, resolver, volume, clock, CoroutineScope(dispatcher), logger)

    /**
     * The app's own start (restore, then a volume restore when Idle) runs on ApplicationScope; under load it could
     * otherwise restore the alarm volume in the middle of a test.
     */
    @Before
    fun awaitAppStart() = GlobalContext.get().get<ApplicationScope>().awaitChildren()

    private fun advance(duration: kotlin.time.Duration) {
        clock.advanceBy(duration)
        dispatcher.scheduler.advanceTimeBy(duration)
        dispatcher.scheduler.runCurrent()
    }

    private val maxAlarm: Int
        get() = audio.getStreamMaxVolume(AudioManager.STREAM_ALARM)

    private fun alarmVolume() = audio.getStreamVolume(AudioManager.STREAM_ALARM)

    private fun play(
        ref: String = Alarm.DEFAULT_SOUND_REF,
        volumePercent: Int = 80,
        gradual: Boolean = false,
    ) = player.play(ref, volumePercent, gradual, rampStartPercent = 20)

    @Test
    fun `a ring sets the alarm stream to the set volume and stopping at the session end restores the user volume`() {
        audio.setStreamVolume(AudioManager.STREAM_ALARM, 2, 0)

        play(volumePercent = 50)

        assertEquals((maxAlarm * 0.5).roundToInt(), alarmVolume())
        assertEquals(2, volume.saved, "the user volume is saved")
        player.stop(restoreVolume = true)
        assertEquals(2, alarmVolume())
        assertNull(volume.saved)
        assertNull(player.sound)
    }

    @Test
    fun `a re-ring keeps the first saved user volume, and a 0 percent alarm still rings at the lowest step`() {
        audio.setStreamVolume(AudioManager.STREAM_ALARM, 3, 0)
        play(volumePercent = 100)
        player.stop(restoreVolume = false)

        play(volumePercent = 0)

        assertTrue(alarmVolume() >= 1, "never silent at 0 percent: ${alarmVolume()}")
        assertEquals(3, volume.saved)
        player.stop(restoreVolume = true)
        assertEquals(3, alarmVolume())
    }

    @Test
    fun `with gradual volume the gain is 0_2 at the start, 0_6 at 15 s and 1_0 at 30 s and 45 s`() {
        play(gradual = true)
        val playback = checkNotNull(playbacks.current)
        assertEquals(0.2f, player.gain, 1e-6f)
        assertEquals(0.2f, playback.gains.first(), 1e-6f, "the first frame already plays at the ramp start")

        listOf(15 to 0.6f, 15 to 1.0f, 15 to 1.0f).forEach { (seconds, expected) ->
            clock.advanceBy(seconds.seconds)
            player.updateGain()
            assertEquals(expected, player.gain, 1e-6f)
        }
        assertEquals(1.0f, playback.gains.last(), 1e-6f)
    }

    @Test
    fun `without gradual volume the first frame plays at full gain`() {
        play(gradual = false)

        assertEquals(listOf(1.0f), checkNotNull(playbacks.current).gains)
        assertTrue(checkNotNull(playbacks.current).playing)
    }

    @Test
    fun `the same request again changes nothing and a changed request starts the ring over`() {
        play()
        play()
        assertEquals(1, playbacks.opened.size)
        assertEquals(1, checkNotNull(playbacks.current).started)

        play(volumePercent = 60)

        assertEquals(2, playbacks.opened.size)
        assertTrue(playbacks.opened.first().released)
    }

    @Test
    fun `mute silences the open sound, unmute returns to full gain, and pause and resume keep the same sound`() {
        play(gradual = true)
        val playback = checkNotNull(playbacks.current)

        player.mute()
        assertEquals(0f, player.gain)
        player.unmute()
        assertEquals(1f, player.gain, "the grace window ends at the set volume")
        player.pause()
        assertTrue(!playback.playing && player.isPaused)
        play(gradual = true)
        assertTrue(playback.playing, "SoundAt again resumes")
        assertEquals(1, playbacks.opened.size)
    }

    @Test
    fun `an unknown sound reference plays the default and the fallback is logged without the reference`() {
        play(ref = "builtin:rain")

        assertEquals(AlarmSound.Default, player.sound)
        assertEquals(
            listOf<LogEvent>(LogEvent.SoundFellBack("unknown sound reference")),
            logger.events,
        )
        assertTrue(logger.events.none { "builtin:rain" in it.toString() })
    }

    @Test
    fun `a chosen sound that cannot be opened is replaced by the default in the same ring`() {
        playbacks.failing += chosen

        play(ref = "test:rain")

        assertEquals(AlarmSound.Default, player.sound)
        assertTrue(checkNotNull(playbacks.current).playing)
        assertEquals(listOf<LogEvent>(LogEvent.SoundFellBack("could not open the sound: IOException")), logger.events)
    }

    @Test
    fun `a sound that fails while ringing is replaced by the default, and a failing default by the phone alarm sound`() {
        play(ref = "test:rain")
        val broken = checkNotNull(playbacks.current)

        broken.failWhilePlaying()

        assertTrue(broken.released)
        assertEquals(AlarmSound.Default, player.sound)
        assertTrue(checkNotNull(playbacks.current).playing, "never silent")
        checkNotNull(playbacks.current).failWhilePlaying()
        assertEquals(AlarmSound.SystemAlarm, player.sound)
        assertEquals(
            List<LogEvent>(2) { LogEvent.SoundFellBack("the sound failed while ringing") },
            logger.events,
        )
    }

    @Test
    fun `when no sound at all can be opened the failure is logged and the default is tried again after 5 s`() {
        playbacks.failing += listOf(chosen, AlarmSound.Default, AlarmSound.SystemAlarm)

        play(ref = "test:rain")

        assertNull(player.sound)
        assertEquals(
            LogEvent.OperationFailed("play alarm sound", "no sound could be opened; trying the default again"),
            logger.events.last(),
        )
        playbacks.failing.clear()
        advance(4.seconds)
        assertNull(player.sound)
        advance(1.seconds)
        assertEquals(AlarmSound.Default, player.sound, "never silent for good")
        assertTrue(checkNotNull(playbacks.current).playing)
    }

    @Test
    fun `a long ring whose sound keeps failing mid-ring keeps reopening it`() {
        play()

        repeat(20) { round ->
            advance(11.seconds)
            checkNotNull(playbacks.current).failWhilePlaying()
            assertTrue(checkNotNull(playbacks.current).playing, "still ringing after failure ${round + 1}")
        }
        assertEquals(21, playbacks.opened.size)
    }

    @Test
    fun `the ramp loop raises the gain by itself every 250 ms and stops at full gain`() {
        play(gradual = true)
        val playback = checkNotNull(playbacks.current)

        advance(15.seconds)
        assertEquals(0.6f, player.gain, 0.01f, "the loop updated the gain without a manual step")
        advance(15.seconds)
        assertEquals(1f, player.gain)
        val steps = playback.gains.size
        advance(30.seconds)
        assertEquals(steps, playback.gains.size, "the loop ended once the ramp was done")
    }

    @Test
    fun `restoring the volume at app start skips a ring that already started`() {
        audio.setStreamVolume(AudioManager.STREAM_ALARM, 2, 0)
        play(volumePercent = 100)

        player.restoreVolumeIfSilent()

        assertEquals(maxAlarm, alarmVolume(), "the ring keeps its volume")
        player.stop(restoreVolume = false)
        player.restoreVolumeIfSilent()
        assertEquals(2, alarmVolume())
    }

    @Test
    fun `after a crash the open sound switches to the default and keeps its mute`() {
        play(ref = "test:rain")
        player.mute()

        player.switchToDefault()

        assertEquals(AlarmSound.Default, player.sound)
        assertTrue(player.isMuted)
        assertEquals(0f, player.gain)
        player.switchToDefault()
        assertEquals(2, playbacks.opened.size, "already the default: nothing reopens")
    }

    @Test
    fun `the emergency ring plays the default at full gain`() {
        player.playDefault(volumePercent = 70)

        assertEquals(AlarmSound.Default, player.sound)
        assertEquals(1f, player.gain)
        assertEquals((maxAlarm * 0.7).roundToInt(), alarmVolume())
    }

    /** The MediaPlayers the real adapter created, in order. */
    private fun recordMediaPlayers(): MutableList<MediaPlayer> {
        ShadowMediaPlayer.setMediaInfoProvider { ShadowMediaPlayer.MediaInfo(3_000, 0) }
        val created = mutableListOf<MediaPlayer>()
        ShadowMediaPlayer.setCreateListener { mediaPlayer, _ -> created += mediaPlayer }
        return created
    }

    private fun MediaPlayer.shadow(): ShadowMediaPlayer = Shadow.extract(this)

    /** Runs what `prepareAsync` posted to the main looper (the prepared callback). */
    private fun prepared() = shadowOf(Looper.getMainLooper()).idle()

    @Test
    fun `the MediaPlayer adapter prepares asynchronously and plays looping on the alarm usage with sonification content`() {
        val created = recordMediaPlayers()

        val playback = MediaPlayerPlaybackFactory(context).open(AlarmSound.Default, {}, {})
        playback.setGain(0.2f)
        playback.start()

        val shadow = created.single().shadow()
        assertEquals(ShadowMediaPlayer.State.PREPARING, shadow.state, "open never blocks on prepare")
        prepared()
        assertEquals(AudioAttributes.USAGE_ALARM, shadow.audioAttributes.usage)
        assertEquals(AudioAttributes.CONTENT_TYPE_SONIFICATION, shadow.audioAttributes.contentType)
        assertTrue(created.single().isLooping)
        assertTrue(shadow.isReallyPlaying, "the start asked for before prepared runs once prepared")
        assertEquals(0.2f, shadow.leftVolume, 1e-6f)
        assertEquals("android.resource://${context.packageName}/${R.raw.alarm_default}", shadow.sourceUri.toString())
        playback.release()
    }

    @Test
    fun `the MediaPlayer adapter keeps a pause asked for while preparing and plays on the next start`() {
        val created = recordMediaPlayers()
        var preparedCalls = 0

        // At ring start during a call: start, then pause, both before the player is prepared.
        val playback = MediaPlayerPlaybackFactory(context).open(AlarmSound.Default, { preparedCalls++ }, {})
        playback.start()
        playback.pause()
        prepared()

        val shadow = created.single().shadow()
        assertEquals(1, preparedCalls)
        assertEquals(ShadowMediaPlayer.State.PREPARED, shadow.state, "still paused once prepared")
        playback.start()
        assertTrue(shadow.isReallyPlaying)
        playback.release()
    }

    @Test
    fun `a sound that never reports prepared is replaced by the default after 5 s`() {
        playbacks.stalling += chosen

        play(ref = "test:rain")
        assertEquals(chosen, player.sound)
        advance(4.seconds)
        assertEquals(chosen, player.sound, "still waiting")
        advance(1.seconds)

        assertEquals(AlarmSound.Default, player.sound)
        assertTrue(checkNotNull(playbacks.current).playing, "never silent")
        assertTrue(playbacks.opened.first().released)
        assertEquals(listOf<LogEvent>(LogEvent.SoundFellBack("the sound did not prepare in time")), logger.events)
        advance(10.seconds)
        assertEquals(AlarmSound.Default, player.sound, "a prepared sound has no watchdog")
    }

    @Test
    fun `time spent preparing does not count as healthy play`() {
        playbacks.stalling += listOf(AlarmSound.Default, AlarmSound.SystemAlarm)

        play()
        // Each stalled open fails after 5 s; the default and the phone alarm take turns, without a fresh set of opens.
        advance(30.seconds)

        assertEquals(6, playbacks.opened.size, "the open budget of one ring, not reset by unprepared time")
        assertNull(player.sound)
    }

    @Test
    fun `a ring start runs the ring-start hook with the ring already on`() {
        var ringingInHook: Boolean? = null
        lateinit var hooked: AndroidAlarmPlayer
        hooked =
            AndroidAlarmPlayer(playbacks, resolver, volume, clock, CoroutineScope(dispatcher), logger) {
                ringingInHook = hooked.isRinging
            }

        hooked.play(Alarm.DEFAULT_SOUND_REF, 80, gradual = false, rampStartPercent = 20)

        assertEquals(true, ringingInHook)
        hooked.stop(restoreVolume = true)
    }

    @Test
    fun `the MediaPlayer adapter reports a playback error and never pauses a player that did not start`() {
        val created = recordMediaPlayers()
        var errors = 0

        val playback = MediaPlayerPlaybackFactory(context).open(AlarmSound.Default, {}) { errors++ }
        prepared()
        val shadow = created.single().shadow()
        playback.pause()
        assertEquals(ShadowMediaPlayer.State.PREPARED, shadow.state, "a prepared player is not paused into the Error state")
        playback.start()
        shadow.invokeErrorListener(MediaPlayer.MEDIA_ERROR_UNKNOWN, 0)

        assertEquals(1, errors)
        playback.release()
    }

    @Test
    fun `a library sound opens its raw resource and a phone ringtone its content uri`() {
        val created = recordMediaPlayers()
        val factory = MediaPlayerPlaybackFactory(context)

        factory.open(AlarmSound.BuiltIn(R.raw.alarm_chimes), {}, {}).release()
        factory.open(AlarmSound.File("content://media/internal/audio/media/7"), {}, {}).release()

        assertEquals("android.resource://${context.packageName}/${R.raw.alarm_chimes}", created[0].shadow().sourceUri.toString())
        assertEquals("content://media/internal/audio/media/7", created[1].shadow().sourceUri.toString())
    }

    private fun realPlayer() =
        AndroidAlarmPlayer(
            MediaPlayerPlaybackFactory(context),
            LibrarySoundResolver(),
            volume,
            clock,
            CoroutineScope(dispatcher),
            logger,
        )

    @Test
    fun `a ringtone uri that no longer resolves rings the default in the same ring`() {
        val created = recordMediaPlayers()
        val gone = Uri.parse("content://media/internal/audio/media/404")
        ShadowMediaPlayer.addException(DataSource.toDataSource(context, gone), IOException("gone"))
        val player = realPlayer()

        player.play("system:Gone|$gone", volumePercent = 80, gradual = false, rampStartPercent = 20)
        prepared()

        assertEquals(AlarmSound.Default, player.sound)
        assertTrue(created.last().shadow().isReallyPlaying, "never silent")
        assertEquals(listOf<LogEvent>(LogEvent.SoundFellBack("could not open the sound: IOException")), logger.events)
        assertTrue(logger.events.none { "404" in it.toString() || "Gone" in it.toString() }, "no uri or name in the log")
        player.stop(restoreVolume = true)
    }

    @Test
    fun `a MediaPlayer error mid-ring switches to the default`() {
        val created = recordMediaPlayers()
        val player = realPlayer()

        player.play("builtin:chimes", volumePercent = 80, gradual = false, rampStartPercent = 20)
        prepared()
        assertEquals(AlarmSound.BuiltIn(R.raw.alarm_chimes), player.sound)
        assertTrue(created.single().shadow().isReallyPlaying)

        created.single().shadow().invokeErrorListener(MediaPlayer.MEDIA_ERROR_SERVER_DIED, 0)
        prepared()

        assertEquals(AlarmSound.Default, player.sound)
        assertTrue(created.last().shadow().isReallyPlaying)
        assertEquals(listOf<LogEvent>(LogEvent.SoundFellBack("the sound failed while ringing")), logger.events)
        player.stop(restoreVolume = true)
    }

    @Test
    fun `a sound that fails while it prepares asynchronously is replaced by the default`() {
        val created = recordMediaPlayers()
        val player = realPlayer()

        player.play("builtin:bell", volumePercent = 80, gradual = false, rampStartPercent = 20)
        created.single().shadow().invokeErrorListener(MediaPlayer.MEDIA_ERROR_UNKNOWN, MediaPlayer.MEDIA_ERROR_MALFORMED)
        prepared()

        assertEquals(AlarmSound.Default, player.sound)
        assertTrue(created.last().shadow().isReallyPlaying)
        player.stop(restoreVolume = true)
    }

    @Test
    fun `the default resource is bundled and always opens`() {
        context.resources.openRawResourceFd(R.raw.alarm_default).use { assertTrue(it.length > 0) }
        assertEquals(AlarmSound.Default, LibrarySoundResolver().resolve(Alarm.DEFAULT_SOUND_REF))
    }

    @Test
    fun `the player reports a ring as ringing until it stops`() {
        assertTrue(!player.isRinging)
        play()
        assertTrue(player.isRinging)
        player.stop(restoreVolume = true)
        assertTrue(!player.isRinging)
    }
}
