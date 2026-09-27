package com.yawnandpawn.app.testing

import com.yawnandpawn.app.core.alarm.Alarm
import com.yawnandpawn.app.core.alarm.AlarmListOrder
import com.yawnandpawn.app.core.alarm.AlarmRepository
import com.yawnandpawn.app.core.alarm.RequestCodes
import com.yawnandpawn.app.core.error.DomainError
import com.yawnandpawn.app.core.error.Outcome
import com.yawnandpawn.app.core.id.IdGenerator
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalTime
import kotlin.time.Instant

/**
 * In-memory [AlarmRepository] with the same rules as `RoomAlarmRepository`: [observeAll] is ordered by
 * [AlarmListOrder], and a request code already used by another alarm is a `StorageFailure`.
 * Set [failure] to make every call fail with it (a storage outage), including [listAll] and collecting [observeAll].
 */
class FakeAlarmRepository(
    initial: List<Alarm> = emptyList(),
) : AlarmRepository {
    private val alarms = MutableStateFlow(initial.associateBy { it.id })

    var failure: DomainError.StorageFailure? = null

    /** The stored alarms right now, in list order. */
    val current: List<Alarm>
        get() = alarms.value.values.sortedWith(AlarmListOrder)

    /** Emits the stored alarms in list order; while [failure] is set, collecting throws, like a failing database. */
    override fun observeAll(): Flow<List<Alarm>> =
        alarms.map { byId ->
            failure?.let { error("storage failure: ${it.cause}") }
            byId.values.sortedWith(AlarmListOrder)
        }

    override suspend fun listAll(): Outcome<List<Alarm>, DomainError> {
        val storageFailure = failure
        return if (storageFailure != null) Outcome.Failure(storageFailure) else Outcome.Success(current)
    }

    override suspend fun get(id: String): Outcome<Alarm, DomainError> {
        failure?.let { return Outcome.Failure(it) }
        return alarms.value[id]?.let { Outcome.Success(it) } ?: Outcome.Failure(DomainError.NotFound(id))
    }

    override suspend fun upsert(alarm: Alarm): Outcome<Unit, DomainError> {
        val storageFailure = failure
        val clash = alarms.value.values.any { it.id != alarm.id && it.requestCode == alarm.requestCode }
        return when {
            storageFailure != null -> {
                Outcome.Failure(storageFailure)
            }

            clash -> {
                Outcome.Failure(DomainError.StorageFailure("UNIQUE constraint failed: alarm.request_code"))
            }

            else -> {
                alarms.value += alarm.id to alarm
                Outcome.Success(Unit)
            }
        }
    }

    override suspend fun delete(id: String): Outcome<Unit, DomainError> {
        val storageFailure = failure
        return when {
            storageFailure != null -> {
                Outcome.Failure(storageFailure)
            }

            id !in alarms.value -> {
                Outcome.Failure(DomainError.NotFound(id))
            }

            else -> {
                alarms.value -= id
                Outcome.Success(Unit)
            }
        }
    }
}

/**
 * Predictable UUID v4 strings: `00000000-0000-4000-8000-000000000001`, then `...0002`, and so on.
 * [generated] lists every id handed out.
 */
class FakeIdGenerator : IdGenerator {
    private var next = 1L
    private val handedOut = mutableListOf<String>()

    val generated: List<String>
        get() = handedOut.toList()

    override fun newId(): String = fakeUuid(next++).also { handedOut += it }

    companion object {
        /** The [n]th id this generator hands out. */
        fun fakeUuid(n: Long): String = "00000000-0000-4000-8000-" + n.toString().padStart(UUID_LAST_GROUP_LENGTH, '0')

        private const val UUID_LAST_GROUP_LENGTH = 12
    }
}

/** Builds an [Alarm] with the story defaults and fixed test values for the rest. */
fun anAlarm(
    id: String = FakeIdGenerator.fakeUuid(1),
    time: LocalTime = DEFAULT_ALARM_TIME,
    repeatDays: Set<DayOfWeek> = emptySet(),
    label: String? = null,
    enabled: Boolean = true,
    requestCode: Int = RequestCodes.FIRST_ALARM,
    createdAt: Instant = DEFAULT_FAKE_INSTANT,
    updatedAt: Instant = createdAt,
): Alarm =
    Alarm(
        id = id,
        time = time,
        repeatDays = repeatDays,
        label = label,
        enabled = enabled,
        requestCode = requestCode,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )

/** 07:00, the builder's default alarm time. */
val DEFAULT_ALARM_TIME: LocalTime = LocalTime(hour = 7, minute = 0)
