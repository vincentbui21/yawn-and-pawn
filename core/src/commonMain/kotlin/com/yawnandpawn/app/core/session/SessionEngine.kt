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
 * [ActiveSessionStore], and only after the commit succeeded publish it in [state] and run the effects through the
 * [EffectRunner]: the one-shot effects in order, then the state's [entryEffects]. A step whose state did not change
 * (an ignored event) commits nothing but still runs its effects. If the commit fails nothing runs, the previous state
 * stays, the failure is logged and returned. A failing effect is logged and never undoes the commit. Once a commit
 * succeeded, the publish, the effects and the timer events that follow run to the end even if the caller is cancelled.
 * After every committed step the timer events from [dueEvents] are dispatched the same way, at most [MAX_DUE_ROUNDS]
 * rounds; a failure there is logged and does not fail the call, whose own event is committed.
 *
 * Nothing is reduced before the stored session is loaded: [dispatch] and [tick] first run the [restore] step if it has
 * not succeeded yet, and return its failure if the store cannot be read (the next call tries again). Expected failures
 * come back as [Outcome] values; nothing here throws for them.
 */
class SessionEngine internal constructor(
    private val reducer: SessionReducer,
    private val store: ActiveSessionStore,
    private val effects: EffectRunner,
    private val clock: Clock,
    private val monotonicClock: MonotonicClock,
    private val bootCounter: BootCounter,
    private val logger: Logger,
    private val due: (SessionState, TimeSnapshot) -> List<SessionEvent>,
) {
    constructor(
        reducer: SessionReducer,
        store: ActiveSessionStore,
        effects: EffectRunner,
        clock: Clock,
        monotonicClock: MonotonicClock,
        bootCounter: BootCounter,
        logger: Logger,
    ) : this(reducer, store, effects, clock, monotonicClock, bootCounter, logger, ::dueEvents)

    private val mutex = Mutex()
    private val current = MutableStateFlow<SessionState>(SessionState.Idle)

    /** The stored session has been loaded (or there was none); until then every call loads it first. */
    private var restored = false

    /** The last committed state. The main app shows "Alarm in progress" from it (AD-11). */
    val state: StateFlow<SessionState> = current.asStateFlow()

    /** Runs [event] through the reducer, commits, runs its effects, then any timer events now due. Returns the state. */
    suspend fun dispatch(event: SessionEvent): Outcome<SessionState, DomainError> =
        mutex.withLock {
            // Nothing is reduced before the stored session is loaded; a failed load is returned and retried next call.
            if (!restored) load().let { if (!restored) return@withLock it }
            when (val stepped = step(event, runOneShot = true)) {
                is Outcome.Failure -> stepped
                is Outcome.Success -> withContext(NonCancellable) { dispatchDue(stepped.value) }
            }
        }

    /** Dispatches the timer events due now (grace end, interaction timeout). The wake runtime calls it (Story 1.14). */
    suspend fun tick(): Outcome<SessionState, DomainError> =
        mutex.withLock {
            if (!restored) load().let { if (!restored) return@withLock it }
            withContext(NonCancellable) { dispatchDue(now()) }
        }

    /**
     * Loads the persisted session; app start calls it, and [dispatch] and [tick] call it first if it has not succeeded.
     * Nothing stored (or a stored Idle, whose row is cleared): stays Idle and runs nothing. A stored session is taken
     * over and gets `ProcessRestored` (committed like any step); that transition's own one-shot effects are discarded,
     * only the entry effects of the resulting state run, and nothing committed before the crash is replayed. Timer
     * events found due after the restore (for example a grace window that ended while the process was dead) are new
     * transitions and run with their effects. An undecodable row is logged and cleared, and the engine stays Idle.
     * Once loaded, further calls return the current state and do nothing.
     */
    suspend fun restore(): Outcome<SessionState, DomainError> = mutex.withLock { if (restored) Outcome.Success(current.value) else load() }

    private suspend fun load(): Outcome<SessionState, DomainError> =
        when (val loaded = store.load()) {
            is Outcome.Failure -> {
                logger.log(LogEvent.OperationFailed.of(RESTORE, loaded.error))
                loaded
            }

            is Outcome.Success -> {
                restored = true
                restoreFrom(loaded.value)
            }
        }

    /** Takes over a stored session; a row that holds none (unreadable, or Idle) is deleted and the engine stays Idle. */
    private suspend fun restoreFrom(stored: StoredSession): Outcome<SessionState, DomainError> {
        val session = (stored as? StoredSession.Found)?.state?.takeIf { it != SessionState.Idle }
        if (session != null) return resume(session)
        if (stored is StoredSession.Unreadable) logger.log(LogEvent.OperationFailed(RESTORE, "unreadable session: ${stored.cause}"))
        if (stored != StoredSession.Empty) {
            val cleared = store.clear()
            if (cleared is Outcome.Failure) logger.log(LogEvent.OperationFailed.of("clear stored session", cleared.error))
        }
        return Outcome.Success(SessionState.Idle)
    }

    private suspend fun resume(loaded: SessionState): Outcome<SessionState, DomainError> =
        when (val restoredStep = step(SessionEvent.ProcessRestored, runOneShot = false, from = loaded)) {
            is Outcome.Success -> {
                withContext(NonCancellable) { dispatchDue(restoredStep.value) }
            }

            is Outcome.Failure -> {
                // The loaded state is already committed, so its runtime may run: an alarm is never left silent.
                withContext(NonCancellable) {
                    current.value = loaded
                    applyEntryEffects(loaded)
                }
                restoredStep
            }
        }

    /** One write-ahead step from [from]. Returns the time it was taken at. */
    private suspend fun step(
        event: SessionEvent,
        runOneShot: Boolean,
        from: SessionState = current.value,
    ): Outcome<TimeSnapshot, DomainError> {
        val now = now()
        val transition = reducer.reduce(from, event, now)
        return withContext(NonCancellable) {
            val committed = if (transition.state == from) Outcome.Success(Unit) else store.commit(transition.state)
            if (committed is Outcome.Failure) {
                logger.log(LogEvent.OperationFailed.of(COMMIT, committed.error))
                committed
            } else {
                current.value = transition.state
                if (runOneShot) transition.effects.forEach { effect -> guarded(effect) { effects.run(effect) } }
                applyEntryEffects(transition.state)
                Outcome.Success(now)
            }
        }
    }

    /**
     * The timer events due after a step at [now], each through the full [step], at most [MAX_DUE_ROUNDS] rounds. The
     * call's own event is already committed, so a failure here is logged (by [step]) and the committed state returned.
     */
    private suspend fun dispatchDue(now: TimeSnapshot): Outcome<SessionState, DomainError> {
        var at = now
        var rounds = 0
        var pending = due(current.value, at)
        while (pending.isNotEmpty() && rounds < MAX_DUE_ROUNDS) {
            for (event in pending) {
                when (val stepped = step(event, runOneShot = true)) {
                    is Outcome.Failure -> return Outcome.Success(current.value)
                    is Outcome.Success -> at = stepped.value
                }
            }
            rounds++
            pending = due(current.value, at)
        }
        if (pending.isNotEmpty()) logger.log(LogEvent.OperationFailed("dispatch due events", "round limit reached"))
        return Outcome.Success(current.value)
    }

    private suspend fun applyEntryEffects(state: SessionState) {
        entryEffects(state).forEach { effect -> guarded(effect) { effects.apply(effect) } }
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
        /** Bound on timer-event rounds after one step; the reducer needs at most two (grace end, then timeout). */
        const val MAX_DUE_ROUNDS = 8

        private const val COMMIT = "commit session state"
        private const val RESTORE = "restore session"
    }
}
