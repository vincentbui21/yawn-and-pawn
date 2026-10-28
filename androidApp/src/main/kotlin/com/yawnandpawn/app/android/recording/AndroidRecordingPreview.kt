package com.yawnandpawn.app.android.recording

import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import com.yawnandpawn.app.core.log.LogEvent
import com.yawnandpawn.app.core.log.Logger
import com.yawnandpawn.app.core.recording.PreviewItem
import com.yawnandpawn.app.core.recording.PreviewPlayback
import com.yawnandpawn.app.core.recording.RecordingPreview
import com.yawnandpawn.app.data.recording.RecordingFiles
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/**
 * The Recordings screen's preview player (Story 7.2): one message or take at a time, played once on the media stream
 * (`USAGE_MEDIA`, `CONTENT_TYPE_SPEECH`) at the phone's own media volume, which it never changes. It is thread-safe (its
 * own lock): the UI calls it on the main thread, the alarm player's ring-start hook and the focus listener on theirs, and
 * every player callback checks it still belongs to the playing preview before it ends anything.
 *
 * - **Never during a ring:** while an alarm rings ([ringing]) a preview never starts, and a ring that starts stops a
 *   playing one (`AndroidAlarmPlayer`'s ring-start hook, bound in `wakeModule`). [ringing] must not take the alarm
 *   player's lock.
 * - **Audio focus:** a preview holds transient focus ([focus], `AUDIOFOCUS_GAIN_TRANSIENT`), so music pauses while the
 *   message plays and resumes after; refused focus (a call) plays nothing, and losing it (a call, another player) stops
 *   the preview.
 * - **Files:** a saved message plays `recordings/{id}.m4a`, a take its temporary file ([RecordingFiles]). A file that is
 *   missing, or cannot be read (credential storage still locked), plays nothing and clears [playing].
 * - **Never blocking:** it prepares with `prepareAsync`; [playing] reports the position about four times a second
 *   ([handler]) and clears when the message ends or fails. Only error codes are logged, never a name or a path.
 */
class AndroidRecordingPreview(
    private val files: RecordingFiles,
    private val ringing: () -> Boolean,
    private val logger: Logger,
    private val focus: RecordingFocus,
    private val newPlayer: () -> MediaPlayer = ::MediaPlayer,
    private val handler: Handler = Handler(Looper.getMainLooper()),
) : RecordingPreview {
    private val state = MutableStateFlow<PreviewPlayback?>(null)
    override val playing: StateFlow<PreviewPlayback?> = state.asStateFlow()

    private var player: MediaPlayer? = null

    /** Reads the playing preview's position while it plays. */
    private val tick =
        object : Runnable {
            override fun run() = progress(this)
        }

    @Synchronized
    override fun play(item: PreviewItem) {
        release()
        val file = if (ringing()) null else fileOf(item)
        when {
            ringing() -> fail("an alarm is ringing")
            file == null -> fail("the file is missing")
            !focus.request(onLoss = ::stop) -> fail("no audio focus")
            else -> open(item, file)
        }
    }

    // MediaPlayer reports a source it cannot open with several exception types; each means "this preview fails".
    @Suppress("TooGenericExceptionCaught")
    private fun open(
        item: PreviewItem,
        file: File,
    ) {
        val opened = newPlayer()
        player = opened
        state.value = PreviewPlayback(item.key)
        try {
            opened.setAudioAttributes(SPEECH_AUDIO_ATTRIBUTES)
            opened.setDataSource(file.absolutePath)
            opened.setOnPreparedListener(::started)
            opened.setOnCompletionListener(::completed)
            opened.setOnErrorListener { broken, _, _ ->
                failed(broken)
                true
            }
            opened.prepareAsync()
        } catch (e: Exception) {
            fail("could not open the message: ${e::class.simpleName}")
        }
    }

    @Synchronized
    override fun stop() {
        if (player != null || state.value != null) finish()
    }

    @Synchronized
    private fun started(prepared: MediaPlayer) {
        if (player !== prepared) return
        prepared.start()
        handler.removeCallbacks(tick)
        handler.postDelayed(tick, TICK_MILLIS)
    }

    /** [ended] played to its end: the preview is over, unless a newer one has replaced it meanwhile. */
    @Synchronized
    private fun completed(ended: MediaPlayer) {
        if (player === ended) finish()
    }

    /** [broken] failed while preparing or playing: the preview is over, unless a newer one has replaced it meanwhile. */
    @Synchronized
    private fun failed(broken: MediaPlayer) {
        if (player === broken) fail("the message failed while playing")
    }

    @Suppress("SwallowedException")
    @Synchronized
    private fun progress(runnable: Runnable) {
        val current = player ?: return
        try {
            state.value = state.value?.copy(positionMs = current.currentPosition.toLong())
        } catch (e: IllegalStateException) {
            // Released or not started any more: the completion or error listener ends the preview.
            return
        }
        handler.postDelayed(runnable, TICK_MILLIS)
    }

    /** The file [item] plays, or null when it is not there or cannot be read (storage locked before the first unlock). */
    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    private fun fileOf(item: PreviewItem): File? =
        try {
            when (item) {
                is PreviewItem.Saved -> files.file(item.recording.fileName)
                is PreviewItem.Take -> files.takeFile(item.take.takeId)
            }?.takeIf { it.isFile }
        } catch (e: Exception) {
            null
        }

    private fun fail(reason: String) {
        logger.log(LogEvent.OperationFailed(OPERATION, reason))
        finish()
    }

    private fun finish() {
        release()
        focus.abandon()
        state.value = null
    }

    private fun release() {
        handler.removeCallbacks(tick)
        player?.release()
        player = null
    }

    companion object {
        const val OPERATION = "preview recording"
        private const val TICK_MILLIS = 250L

        /** The user listening back to their own voice: media, speech. */
        val SPEECH_AUDIO_ATTRIBUTES: AudioAttributes =
            AudioAttributes
                .Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
    }
}
