package com.yawnandpawn.app.core.recording

import com.yawnandpawn.app.core.alarm.AlarmRepository
import com.yawnandpawn.app.core.alarm.AlarmWriteLock
import com.yawnandpawn.app.core.alarm.Motivation
import com.yawnandpawn.app.core.error.DomainError
import com.yawnandpawn.app.core.error.Outcome
import com.yawnandpawn.app.core.error.flatMap
import com.yawnandpawn.app.core.error.map
import com.yawnandpawn.app.core.id.IdGenerator
import com.yawnandpawn.app.core.log.LogEvent
import com.yawnandpawn.app.core.log.Logger
import com.yawnandpawn.app.core.session.SessionLockGuard
import com.yawnandpawn.app.core.time.Clock
import kotlinx.coroutines.flow.first
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Instant

/**
 * The automatic name of a new recording (Story 7.1): "Message {n}", n one above the highest "Message k" already stored
 * (gaps are not filled, names the user gave are ignored). A plain constant: the screens show the stored name as it is.
 */
object RecordingNames {
    const val AUTO_NAME_FORMAT = "Message {n}"
    private const val PLACEHOLDER = "{n}"

    private val prefix = AUTO_NAME_FORMAT.substringBefore(PLACEHOLDER)
    private val suffix = AUTO_NAME_FORMAT.substringAfter(PLACEHOLDER)

    /** The name after the auto names among [existing]: "Message 1" when there are none. */
    fun next(existing: List<String>): String {
        val highest =
            existing
                .mapNotNull { name ->
                    name
                        .takeIf { it.startsWith(prefix) && it.endsWith(suffix) && it.length > prefix.length + suffix.length }
                        ?.substring(prefix.length, name.length - suffix.length)
                        ?.takeIf { digits -> digits.all { it in '0'..'9' } }
                        ?.toLongOrNull()
                        ?.takeIf { it < Long.MAX_VALUE }
                }.maxOrNull() ?: 0L
        return AUTO_NAME_FORMAT.replace(PLACEHOLDER, (highest + 1L).toString())
    }
}

/**
 * Saves a new take as a new recording (Story 7.1): a new UUID, the next "Message {n}" name ([RecordingNames]),
 * `createdAt` now and the file `{id}.m4a`. A take outside 1 to 60 s is `RecordingTooShort` (under 1 s) or
 * `RecorderFailure`; while a wake session is in progress it is `SessionActive` (FR-SES-3: nothing the user owns changes
 * then). A take that is not stored is discarded, and the failure is logged (its error code only).
 */
class SaveRecording(
    private val repository: RecordingRepository,
    private val recorder: AudioRecorder,
    private val idGenerator: IdGenerator,
    private val clock: Clock,
    private val sessionLock: SessionLockGuard,
    private val logger: Logger,
) {
    suspend operator fun invoke(take: RecordedTake): Outcome<Recording, DomainError> {
        val result =
            validate(take) ?: sessionLock.whenIdle {
                when (val names = currentNames(repository)) {
                    is Outcome.Failure -> {
                        names
                    }

                    is Outcome.Success -> {
                        val id = idGenerator.newId()
                        val recording =
                            Recording(
                                id = id,
                                name = RecordingNames.next(names.value),
                                durationMs = take.durationMs,
                                fileName = Recording.fileNameFor(id),
                                createdAt = Instant.fromEpochMilliseconds(clock.now().toEpochMilliseconds()),
                            )
                        repository.save(recording, take).map { recording }
                    }
                }
            }
        return result.settle(take, recorder, logger, OPERATION)
    }

    companion object {
        const val OPERATION = "save recording"
    }
}

/**
 * Replaces the audio of the recording [id] with a new take (Story 7.1): the id, name, file name and `createdAt` stay, the
 * duration is the take's. An unknown id is `NotFound(id)`; a take outside 1 to 60 s fails as in [SaveRecording]. A take
 * that is not stored is discarded, and the failure is logged. Refused with `SessionActive` during a wake session.
 */
class ReRecord(
    private val repository: RecordingRepository,
    private val recorder: AudioRecorder,
    private val sessionLock: SessionLockGuard,
    private val logger: Logger,
) {
    suspend operator fun invoke(
        id: String,
        take: RecordedTake,
    ): Outcome<Recording, DomainError> {
        val result =
            validate(take) ?: sessionLock.whenIdle {
                when (val stored = repository.get(id)) {
                    is Outcome.Failure -> {
                        stored
                    }

                    is Outcome.Success -> {
                        val existing = stored.value
                        if (existing == null) {
                            Outcome.Failure(DomainError.NotFound(id))
                        } else {
                            val updated = existing.copy(durationMs = take.durationMs, missing = false)
                            repository.save(updated, take).map { updated }
                        }
                    }
                }
            }
        return result.settle(take, recorder, logger, OPERATION)
    }

    companion object {
        const val OPERATION = "re-record"
    }
}

/**
 * Deletes the recording [id] and its file (Story 7.1), then sets every alarm whose motivation is that recording to
 * `Motivation.None` (Story 7.2: "Alarms using it will play no message."); alarms on `Random` keep it while another
 * recording remains, and fall back to None with the last one. The recording goes first, so a failed delete leaves every
 * alarm as it was; a failed alarm write after it leaves a choice that no longer resolves, which plays no message. One
 * write path, under the [alarmLock] like every alarm write, and refused with `SessionActive` during a wake session
 * (nothing changes then). An unknown id is `NotFound(id)` and no alarm changes. Failures are logged (their error code
 * only).
 */
class DeleteRecording(
    private val repository: RecordingRepository,
    private val alarmRepository: AlarmRepository,
    private val alarmLock: AlarmWriteLock,
    private val sessionLock: SessionLockGuard,
    private val logger: Logger,
) {
    suspend operator fun invoke(id: String): Outcome<Unit, DomainError> {
        val result =
            alarmLock.withLock {
                sessionLock.whenIdle {
                    repository
                        .delete(id)
                        .flatMap { alarmRepository.clearMotivation(Motivation.Recording(id)) }
                        .flatMap { currentNames(repository) }
                        .flatMap { left ->
                            if (left.isEmpty()) alarmRepository.clearMotivation(Motivation.Random) else Outcome.Success(Unit)
                        }
                }
            }
        if (result is Outcome.Failure) logger.log(LogEvent.OperationFailed.of(OPERATION, result.error))
        return result
    }

    companion object {
        const val OPERATION = "delete recording"
    }
}

/** The failure of a take outside 1 to 60 s, or null for a valid one. */
private fun validate(take: RecordedTake): Outcome.Failure<DomainError>? =
    when {
        take.durationMs < Recording.MIN_DURATION_MS -> Outcome.Failure(DomainError.RecordingTooShort)
        take.durationMs > Recording.MAX_DURATION_MS -> Outcome.Failure(DomainError.RecorderFailure("take longer than 60 s"))
        else -> null
    }

/** Every stored name now; a storage error is a `StorageFailure` (the flow's exception class only, never its text). */
@Suppress("TooGenericExceptionCaught")
private suspend fun currentNames(repository: RecordingRepository): Outcome<List<String>, DomainError> =
    try {
        Outcome.Success(repository.observeAll().first().map { it.name })
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Outcome.Failure(DomainError.StorageFailure("read recordings: ${e::class.simpleName}"))
    }

/** On failure, discards [take] and logs the error code; the result is returned unchanged. */
private suspend fun <T> Outcome<T, DomainError>.settle(
    take: RecordedTake,
    recorder: AudioRecorder,
    logger: Logger,
    operation: String,
): Outcome<T, DomainError> {
    if (this is Outcome.Failure) {
        recorder.discard(take)
        logger.log(LogEvent.OperationFailed.of(operation, error))
    }
    return this
}
