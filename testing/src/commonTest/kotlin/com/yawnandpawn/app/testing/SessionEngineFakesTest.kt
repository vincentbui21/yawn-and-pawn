package com.yawnandpawn.app.testing

import com.yawnandpawn.app.core.checks.CheckAnswer
import com.yawnandpawn.app.core.error.DomainError
import com.yawnandpawn.app.core.error.Outcome
import com.yawnandpawn.app.core.history.SessionOutcome
import com.yawnandpawn.app.core.session.EntryEffect
import com.yawnandpawn.app.core.session.PurchaseIntent
import com.yawnandpawn.app.core.session.PurchaseIntentId
import com.yawnandpawn.app.core.session.SessionEffect
import com.yawnandpawn.app.core.session.SessionEngine
import com.yawnandpawn.app.core.session.SessionEvent
import com.yawnandpawn.app.core.session.SessionJson
import com.yawnandpawn.app.core.session.SessionRecorder
import com.yawnandpawn.app.core.session.SessionReducer
import com.yawnandpawn.app.core.session.SessionState
import com.yawnandpawn.app.core.session.StoredSession
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

class SessionEngineFakesTest {
    private val intent = PurchaseIntent(PurchaseIntentId("intent-1"), sessionId = "s", productId = "snooze_usd_01", snoozeNumber = 1)

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
    fun `FakePurchaseIntentStore saves and finds intents by id, and fails on demand`() =
        runTest {
            val store = FakePurchaseIntentStore()
            assertEquals(Outcome.Failure(DomainError.NotFound("intent-1")), store.get(intent.intentId))

            assertEquals(Outcome.Success(Unit), store.save(intent))
            assertEquals(Outcome.Success(intent), store.get(intent.intentId))
            assertEquals(listOf(intent), store.saved)

            store.failure = DomainError.StorageFailure("closed")
            assertEquals(Outcome.Failure(DomainError.StorageFailure("closed")), store.save(intent))
            assertEquals(Outcome.Failure(DomainError.StorageFailure("closed")), store.get(intent.intentId))
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
            val store = FakeActiveSessionStore()
            val runner = FakeEffectRunner()
            val billing = FakeBilling()
            val intents = FakePurchaseIntentStore()
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
            engine.dispatch(SessionEvent.PayConfirmed(PurchaseIntentId("intent-1")))

            // The runtime's part, done by hand: persist the intent, then launch billing and feed its result back.
            val persist = runner.oneShot.filterIsInstance<SessionEffect.PersistPurchaseIntent>().single()
            val launch = runner.oneShot.filterIsInstance<SessionEffect.LaunchBilling>().single()
            val purchase = PurchaseIntent(persist.intentId, persist.sessionId, persist.offer.productId, persist.offer.snoozeNumber)
            intents.save(purchase)
            billing.result = FakeBilling.grants(launch.offer.productId)
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
