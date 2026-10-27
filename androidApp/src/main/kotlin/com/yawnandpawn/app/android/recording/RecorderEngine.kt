package com.yawnandpawn.app.android.recording

import android.annotation.SuppressLint
import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import java.io.File

/**
 * The few `MediaRecorder` calls [AndroidAudioRecorder] makes (Story 7.1), so a test can script a take. One engine
 * records one take: [start], then [stop] (which can throw when nothing usable was recorded), then [release].
 */
interface RecorderEngine {
    /**
     * Records the microphone into [output] (MPEG-4/AAC, mono, 44.1 kHz, 64 kbit/s) for at most [maxDurationMs].
     * [onMaxDuration] runs when that limit stops it, [onError] with the recorder's error code. Throws when the
     * recorder cannot be set up or started.
     */
    fun start(
        output: File,
        maxDurationMs: Int,
        onMaxDuration: () -> Unit,
        onError: (Int) -> Unit,
    )

    /** The highest amplitude since the last call, 0 to 32767. */
    fun maxAmplitude(): Int

    fun stop()

    fun release()
}

/**
 * [RecorderEngine] over the platform `MediaRecorder` (Story 7.1): microphone source, AAC in MPEG-4, mono. [create] makes
 * the recorder (a test keeps it to read its settings).
 */
class MediaRecorderEngine(
    private val context: Context,
    private val create: () -> MediaRecorder = { platformRecorder(context) },
) : RecorderEngine {
    private var recorder: MediaRecorder? = null

    // AndroidAudioRecorder checks RECORD_AUDIO before every start; a revoked permission throws, which it maps to
    // MicrophoneUnavailable.
    @SuppressLint("MissingPermission")
    override fun start(
        output: File,
        maxDurationMs: Int,
        onMaxDuration: () -> Unit,
        onError: (Int) -> Unit,
    ) {
        val created = create()
        recorder = created
        created.setAudioSource(MediaRecorder.AudioSource.MIC)
        created.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        created.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
        created.setAudioChannels(CHANNELS)
        created.setAudioSamplingRate(SAMPLE_RATE_HZ)
        created.setAudioEncodingBitRate(BIT_RATE)
        created.setMaxDuration(maxDurationMs)
        created.setOutputFile(output.absolutePath)
        created.setOnInfoListener { _, what, _ -> if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED) onMaxDuration() }
        created.setOnErrorListener { _, what, _ -> onError(what) }
        created.prepare()
        created.start()
    }

    override fun maxAmplitude(): Int = recorder?.maxAmplitude ?: 0

    override fun stop() {
        recorder?.stop()
    }

    override fun release() {
        recorder?.release()
        recorder = null
    }

    companion object {
        const val CHANNELS = 1
        const val SAMPLE_RATE_HZ = 44_100
        const val BIT_RATE = 64_000

        // The context constructor exists from API 31; the older one is deprecated there.
        @Suppress("DEPRECATION")
        private fun platformRecorder(context: Context): MediaRecorder =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(context) else MediaRecorder()
    }
}
