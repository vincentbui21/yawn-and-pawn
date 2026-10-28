package com.yawnandpawn.app.core.alarm

import com.yawnandpawn.app.core.error.DomainError
import com.yawnandpawn.app.core.error.Outcome
import com.yawnandpawn.app.core.history.SessionHistoryRepository
import com.yawnandpawn.app.core.history.SessionHistoryRow
import com.yawnandpawn.app.core.history.SessionMergeRow
import com.yawnandpawn.app.core.history.SessionOutcome
import com.yawnandpawn.app.core.session.SessionLockGuard
import com.yawnandpawn.app.core.session.SessionRecorder
import com.yawnandpawn.app.core.session.SessionState
import com.yawnandpawn.app.core.session.UserLockState
import com.yawnandpawn.app.core.work.BackgroundJob
import com.yawnandpawn.app.core.work.BackgroundWork
import com.yawnandpawn.app.core.work.SchedulePolicy
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.datetime.TimeZone
import kotlin.time.Duration
import kotlin.time.Instant

// Story 7.11's core doubles (core cannot depend on :testing, AD-1): the pre-alarm work and notifier, a history store and
// the user lock, plus one fixture wiring the skip use cases as the app does.

/** One pre-alarm call on [PreAlarmWork]. */
internal sealed interface PreAlarmWorkCall {
    data class Schedule(
        val alarmId: String,
        val delay: Duration,
    ) : PreAlarmWorkCall

    data class Cancel(
        val alarmId: String,
    ) : PreAlarmWorkCall
}

/** [BackgroundWork] that keeps each alarm's pending pre-alarm delay in [pending] (REPLACE) and every call in [calls]. */
internal class PreAlarmWork : BackgroundWork {
    val pending = mutableMapOf<String, Duration>()
    val calls = mutableListOf<PreAlarmWorkCall>()
    var failure: DomainError? = null

    override fun enqueue(job: BackgroundJob): Outcome<Unit, DomainError> = error("the pre-alarm enqueues no BackgroundJob")

    override fun scheduleWeeklySummary(
        delay: Duration,
        policy: SchedulePolicy,
    ): Outcome<Unit, DomainError> = error("not the weekly summary")

    override fun cancelWeeklySummary(): Outcome<Unit, DomainError> = error("not the weekly summary")

    override fun schedulePreAlarm(
        alarmId: String,
        delay: Duration,
    ): Outcome<Unit, DomainError> {
        failure?.let { return Outcome.Failure(it) }
        calls += PreAlarmWorkCall.Schedule(alarmId, delay)
        pending[alarmId] = delay
        return Outcome.Success(Unit)
    }

    override fun cancelPreAlarm(alarmId: String): Outcome<Unit, DomainError> {
        failure?.let { return Outcome.Failure(it) }
        calls += PreAlarmWorkCall.Cancel(alarmId)
        pending -= alarmId
        return Outcome.Success(Unit)
    }
}

/** [PreAlarmNotifier] that keeps the notification showing per request code in [showing] and every post in [posts]. */
internal class RecordingPreAlarmNotifier(
    var allowed: Boolean = true,
) : PreAlarmNotifier {
    val showing = mutableMapOf<Int, Duration>()
    val posts = mutableListOf<Pair<String, Duration>>()
    var failure: DomainError? = null

    override fun canPost(): Boolean = allowed

    override fun post(
        alarm: Alarm,
        ringsIn: Duration,
    ): Outcome<Unit, DomainError> {
        failure?.let { return Outcome.Failure(it) }
        showing[alarm.requestCode] = ringsIn
        posts += alarm.id to ringsIn
        return Outcome.Success(Unit)
    }

    override fun cancel(requestCode: Int): Outcome<Unit, DomainError> {
        showing -= requestCode
        return Outcome.Success(Unit)
    }
}

internal class TestUserLockState(
    var unlocked: Boolean = true,
) : UserLockState {
    override fun isUserUnlocked(): Boolean = unlocked

    override fun observe(): Flow<Boolean> = flowOf(unlocked)
}

/** An in-memory history: rows by session id; [upsertFailure] and [deleteFailure] refuse those writes. */
internal class MemoryHistory : SessionHistoryRepository {
    val rows = linkedMapOf<String, SessionHistoryRow>()
    var upsertFailure: DomainError? = null
    var deleteFailure: DomainError? = null

    override suspend fun upsert(row: SessionHistoryRow): Outcome<Unit, DomainError> {
        upsertFailure?.let { return Outcome.Failure(it) }
        rows[row.sessionId] = row
        return Outcome.Success(Unit)
    }

    override suspend fun find(sessionId: String): Outcome<SessionHistoryRow?, DomainError> = Outcome.Success(rows[sessionId])

    override fun observeLatestMissed(): Flow<SessionHistoryRow?> = emptyFlow()

    override fun observeAll(): Flow<List<SessionHistoryRow>> = emptyFlow()

    override suspend fun recordMerge(merge: SessionMergeRow): Outcome<Unit, DomainError> = error("no merges here")

    override suspend fun merges(sessionId: String): Outcome<List<SessionMergeRow>, DomainError> = Outcome.Success(emptyList())

    override suspend fun all(): Outcome<List<SessionHistoryRow>, DomainError> = Outcome.Success(rows.values.toList())

    override suspend fun deleteSkipped(
        alarmId: String,
        scheduledAt: Instant,
    ): Outcome<Unit, DomainError> {
        deleteFailure?.let { return Outcome.Failure(it) }
        rows.values
            .filter { it.alarmId == alarmId && it.scheduledAt == scheduledAt && it.outcome == SessionOutcome.Skipped }
            .forEach { rows.remove(it.sessionId) }
        return Outcome.Success(Unit)
    }
}

/** The skip use cases, the scheduling and the pre-alarm jobs over in-memory doubles, wired as the app wires them. */
internal class SkipFixture(
    now: Instant,
    zone: TimeZone = TimeZone.UTC,
) {
    val clock = TestClock(now)
    val zones = TestZone(zone)
    val alarms = InMemoryAlarms()
    val scheduler = RecordingScheduler()
    val work = PreAlarmWork()
    val notifier = RecordingPreAlarmNotifier()
    val userLock = TestUserLockState()
    val logger = RecordingLogger()
    val history = MemoryHistory()
    val lock = AlarmWriteLock()
    val session = MutableStateFlow<SessionState>(SessionState.Idle)
    val sessionLock = SessionLockGuard(session, MutableStateFlow(true), MutableStateFlow(false))
    val preAlarms = PreAlarmScheduler(work, notifier, clock, zones, userLock, logger)
    val scheduling = AlarmScheduling(alarms, scheduler, clock, zones, lock, logger, preAlarms = preAlarms)
    val recorder = SessionRecorder(history)
    val skip = SkipNextAlarm(alarms, recorder, SequentialIds(), clock, zones, lock, scheduling, sessionLock, logger)
    val undo = UndoSkip(alarms, history, recorder, clock, zones, lock, scheduling, sessionLock, logger)
    val task = PreAlarmTask(alarms, notifier, clock, zones, logger)

    suspend fun store(alarm: Alarm) {
        alarms.upsert(alarm)
    }

    fun stored(id: String): Alarm = alarms.alarms.value.getValue(id)
}
