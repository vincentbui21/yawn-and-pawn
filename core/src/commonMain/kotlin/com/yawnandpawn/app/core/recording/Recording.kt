package com.yawnandpawn.app.core.recording

import com.yawnandpawn.app.core.error.DomainError
import com.yawnandpawn.app.core.error.Outcome
import kotlinx.coroutines.flow.Flow
import kotlin.time.Duration
import kotlin.time.Instant

/**
 * A motivation message the user recorded in their own voice (Story 7.1, Epic 7). The row (`recording` in `app.db`) is
 * backed up; the audio is not: it lives only in the credential-protected `recordings/[fileName]` on this phone.
 * [missing] is true when that file is not there (a phone restored from a backup, or credential storage still locked
 * before the first unlock): the message is listed but cannot play.
 */
data class Recording(
    val id: String,
    val name: String,
    val durationMs: Long,
    val fileName: String,
    val createdAt: Instant,
    val missing: Boolean = false,
) {
    companion object {
        /** AAC in an MPEG-4 container. */
        const val FILE_EXTENSION = "m4a"

        /** A take shorter than this is [DomainError.RecordingTooShort]. */
        const val MIN_DURATION_MS = 1_000L

        /** The recorder stops by itself here. */
        const val MAX_DURATION_MS = 60_000L

        /** The audio file of the recording [id]: `{id}.m4a`. */
        fun fileNameFor(id: String): String = "$id.$FILE_EXTENSION"
    }
}

/**
 * A finished take, not saved yet (Story 7.1): [takeId] names it to the recorder, which owns its temporary file;
 * [durationMs] is its length. It becomes a [Recording] through `SaveRecording` or `ReRecord`, or is discarded.
 */
data class RecordedTake(
    val takeId: String,
    val durationMs: Long,
)

/** A take being recorded now (Story 7.1), from [AudioRecorder.start] to its stop. */
data class RecordingSession(
    val takeId: String,
)

/** Port for the stored recordings (`app.db` and `recordings/` in production, `FakeRecordingRepository` in tests). */
interface RecordingRepository {
    /** Every recording, oldest first, each with [Recording.missing] set from its file; emits again after each change. */
    fun observeAll(): Flow<List<Recording>>

    /** The recording [id] (with [Recording.missing] set), or null when there is none; a storage error is a `StorageFailure`. */
    suspend fun get(id: String): Outcome<Recording?, DomainError>

    /**
     * Stores [recording] (insert or replace by id) with [take] as its audio: the take's file is moved over
     * [Recording.fileName], replacing an older take of the same recording. A take whose file is gone, or any storage
     * error, is a `StorageFailure`; the take's file is then deleted.
     */
    suspend fun save(
        recording: Recording,
        take: RecordedTake,
    ): Outcome<Unit, DomainError>

    /** Deletes the recording [id] and its file, or returns `NotFound(id)` and changes nothing. */
    suspend fun delete(id: String): Outcome<Unit, DomainError>
}

/**
 * Port for the microphone recorder (Story 7.1): one take at a time, AAC/M4A mono, at most 60 s. The recorder also stops
 * by itself, keeping the take, at 60 s, when the app goes to the background, when a call takes audio focus and when the
 * screen turns off; each such take (or its error) arrives on [autoStopped]. A take under 1 s is
 * [DomainError.RecordingTooShort]. Every failure deletes the take's file; only error codes are logged.
 */
interface AudioRecorder {
    /**
     * Starts a new take. `MicrophoneUnavailable` without the permission, a microphone or the app in front;
     * `StorageFailure` when the phone has no room; `RecorderFailure` otherwise (also while a take is being recorded).
     */
    fun start(): Outcome<RecordingSession, DomainError>

    /** Stops the take being recorded and returns it; `RecordingTooShort` under 1 s, `RecorderFailure` when none. */
    suspend fun stop(): Outcome<RecordedTake, DomainError>

    /** Stops the take being recorded, if any, and deletes it. */
    fun cancel()

    /** The input level, 0 to 1, about 10 times a second while collected; 0 when not recording. */
    fun level(): Flow<Float>

    /** The time recorded so far, about 10 times a second while collected; zero when not recording. */
    fun elapsed(): Flow<Duration>

    /** Each take the recorder stopped by itself (60 s, background, call, screen off), or the error that ended it. */
    fun autoStopped(): Flow<Outcome<RecordedTake, DomainError>>

    /** Deletes [take]'s file; nothing there is not an error. */
    suspend fun discard(take: RecordedTake)
}
