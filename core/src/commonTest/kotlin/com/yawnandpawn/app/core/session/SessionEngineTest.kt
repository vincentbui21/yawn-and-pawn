package com.yawnandpawn.app.core.session

import com.yawnandpawn.app.core.checks.CheckAnswer
import com.yawnandpawn.app.core.checks.CheckMode
import com.yawnandpawn.app.core.checks.CheckPlan
import com.yawnandpawn.app.core.checks.CheckType
import com.yawnandpawn.app.core.error.DomainError
import com.yawnandpawn.app.core.error.Outcome
import com.yawnandpawn.app.core.history.SessionHistoryRow
import com.yawnandpawn.app.core.history.SessionOutcome
import com.yawnandpawn.app.core.log.LogEvent
import com.yawnandpawn.app.core.time.Deadline
import com.yawnandpawn.app.core.time.TimeSnapshot
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/** AD-2 rules 1 and 2: the engine commits every transition before its effects, serially, and restores without replay. */
class SessionEngineTest {
    private val time = EngineTime()
    private val store = InMemorySessionStore()
    private val runner = RecordingRunner()
    private val logger = EngineLogger()
    private val history = InMemoryHistory()
    private val reducer = reducer(availability = SnoozeAvailability.Available(OFFER), check = StepResult.ValidLast)

    private fun engine(
        effects: EffectRunner = runner,
        sessionReducer: SessionReducer = reducer,
    ) = SessionEngine(sessionReducer, store, effects, SessionRecorder(history), time.clock, time.monotonicClock, time.bootCounter, logger)

    private val alarmFired = SessionEvent.AlarmFired(SESSION_ID, testConfig(), beforeFirstUnlock = false)

    private fun Outcome<SessionState, DomainError>.state(): SessionState = assertIs<Outcome.Success<SessionState>>(this).value

    private fun Outcome<SessionState, DomainError>.session(): SessionData = assertIs<SessionState.Active>(state()).session

    @Test
    fun `a dispatch commits the new state, then runs the one-shot effects in order, then the entry effects`() =
        runTest {
            val engine = engine()
            var effectsAtCommit = -1
            store.onCommit = { effectsAtCommit = runner.ran.size }

            val ringing = assertIs<SessionState.Ringing>(engine.dispatch(alarmFired).state())

            assertEquals(0, effectsAtCommit, "nothing ran before the commit")
            assertEquals(listOf<SessionState>(ringing), store.commits)
            assertEquals(ringing, store.stored)
            assertEquals(ringing, engine.state.value)
            val transition = reducer.reduce(SessionState.Idle, alarmFired, T0)
            assertEquals(runnerEffects(transition) + entryEffects(ringing), runner.ran)
            assertEquals(
                listOf<SessionEffect>(SessionEffect.StartWakeRuntime(SESSION_ID)),
                runner.oneShot.filter { it !is SessionEffect.ArmSlot },
            )
            assertTrue(SessionEffect.RecordSessionStart(SESSION_ID, testConfig()) in transition.effects)
            assertEquals(null, history.rows.getValue(SESSION_ID).outcome, "the recorder wrote the start row, not the runner")
        }

    @Test
    fun `a failed commit keeps the previous state, runs nothing, logs and returns the failure, and the next dispatch works`() =
        runTest {
            val engine = engine()
            store.commitFailure = DomainError.StorageFailure("disk full")

            assertEquals(Outcome.Failure(DomainError.StorageFailure("disk full")), engine.dispatch(alarmFired))

            assertEquals(SessionState.Idle, engine.state.value)
            assertEquals(emptyList(), runner.ran)
            assertNull(store.row)
            assertEquals(listOf<LogEvent>(LogEvent.OperationFailed("commit session state", "storage failure: disk full")), logger.events)

            store.commitFailure = null
            assertIs<SessionState.Ringing>(engine.dispatch(alarmFired).state())
            assertTrue(runner.ran.isNotEmpty())
        }

    @Test
    fun `100 concurrent dispatches give a strictly serial transition log`() =
        runTest {
            val engine = engine()
            // Every snapshot differs, so two interleaved transitions from one state would commit different states.
            time.stepPerRead = 1
            engine.dispatch(alarmFired)
            val log = mutableListOf<Pair<SessionState, SessionState>>()
            store.onCommit = { committed ->
                log += engine.state.value to committed
                // Give every other dispatch the chance to interleave here, between commit and publish.
                yield()
            }

            coroutineScope { repeat(100) { launch { engine.dispatch(SessionEvent.UserInteracted) } } }

            assertEquals(100, log.size)
            log.zipWithNext().forEach { (previous, next) -> assertEquals(previous.second, next.first) }
            assertEquals(100, log.map { it.second }.toSet().size, "every transition moved the deadline")
            assertEquals(log.last().second, engine.state.value)
        }

    @Test
    fun `an interaction deadline that passed is dispatched right after any dispatch, and Missed is committed and recorded`() =
        runTest {
            val engine = engine()
            engine.dispatch(alarmFired)
            time.advanceBy(30.minutes)
            runner.ran.clear()

            assertEquals(Outcome.Success(SessionState.Idle), engine.dispatch(SessionEvent.SlotFired))

            assertEquals(listOf("Ringing", "Missed", "Idle"), store.commits.map { it.kind }, "the heartbeat changes no state")
            assertNull(store.row)
            val stop = listOf(SessionEffect.StopSound, SessionEffect.CancelSlot, SessionEffect.ClearRuntimeSession(SESSION_ID))
            assertEquals<List<Any>>(stop, runner.ran.takeLast(3))
            val row = history.rows.getValue(SESSION_ID)
            assertEquals(SessionOutcome.Missed, row.outcome)
            assertNull(row.timeToCompleteMs)
            assertEquals(Instant.fromEpochMilliseconds(time.now.wallMillis), row.endedAt)
        }

    @Test
    fun `tick dispatches the grace end once it passed and Loud is committed`() =
        runTest {
            val engine = engine()
            engine.dispatch(alarmFired)
            engine.dispatch(SessionEvent.ImUpTapped)
            val commits = store.commits.size

            assertIs<SessionState.Grace>(engine.tick().state(), "nothing due yet")
            assertEquals(commits, store.commits.size, "a tick with nothing due commits nothing")

            time.advanceBy(20.seconds)
            runner.ran.clear()
            val loud = assertIs<SessionState.Loud>(engine.tick().state())

            assertEquals(loud, store.stored)
            assertEquals(listOf(SessionEffect.UnmuteToVolume(80), SessionEffect.StrongHaptic) + entryEffects(loud), runner.ran)
        }

    @Test
    fun `a session killed after committing PayConfirmed is restored without billing and with paying cleared`() =
        runTest {
            val crashing = RecordingRunner().apply { hangOn = { it is SessionEffect.PersistPurchaseIntent } }
            val first = engine(effects = crashing)
            first.dispatch(alarmFired)
            // The process dies inside the first effect: that dispatch never finishes, and its engine is never used again.
            backgroundScope.launch { first.dispatch(SessionEvent.PayConfirmed(INTENT)) }
            runCurrent()
            assertEquals(INTENT, assertIs<SessionState.Ringing>(store.stored).session.paying, "committed before its effects")

            time.advanceBy(2.minutes)
            val second = engine()
            var publishedAtCommit: SessionState? = null
            store.onCommit = { publishedAtCommit = second.state.value }
            val restored = second.restore()

            assertEquals(SessionState.Idle, publishedAtCommit, "the pre-crash state is never published before the restore commit")

            val session = restored.session()
            assertNull(session.paying)
            assertEquals(session, assertIs<SessionState.Ringing>(store.stored).session, "ProcessRestored committed")
            assertEquals<List<Any>>(entryEffects(restored.state()), runner.ran, "only entry effects ran")
            assertTrue((crashing.ran + runner.ran).none { it is SessionEffect.LaunchBilling || it is SessionEffect.PersistPurchaseIntent })
        }

    @Test
    fun `a restored ring gets a fresh interaction deadline from the restore`() =
        runTest {
            engine().dispatch(alarmFired)
            time.advanceBy(29.minutes)

            val session = engine(effects = RecordingRunner()).restore().session()

            assertEquals(time.now.elapsedMillis + 30.minutes.inWholeMilliseconds, session.interactionDeadline?.elapsedMillis)
        }

    @Test
    fun `restoring an overdue snooze rings again but discards the restore's one-shot effects`() =
        runTest {
            store.row = SessionJson.encode(SessionState.Snoozed(snoozedSession()))
            time.advanceBy(10.minutes)
            val engine = engine()

            val ringing = assertIs<SessionState.Ringing>(engine.restore().state())

            assertEquals(2, ringing.session.ringIndex)
            assertEquals(ringing, store.stored)
            assertEquals<List<Any>>(entryEffects(ringing), runner.ran)
            assertEquals(ringing, engine.state.value)
        }

    @Test
    fun `restoring a Grace whose window ended while the process was dead goes Loud with the grace end effects`() =
        runTest {
            val grace = SessionState.Grace(ringSession().copy(graceEnd = Deadline.after(T0, 20.seconds)))
            store.row = SessionJson.encode(grace)
            time.advanceBy(30.seconds)
            val engine = engine()

            val loud = assertIs<SessionState.Loud>(engine.restore().state())

            assertEquals(loud, store.stored)
            assertEquals(listOf("Grace", "Loud"), store.commits.map { it.kind })
            val restoredGrace = assertIs<SessionState.Grace>(store.commits.first())
            val graceEnd = listOf(SessionEffect.UnmuteToVolume(80), SessionEffect.StrongHaptic)
            assertEquals<List<Any>>(entryEffects(restoredGrace) + graceEnd + entryEffects(loud), runner.ran)
        }

    @Test
    fun `restoring with nothing stored stays Idle and runs nothing`() =
        runTest {
            val engine = engine()

            assertEquals(Outcome.Success(SessionState.Idle), engine.restore())

            assertEquals(SessionState.Idle, engine.state.value)
            assertEquals(emptyList(), runner.ran)
            assertEquals(emptyList(), store.commits)
            assertEquals(0, store.clears)
            assertEquals(emptyList(), logger.events)
        }

    @Test
    fun `a stored Idle row is cleared and the engine stays Idle`() =
        runTest {
            store.row = SessionJson.encode(SessionState.Idle)

            assertEquals(Outcome.Success(SessionState.Idle), engine().restore())

            assertNull(store.row)
            assertEquals(1, store.clears)
            assertEquals(emptyList(), runner.ran)
            assertEquals(emptyList(), store.commits)
        }

    @Test
    fun `a dispatch before restore restores the stored session first, keeps its row and applies to it`() =
        runTest {
            val persisted = SessionState.Ringing(ringSession())
            store.row = SessionJson.encode(persisted)
            time.advanceBy(1.minutes)
            val engine = engine()

            val grace = assertIs<SessionState.Grace>(engine.dispatch(SessionEvent.ImUpTapped).state())

            assertEquals(SESSION_ID, grace.session.sessionId)
            assertEquals(grace, store.stored, "the row is kept and holds the event's result")
            assertEquals(listOf("Ringing", "Grace"), store.commits.map { it.kind }, "ProcessRestored, then the event")
            assertEquals(time.now.elapsedMillis + 30.minutes.inWholeMilliseconds, grace.session.interactionDeadline?.elapsedMillis)
            assertEquals(Outcome.Success(grace), engine.restore(), "restore after that does nothing")
        }

    @Test
    fun `restored turns true only once the stored session is published, and stays false while the load fails (Story 2-6)`() =
        runTest {
            store.row = SessionJson.encode(SessionState.Ringing(ringSession()))
            store.loadFailure = DomainError.StorageFailure("locked")
            val engine = engine()
            var stateWhenRestored: SessionState? = null
            val watch = launch { engine.restored.collect { if (it) stateWhenRestored = engine.state.value } }
            runCurrent()

            engine.restore()
            runCurrent()
            assertEquals(false, engine.restored.value, "a failed load is not restored")

            store.loadFailure = null
            engine.restore()
            runCurrent()

            assertEquals(true, engine.restored.value)
            assertIs<SessionState.Ringing>(stateWhenRestored, "the restored state is published first")
            watch.cancel()
        }

    @Test
    fun `a failed load fails the dispatch without reducing and the next dispatch loads again`() =
        runTest {
            store.row = SessionJson.encode(SessionState.Ringing(ringSession()))
            store.loadFailure = DomainError.StorageFailure("locked")
            val engine = engine()

            assertEquals(Outcome.Failure(DomainError.StorageFailure("locked")), engine.dispatch(SessionEvent.UserInteracted))
            assertEquals(Outcome.Failure(DomainError.StorageFailure("locked")), engine.tick())
            assertEquals(emptyList(), store.commits)
            assertEquals(emptyList(), runner.ran)
            assertEquals(SessionState.Idle, engine.state.value)

            store.loadFailure = null
            time.advanceBy(1.minutes)
            val grace = assertIs<SessionState.Grace>(engine.dispatch(SessionEvent.ImUpTapped).state())
            assertEquals(SESSION_ID, grace.session.sessionId)
            assertEquals(grace, store.stored)
        }

    @Test
    fun `a caller cancelled while effects run still gets every effect run and the state published`() =
        runTest {
            runner.hangOn = { it is SessionEffect.ArmSlot }
            val engine = engine()
            val caller = launch { engine.dispatch(alarmFired) }
            runCurrent()
            assertEquals(listOf<Any>(SessionEffect.StartWakeRuntime(SESSION_ID)), runner.ran)

            caller.cancel()
            runner.release.complete(Unit)
            caller.join()

            val ringing = assertIs<SessionState.Ringing>(engine.state.value)
            assertEquals(ringing, store.stored)
            assertEquals(runnerEffects(reducer.reduce(SessionState.Idle, alarmFired, T0)) + entryEffects(ringing), runner.ran)
        }

    @Test
    fun `hitting the round limit with events still due is logged`() =
        runTest {
            val alwaysDue =
                SessionEngine(
                    reducer,
                    store,
                    runner,
                    SessionRecorder(history),
                    time.clock,
                    time.monotonicClock,
                    time.bootCounter,
                    logger,
                    due = { _, _ -> listOf(SessionEvent.GraceElapsed) },
                )

            assertEquals(Outcome.Success(SessionState.Idle), alwaysDue.tick())

            assertEquals(SessionEngine.MAX_DUE_ROUNDS, runner.ran.size, "one ignored GraceElapsed per round")
            assertEquals(LogEvent.OperationFailed("dispatch due events", "round limit reached"), logger.events.last())
        }

    @Test
    fun `an undecodable stored session is logged and cleared and the engine stays Idle`() =
        runTest {
            store.row = "{\"type\":\"Ringing\",\"session\":{\"label\":\"Work\""
            val engine = engine()

            assertEquals(Outcome.Success(SessionState.Idle), engine.restore())

            assertNull(store.row)
            assertEquals(SessionState.Idle, engine.state.value)
            assertEquals(emptyList(), runner.ran)
            val logged = assertIs<LogEvent.OperationFailed>(logger.events.single())
            assertEquals("restore session", logged.operation)
            assertTrue(logged.cause.startsWith("unreadable session: ") && "Work" !in logged.cause, logged.cause)
        }

    @Test
    fun `an unreadable session that cannot be cleared is logged twice and the engine still stays Idle`() =
        runTest {
            store.row = "garbage"
            store.clearFailure = DomainError.StorageFailure("read-only")

            assertEquals(Outcome.Success(SessionState.Idle), engine().restore())

            assertEquals(1, store.clears)
            assertEquals(
                LogEvent.OperationFailed("clear stored session", "storage failure: read-only"),
                logger.events.last(),
            )
            assertEquals(2, logger.events.size)
        }

    @Test
    fun `a storage failure on load is logged and returned and nothing is cleared`() =
        runTest {
            store.row = SessionJson.encode(SessionState.Ringing(ringSession()))
            store.loadFailure = DomainError.StorageFailure("locked")

            assertEquals(Outcome.Failure(DomainError.StorageFailure("locked")), engine().restore())

            assertEquals(0, store.clears)
            assertEquals(listOf<LogEvent>(LogEvent.OperationFailed("restore session", "storage failure: locked")), logger.events)
        }

    @Test
    fun `when the restore commit fails the loaded session is kept and its entry effects still run`() =
        runTest {
            val loaded = SessionState.Loud(ringSession())
            store.row = SessionJson.encode(loaded)
            store.commitFailure = DomainError.StorageFailure("disk full")
            time.advanceBy(1.minutes)
            val engine = engine()

            assertEquals(Outcome.Failure(DomainError.StorageFailure("disk full")), engine.restore())

            assertEquals(loaded, engine.state.value)
            assertEquals<List<Any>>(entryEffects(loaded), runner.ran)
        }

    @Test
    fun `restore does nothing once the engine holds a session`() =
        runTest {
            val engine = engine()
            val ringing = engine.dispatch(alarmFired).state()
            runner.ran.clear()
            val commits = store.commits.size

            assertEquals(Outcome.Success(ringing), engine.restore())

            assertEquals(commits, store.commits.size)
            assertEquals(emptyList(), runner.ran)
        }

    @Test
    fun `a failing effect is logged by type name, the commit stands and later effects still run`() =
        runTest {
            runner.throwOn = { it is SessionEffect.StartWakeRuntime }
            val engine = engine()

            val ringing = engine.dispatch(alarmFired).state()

            assertEquals(ringing, store.stored)
            assertEquals(runnerEffects(reducer.reduce(SessionState.Idle, alarmFired, T0)) + entryEffects(ringing), runner.ran)
            assertEquals(
                listOf<LogEvent>(LogEvent.OperationFailed("run session effect StartWakeRuntime", "IllegalStateException")),
                logger.events,
            )
        }

    @Test
    fun `an error thrown by an effect is logged by type name and does not escape the dispatch`() =
        runTest {
            runner.throwOn = { it is SessionEffect.StartWakeRuntime }
            runner.failure = { NotImplementedError("not yet") }

            val ringing = engine().dispatch(alarmFired).state()

            assertEquals(ringing, store.stored)
            assertEquals(LogEvent.OperationFailed("run session effect StartWakeRuntime", "NotImplementedError"), logger.events.single())
        }

    @Test
    fun `an ignored event commits nothing and passes the log effect to the runner`() =
        runTest {
            val engine = engine()
            val ringing = engine.dispatch(alarmFired).state()
            runner.ran.clear()

            assertEquals(Outcome.Success(ringing), engine.dispatch(SessionEvent.GraceElapsed))

            assertEquals(listOf(ringing), store.commits, "only the alarm was committed")
            assertEquals<List<Any>>(listOf(SessionEffect.LogIgnored("GraceElapsed", SESSION_ID)) + entryEffects(ringing), runner.ran)
        }

    @Test
    fun `a due event whose commit fails is logged and the dispatch returns its own committed state`() =
        runTest {
            val engine = engine()
            engine.dispatch(alarmFired.copy(beforeFirstUnlock = true))
            time.advanceBy(31.minutes)
            store.onCommit = { store.commitFailure = DomainError.StorageFailure("disk full") }

            val ringing = assertIs<SessionState.Ringing>(engine.dispatch(SessionEvent.UserUnlocked).state())

            assertEquals(ringing, store.stored, "the UserUnlocked step is committed")
            assertEquals(false, ringing.session.beforeFirstUnlock)
            assertEquals(ringing, engine.state.value)
            assertTrue(history.rows.values.none { it.outcome != null }, "nothing was recorded as Missed")
            assertEquals(LogEvent.OperationFailed("commit session state", "storage failure: disk full"), logger.events.last())
        }

    @Test
    fun `a full morning from the alarm to Idle writes one history row and ends with the store empty`() =
        runTest {
            val engine = engine()

            assertIs<SessionState.Ringing>(engine.dispatch(alarmFired).state())
            engine.dispatch(SessionEvent.SnoozeTapped)
            assertTrue(SessionEffect.ShowSnoozeConfirm(OFFER) in runner.ran)
            assertEquals(INTENT, engine.dispatch(SessionEvent.PayConfirmed(INTENT)).session().paying)
            assertTrue(runner.ran.any { it is SessionEffect.LaunchBilling })
            val grant = SessionEvent.PurchaseGranted(PRODUCT, TOKEN, PurchaseVerdict.Grant)
            val snoozed = assertIs<SessionState.Snoozed>(engine.dispatch(grant).state())
            assertEquals(1, snoozed.session.snoozesGranted)

            time.advanceBy(9.minutes)
            assertEquals(2, assertIs<SessionState.Ringing>(engine.dispatch(SessionEvent.SlotFired).state()).session.ringIndex)
            assertIs<SessionState.Grace>(engine.dispatch(SessionEvent.ImUpTapped).state())
            time.advanceBy(1.minutes)
            assertEquals(Outcome.Success(SessionState.Idle), engine.dispatch(SessionEvent.CheckAnswerSubmitted(CheckAnswer.Placeholder)))

            assertNull(store.row)
            assertEquals(SessionState.Idle, engine.state.value)
            assertEquals(SessionEffect.ClearRuntimeSession(SESSION_ID), runner.ran.last())
            assertEquals(
                listOf("Ringing", "Ringing", "Snoozed", "Ringing", "Grace", "Completed", "Idle"),
                store.commits.map { it.kind },
            )
            val firstRing = Instant.fromEpochMilliseconds(T0.wallMillis)
            assertEquals(
                SessionHistoryRow(
                    sessionId = SESSION_ID,
                    alarmId = "alarm-1",
                    scheduledAt = SCHEDULED_AT,
                    firstRingAt = firstRing,
                    endedAt = firstRing + 10.minutes,
                    snoozeCount = 1,
                    checkTypes = listOf("Placeholder"),
                    timeToCompleteMs = 10.minutes.inWholeMilliseconds,
                    fallbackUsed = false,
                    directBoot = false,
                    outcome = SessionOutcome.Snoozed,
                ),
                history.rows.values.single(),
            )
            assertEquals(
                Outcome.Success(SessionState.Idle),
                engine.dispatch(SessionEvent.Recorded(SESSION_ID)),
                "a late Recorded is ignored",
            )
        }

    @Test
    fun `ended keeps a Completed session after Recorded makes the engine Idle, until the next session ends (Story 3-3)`() =
        runTest {
            val engine = engine()
            engine.dispatch(alarmFired)
            engine.dispatch(SessionEvent.ImUpTapped)
            assertNull(engine.ended.value, "nothing ended yet")

            assertEquals(Outcome.Success(SessionState.Idle), engine.dispatch(SessionEvent.CheckAnswerSubmitted(CheckAnswer.Placeholder)))

            val completed = assertIs<SessionState.Completed>(engine.ended.value)
            assertEquals(SESSION_ID, completed.session.sessionId)
            assertTrue(store.commits.any { it.kind == "Completed" }, "the Completed state was committed")

            // A new session rings: the last ending stays until that one ends, here Missed after 30 minutes.
            engine.dispatch(SessionEvent.AlarmFired("session-2", testConfig(), beforeFirstUnlock = false))
            assertEquals(completed, engine.ended.value, "a ring changes nothing")
            time.advanceBy(30.minutes)
            assertEquals(Outcome.Success(SessionState.Idle), engine.dispatch(SessionEvent.SlotFired))

            assertEquals("session-2", assertIs<SessionState.Missed>(engine.ended.value).session.sessionId)
        }

    @Test
    fun `a failed Completed commit leaves ended untouched`() =
        runTest {
            val engine = engine()
            engine.dispatch(alarmFired)
            engine.dispatch(SessionEvent.ImUpTapped)
            store.commitFailure = DomainError.StorageFailure("disk full")

            engine.dispatch(SessionEvent.CheckAnswerSubmitted(CheckAnswer.Placeholder))

            assertIs<SessionState.Grace>(engine.state.value)
            assertNull(engine.ended.value)
        }

    /** What the runner gets of [transition]'s one-shot effects: everything but the history start, which the recorder writes. */
    private fun runnerEffects(transition: Transition): List<SessionEffect> =
        transition.effects.filter { it !is SessionEffect.RecordSessionStart }
}
