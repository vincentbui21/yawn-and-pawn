package com.yawnandpawn.app.android.recording

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.yawnandpawn.app.StopAppRule
import com.yawnandpawn.app.core.log.LogEvent
import com.yawnandpawn.app.core.recording.PreviewItem
import com.yawnandpawn.app.core.recording.PreviewPlayback
import com.yawnandpawn.app.core.recording.RecordedTake
import com.yawnandpawn.app.data.recording.RecordingFiles
import com.yawnandpawn.app.testing.FakeLogger
import com.yawnandpawn.app.testing.aRecording
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowMediaPlayer
import org.robolectric.shadows.util.DataSource
import java.io.File
import java.io.IOException
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Story 7.2: the Recordings preview player, over Robolectric's `MediaPlayer`. */
@RunWith(RobolectricTestRunner::class)
class AndroidRecordingPreviewTest {
    @get:Rule
    val stopApp = StopAppRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val folder = File(context.filesDir, "recordings-test").apply { mkdirs() }
    private val files = RecordingFiles { folder }
    private val logger = FakeLogger()
    private var ringing = false
    private val created = mutableListOf<MediaPlayer>()
    private val focus = TestFocus()
    private val preview = AndroidRecordingPreview(files, { ringing }, logger, focus)
    private val saved = aRecording(id = "r1").also { File(folder, it.fileName).writeText("audio") }
    private val take = RecordedTake("t1", 4_000).also { files.takeFile(it.takeId).writeText("audio") }

    init {
        ShadowMediaPlayer.setMediaInfoProvider { ShadowMediaPlayer.MediaInfo(2_000, 0) }
        ShadowMediaPlayer.setCreateListener { mediaPlayer, _ -> created += mediaPlayer }
    }

    private fun MediaPlayer.shadow(): ShadowMediaPlayer = Shadow.extract(this)

    private fun idle(duration: Duration = Duration.ZERO) = shadowOf(Looper.getMainLooper()).idleFor(duration)

    @Test
    fun `a saved message plays once on the media stream as speech, never touching a volume, and reports its position`() {
        val audio = context.getSystemService(AudioManager::class.java)
        val volumes = listOf(AudioManager.STREAM_MUSIC, AudioManager.STREAM_ALARM).map(audio::getStreamVolume)

        preview.play(PreviewItem.Saved(saved))
        idle()

        val shadow = created.single().shadow()
        assertEquals(AudioAttributes.USAGE_MEDIA, shadow.audioAttributes.usage)
        assertEquals(AudioAttributes.CONTENT_TYPE_SPEECH, shadow.audioAttributes.contentType)
        assertTrue(shadow.isReallyPlaying)
        assertEquals(DataSource.toDataSource(File(folder, "r1.m4a").absolutePath), shadow.dataSource)
        assertEquals("r1", preview.playing.value?.key)
        idle(Duration.ofMillis(1_000))
        assertTrue(assertNotNullPosition() > 0, "the position moves while it plays")
        assertEquals(volumes, listOf(AudioManager.STREAM_MUSIC, AudioManager.STREAM_ALARM).map(audio::getStreamVolume))
    }

    private fun assertNotNullPosition(): Long = requireNotNull(preview.playing.value).positionMs

    @Test
    fun `a take plays from its temporary file, and ends by itself`() {
        preview.play(PreviewItem.Take(take))
        idle()

        assertEquals(DataSource.toDataSource(files.takeFile("t1").absolutePath), created.single().shadow().dataSource)
        assertEquals(PreviewPlayback("t1"), preview.playing.value?.copy(positionMs = 0))
        idle(Duration.ofMillis(2_500))
        assertNull(preview.playing.value)
    }

    @Test
    fun `starting another preview stops the first, and stop stops it`() {
        preview.play(PreviewItem.Saved(saved))
        idle()
        preview.play(PreviewItem.Take(take))
        idle()

        assertEquals(2, created.size)
        assertEquals("t1", preview.playing.value?.key)
        preview.stop()
        assertNull(preview.playing.value)
    }

    @Test
    fun `nothing plays while an alarm rings, or for a missing file, and the logs hold no name or path`() {
        ringing = true
        preview.play(PreviewItem.Saved(saved))
        ringing = false
        preview.play(PreviewItem.Saved(aRecording(id = "gone")))
        preview.play(PreviewItem.Take(RecordedTake("../escape", 1_000)))
        idle()

        assertTrue(created.isEmpty())
        assertNull(preview.playing.value)
        assertEquals(3, logger.events.size)
        logger.events.forEach {
            val text = it.toString()
            assertTrue(it is LogEvent.OperationFailed && "m4a" !in text && "gone" !in text && folder.name !in text, text)
        }
    }

    @Test
    fun `a message that cannot be opened clears the preview`() {
        ShadowMediaPlayer.addException(
            DataSource.toDataSource(File(folder, "r1.m4a").absolutePath),
            IOException("broken"),
        )

        preview.play(PreviewItem.Saved(saved))
        idle()

        assertNull(preview.playing.value)
        assertTrue(
            logger.events
                .single()
                .toString()
                .contains(AndroidRecordingPreview.OPERATION),
        )
    }

    @Test
    fun `an error while it plays ends that preview only, never a newer one`() {
        preview.play(PreviewItem.Saved(saved))
        idle()
        val first = created.single()
        assertTrue(first.shadow().isReallyPlaying)

        first.shadow().invokeErrorListener(MediaPlayer.MEDIA_ERROR_UNKNOWN, 0)

        assertNull(preview.playing.value)
        assertEquals(1, focus.abandons, "focus given back")
        assertTrue(
            logger.events
                .single()
                .toString()
                .contains("failed while playing"),
        )

        preview.play(PreviewItem.Take(take))
        idle()
        // A late callback of the first player changes nothing for the take playing now.
        first.shadow().invokeErrorListener(MediaPlayer.MEDIA_ERROR_UNKNOWN, 0)
        assertEquals("t1", preview.playing.value?.key)
        assertTrue(created.last().shadow().isReallyPlaying)
    }

    @Test
    fun `a preview holds transient focus, gives it back when it ends, plays nothing without it and stops when it is lost`() {
        preview.play(PreviewItem.Saved(saved))
        idle()
        assertEquals(1, focus.requests)
        preview.stop()
        assertEquals(1, focus.abandons)

        preview.play(PreviewItem.Take(take))
        idle()
        idle(Duration.ofMillis(2_500))
        assertNull(preview.playing.value, "ended by itself")
        assertEquals(2, focus.abandons)

        preview.play(PreviewItem.Saved(saved))
        idle()
        focus.lose()
        assertNull(preview.playing.value, "a call or another player took the audio")
        assertEquals(ShadowMediaPlayer.State.END, created.last().shadow().state)

        focus.granted = false
        val before = created.size
        preview.play(PreviewItem.Saved(saved))
        idle()
        assertNull(preview.playing.value)
        assertEquals(before, created.size, "no player without focus")
    }

    /** A [RecordingFocus] that grants while [granted], counts requests and abandons, and can be [lose]t. */
    private class TestFocus : RecordingFocus {
        var granted = true
        var requests = 0
        var abandons = 0
        private var onLoss: (() -> Unit)? = null

        override fun request(onLoss: () -> Unit): Boolean {
            requests++
            this.onLoss = onLoss.takeIf { granted }
            return granted
        }

        override fun abandon() {
            abandons++
        }

        fun lose() = onLoss?.invoke()
    }
}
