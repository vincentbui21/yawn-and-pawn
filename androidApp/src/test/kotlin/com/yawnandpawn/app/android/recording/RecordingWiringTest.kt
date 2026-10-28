package com.yawnandpawn.app.android.recording

import android.media.MediaPlayer
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.yawnandpawn.app.StopAppRule
import com.yawnandpawn.app.YawnAndPawnApp
import com.yawnandpawn.app.android.wake.AndroidAlarmPlayer
import com.yawnandpawn.app.core.alarm.Alarm
import com.yawnandpawn.app.core.recording.PreviewItem
import com.yawnandpawn.app.core.recording.RecordingPreview
import com.yawnandpawn.app.data.recording.RecordingFiles
import com.yawnandpawn.app.restartKoin
import com.yawnandpawn.app.testing.aRecording
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowMediaPlayer
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Story 7.2: the app's own Koin graph keeps the Recordings preview out of a ring. */
@RunWith(RobolectricTestRunner::class)
class RecordingWiringTest {
    @get:Rule
    val stopApp = StopAppRule()

    private val app: YawnAndPawnApp = ApplicationProvider.getApplicationContext()
    private val created = mutableListOf<MediaPlayer>()

    init {
        ShadowMediaPlayer.setMediaInfoProvider { ShadowMediaPlayer.MediaInfo(2_000, 0) }
        ShadowMediaPlayer.setCreateListener { player, _ -> created += player }
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    @Test
    fun `a ring stops a playing Recordings preview, and a preview during the ring plays nothing`() {
        restartKoin(app)
        val koin = GlobalContext.get()
        val player = koin.get<AndroidAlarmPlayer>()
        val preview = koin.get<RecordingPreview>()
        val message = aRecording(id = "r1")
        val folder = koin.get<RecordingFiles>().folder().apply { mkdirs() }
        File(folder, message.fileName).writeText("audio")

        preview.play(PreviewItem.Saved(message))
        idle()
        val previewPlayer = created.single()
        assertTrue(Shadow.extract<ShadowMediaPlayer>(previewPlayer).isReallyPlaying)
        assertEquals("r1", preview.playing.value?.key)

        player.play(Alarm.DEFAULT_SOUND_REF, volumePercent = 100, gradual = false, rampStartPercent = 20)
        idle()

        assertNull(preview.playing.value, "the ring stopped the preview")
        assertEquals(ShadowMediaPlayer.State.END, Shadow.extract<ShadowMediaPlayer>(previewPlayer).state)

        preview.play(PreviewItem.Saved(message))
        idle()

        assertNull(preview.playing.value, "no preview during a ring")
        assertEquals(2, created.size, "only the preview and the ring opened a player")
        player.stop(restoreVolume = true)
    }
}
