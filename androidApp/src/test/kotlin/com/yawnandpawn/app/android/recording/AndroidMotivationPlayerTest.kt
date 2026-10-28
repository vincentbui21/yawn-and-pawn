package com.yawnandpawn.app.android.recording

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.yawnandpawn.app.StopAppRule
import com.yawnandpawn.app.core.error.DomainError
import com.yawnandpawn.app.core.log.LogEvent
import com.yawnandpawn.app.core.recording.MotivationPlayback
import com.yawnandpawn.app.data.recording.RecordingFiles
import com.yawnandpawn.app.testing.FakeLogger
import com.yawnandpawn.app.testing.FakeRecordingRepository
import com.yawnandpawn.app.testing.aRecording
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Story 7.3: the Success message player, over Robolectric's `MediaPlayer`. */
@RunWith(RobolectricTestRunner::class)
class AndroidMotivationPlayerTest {
    @get:Rule
    val stopApp = StopAppRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val folder = File(context.filesDir, "motivation-test").apply { mkdirs() }
    private val files = RecordingFiles { folder }
    private val logger = FakeLogger()
    private var ringing = false
    private var volumeRestores = 0
    private val created = mutableListOf<MediaPlayer>()
    private val focus = TestFocus()
    private val message = aRecording(id = "r1").also { File(folder, it.fileName).writeText("audio") }
    private val repository = FakeRecordingRepository(listOf(message))
    private val player =
        AndroidMotivationPlayer(
            repository,
            files,
            ringing = { ringing },
            restoreVolume = { volumeRestores++ },
            logger = logger,
            focus = focus,
            // The row is read at once, on the calling thread; the player opens on the main looper.
            scope = CoroutineScope(Dispatchers.Unconfined),
        )

    init {
        ShadowMediaPlayer.setMediaInfoProvider { ShadowMediaPlayer.MediaInfo(MESSAGE_MILLIS, 0) }
        ShadowMediaPlayer.setCreateListener { mediaPlayer, _ -> created += mediaPlayer }
    }

    private fun MediaPlayer.shadow(): ShadowMediaPlayer = Shadow.extract(this)

    private fun idle(duration: Duration = Duration.ZERO) = shadowOf(Looper.getMainLooper()).idleFor(duration)

    private fun playing(): MotivationPlayback = requireNotNull(player.state.value)

    private fun assertLogsHoldNoNameOrPath() =
        logger.events.forEach {
            val text = it.toString()
            assertTrue(it is LogEvent.OperationFailed && "m4a" !in text && "Message 1" !in text && folder.name !in text, text)
        }

    @Test
    fun `the message plays once on the alarm stream at the user's alarm volume, and reports its session and position`() {
        player.play("session-1", "r1")
        assertNull(player.state.value, "no card before the message is ready")
        idle()

        val shadow = created.single().shadow()
        assertEquals(AudioAttributes.USAGE_ALARM, shadow.audioAttributes.usage)
        assertFalse(created.single().isLooping, "once, no loop")
        assertTrue(shadow.isReallyPlaying)
        assertEquals(DataSource.toDataSource(File(folder, "r1.m4a").absolutePath), shadow.dataSource)
        assertEquals(1, volumeRestores, "the user's alarm volume first, never the ring's")
        assertEquals(MotivationPlayback("session-1", playing = true, positionMs = 0, durationMs = MESSAGE_MILLIS.toLong()), playing())
        assertEquals(1, focus.requests)

        idle(Duration.ofMillis(1_000))
        assertTrue(playing().positionMs > 0, "the position moves while it plays")
    }

    @Test
    fun `played to its end the card stays at the end, play restarts it and replay always starts over`() {
        player.play("session-1", "r1")
        idle()
        val abandons = focus.abandons
        idle(Duration.ofMillis(MESSAGE_MILLIS + 500L))

        val length = MESSAGE_MILLIS.toLong()
        assertEquals(MotivationPlayback("session-1", playing = false, positionMs = length, durationMs = length), playing())
        assertEquals(abandons + 1, focus.abandons, "focus given back at the end")

        player.resume()
        assertTrue(playing().playing)
        assertEquals(0, playing().positionMs, "play after the end starts over")
        assertTrue(created.single().shadow().isReallyPlaying)
        idle(Duration.ofMillis(1_000))

        player.replay()
        assertEquals(0, playing().positionMs)
        assertTrue(playing().playing)
        assertEquals(1, created.size, "the same player, no second message")
    }

    @Test
    fun `pause keeps the place and resume plays on from it`() {
        player.play("session-1", "r1")
        idle()
        idle(Duration.ofMillis(1_000))

        player.pause()
        val paused = playing()
        assertFalse(paused.playing)
        assertTrue(paused.positionMs > 0)
        assertFalse(created.single().shadow().isReallyPlaying)

        player.resume()
        assertTrue(playing().playing)
        assertEquals(paused.positionMs, playing().positionMs)
        assertTrue(created.single().shadow().isReallyPlaying)
    }

    @Test
    fun `stop ends it at once and gives the focus back`() {
        player.play("session-1", "r1")
        idle()

        player.stop()

        assertNull(player.state.value)
        assertEquals(ShadowMediaPlayer.State.END, created.single().shadow().state)
        assertTrue(focus.abandons >= 1)
        player.resume()
        player.replay()
        assertNull(player.state.value, "nothing to resume after a stop")
    }

    @Test
    fun `a stop before the row is read opens nothing`() {
        val waiting = AndroidMotivationPlayer(repository, files, { false }, {}, logger, focus, CoroutineScope(Dispatchers.Main))
        waiting.play("session-1", "r1")
        waiting.stop()
        idle()

        assertTrue(created.isEmpty())
        assertNull(waiting.state.value)
    }

    @Test
    fun `a missing row, a missing file or a failed read plays nothing, and the logs hold no name or path`() {
        player.play("session-1", "gone")
        idle()
        repository.markMissing("r1")
        player.play("session-1", "r1")
        idle()
        File(folder, "r1.m4a").delete()
        repository.files.value += message.fileName
        player.play("session-1", "r1")
        idle()
        repository.failure = DomainError.StorageFailure("IOException")
        player.play("session-1", "r1")
        idle()

        assertTrue(created.isEmpty())
        assertNull(player.state.value)
        assertEquals(0, volumeRestores)
        // One line each, and the failed read also logs its error code first.
        assertEquals(5, logger.events.size)
        assertLogsHoldNoNameOrPath()
    }

    @Test
    fun `a message that cannot be prepared plays nothing`() {
        ShadowMediaPlayer.addException(DataSource.toDataSource(File(folder, "r1.m4a").absolutePath), IOException("broken"))

        player.play("session-1", "r1")
        idle()

        assertNull(player.state.value)
        assertTrue(
            logger.events
                .single()
                .toString()
                .contains(AndroidMotivationPlayer.OPERATION),
        )
        assertLogsHoldNoNameOrPath()
    }

    @Test
    fun `an error while it plays hides the card and logs the error codes only`() {
        player.play("session-1", "r1")
        idle()

        created.single().shadow().invokeErrorListener(MediaPlayer.MEDIA_ERROR_UNKNOWN, -1004)

        assertNull(player.state.value)
        val logged = logger.events.single().toString()
        assertTrue("1/-1004" in logged, logged)
        assertLogsHoldNoNameOrPath()
    }

    @Test
    fun `a late callback of an older message never ends the newer one`() {
        player.play("session-1", "r1")
        idle()
        val first = created.single()
        player.play("session-2", "r1")
        idle()

        first.shadow().invokeErrorListener(MediaPlayer.MEDIA_ERROR_UNKNOWN, 0)

        assertEquals("session-2", playing().sessionId)
        assertTrue(created.last().shadow().isReallyPlaying)
    }

    @Test
    fun `nothing starts, resumes or replays while an alarm rings`() {
        ringing = true
        player.play("session-1", "r1")
        idle()
        assertTrue(created.isEmpty())
        assertNull(player.state.value)

        ringing = false
        player.play("session-1", "r1")
        idle()
        player.pause()
        ringing = true
        player.resume()
        player.replay()
        assertFalse(playing().playing)
        assertFalse(created.single().shadow().isReallyPlaying)
    }

    @Test
    fun `refused focus plays nothing, and a lost focus pauses it`() {
        player.play("session-1", "r1")
        idle()
        focus.lose()
        assertFalse(playing().playing, "a call took the audio")

        focus.granted = false
        player.resume()
        assertFalse(playing().playing)
        player.play("session-2", "r1")
        idle()
        assertNull(player.state.value)
        assertEquals(1, created.size, "no player without focus")
    }

    @Test
    fun `focus lost while the message prepares means it never starts over the call`() {
        ShadowMediaPlayer.setMediaInfoProvider { ShadowMediaPlayer.MediaInfo(MESSAGE_MILLIS, PREPARE_MILLIS) }
        player.play("session-1", "r1")
        idle()
        assertEquals(1, focus.requests, "focus taken before it prepares")

        focus.lose()
        idle(Duration.ofMillis(PREPARE_MILLIS + 100L))

        assertNull(player.state.value)
        assertFalse(created.single().shadow().isReallyPlaying)
        assertTrue(
            logger.events
                .single()
                .toString()
                .contains("focus lost"),
        )

        player.play("session-2", "r1")
        idle(Duration.ofMillis(PREPARE_MILLIS + 100L))
        assertTrue(playing().playing, "a new play takes focus again and plays")
    }

    @Test
    fun `a player that does not know its length takes the row's, so pause and play go on from the same place`() {
        val unknownLength =
            AndroidMotivationPlayer(
                repository,
                files,
                ringing = { false },
                restoreVolume = {},
                logger = logger,
                focus = focus,
                scope = CoroutineScope(Dispatchers.Unconfined),
                newPlayer = {
                    object : MediaPlayer() {
                        override fun getDuration(): Int = -1
                    }
                },
            )
        unknownLength.play("session-1", "r1")
        idle()
        assertEquals(message.durationMs, unknownLength.state.value?.durationMs)
        idle(Duration.ofMillis(1_000))

        unknownLength.pause()
        val paused = requireNotNull(unknownLength.state.value).positionMs
        assertTrue(paused in 1..message.durationMs, "paused at $paused")
        unknownLength.resume()

        assertEquals(paused, unknownLength.state.value?.positionMs, "plays on, not from the start")
        unknownLength.stop()
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

    private companion object {
        const val MESSAGE_MILLIS = 2_000
        const val PREPARE_MILLIS = 500
    }
}
