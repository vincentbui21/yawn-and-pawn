package com.yawnandpawn.app.core.session

import com.yawnandpawn.app.core.error.DomainError
import com.yawnandpawn.app.core.error.Outcome
import com.yawnandpawn.app.core.log.LogEvent
import com.yawnandpawn.app.core.log.Logger
import com.yawnandpawn.app.core.time.BootCounter
import com.yawnandpawn.app.core.time.Clock
import com.yawnandpawn.app.core.time.MonotonicClock
import com.yawnandpawn.app.core.time.TimeSnapshot
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException

/**
 * The one owner of the wake session (AD-2 rule 1) and the only caller of [SessionReducer]. One instance per process;
 * every call runs under one Mutex, so transitions are strictly serial.
 *
 * Each step is write-ahead (AD-2 rule 2): read the time ports once, reduce, commit the new state to the
 * [ActiveSessionStore] (with the purchase intent of a Pay or the grant ledger row of a paid snooze in the same
 * transaction, Stories 4.8 and 4.10), and only after the
 * commit succeeded publish it in [state] and run the effects through the
 * [EffectRunner]: the one-shot effects in order, then the state's [entryEffects]. A step whose state did not change
 * (an ignored event) commits nothing but still runs its effects. If the commit fails nothing runs, the previous state
 * stays, the failure is logged and returned. A failing effect is logged and never undoes the commit. Once a commit
 * succeeded, the publish, the effects and the timer events that follow run to the end even if the caller is cancelled.
 * After every committed step the follow-up events are dispatched the same way, at most [MAX_DUE_ROUNDS] rounds; a
 * failure there is logged and does not fail the call, whose own event is committed. The follow-up events are the
 * timer events from [dueEvents] and, once the history row of a Completed or Missed session is written, `Recorded`.
 *
 * Session history (AD-18) is the engine's own business, never the runner's: the one-shot
 * [SessionEffect.RecordSessionStart], the one-shot [SessionEffect.RecordMergedOccurrence] (Story 2.9) and the entry effect
 * [EntryEffect.HistoryWriteRequested] go to the [SessionRecorder]. The start row is written after the step's other
 * effects and its entry effects, so the first sound never waits for it (the state is already committed, AD-2). The
 * merge row is written before the step's commit, so a kill right after the commit cannot lose it (insert or ignore:
 * written again, it leaves one row). When the end write succeeds, `Recorded` is reduced in the same lock, so the
 * session goes Idle and its `runtime.db` row is cleared by that commit. When it fails, the failure is logged, the
 * state stays Completed or Missed, and the next [dispatch], [tick] or [restore] writes it again.
 *
 * Nothing is reduced before the stored session is loaded: [dispatch] and [tick] first run the [restore] step if it has
 * not succeeded yet, and return its failure if the store cannot be read (the next call tries again). Expected failures
 * come back as [Outcome] values; nothing here throws for them.
 */
class SessionEngine internal constructor(
    private val reducer: SessionReducer,
    private val store: ActiveSessionStore,
    private val effects: EffectRunner,
    private val recorder: SessionRecorder,
    private val clock: Clock,
    private val monotonicClock: MonotonicClock,
    private val bootCounter: BootCounter,
    private val logger: Logger,
    private val due: (SessionState, TimeSnapshot) -> List<SessionEvent>,
    private val userLock: UserLockState = UserLockState.Unlocked,
    private val unlock: UnlockPort = UnlockPort.Unlocked,
) {
    constructor(
        reducer: SessionReducer,
        store: ActiveSessionStore,
        effects: EffectRunner,
        recorder: SessionRecorder,
        clock: Clock,
        monotonicClock: MonotonicClock,
        bootCounter: BootCounter,
        logger: Logger,
        userLock: UserLockState = UserLockState.Unlocked,
        unlock: UnlockPort = UnlockPort.Unlocked,
    ) : this(reducer, store, effects, recorder, clock, monotonicClock, bootCounter, logger, ::dueEvents, userLock, unlock)

    private val mutex = Mutex()
    private val current = MutableStateFlow<SessionState>(SessionState.Idle)

    /** The stored session has been loaded (or there was none); until then every call loads it first. */
    private var storeLoaded = false

    private val restoredFlag = MutableStateFlow(false)

    private val endedFlag = MutableStateFlow<SessionState.Active?>(null)

    /** The history effects go here, never to the runner. */
    private val history = EngineHistory(recorder, logger)

    /** The last committed state. The main app shows "Alarm in progress" from it (AD-11). */
    val state: StateFlow<SessionState> = current.asStateFlow()

    /**
     * True once the stored session is loaded and its state published in [state]. It is set after [state], so a reader
     * that sees true here then reads the restored state there. Until then [state] is Idle only because nothing is
     * loaded yet, so the session lock treats that as locked (Story 2.6). It stays false while loading fails.
     */
    val restored: StateFlow<Boolean> = restoredFlag.asStateFlow()

    /**
     * The last session that ended in this process: its Completed or Missed state, kept after `Recorded` makes [state]
     * Idle. [state] publishes Completed and Idle in one call, so a collector may only see Idle; this is set before
     * [state], so a reader that sees the session gone there reads how it ended here (Story 3.3: the Success screen). In
     * memory only, never stored; null until a session ends.
     */
    val ended: StateFlow<SessionState.Active?> = endedFlag.asStateFlow()

    /** Runs [event] through the reducer, commits, runs its effects, then any follow-up events now due. Returns the state. */
    suspend fun dispatch(event: SessionEvent): Outcome<SessionState, DomainError> =
        mutex.withLock {
            // Nothing is reduced before the stored session is loaded; a failed load is returned and retried next call.
            if (!storeLoaded) load().let { if (!storeLoaded) return@withLock it }
            // AD-2 guard "history row written": a Recorded from outside never ends a session whose row is not written.
            // It only retries the write, and the engine reduces its own Recorded once that succeeds.
            if (event is SessionEvent.Recorded && history.pending(current.value)) return@withLock settleHistory(forNewAlarm = false)
            // A new alarm never stays silent behind an ended session (NFR-2): retry its write once, give up if it still
            // fails, and reduce Recorded (Idle) before the new alarm's own event.
            val startsSession = event is SessionEvent.AlarmFired || event is SessionEvent.TestAlarmFired
            if (startsSession && history.ended(current.value)) settleHistory(forNewAlarm = true)
            when (val stepped = step(event, runOneShot = true)) {
                is Outcome.Failure -> stepped
                is Outcome.Success -> withContext(NonCancellable) { dispatchDue(stepped.value) }
            }
        }

    /**
     * Dispatches the timer events due now (grace end, interaction timeout), and retries a history write that failed.
     * The wake runtime calls it (Story 1.14).
     */
    suspend fun tick(): Outcome<SessionState, DomainError> =
        mutex.withLock {
            if (!storeLoaded) load().let { if (!storeLoaded) return@withLock it }
            settleHistory(forNewAlarm = false)
        }

    /**
     * Retries the end write of an ended session (nothing when it is written or the state did not end); for a new alarm
     * a write that still fails is abandoned. Then runs the follow-up events due now: `Recorded` once written.
     */
    private suspend fun settleHistory(forNewAlarm: Boolean): Outcome<SessionState, DomainError> =
        withContext(NonCancellable) {
            val now = now()
            val ended = current.value
            if (ended is SessionState.Active && history.pending(ended)) {
                guarded(EntryEffect.HistoryWriteRequested(ended.session.sessionId)) { history.recordEnd(ended, now) }
            }
            if (forNewAlarm) history.abandon(ended)
            dispatchDue(now)
        }

    /**
     * Loads the persisted session; app start calls it, and [dispatch] and [tick] call it first if it has not succeeded.
     * Nothing stored (or a stored Idle, whose row is cleared): stays Idle and runs nothing. A stored session is taken
     * over and gets `ProcessRestored` (committed like any step); that transition's own one-shot effects are discarded,
     * only the entry effects of the resulting state run, and nothing committed before the crash is replayed. Follow-up
     * events found due after the restore (for example a grace window that ended while the process was dead, or
     * `Recorded` once a stored Completed session's history row is written) are new transitions and run with their
     * effects. An undecodable row is logged and cleared, and the engine stays Idle.
     * Once loaded, further calls return the current state and do nothing.
     */
    suspend fun restore(): Outcome<SessionState, DomainError> =
        mutex.withLock { if (storeLoaded) Outcome.Success(current.value) else load() }

    private suspend fun load(): Outcome<SessionState, DomainError> =
        when (val loaded = store.load()) {
            is Outcome.Failure -> {
                logger.log(LogEvent.OperationFailed.of(RESTORE, loaded.error))
                loaded
            }

            is Outcome.Success -> {
                storeLoaded = true
                try {
                    restoreFrom(loaded.value)
                } finally {
                    restoredFlag.value = true
                }
            }
        }

    /**
     * Takes over a stored session with `ProcessRestored`; a row that holds none (unreadable, or Idle) is deleted and the
     * engine stays Idle.
     */
    private suspend fun restoreFrom(stored: StoredSession): Outcome<SessionState, DomainError> {
        val loaded = (stored as? StoredSession.Found)?.state?.takeIf { it != SessionState.Idle }
        if (loaded == null) {
            if (stored is StoredSession.Unreadable) logger.log(LogEvent.OperationFailed(RESTORE, "unreadable session: ${stored.cause}"))
            if (stored != StoredSession.Empty) {
                val cleared = store.clear()
                if (cleared is Outcome.Failure) logger.log(LogEvent.OperationFailed.of("clear stored session", cleared.error))
            }
            return Outcome.Success(SessionState.Idle)
        }
        return when (val restoredStep = step(SessionEvent.ProcessRestored, runOneShot = false, from = loaded)) {
            is Outcome.Success -> {
                withContext(NonCancellable) { dispatchDue(restoredStep.value) }
            }

            is Outcome.Failure -> {
                // The loaded state is already committed, so its runtime may run: an alarm is never left silent.
                withContext(NonCancellable) {
                    current.value = loaded
                    applyEntryEffects(loaded, now())
                }
                restoredStep
            }
        }
    }

    /**
     * One write-ahead step from [from]. Returns the time it was taken at. The history start goes to [history], every
     * other one-shot effect to the runner.
     */
    private suspend fun step(
        event: SessionEvent,
        runOneShot: Boolean,
        from: SessionState = current.value,
    ): Outcome<TimeSnapshot, DomainError> {
        val now = now()
        // Read with the time ports: a ring that starts or is restored while locked is before the first unlock (Story 2.3).
        // The keyguard only matters to a Pay (Spike S1), so no other step makes that platform call. A port that cannot tell
        // counts as locked: the unlock request then succeeds at once on an unlocked phone (S1 run L-s1), whereas a launch
        // behind a keyguard shows nothing.
        val keyguardLocked = event is SessionEvent.PayConfirmed && runCatching { unlock.isKeyguardLocked() }.getOrDefault(true)
        val transition = reducer.reduce(from, event, now, userLocked = !userLock.isUserUnlocked(), keyguardLocked = keyguardLocked)
        val oneShot = if (runOneShot) transition.effects else emptyList()
        // Rows written with the state (AD-2 rule 2, AD-7): the purchase intent of a Pay and the grant ledger row of a paid
        // snooze (Story 4.10) are committed in its transaction.
        val writes =
            transition.effects.mapNotNull { effect ->
                when (effect) {
                    is SessionEffect.PersistPurchaseIntent -> RuntimeWrite.PutPurchaseIntent(effect.intent)
                    is SessionEffect.PersistGrant -> RuntimeWrite.PutGrant(effect.grant)
                    else -> null
                }
            }
        return withContext(NonCancellable) {
            // The merge row is written before the commit (Story 2.9 review): a kill between the two would otherwise lose
            // it for good, since a restore runs no one-shot effects. Insert or ignore, so a merge dispatched again after a
            // failed commit still leaves one row with the first time.
            oneShot.filterIsInstance<SessionEffect.RecordMergedOccurrence>().forEach { guarded(it) { history.recordMerge(it, now) } }
            val unchanged = transition.state == from && writes.isEmpty()
            val committed = if (unchanged) Outcome.Success(Unit) else store.commit(transition.state, writes)
            if (committed is Outcome.Failure) {
                logger.log(LogEvent.OperationFailed.of(COMMIT, committed.error))
                committed
            } else {
                val next = transition.state
                if (next is SessionState.Completed || next is SessionState.Missed) endedFlag.value = next
                current.value = next
                oneShot.filterNot { it.isEngineOwned() }.forEach { effect -> guarded(effect) { effects.run(effect) } }
                applyEntryEffects(transition.state, now)
                // The start row is written after the effects (device test round 1): the sound never waits for it.
                // Still inside the lock, so a start row lands before any end row of this session (recordEnd merges).
                oneShot.filterIsInstance<SessionEffect.RecordSessionStart>().forEach { start ->
                    guarded(start) { history.recordStart(start, transition.state) }
                }
                Outcome.Success(now)
            }
        }
    }

    /**
     * The follow-up events after a step at [now], each through the full [step], at most [MAX_DUE_ROUNDS] rounds:
     * `Recorded` once an ended session's history row is written, otherwise the timer events now due. The call's own
     * event is already committed, so a failure here is logged (by [step]) and the committed state returned.
     */
    private suspend fun dispatchDue(now: TimeSnapshot): Outcome<SessionState, DomainError> {
        val followUps = { at: TimeSnapshot -> history.followUp(current.value).ifEmpty { due(current.value, at) } }
        var at = now
        var rounds = 0
        var pending = followUps(at)
        while (pending.isNotEmpty() && rounds < MAX_DUE_ROUNDS) {
            for (event in pending) {
                when (val stepped = step(event, runOneShot = true)) {
                    is Outcome.Failure -> return Outcome.Success(current.value)
                    is Outcome.Success -> at = stepped.value
                }
            }
            rounds++
            pending = followUps(at)
        }
        if (pending.isNotEmpty()) logger.log(LogEvent.OperationFailed("dispatch due events", "round limit reached"))
        return Outcome.Success(current.value)
    }

    /** The entry effects of [state] at [now]: the history write goes to [history], the rest to the runner. */
    private suspend fun applyEntryEffects(
        state: SessionState,
        now: TimeSnapshot,
    ) {
        entryEffects(state).forEach { effect ->
            guarded(effect) { if (effect is EntryEffect.HistoryWriteRequested) history.recordEnd(state, now) else effects.apply(effect) }
        }
    }

    // An effect is an adapter call; whatever it throws (errors too) is logged by type name only (no user content) and
    // the session goes on. Cancellation still propagates.
    @Suppress("TooGenericExceptionCaught")
    private suspend inline fun guarded(
        effect: Any,
        block: () -> Unit,
    ) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            logger.log(LogEvent.OperationFailed("run session effect ${effect::class.simpleName}", e::class.simpleName ?: "Throwable"))
        }
    }

    private fun now(): TimeSnapshot = TimeSnapshot.of(clock, monotonicClock, bootCounter)

    companion object {
        /** Bound on follow-up rounds after one step; the reducer needs at most three (grace end, timeout, Recorded). */
        const val MAX_DUE_ROUNDS = 8

        private const val COMMIT = "commit session state"
        private const val RESTORE = "restore session"
    }
}

/**
 * A one-shot effect the engine carries out itself, never the runner: the history writes (AD-18; merges since Story 2.9)
 * go to the [SessionRecorder], and the purchase intent (Story 4.8) and the grant ledger row (Story 4.10) are written in
 * the step's commit.
 */
private fun SessionEffect.isEngineOwned(): Boolean =
    this is SessionEffect.RecordSessionStart ||
        this is SessionEffect.RecordMergedOccurrence ||
        this is SessionEffect.PersistPurchaseIntent ||
        this is SessionEffect.PersistGrant
