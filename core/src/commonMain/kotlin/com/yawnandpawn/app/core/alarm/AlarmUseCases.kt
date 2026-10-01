package com.yawnandpawn.app.core.alarm

import com.yawnandpawn.app.core.error.DomainError
import com.yawnandpawn.app.core.error.Outcome
import com.yawnandpawn.app.core.error.flatMap
import com.yawnandpawn.app.core.error.map
import com.yawnandpawn.app.core.id.IdGenerator
import com.yawnandpawn.app.core.time.Clock
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalTime
import kotlin.time.Instant

/**
 * What the editor hands to [SaveAlarm]: every user-editable field. [id] is `null` for a new alarm and the stored
 * alarm's id for an edit.
 */
data class AlarmDraft(
    val id: String? = null,
    val time: LocalTime,
    val repeatDays: Set<DayOfWeek> = emptySet(),
    val label: String? = null,
    val enabled: Boolean = true,
    val soundRef: String = Alarm.DEFAULT_SOUND_REF,
    val volumePercent: Int = Alarm.DEFAULT_VOLUME_PERCENT,
    val gradualVolume: Boolean = true,
    val rampStartPercent: Int = Alarm.DEFAULT_RAMP_START_PERCENT,
    val vibration: Boolean = true,
    val snoozeLengthMinutes: Int = Alarm.DEFAULT_SNOOZE_LENGTH_MINUTES,
    val graceSeconds: Int = Alarm.DEFAULT_GRACE_SECONDS,
)

/**
 * Serializes the read-modify-write of every alarm use case (request-code allocation, get-then-upsert, the scheduler
 * sync after it), so two concurrent saves never pick the same code, an edit racing a delete never re-inserts the
 * deleted alarm, and the system alarms are changed in the same order as the stored ones.
 * One shared instance per app (a Koin `single`), passed to each use case and to [AlarmScheduling].
 */
class AlarmWriteLock {
    private val mutex = Mutex()

    suspend fun <T> withLock(block: suspend () -> T): T = mutex.withLock { block() }
}

/**
 * Creates or edits an alarm. A new alarm gets a new id, the next code from [RequestCodeSequence] and
 * `createdAt = updatedAt = now`; an edit keeps id, request code and `createdAt` and sets `updatedAt = now`. The time
 * is truncated to whole minutes, and a blank label is stored as no label. Invalid input is `InvalidAlarm(field)` and
 * nothing is stored. Once stored, the system alarm follows ([AlarmScheduling.sync]).
 */
class SaveAlarm(
    private val repository: AlarmRepository,
    private val idGenerator: IdGenerator,
    private val clock: Clock,
    private val lock: AlarmWriteLock,
    private val requestCodes: RequestCodeSequence,
    private val scheduling: AlarmScheduling,
) {
    suspend operator fun invoke(draft: AlarmDraft): Outcome<Alarm, DomainError> =
        lock.withLock {
            val now = clock.nowMillis()
            val id = draft.id
            val base: Outcome<Alarm, DomainError> =
                if (id == null) {
                    val template = draft.toAlarm(id = idGenerator.newId(), requestCode = RequestCodes.FIRST_ALARM, createdAt = now)
                    // Validate before allocating a code, so invalid input never touches storage.
                    val invalid = validate(template)
                    if (invalid != null) {
                        Outcome.Failure(DomainError.InvalidAlarm(invalid))
                    } else {
                        requestCodes.next().map { code -> template.copy(requestCode = code) }
                    }
                } else {
                    repository.get(id).map { stored -> draft.toAlarm(id, stored.requestCode, stored.createdAt) }
                }
            base
                .flatMap { alarm -> validateAndStore(repository, alarm.copy(updatedAt = now)) }
                .onSuccess(scheduling::sync)
        }

    private fun AlarmDraft.toAlarm(
        id: String,
        requestCode: Int,
        createdAt: Instant,
    ) = Alarm(
        id = id,
        time = LocalTime(time.hour, time.minute),
        repeatDays = repeatDays,
        label = label?.trim()?.ifEmpty { null },
        enabled = enabled,
        soundRef = soundRef,
        volumePercent = volumePercent,
        gradualVolume = gradualVolume,
        rampStartPercent = rampStartPercent,
        vibration = vibration,
        snoozeLengthMinutes = snoozeLengthMinutes,
        graceSeconds = graceSeconds,
        requestCode = requestCode,
        createdAt = createdAt,
        updatedAt = createdAt,
    )
}

/**
 * Turns an alarm on or off; `updatedAt = now`. `NotFound(id)` when there is no such alarm. Other fields are not
 * re-validated, so a stored alarm with an out-of-range value can still be switched off. Once stored, the alarm is
 * armed (on) or cancelled (off).
 */
class SetAlarmEnabled(
    private val repository: AlarmRepository,
    private val clock: Clock,
    private val lock: AlarmWriteLock,
    private val scheduling: AlarmScheduling,
) {
    suspend operator fun invoke(
        id: String,
        enabled: Boolean,
    ): Outcome<Alarm, DomainError> =
        lock.withLock {
            repository
                .get(id)
                .flatMap { stored ->
                    val changed = stored.copy(enabled = enabled, updatedAt = clock.nowMillis())
                    repository.upsert(changed).map { changed }
                }.onSuccess(scheduling::sync)
        }
}

/**
 * Removes an alarm and then cancels its system alarm. `NotFound(id)` when there is no such alarm, and nothing changes;
 * a failed delete cancels nothing.
 */
class DeleteAlarm(
    private val repository: AlarmRepository,
    private val lock: AlarmWriteLock,
    private val scheduling: AlarmScheduling,
) {
    suspend operator fun invoke(id: String): Outcome<Unit, DomainError> =
        lock.withLock {
            repository.get(id).flatMap { stored ->
                repository.delete(id).onSuccess { scheduling.cancel(stored.requestCode) }
            }
        }
}

/**
 * Copies an alarm with a new id, a new request code and fresh timestamps; the copy is then armed when it is enabled.
 * `NotFound(id)` when there is no such alarm.
 */
class DuplicateAlarm(
    private val repository: AlarmRepository,
    private val idGenerator: IdGenerator,
    private val clock: Clock,
    private val lock: AlarmWriteLock,
    private val requestCodes: RequestCodeSequence,
    private val scheduling: AlarmScheduling,
) {
    suspend operator fun invoke(id: String): Outcome<Alarm, DomainError> =
        lock.withLock {
            repository
                .get(id)
                .flatMap { stored ->
                    requestCodes.next().flatMap { code ->
                        val now = clock.nowMillis()
                        val copy = stored.copy(id = idGenerator.newId(), requestCode = code, createdAt = now, updatedAt = now)
                        validateAndStore(repository, copy)
                    }
                }.onSuccess(scheduling::sync)
        }
}

private suspend fun validateAndStore(
    repository: AlarmRepository,
    alarm: Alarm,
): Outcome<Alarm, DomainError> {
    validate(alarm)?.let { return Outcome.Failure(DomainError.InvalidAlarm(it)) }
    return repository.upsert(alarm).map { alarm }
}

/** Runs [action] on the success value and returns this outcome unchanged. */
private inline fun <T> Outcome<T, DomainError>.onSuccess(action: (T) -> Unit): Outcome<T, DomainError> {
    if (this is Outcome.Success) action(value)
    return this
}

/** Now, truncated to whole milliseconds: the precision `app.db` stores, so a saved alarm equals the stored one. */
private fun Clock.nowMillis(): Instant = Instant.fromEpochMilliseconds(now().toEpochMilliseconds())
