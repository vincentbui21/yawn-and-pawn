package com.yawnandpawn.app.core.session

import com.yawnandpawn.app.core.billing.GrantLedgerEntry
import com.yawnandpawn.app.core.billing.LedgerStatus
import com.yawnandpawn.app.core.billing.LedgerWorld
import com.yawnandpawn.app.core.billing.RecordStatus
import com.yawnandpawn.app.core.billing.SettleResult
import com.yawnandpawn.app.core.billing.hash
import com.yawnandpawn.app.core.billing.record
import com.yawnandpawn.app.core.error.DomainError
import com.yawnandpawn.app.core.error.Outcome
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * Story 4.10: a paid snooze commits the Snoozed state and its grant ledger row in one `runtime.db` transaction; only then
 * is the payment settled, outside the engine, and a crash anywhere is replayed from the ledger without a second grant.
 * The engine's store and the ledger share one `grant_ledger` table ([LedgerWorld] over the store's map).
 */
class SessionEngineGrantLedgerTest {
    private val time = EngineTime()
    private val store = InMemorySessionStore()
    private val runner = RecordingRunner()
    private val logger = EngineLogger()
    private val history = InMemoryHistory()
    private val world = LedgerWorld(store.grants)

    private fun engine(effects: EffectRunner = runner) =
        SessionEngine(reducer(), store, effects, SessionRecorder(history), time.clock, time.monotonicClock, time.bootCounter, logger)

    private val alarmFired = SessionEvent.AlarmFired(SESSION_ID, testConfig(), beforeFirstUnlock = false)
    private val granted = SessionEvent.PurchaseGranted(PRODUCT, TOKEN, PurchaseVerdict.Grant, orderId = "GPA.3")

    private fun Outcome<SessionState, DomainError>.state(): SessionState = assertIs<Outcome.Success<SessionState>>(this).value

    @Test
    fun `a grant commits Snoozed and its ledger row together, then the runner gets only Consume`() =
        runTest {
            val engine = engine()
            engine.dispatch(alarmFired)
            time.advanceBy(2.minutes)
            var grantsAtCommit = -1
            store.onCommit = { grantsAtCommit = store.grants.size }
            runner.ran.clear()

            val snoozed = assertIs<SessionState.Snoozed>(engine.dispatch(granted).state())

            val row =
                GrantLedgerEntry(
                    TOKEN,
                    SESSION_ID,
                    "alarm-1",
                    PRODUCT,
                    1,
                    "GPA.3",
                    LedgerStatus.Granted,
                    Instant.fromEpochMilliseconds(time.now.wallMillis),
                )
            assertEquals(1, grantsAtCommit, "the row is in the Snoozed commit")
            assertEquals(listOf<RuntimeWrite>(RuntimeWrite.PutGrant(row)), store.writeLog.last())
            assertEquals(snoozed, store.stored)
            assertEquals(1, snoozed.session.snoozesGranted)
            assertTrue(runner.oneShot.none { it is SessionEffect.PersistGrant }, "the engine writes the row, never the runner")
            assertEquals(SessionEffect.Consume(TOKEN), runner.oneShot.last(), "settling comes after everything else")
        }

    @Test
    fun `a failing commit writes no ledger row, settles nothing and stays ringing`() =
        runTest {
            val engine = engine()
            engine.dispatch(alarmFired)
            runner.ran.clear()
            store.commitFailure = DomainError.StorageFailure("disk full")

            assertIs<Outcome.Failure<DomainError>>(engine.dispatch(granted))

            assertTrue(store.grants.isEmpty())
            assertTrue(runner.oneShot.none { it is SessionEffect.Consume })
            assertIs<SessionState.Ringing>(engine.state.value)
        }

    @Test
    fun `a token redelivered after its payment was settled fails its commit, so it never grants twice`() =
        runTest {
            val engine = engine()
            engine.dispatch(alarmFired)
            engine.dispatch(granted)
            assertEquals(SettleResult.Settled, world.ledger().settle(TOKEN), "recorded, consumed, settled")
            time.advanceBy(9.minutes)
            assertIs<SessionState.Ringing>(engine.dispatch(SessionEvent.SlotFired).state())
            runner.ran.clear()

            // A duplicate delivery the reconciler missed: the settled marker refuses the token.
            assertIs<Outcome.Failure<DomainError>>(engine.dispatch(granted))

            assertEquals(1, assertIs<SessionState.Ringing>(engine.state.value).session.snoozesGranted)
            assertTrue(runner.oneShot.none { it is SessionEffect.Consume })
            assertEquals(1, world.play.calls.size)
            assertEquals(RecordStatus.Consumed, world.recordOf(TOKEN).status)
        }

    @Test
    fun `the snooze starts at the commit, Snoozed while Play has not answered the consume`() =
        runTest {
            val play = CompletableDeferred<Unit>()
            world.play.hold = play
            val ledger = world.ledger()
            val settles = mutableListOf<SettleResult>()
            // The wake runtime launches the settle on the app scope and returns at once (WakeModule).
            val settling =
                object : EffectRunner {
                    override suspend fun run(effect: SessionEffect) {
                        if (effect is SessionEffect.Consume) backgroundScope.launch { settles += ledger.settle(effect.token) }
                    }

                    override suspend fun apply(effect: EntryEffect) = Unit
                }
            val engine = engine(settling)
            engine.dispatch(alarmFired)

            assertIs<SessionState.Snoozed>(engine.dispatch(granted).state())
            runCurrent()
            assertEquals(listOf(TOKEN), world.play.calls, "the consume is in flight")
            assertEquals(emptyList(), settles)
            assertEquals(RecordStatus.Granted, world.recordOf(TOKEN).status, "the charge is already in history")
            assertIs<SessionState.Snoozed>(engine.state.value)

            play.complete(Unit)
            runCurrent()
            assertEquals(listOf(SettleResult.Settled), settles)
            assertEquals(RecordStatus.Consumed, world.recordOf(TOKEN).status)
            assertNotNull(store.grants.getValue(TOKEN).settledAt)
        }

    @Test
    fun `a crash right after the grant's commit is replayed from the ledger, Snoozed once, recorded and consumed`() =
        runTest {
            engine().run {
                dispatch(alarmFired)
                dispatch(granted)
            }
            // The process dies before the Consume effect ran anything.

            val restored = engine()
            val snoozed = assertIs<SessionState.Snoozed>(restored.restore().state())
            assertEquals(SettleResult.Settled, world.ledger().settleAll(), "app start replays the ledger")

            assertEquals(1, snoozed.session.snoozesGranted)
            val record = world.recordOf(TOKEN)
            assertEquals(RecordStatus.Consumed, record.status)
            assertEquals(TOKEN.hash(), record.tokenHash)
            val paidFor = Triple(record.sessionId, record.alarmId, record.snoozeNumber)
            assertEquals(Triple<String?, String?, Int?>(SESSION_ID, "alarm-1", 1), paidFor)
            assertEquals(listOf(TOKEN), world.play.calls)
            assertNotNull(store.grants.getValue(TOKEN).settledAt)
            assertEquals(1, assertIs<SessionState.Snoozed>(restored.state.value).session.snoozesGranted)
        }

    @Test
    fun `ReuseAccepted writes the ledger row like a grant, and settling makes the stranded record reused and consumed`() =
        runTest {
            val yesterday = Instant.fromEpochMilliseconds(T0.wallMillis) - 1.days
            world.recordRows[TOKEN.hash()] = record(RecordStatus.Stranded, token = TOKEN, purchasedAt = yesterday)
            val engine = engine()
            engine.dispatch(alarmFired)

            assertIs<SessionState.Snoozed>(engine.dispatch(SessionEvent.ReuseAccepted(PRODUCT, TOKEN)).state())
            world.now = Instant.fromEpochMilliseconds(time.now.wallMillis)
            assertEquals(SettleResult.Settled, world.ledger().settle(TOKEN))

            val reused = world.recordOf(TOKEN)
            assertEquals(RecordStatus.Reused, reused.status)
            assertNotNull(reused.consumedAt)
            assertEquals(SESSION_ID, reused.sessionId)
            assertEquals(1, reused.snoozeNumber)
        }

    @Test
    fun `a reuse whose commit failed leaves the stranded record stranded and grants nothing`() =
        runTest {
            val stranded = record(RecordStatus.Stranded, token = TOKEN)
            world.recordRows[TOKEN.hash()] = stranded
            val engine = engine()
            engine.dispatch(alarmFired)
            store.commitFailure = DomainError.StorageFailure("disk full")

            assertIs<Outcome.Failure<DomainError>>(engine.dispatch(SessionEvent.ReuseAccepted(PRODUCT, TOKEN)))
            store.commitFailure = null
            assertEquals(SettleResult.Settled, world.ledger().settleAll())
            val early = world.ledger().markReused(TOKEN.hash(), SESSION_ID, "alarm-1", 1)

            assertIs<Outcome.Failure<DomainError>>(early, "no reuse is marked before its commit")
            assertEquals(stranded, world.recordOf(TOKEN))
            assertTrue(store.grants.isEmpty())
            assertEquals(emptyList(), world.play.calls)
        }

    @Test
    fun `a test session never writes a ledger row`() =
        runTest {
            val engine = engine()
            engine.dispatch(SessionEvent.AlarmFired(SESSION_ID, testConfig(testMode = true), beforeFirstUnlock = false))

            engine.dispatch(granted)

            assertTrue(store.grants.isEmpty())
            assertIs<SessionState.Ringing>(engine.state.value)
        }
}
