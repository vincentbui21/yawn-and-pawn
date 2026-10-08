package com.yawnandpawn.app.core.session

import com.yawnandpawn.app.core.billing.GrantLedgerEntry
import com.yawnandpawn.app.core.billing.LedgerStatus
import com.yawnandpawn.app.core.billing.LedgerWorld
import com.yawnandpawn.app.core.billing.RecordStatus
import com.yawnandpawn.app.core.billing.SettleResult
import com.yawnandpawn.app.core.billing.hash
import com.yawnandpawn.app.core.error.DomainError
import com.yawnandpawn.app.core.error.Outcome
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * Story 4.10: a paid snooze commits the Snoozed state and its grant ledger row in one `runtime.db` transaction; only then
 * is the payment settled, outside the engine, and a crash anywhere is replayed from the ledger without a second grant.
 */
class SessionEngineGrantLedgerTest {
    private val time = EngineTime()
    private val store = InMemorySessionStore()
    private val runner = RecordingRunner()
    private val logger = EngineLogger()
    private val history = InMemoryHistory()

    /** The same `runtime.db`: the ledger reads the rows the engine's commits wrote. */
    private val world = LedgerWorld()

    private fun engine(effects: EffectRunner = runner) =
        SessionEngine(reducer(), store, effects, SessionRecorder(history), time.clock, time.monotonicClock, time.bootCounter, logger)

    private val alarmFired = SessionEvent.AlarmFired(SESSION_ID, testConfig(), beforeFirstUnlock = false)
    private val granted = SessionEvent.PurchaseGranted(PRODUCT, TOKEN, PurchaseVerdict.Grant, orderId = "GPA.3")

    private fun Outcome<SessionState, DomainError>.state(): SessionState = assertIs<Outcome.Success<SessionState>>(this).value

    /** Moves the rows the engine committed into the ledger the [world] reads (one `runtime.db`). */
    private fun syncLedger() {
        world.ledgerRows.putAll(store.grants.filterKeys { it !in world.ledgerRows })
    }

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
    fun `the same token granted again fails its commit, so it never grants twice`() =
        runTest {
            val engine = engine()
            engine.dispatch(alarmFired)
            engine.dispatch(granted)
            time.advanceBy(9.minutes)
            assertIs<SessionState.Ringing>(engine.dispatch(SessionEvent.SlotFired).state())
            runner.ran.clear()

            assertIs<Outcome.Failure<DomainError>>(engine.dispatch(granted), "a duplicate delivery the reconciler missed")

            assertEquals(1, assertIs<SessionState.Ringing>(engine.state.value).session.snoozesGranted)
            assertTrue(runner.oneShot.none { it is SessionEffect.Consume })
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
                        if (effect is SessionEffect.Consume) {
                            syncLedger()
                            backgroundScope.launch { settles += ledger.settle(effect.token) }
                        }
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
            assertTrue(world.ledgerRows.isEmpty())
        }

    @Test
    fun `a crash right after the grant's commit is replayed from the ledger, Snoozed once, recorded and consumed`() =
        runTest {
            engine().run {
                dispatch(alarmFired)
                dispatch(granted)
            }
            // The process dies before the Consume effect ran anything.
            syncLedger()

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
            assertTrue(world.ledgerRows.isEmpty())
            assertEquals(1, assertIs<SessionState.Snoozed>(restored.state.value).session.snoozesGranted)
        }

    @Test
    fun `ReuseAccepted writes the ledger row like a grant, and settling makes the stranded record reused`() =
        runTest {
            world.recordRows[TOKEN.hash()] =
                com.yawnandpawn.app.core.billing
                    .record(RecordStatus.Stranded, token = TOKEN)
            val engine = engine()
            engine.dispatch(alarmFired)

            assertIs<SessionState.Snoozed>(engine.dispatch(SessionEvent.ReuseAccepted(PRODUCT, TOKEN)).state())
            syncLedger()
            assertEquals(SettleResult.Settled, world.ledger().settle(TOKEN))

            val reused = world.recordOf(TOKEN)
            assertEquals(RecordStatus.Reused, reused.status)
            assertEquals(SESSION_ID, reused.sessionId)
            assertEquals(1, reused.snoozeNumber)
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
