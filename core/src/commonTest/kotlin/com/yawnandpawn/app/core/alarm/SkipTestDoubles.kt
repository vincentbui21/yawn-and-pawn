package com.yawnandpawn.app.core.alarm

import com.yawnandpawn.app.core.error.DomainError
import com.yawnandpawn.app.core.error.Outcome
import com.yawnandpawn.app.core.history.SessionHistoryRepository
import com.yawnandpawn.app.core.history.SessionHistoryRow
import com.yawnandpawn.app.core.history.SessionMergeRow
import com.yawnandpawn.app.core.history.SessionOutcome
import com.yawnandpawn.app.core.reminder.SetSkipReminder
import com.yawnandpawn.app.core.reminder.SkipReminderNotifier
import com.yawnandpawn.app.core.reminder.SkipReminderPrefs
import com.yawnandpawn.app.core.reminder.SkipReminderScheduler
import com.yawnandpawn.app.core.reminder.SkipReminderStore
import com.yawnandpawn.app.core.reminder.SkipReminderTarget
import com.yawnandpawn.app.core.reminder.SkipReminderTask
import com.yawnandpawn.app.core.reminder.SkipTodaysAlarms
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
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlin.time.Duration
import kotlin.time.Instant

// The skip doubles (Story 7.11, 0.1.8; core cannot depend on :testing, AD-1): the Skip reminder's work, notifier and
// store, a history store and the user lock, plus one fixture wiring the skip use cases and the reminder as the app does.

/** [BackgroundWork] that keeps the pending Skip reminder delay in [reminder] (REPLACE) and the legacy cancels. */
internal class ReminderWork : BackgroundWork {
    /** The pending job's delay, or null when none is pending. */
    var reminder: Duration? = null
    val calls = mutableListOf<Duration?>()

    /** The policy of each schedule, oldest first. */
    val policies = mutableListOf<SchedulePolicy>()
    val preAlarmCancels = mutableListOf<String>()
    var failure: DomainError? = null

    override fun enqueue(job: BackgroundJob): Outcome<Unit, DomainError> = error("the reminder enqueues no BackgroundJob")

    override fun scheduleWeeklySummary(
        delay: Duration,
        policy: SchedulePolicy,
    ): Outcome<Unit, DomainError> = error("not the weekly summary")

    override fun cancelWeeklySummary(): Outcome<Unit, DomainError> = error("not the weekly summary")

    override fun cancelPreAlarm(alarmId: String): Outcome<Unit, DomainError> {
        preAlarmCancels += alarmId
        return Outcome.Success(Unit)
    }

    override fun scheduleSkipReminder(
        delay: Duration,
        policy: SchedulePolicy,
    ): Outcome<Unit, DomainError> {
        failure?.let { return Outcome.Failure(it) }
        calls += delay
        policies += policy
        reminder = delay
        return Outcome.Success(Unit)
    }

    override fun cancelSkipReminder(): Outcome<Unit, DomainError> {
        failure?.let { return Outcome.Failure(it) }
        calls += null
        reminder = null
        return Outcome.Success(Unit)
    }
}

/** What [RecordingSkipReminderNotifier] shows. */
internal sealed interface Shown {
    data class Reminder(
        val target: SkipReminderTarget,
        val ringsIn: Duration,
        val tomorrow: Boolean = false,
    ) : Shown

    data class Skipped(
        val dismissIn: Duration,
        val tomorrow: Boolean = false,
    ) : Shown
}

/**
 * [SkipReminderNotifier] that keeps the reminder showing in [showing], the confirmation in [skipped] (two notifications of
 * their own), every reminder in [posts] and every legacy cancel in [legacyCancels].
 */
internal class RecordingSkipReminderNotifier(
    var allowed: Boolean = true,
) : SkipReminderNotifier {
    var showing: Shown.Reminder? = null
    var skipped: Shown.Skipped? = null
    val posts = mutableListOf<Shown.Reminder>()
    val legacyCancels = mutableListOf<Int>()
    var failure: DomainError? = null

    override fun canPost(): Boolean = allowed

    override fun post(
        target: SkipReminderTarget,
        ringsIn: Duration,
        tomorrow: Boolean,
    ): Outcome<Unit, DomainError> {
        failure?.let { return Outcome.Failure(it) }
        val reminder = Shown.Reminder(target, ringsIn, tomorrow)
        showing = reminder
        posts += reminder
        return Outcome.Success(Unit)
    }

    override fun showSkipped(
        dismissIn: Duration,
        tomorrow: Boolean,
    ): Outcome<Unit, DomainError> {
        showing = null
        skipped = Shown.Skipped(dismissIn, tomorrow)
        return Outcome.Success(Unit)
    }

    override fun cancelSkipped(): Outcome<Unit, DomainError> {
        skipped = null
        return Outcome.Success(Unit)
    }

    override fun cancelLegacy(requestCode: Int): Outcome<Unit, DomainError> {
        legacyCancels += requestCode
        return Outcome.Success(Unit)
    }

    override fun cancel(): Outcome<Unit, DomainError> {
        showing = null
        skipped = null
        return Outcome.Success(Unit)
    }
}

/** [SkipReminderStore] in memory; [readFailure] and [writeFailure] refuse those. */
internal class MemorySkipReminderStore(
    var prefs: SkipReminderPrefs = SkipReminderPrefs(),
) : SkipReminderStore {
    var readFailure: DomainError? = null
    var writeFailure: DomainError? = null

    override fun observe(): Flow<SkipReminderPrefs> = flowOf(prefs)

    override suspend fun read(): Outcome<SkipReminderPrefs, DomainError> =
        readFailure?.let { Outcome.Failure(it) } ?: Outcome.Success(prefs)

    override suspend fun setEnabled(on: Boolean): Outcome<Unit, DomainError> = write { it.copy(enabled = on) }

    override suspend fun setLastRemindedDate(date: LocalDate): Outcome<Unit, DomainError> = write { it.copy(lastRemindedDate = date) }

    private fun write(change: (SkipReminderPrefs) -> SkipReminderPrefs): Outcome<Unit, DomainError> {
        writeFailure?.let { return Outcome.Failure(it) }
        prefs = change(prefs)
        return Outcome.Success(Unit)
    }
}

internal class TestUserLockState(
    var unlocked: Boolean = true,
) : UserLockState {
    override fun isUserUnlocked(): Boolean = unlocked

    override fun observe(): Flow<Boolean> = flowOf(unlocked)
}

/**
 * An in-memory history: rows by session id; [upsertFailure] and [deleteFailure] refuse those writes ([upsertFailure]
 * only after [upsertsBeforeFailure] successful upserts).
 */
internal class MemoryHistory : SessionHistoryRepository {
    val rows = linkedMapOf<String, SessionHistoryRow>()
    var upsertFailure: DomainError? = null
    var upsertsBeforeFailure: Int = 0
    var deleteFailure: DomainError? = null

    override suspend fun upsert(row: SessionHistoryRow): Outcome<Unit, DomainError> {
        if (upsertsBeforeFailure > 0) {
            upsertsBeforeFailure--
        } else {
            upsertFailure?.let { return Outcome.Failure(it) }
        }
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

/** The skip use cases, the scheduling and the Skip reminder over in-memory doubles, wired as the app wires them. */
internal class SkipFixture(
    now: Instant,
    zone: TimeZone = TimeZone.UTC,
) {
    val clock = TestClock(now)
    val zones = TestZone(zone)
    val alarms = InMemoryAlarms()
    val scheduler = RecordingScheduler()
    val work = ReminderWork()
    val notifier = RecordingSkipReminderNotifier()
    val reminderStore = MemorySkipReminderStore()
    val userLock = TestUserLockState()
    val logger = RecordingLogger()
    val history = MemoryHistory()
    val lock = AlarmWriteLock()
    val session = MutableStateFlow<SessionState>(SessionState.Idle)
    val sessionLock = SessionLockGuard(session, MutableStateFlow(true), MutableStateFlow(false))
    val reminder =
        SkipReminderScheduler(alarms, reminderStore, notifier, work, clock, zones, userLock, logger) { sessionLock.isLocked }
    val scheduling = AlarmScheduling(alarms, scheduler, clock, zones, lock, logger, skipReminder = reminder)
    val recorder = SessionRecorder(history)
    val skip = SkipNextAlarm(alarms, recorder, SequentialIds(), clock, zones, lock, scheduling, sessionLock, logger)
    val undo = UndoSkip(alarms, history, recorder, clock, zones, lock, scheduling, sessionLock, logger)
    val skipToday = SkipTodaysAlarms(alarms, skip, clock, zones)
    val setReminder = SetSkipReminder(reminderStore, reminder, sessionLock)
    val task = SkipReminderTask(reminder)

    suspend fun store(alarm: Alarm) {
        alarms.upsert(alarm)
    }

    fun stored(id: String): Alarm = alarms.alarms.value.getValue(id)
}
