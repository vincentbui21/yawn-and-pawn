package com.yawnandpawn.app.android.sound

import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.yawnandpawn.app.StopAppRule
import com.yawnandpawn.app.YawnAndPawnApp
import com.yawnandpawn.app.android.wake.AndroidAlarmPlayer
import com.yawnandpawn.app.core.alarm.Alarm
import com.yawnandpawn.app.core.sound.SoundPreview
import com.yawnandpawn.app.core.sound.SoundRef
import com.yawnandpawn.app.restartKoin
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowMediaPlayer
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Story 1.17: the app's own Koin graph keeps the Sound preview out of a ring. */
@RunWith(RobolectricTestRunner::class)
class SoundWiringTest {
    @get:Rule
    val stopApp = StopAppRule()

    private val app: YawnAndPawnApp = ApplicationProvider.getApplicationContext()
    private val audio = app.getSystemService(AudioManager::class.java)
    private val created = mutableListOf<MediaPlayer>()

    init {
        ShadowMediaPlayer.setMediaInfoProvider { ShadowMediaPlayer.MediaInfo(2_000, 0) }
        ShadowMediaPlayer.setCreateListener { player, _ -> created += player }
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun alarmVolume() = audio.getStreamVolume(AudioManager.STREAM_ALARM)

    @Test
    fun `a ring stops a playing preview, and a preview during the ring is ignored and leaves the stream alone`() {
        restartKoin(app)
        val koin = GlobalContext.get()
        val player = koin.get<AndroidAlarmPlayer>()
        val preview = koin.get<SoundPreview>()
        audio.setStreamVolume(AudioManager.STREAM_ALARM, 2, 0)
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_ALARM)

        preview.play(SoundRef.BuiltIn("bell"), volumePercent = 50)
        idle()
        val previewPlayer = created.single()
        assertTrue(Shadow.extract<ShadowMediaPlayer>(previewPlayer).isReallyPlaying)

        player.play(Alarm.DEFAULT_SOUND_REF, volumePercent = 100, gradual = false, rampStartPercent = 20)
        idle()

        assertNull(preview.previewing.value, "the ring stopped the preview")
        assertEquals(ShadowMediaPlayer.State.END, Shadow.extract<ShadowMediaPlayer>(previewPlayer).state)
        assertEquals(max, alarmVolume(), "the preview's stop did not restore the user volume mid-ring")

        preview.play(SoundRef.BuiltIn("sonar"), volumePercent = 10)
        preview.setVolume(10)
        idle()

        assertNull(preview.previewing.value, "no preview during a ring")
        assertEquals(2, created.size, "only the preview and the ring opened a player")
        assertEquals(max, alarmVolume(), "the stream volume is unchanged")
        player.stop(restoreVolume = true)
        assertEquals(2, alarmVolume(), "the ring's end restores the volume the preview saved")
    }
}
