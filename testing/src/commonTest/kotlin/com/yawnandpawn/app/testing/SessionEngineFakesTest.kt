package com.yawnandpawn.app.testing

import com.yawnandpawn.app.core.billing.PurchaseIntent
import com.yawnandpawn.app.core.checks.CheckAnswer
import com.yawnandpawn.app.core.error.DomainError
import com.yawnandpawn.app.core.error.Outcome
import com.yawnandpawn.app.core.history.SessionOutcome
import com.yawnandpawn.app.core.session.EntryEffect
import com.yawnandpawn.app.core.session.PurchaseIntentId
import com.yawnandpawn.app.core.session.RuntimeWrite
import com.yawnandpawn.app.core.session.SessionEffect
import com.yawnandpawn.app.core.session.SessionEngine
import com.yawnandpawn.app.core.session.SessionEvent
import com.yawnandpawn.app.core.session.SessionJson
import com.yawnandpawn.app.core.session.SessionRecorder
import com.yawnandpawn.app.core.session.SessionReducer
import com.yawnandpawn.app.core.session.SessionState
import com.yawnandpawn.app.core.session.StoredSession
import com.yawnandpawn.app.core.session.UnlockResult
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes

class SessionEngineFakesTest {
    private val intent = aPurchaseIntent(sessionId = "s")

    @Test
    fun `FakeActiveSessionStore keeps one JSON row, Idle deletes it, and failures change nothing`() =
        runTest {
            val store = FakeActiveSessionStore()
            assertEquals(Outcome.Success(StoredSession.Empty), store.load())

            val ringing = SessionState.Ringing(aSession())
            assertEquals(Outcome.Success(Unit), store.commit(ringing))
            assertEquals(SessionJson.encode(ringing), store.row)
            assertEquals(Outcome.Success(StoredSession.Found(ringing)), store.load())

            store.commitFailure = DomainError.StorageFailure("disk full")
            assertEquals(Outcome.Failure(DomainError.StorageFailure("disk full")), store.commit(SessionState.Idle))
            assertEquals(ringing, store.stored)

            store.commitFailure = null
            store.commit(SessionState.Idle)
            assertNull(store.row)
            assertEquals(SessionState.Idle, store.stored)
            assertEquals(listOf(ringing, SessionState.Idle), store.commits)
        }

    @Test
    fun `FakeActiveSessionStore can hold an unreadable row, fail loads and be cleared`() =
        runTest {
            val store = FakeActiveSessionStore(SessionState.Loud(aSession()))
            assertIs<SessionState.Loud>(store.stored)

            store.row = "{broken"
            assertNull(store.stored)
            assertIs<StoredSession.Unreadable>(assertIs<Outcome.Success<StoredSession>>(store.load()).value)

            store.loadFailure = DomainError.StorageFailure("locked")
            assertEquals(Outcome.Failure(DomainError.StorageFailure("locked")), store.load())

            assertEquals(Outcome.Success(Unit), store.clear())
            assertNull(store.row)
            assertEquals(1, store.clears)
        }

    @Test
    fun `FakeActiveSessionStore fails clears on demand and keeps the row`() =
        runTest {
            val loud = SessionState.Loud(aSession())
            val store = FakeActiveSessionStore(loud)
            store.clearFailure = DomainError.StorageFailure("read-only")

            assertEquals(Outcome.Failure(DomainError.StorageFailure("read-only")), store.clear())
            assertEquals(loud, store.stored)
            assertEquals(1, store.clears)

            store.clearFailure = null
            assertEquals(Outcome.Success(Unit), store.clear())
            assertNull(store.row)
        }

    @Test
    fun `FakeEffectRunner records one-shot and entry effects in the order they ran`() =
        runTest {
            val runner = FakeEffectRunner()
            runner.run(SessionEffect.Mute)
            runner.apply(EntryEffect.WakeUiShown)
            runner.run(SessionEffect.StopSound)

            assertEquals(
                listOf(
                    RanEffect.OneShot(SessionEffect.Mute),
                    RanEffect.Entry(EntryEffect.WakeUiShown),
                    RanEffect.OneShot(SessionEffect.StopSound),
                ),
                runner.ran,
            )
            assertEquals(listOf(SessionEffect.Mute, SessionEffect.StopSound), runner.oneShot)
            assertEquals(listOf<EntryEffect>(EntryEffect.WakeUiShown), runner.entry)
            runner.reset()
            assertEquals(emptyList(), runner.ran)
        }

    @Test
    fun `FakeBilling returns its programmed result for every launch and records the intents`() =
        runTest {
            val billing = FakeBilling()
            assertEquals(SessionEvent.PurchaseFailed, billing.launch(intent))

            val granted = FakeBilling.grants("snooze_usd_01")
            listOf(granted, SessionEvent.PurchaseCancelled, SessionEvent.PurchasePending).forEach { result ->
                billing.result = result
                assertEquals(result, billing.launch(intent))
            }
            assertEquals(List(4) { intent }, billing.launched)
        }

    @Test
    fun `FakePurchaseIntentStore finds intents by id, session and product, purges strictly older ones, and fails on demand`() =
        runTest {
            val store = FakePurchaseIntentStore()
            assertEquals(Outcome.Failure(DomainError.NotFound("intent-1")), store.get(intent.intentId))
            val later =
                aPurchaseIntent(
                    intentId = "intent-2",
                    sessionId = "s",
                    productId = "snooze_usd_02",
                    createdAt =
                        DEFAULT_FAKE_INSTANT + 1.minutes,
                )
            val other = aPurchaseIntent(intentId = "intent-3", sessionId = "t", createdAt = DEFAULT_FAKE_INSTANT - 8.days)
            listOf(later, other, intent).forEach(store::put)

            assertEquals(Outcome.Success(intent), store.get(intent.intentId))
            assertEquals(Outcome.Success(listOf(intent, later)), store.forSession("s"), "oldest first")
            assertEquals(Outcome.Success(listOf(later)), store.forProduct("s", "snooze_usd_02"))
            assertEquals(Outcome.Success(emptyList()), store.forProduct("t", "snooze_usd_02"))
            assertEquals(Outcome.Success(0), store.purgeOlderThan(other.createdAt), "strictly older only")
            assertEquals(Outcome.Success(1), store.purgeOlderThan(DEFAULT_FAKE_INSTANT - 7.days))
            assertEquals(listOf(intent, later), store.saved)

            store.failure = DomainError.StorageFailure("closed")
            assertEquals(Outcome.Failure(DomainError.StorageFailure("closed")), store.get(intent.intentId))
            assertEquals(Outcome.Failure(DomainError.StorageFailure("closed")), store.forSession("s"))
            assertEquals(Outcome.Failure(DomainError.StorageFailure("closed")), store.purgeOlderThan(DEFAULT_FAKE_INSTANT))
        }

    @Test
    fun `FakeActiveSessionStore writes intents with the state, and a duplicate intent fails the whole commit`() =
        runTest {
            val store = FakeActiveSessionStore()
            val ringing = SessionState.Ringing(aSession().copy(paying = intent.intentId))

            assertEquals(Outcome.Success(Unit), store.commit(ringing, listOf(RuntimeWrite.PutPurchaseIntent(intent))))
            assertEquals(listOf(intent), store.intents.saved)
            assertEquals(listOf<RuntimeWrite>(RuntimeWrite.PutPurchaseIntent(intent)), store.writes)

            val idle = store.commit(SessionState.Idle, listOf(RuntimeWrite.PutPurchaseIntent(intent.copy(formattedPrice = "x"))))
            assertIs<Outcome.Failure<DomainError>>(idle)
            assertEquals(ringing, store.stored, "nothing changed")
            assertEquals(listOf(intent), store.intents.saved)
        }

    @Test
    fun `FakeUnlockPort reports the keyguard, counts requests, and can hold one until released`() =
        runTest {
            val port = FakeUnlockPort(locked = true, result = UnlockResult.Failed)
            assertTrue(port.isKeyguardLocked())
            assertEquals(UnlockResult.Failed, port.requestUnlock())

            port.hold = true
            val held = async { port.requestUnlock() }
            runCurrent()
            assertTrue(held.isActive, "a PIN prompt still up")
            port.release(UnlockResult.Succeeded)
            assertEquals(UnlockResult.Succeeded, held.await())
            assertEquals(2, port.requests)
            assertEquals(SessionEvent.UnlockSucceeded, UnlockResult.Succeeded.event())
            assertEquals(SessionEvent.UnlockFailed, UnlockResult.Failed.event())
        }

    @Test
    fun `the price builders default to a live one-dollar price for the first product`() {
        val pay = aPayConfirmed()
        assertEquals(PurchaseIntentId("intent-1"), pay.intentId)
        assertEquals("snooze_usd_01", pay.livePrice.productId)
        assertEquals(1_000_000L, pay.livePrice.price.micros)
    }

    @Test
    fun `the session builders give one valid state per variant that round trips`() {
        val states = everySessionState()
        assertEquals(7, states.map { it::class }.toSet().size)
        states.forEach { assertEquals(StoredSession.Found(it), SessionJson.decode(SessionJson.encode(it))) }
    }

    @Test
    fun `the fakes drive the engine through a full morning, from the alarm to Idle with one history row and the store empty`() =
        runTest {
            val time = FakeTime()
            val intents = FakePurchaseIntentStore()
            val store = FakeActiveSessionStore(intents = intents)
            val runner = FakeEffectRunner()
            val billing = FakeBilling()
            val history = FakeSessionHistoryRepository()
            val reducer = SessionReducer(FakeSnoozeAvailability(), FakeCheck(), FakeFallbackPolicy())
            val engine =
                SessionEngine(
                    reducer,
                    store,
                    runner,
                    SessionRecorder(history),
                    time.clock,
                    time.monotonicClock,
                    time.bootCounter,
                    FakeLogger(),
                )
            val config = aSessionConfig()
            val sessionId = "session-1"

            engine.dispatch(SessionEvent.AlarmFired(sessionId, config, beforeFirstUnlock = false))
            assertEquals(listOf<SessionOutcome?>(null), history.rows.map { it.outcome }, "the start row")
            engine.dispatch(SessionEvent.SnoozeTapped)
            engine.dispatch(aPayConfirmed())

            // The engine wrote the intent with the state. The runtime's part, done by hand: read it, launch billing and
            // feed its result back.
            assertTrue(runner.oneShot.none { it is SessionEffect.PersistPurchaseIntent }, "the engine persists, not the runner")
            val launch = runner.oneShot.filterIsInstance<SessionEffect.LaunchBilling>().single()
            val purchase = assertIs<Outcome.Success<PurchaseIntent>>(intents.get(launch.intentId)).value
            assertEquals(1, purchase.snoozeNumber)
            billing.result = FakeBilling.grants(purchase.productId)
            assertIs<SessionState.Snoozed>(assertIs<Outcome.Success<SessionState>>(engine.dispatch(billing.launch(purchase))).value)

            time.advanceBy(config.snoozeLengthMinutes.minutes)
            engine.dispatch(SessionEvent.SlotFired)
            engine.dispatch(SessionEvent.ImUpTapped)
            time.advanceBy(1.minutes)
            assertEquals(Outcome.Success(SessionState.Idle), engine.dispatch(SessionEvent.CheckAnswerSubmitted(CheckAnswer.Placeholder)))

            assertEquals(
                listOf("Ringing", "Ringing", "Snoozed", "Ringing", "Grace", "Completed", "Idle"),
                store.commits.map { it::class.simpleName },
            )
            assertNull(store.row)
            assertEquals(SessionState.Idle, engine.state.value)
            assertEquals(
                aSessionHistoryRow(sessionId, config.alarmId).copy(
                    endedAt = DEFAULT_FAKE_INSTANT + 10.minutes,
                    snoozeCount = 1,
                    timeToCompleteMs = 10.minutes.inWholeMilliseconds,
                    outcome = SessionOutcome.Snoozed,
                ),
                history.rows.single(),
            )
            assertTrue(runner.entry.none { it is EntryEffect.HistoryWriteRequested }, "the engine writes history itself")
            assertEquals(listOf(purchase), intents.saved)
            assertEquals(listOf(purchase), billing.launched)
        }

    @Test
    fun `FakeSessionHistoryRepository keeps one row per session, replaces it on upsert and fails on demand`() =
        runTest {
            val history = FakeSessionHistoryRepository()
            val row = aSessionHistoryRow()
            assertEquals(Outcome.Success(null), history.find(row.sessionId))

            assertEquals(Outcome.Success(Unit), history.upsert(row.copy(outcome = null)))
            assertEquals(Outcome.Success(Unit), history.upsert(row))
            assertEquals(listOf(row), history.rows)
            assertEquals(Outcome.Success(row), history.find(row.sessionId))
            assertEquals(2, history.upserts.size)

            history.upsertFailure = DomainError.StorageFailure("disk full")
            assertEquals(Outcome.Failure(DomainError.StorageFailure("disk full")), history.upsert(row.copy(snoozeCount = 3)))
            assertEquals(listOf(row), history.rows)
            history.findFailure = DomainError.StorageFailure("locked")
            assertEquals(Outcome.Failure(DomainError.StorageFailure("locked")), history.find(row.sessionId))
        }
}
