package com.yawnandpawn.app.core.alarm

import com.yawnandpawn.app.core.error.DomainError
import com.yawnandpawn.app.core.error.Outcome
import com.yawnandpawn.app.core.id.IdGenerator
import com.yawnandpawn.app.core.log.LogEvent
import com.yawnandpawn.app.core.log.Logger
import com.yawnandpawn.app.core.time.Clock
import com.yawnandpawn.app.core.time.Deadline
import com.yawnandpawn.app.core.time.TimeZoneProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.yield
import kotlinx.datetime.TimeZone
import kotlin.time.Duration
import kotlin.time.Instant

// Core cannot depend on :testing (AD-1), so the core tests share these small local doubles; :testing has the fakes
// the other modules use.

internal class TestClock(
    var now: Instant,
) : Clock {
    override fun now(): Instant = now

    fun advanceBy(duration: Duration) {
        now += duration
    }
}

internal class TestZone(
    var zone: TimeZone,
) : TimeZoneProvider {
    override fun current(): TimeZone = zone
}

internal class SequentialIds : IdGenerator {
    private var next = 1

    override fun newId(): String = "id-${next++}"
}

internal class InMemoryAlarms : AlarmRepository {
    val alarms = MutableStateFlow<Map<String, Alarm>>(emptyMap())

    /** Fails every upsert. */
    var failure: DomainError.StorageFailure? = null

    /** Fails every listAll. */
    var listFailure: DomainError.StorageFailure? = null

    /** Fails every delete. */
    var deleteFailure: DomainError.StorageFailure? = null
    var upserts = 0

    override fun observeAll(): Flow<List<Alarm>> = alarms.map { it.values.sortedWith(AlarmListOrder) }

    override suspend fun listAll(): Outcome<List<Alarm>, DomainError> {
        // Suspends like a real database read, so concurrent callers can interleave here.
        yield()
        listFailure?.let { return Outcome.Failure(it) }
        return Outcome.Success(alarms.value.values.sortedWith(AlarmListOrder))
    }

    override suspend fun get(id: String): Outcome<Alarm, DomainError> =
        alarms.value[id]?.let { Outcome.Success(it) } ?: Outcome.Failure(DomainError.NotFound(id))

    override suspend fun upsert(alarm: Alarm): Outcome<Unit, DomainError> {
        yield()
        upserts++
        failure?.let { return Outcome.Failure(it) }
        alarms.value += alarm.id to alarm
        return Outcome.Success(Unit)
    }

    override suspend fun delete(id: String): Outcome<Unit, DomainError> {
        val storageFailure = deleteFailure
        return when {
            storageFailure != null -> Outcome.Failure(storageFailure)
            id !in alarms.value -> Outcome.Failure(DomainError.NotFound(id))
            else -> Outcome.Success(Unit).also { alarms.value -= id }
        }
    }
}

/** Hands out mark + 1, like the persisted high-water mark; [failure] fails every call. */
internal class InMemorySequence(
    var lastUsed: Int = RequestCodes.INITIAL_HIGH_WATER_MARK,
) : RequestCodeSequence {
    var failure: DomainError.StorageFailure? = null

    override suspend fun next(): Outcome<Int, DomainError> {
        // Suspends like the database transaction behind it.
        yield()
        failure?.let { return Outcome.Failure(it) }
        lastUsed += 1
        return Outcome.Success(lastUsed)
    }
}

internal sealed interface Call {
    data class Schedule(
        val alarmId: String,
        val requestCode: Int,
        val at: Instant,
    ) : Call

    data class Cancel(
        val requestCode: Int,
    ) : Call

    data class Slot(
        val deadline: Deadline,
        val alarm: AlarmFired? = null,
    ) : Call

    data object CancelSlot : Call

    data class Test(
        val at: Instant,
    ) : Call
}

/** Records every call; [failure] fails every arming call (cancelling needs no permission); [onCall] runs as each call is made. */
internal class RecordingScheduler : AlarmScheduler {
    val calls = mutableListOf<Call>()
    var failure: DomainError? = null
    var onCall: (Call) -> Unit = {}

    private fun record(call: Call): Outcome<Unit, DomainError> {
        calls += call
        onCall(call)
        val arming = call !is Call.Cancel && call != Call.CancelSlot
        return failure?.takeIf { arming }?.let { Outcome.Failure(it) } ?: Outcome.Success(Unit)
    }

    override fun schedule(
        alarmId: String,
        requestCode: Int,
        triggerAtWallMillis: Long,
    ) = record(Call.Schedule(alarmId, requestCode, Instant.fromEpochMilliseconds(triggerAtWallMillis)))

    override fun cancel(requestCode: Int) = record(Call.Cancel(requestCode))

    override fun armSessionSlot(
        deadline: Deadline,
        alarm: AlarmFired?,
    ) = record(Call.Slot(deadline, alarm))

    override fun cancelSessionSlot() = record(Call.CancelSlot)

    override fun scheduleTest(triggerAtWallMillis: Long) = record(Call.Test(Instant.fromEpochMilliseconds(triggerAtWallMillis)))
}

internal class RecordingLogger : Logger {
    val events = mutableListOf<LogEvent>()

    override fun log(event: LogEvent) {
        events += event
    }
}
