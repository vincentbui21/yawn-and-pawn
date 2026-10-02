package com.yawnandpawn.app.android.sound

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
import com.yawnandpawn.app.android.wake.AlarmVolume
import com.yawnandpawn.app.awaitChildren
import com.yawnandpawn.app.core.sound.SoundRef
import com.yawnandpawn.app.testing.FakeLogger
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
import java.time.Duration
import kotlin.math.roundToInt
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Story 1.17: the Sound picker's preview player, over Robolectric's `MediaPlayer`. */
@RunWith(RobolectricTestRunner::class)
class AndroidSoundPreviewTest {
    @get:Rule
    val stopApp = StopAppRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val audio = context.getSystemService(AudioManager::class.java)
    private val logger = FakeLogger()
    private val volume = AlarmVolume(context, logger)
    private var ringing = false
    private val created = mutableListOf<MediaPlayer>()
    private val preview = AndroidSoundPreview(context, volume, LibrarySoundResolver(), { ringing }, logger)
    private val bell = SoundRef.BuiltIn("bell")

    /**
     * The app's own start (restore, then a volume restore when Idle) runs on ApplicationScope; under load it could
     * otherwise restore the saved alarm volume in the middle of a test (as in AndroidAlarmPlayerTest).
     */
    @Before
    fun awaitAppStart() = GlobalContext.get().get<ApplicationScope>().awaitChildren()

    init {
        ShadowMediaPlayer.setMediaInfoProvider { ShadowMediaPlayer.MediaInfo(2_000, 0) }
        ShadowMediaPlayer.setCreateListener { mediaPlayer, _ -> created += mediaPlayer }
    }

    private val maxAlarm: Int
        get() = audio.getStreamMaxVolume(AudioManager.STREAM_ALARM)

    private fun alarmVolume() = audio.getStreamVolume(AudioManager.STREAM_ALARM)

    private fun MediaPlayer.shadow(): ShadowMediaPlayer = Shadow.extract(this)

    private fun idle(duration: Duration = Duration.ZERO) = shadowOf(Looper.getMainLooper()).idleFor(duration)

    @Test
    fun `a preview plays once on the alarm usage with the alarm stream at the alarm volume`() {
        audio.setStreamVolume(AudioManager.STREAM_ALARM, 2, 0)

        preview.play(bell, volumePercent = 50)
        idle()

        val shadow = created.single().shadow()
        assertEquals(AudioAttributes.USAGE_ALARM, shadow.audioAttributes.usage)
        assertFalse(created.single().isLooping, "played once")
        assertTrue(shadow.isReallyPlaying)
        assertEquals("android.resource://${context.packageName}/${R.raw.alarm_bell}", shadow.sourceUri.toString())
        assertEquals((maxAlarm * 0.5).roundToInt(), alarmVolume())
        assertEquals("builtin:bell", preview.previewing.value)
    }

    @Test
    fun `the sound ending by itself clears the preview and puts the user volume back`() {
        audio.setStreamVolume(AudioManager.STREAM_ALARM, 2, 0)
        preview.play(bell, volumePercent = 100)
        idle()

        idle(Duration.ofSeconds(3))

        assertNull(preview.previewing.value)
        assertEquals(2, alarmVolume())
        assertNull(volume.saved)
    }

    @Test
    fun `a second preview stops the first, and stop puts the user volume back`() {
        audio.setStreamVolume(AudioManager.STREAM_ALARM, 3, 0)
        preview.play(bell, volumePercent = 100)
        idle()

        preview.play(SoundRef.BuiltIn("sonar"), volumePercent = 100)
        idle()

        assertEquals(ShadowMediaPlayer.State.END, created[0].shadow().state, "the first was released")
        assertTrue(created[1].shadow().isReallyPlaying)
        assertEquals("builtin:sonar", preview.previewing.value)
        assertEquals(3, volume.saved, "the user volume saved by the first preview is kept")
        preview.stop()
        assertNull(preview.previewing.value)
        assertEquals(3, alarmVolume())
        assertEquals(ShadowMediaPlayer.State.END, created[1].shadow().state)
    }

    @Test
    fun `the volume slider moves the stream while a preview plays`() {
        preview.setVolume(100)
        assertNull(volume.saved, "no preview, no change")
        preview.play(bell, volumePercent = 20)

        preview.setVolume(100)

        assertEquals(maxAlarm, alarmVolume())
        preview.stop()
    }

    @Test
    fun `a sound that cannot be opened or fails clears the preview without naming it`() {
        val gone = Uri.parse("content://media/internal/audio/media/404")
        ShadowMediaPlayer.addException(DataSource.toDataSource(context, gone), IOException("gone"))

        preview.play(SoundRef.System(gone.toString(), "Gone"), volumePercent = 80)
        assertNull(preview.previewing.value)

        preview.play(bell, volumePercent = 80)
        created.last().shadow().invokeErrorListener(MediaPlayer.MEDIA_ERROR_UNKNOWN, 0)
        assertNull(preview.previewing.value)
        preview.play(SoundRef.BuiltIn("birds"), volumePercent = 80)
        assertNull(preview.previewing.value)
        assertEquals(3, logger.events.size)
        assertTrue(logger.events.none { "404" in it.toString() || "Gone" in it.toString() || "birds" in it.toString() })
        assertNull(volume.saved, "the user volume is back")
    }

    @Test
    fun `while an alarm rings a preview never plays and never touches the volume`() {
        audio.setStreamVolume(AudioManager.STREAM_ALARM, 2, 0)
        ringing = true

        preview.play(bell, volumePercent = 100)
        preview.setVolume(100)

        assertTrue(created.isEmpty())
        assertNull(preview.previewing.value)
        assertEquals(2, alarmVolume())
        assertNull(volume.saved)
    }

    @Test
    fun `a ring that starts during a preview keeps the stream when the preview stops`() {
        audio.setStreamVolume(AudioManager.STREAM_ALARM, 2, 0)
        preview.play(bell, volumePercent = 50)
        idle()

        // The ring takes the stream (it keeps the user volume the preview saved) ...
        ringing = true
        volume.setForRing(100)
        preview.stop()

        assertEquals(maxAlarm, alarmVolume(), "the preview does not turn the ring down")
        assertEquals(2, volume.saved, "the ring restores the user volume when the session ends")
        volume.restore()
        assertEquals(2, alarmVolume())
    }
}
