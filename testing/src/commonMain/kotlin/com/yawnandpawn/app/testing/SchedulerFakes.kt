package com.yawnandpawn.app.testing

import com.yawnandpawn.app.core.alarm.AlarmFired
import com.yawnandpawn.app.core.alarm.AlarmRepository
import com.yawnandpawn.app.core.alarm.AlarmScheduler
import com.yawnandpawn.app.core.alarm.AlarmScheduling
import com.yawnandpawn.app.core.alarm.AlarmWriteLock
import com.yawnandpawn.app.core.alarm.DeleteAlarm
import com.yawnandpawn.app.core.alarm.DuplicateAlarm
import com.yawnandpawn.app.core.alarm.RequestCodeSequence
import com.yawnandpawn.app.core.alarm.RequestCodes
import com.yawnandpawn.app.core.alarm.SaveAlarm
import com.yawnandpawn.app.core.alarm.SetAlarmEnabled
import com.yawnandpawn.app.core.error.DomainError
import com.yawnandpawn.app.core.error.Outcome
import com.yawnandpawn.app.core.id.IdGenerator
import com.yawnandpawn.app.core.log.Logger
import com.yawnandpawn.app.core.session.SessionLockGuard
import com.yawnandpawn.app.core.session.SessionState
import com.yawnandpawn.app.core.time.Clock
import com.yawnandpawn.app.core.time.Deadline
import com.yawnandpawn.app.core.time.TimeZoneProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.time.Instant

/** One call on [FakeAlarmScheduler], in the order it was made. */
sealed interface SchedulerCall {
    data class Schedule(
        val alarmId: String,
        val requestCode: Int,
        val triggerAtWallMillis: Long,
    ) : SchedulerCall

    data class Cancel(
        val requestCode: Int,
    ) : SchedulerCall

    data class ArmSessionSlot(
        val deadline: Deadline,
        val alarm: AlarmFired? = null,
        val retrySince: Instant? = null,
    ) : SchedulerCall

    data object CancelSessionSlot : SchedulerCall

    data class ScheduleTest(
        val triggerAtWallMillis: Long,
    ) : SchedulerCall
}

/**
 * [AlarmScheduler] that records every call in [calls] and keeps what is armed in [armed] (request code to trigger,
 * like the system: scheduling a code again replaces it). Set [failure] to make every arming call ([schedule],
 * [armSessionSlot], [scheduleTest]) fail with it (for example `ExactAlarmNotPermitted`); a failed call is still
 * recorded but arms nothing. Cancelling needs no permission, so [cancel] and [cancelSessionSlot] always succeed.
 */
class FakeAlarmScheduler : AlarmScheduler {
    private val recorded = mutableListOf<SchedulerCall>()
    private val armedCodes = linkedMapOf<Int, Long>()

    var failure: DomainError? = null

    /** Every call so far, oldest first. */
    val calls: List<SchedulerCall>
        get() = recorded.toList()

    /**
     * What is armed right now: request code to trigger time (epoch millis). The session slot's entry is the raw
     * `Deadline.wallMillis`; the Android adapter converts a same-boot deadline through the monotonic time left instead.
     */
    val armed: Map<Int, Long>
        get() = armedCodes.toMap()

    /** Forgets the recorded calls (what is armed stays). */
    fun clearCalls() {
        recorded.clear()
    }

    override fun schedule(
        alarmId: String,
        requestCode: Int,
        triggerAtWallMillis: Long,
    ): Outcome<Unit, DomainError> = arm(SchedulerCall.Schedule(alarmId, requestCode, triggerAtWallMillis), requestCode, triggerAtWallMillis)

    override fun cancel(requestCode: Int): Outcome<Unit, DomainError> = disarm(SchedulerCall.Cancel(requestCode), requestCode)

    /** The alarm the armed slot carries: the last successful [armSessionSlot]'s, null after [cancelSessionSlot]. */
    var slotAlarm: AlarmFired? = null

    override fun armSessionSlot(
        deadline: Deadline,
        alarm: AlarmFired?,
        retrySince: Instant?,
    ): Outcome<Unit, DomainError> =
        arm(SchedulerCall.ArmSessionSlot(deadline, alarm, retrySince), RequestCodes.SESSION_SLOT, deadline.wallMillis)
            .also { if (it is Outcome.Success) slotAlarm = alarm }

    override fun sessionSlotAlarm(): AlarmFired? = slotAlarm

    override fun cancelSessionSlot(): Outcome<Unit, DomainError> =
        disarm(SchedulerCall.CancelSessionSlot, RequestCodes.SESSION_SLOT).also { slotAlarm = null }

    override fun scheduleTest(triggerAtWallMillis: Long): Outcome<Unit, DomainError> =
        arm(SchedulerCall.ScheduleTest(triggerAtWallMillis), RequestCodes.TEST_ALARM, triggerAtWallMillis)

    private fun arm(
        call: SchedulerCall,
        requestCode: Int,
        triggerAtWallMillis: Long,
    ): Outcome<Unit, DomainError> {
        recorded += call
        val error = failure
        return if (error != null) {
            Outcome.Failure(error)
        } else {
            armedCodes[requestCode] = triggerAtWallMillis
            Outcome.Success(Unit)
        }
    }

    private fun disarm(
        call: SchedulerCall,
        requestCode: Int,
    ): Outcome<Unit, DomainError> {
        recorded += call
        armedCodes.remove(requestCode)
        return Outcome.Success(Unit)
    }
}

/**
 * In-memory [RequestCodeSequence]: hands out [lastUsed] + 1 and remembers it, like the persisted high-water mark.
 * Set [failure] to make [next] fail with it (and hand out nothing).
 */
class FakeRequestCodeSequence(
    lastUsed: Int = RequestCodes.INITIAL_HIGH_WATER_MARK,
) : RequestCodeSequence {
    /** The highest code handed out so far (the mark). */
    var lastUsed: Int = lastUsed
        private set

    var failure: DomainError? = null

    override suspend fun next(): Outcome<Int, DomainError> {
        failure?.let { return Outcome.Failure(it) }
        lastUsed += 1
        return Outcome.Success(lastUsed)
    }
}

/**
 * The four alarm use cases and [AlarmScheduling] wired to one repository, lock, request-code sequence and scheduler,
 * as the app wires them. Every part can be replaced; the defaults are fakes.
 */
class AlarmUseCasesFixture(
    val repository: AlarmRepository = FakeAlarmRepository(),
    val clock: Clock = FakeClock(),
    val timeZoneProvider: TimeZoneProvider = FakeTimeZoneProvider(),
    val ids: IdGenerator = FakeIdGenerator(),
    val requestCodes: RequestCodeSequence = FakeRequestCodeSequence(),
    val scheduler: AlarmScheduler = FakeAlarmScheduler(),
    val logger: Logger = FakeLogger(),
    val lock: AlarmWriteLock = AlarmWriteLock(),
    /** The session state the use cases' [SessionLockGuard] reads (Story 2.6); Idle unless a test starts a session. */
    val sessionState: MutableStateFlow<SessionState> = MutableStateFlow(SessionState.Idle),
) {
    val scheduling = AlarmScheduling(repository, scheduler, clock, timeZoneProvider, lock, logger)
    val sessionLock = SessionLockGuard(sessionState, restored = MutableStateFlow(true), emergency = MutableStateFlow(false))
    val save = SaveAlarm(repository, ids, clock, lock, requestCodes, scheduling, sessionLock)
    val setEnabled = SetAlarmEnabled(repository, clock, lock, scheduling, sessionLock)
    val delete = DeleteAlarm(repository, lock, scheduling, sessionLock)
    val duplicate = DuplicateAlarm(repository, ids, clock, lock, requestCodes, scheduling, sessionLock)
}
