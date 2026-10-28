package com.yawnandpawn.app.core.recording

import kotlinx.coroutines.flow.StateFlow

/** What a [RecordingPreview] plays: a saved [Recording] or a finished take that is not saved yet. */
sealed interface PreviewItem {
    /** The key [PreviewPlayback.key] reports while this plays: the recording's id, or the take's id. */
    val key: String

    data class Saved(
        val recording: Recording,
    ) : PreviewItem {
        override val key: String get() = recording.id
    }

    data class Take(
        val take: RecordedTake,
    ) : PreviewItem {
        override val key: String get() = take.takeId
    }
}

/** The preview playing now: [key] of its [PreviewItem], [positionMs] into it. */
data class PreviewPlayback(
    val key: String,
    val positionMs: Long = 0,
)

/**
 * Port for the Recordings screen's preview player (Story 7.2): one message at a time, played once on the media stream
 * (not the alarm stream: it is the user listening back, not an alarm sound) at the phone's own media volume, which it
 * never changes. Starting one stops the other; the Android adapter never plays while an alarm rings (a ring that starts
 * stops it), and a file that is missing or cannot be read (storage locked) plays nothing. Only error codes are logged,
 * never a name or a path.
 */
interface RecordingPreview {
    /** The preview playing now, with its position (updated a few times a second), or null; clears when it ends or fails. */
    val playing: StateFlow<PreviewPlayback?>

    /** Plays [item] from its start, stopping any other preview first. */
    fun play(item: PreviewItem)

    /** Stops the preview, if any. */
    fun stop()
}
