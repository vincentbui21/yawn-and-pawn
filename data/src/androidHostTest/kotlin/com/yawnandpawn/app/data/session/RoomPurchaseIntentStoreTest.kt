package com.yawnandpawn.app.data.session

import android.content.Context
import androidx.room3.Room
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import com.yawnandpawn.app.core.billing.Money
import com.yawnandpawn.app.core.billing.PurchaseIntent
import com.yawnandpawn.app.core.error.DomainError
import com.yawnandpawn.app.core.error.Outcome
import com.yawnandpawn.app.core.session.PurchaseIntentId
import com.yawnandpawn.app.core.session.RuntimeWrite
import com.yawnandpawn.app.core.session.SessionState
import com.yawnandpawn.app.core.session.StoredSession
import com.yawnandpawn.app.data.db.RuntimeDatabase
import com.yawnandpawn.app.data.db.RuntimeDatabaseConstructor
import com.yawnandpawn.app.testing.DEFAULT_FAKE_INSTANT
import com.yawnandpawn.app.testing.FakeClock
import com.yawnandpawn.app.testing.aPurchaseIntent
import com.yawnandpawn.app.testing.aSession
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes

/** Story 4.8: `purchase_intent` is written only in the session commit's transaction, and read and purged by the store. */
@RunWith(RobolectricTestRunner::class)
class RoomPurchaseIntentStoreTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val database =
        Room
            .inMemoryDatabaseBuilder<RuntimeDatabase>(context, factory = { RuntimeDatabaseConstructor.initialize() })
            .setDriver(AndroidSQLiteDriver())
            .build()
    private val sessions = RoomActiveSessionStore(database.activeSessionDao(), FakeClock())
    private val intents = RoomPurchaseIntentStore(database.purchaseIntentDao())

    private val ringing = SessionState.Ringing(aSession(sessionId = "s"))

    @After
    fun tearDown() {
        database.close()
    }

    private suspend fun commit(
        state: SessionState,
        vararg added: PurchaseIntent,
    ) = sessions.commit(state, added.map { RuntimeWrite.PutPurchaseIntent(it) })

    @Test
    fun `an intent committed with the state reads back exactly, live price and all`() =
        runTest {
            val intent = aPurchaseIntent(sessionId = "s", price = Money(1_190_000, "EUR"), formattedPrice = "1,19 €")
            val paying = SessionState.Ringing(aSession(sessionId = "s").copy(paying = intent.intentId))

            assertEquals(Outcome.Success(Unit), commit(paying, intent))

            assertEquals(Outcome.Success(StoredSession.Found(paying)), sessions.load())
            assertEquals(Outcome.Success(intent), intents.get(intent.intentId))
        }

    @Test
    fun `a commit whose intent cannot be written writes nothing, so the previous state stays`() =
        runTest {
            val first = aPurchaseIntent(intentId = "intent-1", sessionId = "s")
            commit(ringing, first)
            val later = SessionState.Loud(aSession(sessionId = "s").copy(paying = PurchaseIntentId("intent-1")))

            // The same intent id again: the insert fails inside the transaction.
            val failed = commit(later, aPurchaseIntent(intentId = "intent-2", sessionId = "s"), first.copy(formattedPrice = "x"))

            assertIs<Outcome.Failure<DomainError.StorageFailure>>(failed)
            assertEquals(Outcome.Success(StoredSession.Found(ringing)), sessions.load(), "the state is not replaced")
            assertEquals(Outcome.Success(listOf(first)), intents.forSession("s"), "no intent of the failed commit")
        }

    @Test
    fun `intents outlive the session row, Idle and clear keep them`() =
        runTest {
            val intent = aPurchaseIntent(sessionId = "s")
            commit(ringing, intent)

            sessions.commit(SessionState.Idle)
            sessions.clear()

            assertEquals(Outcome.Success(StoredSession.Empty), sessions.load())
            assertEquals(Outcome.Success(intent), intents.get(intent.intentId))
        }

    @Test
    fun `get, forSession and forProduct find the right intents, oldest first`() =
        runTest {
            val a1 = aPurchaseIntent(intentId = "a1", sessionId = "s", productId = "snooze_usd_01", createdAt = DEFAULT_FAKE_INSTANT)
            val a2 =
                aPurchaseIntent(
                    intentId = "a2",
                    sessionId = "s",
                    productId = "snooze_usd_02",
                    snoozeNumber = 2,
                    createdAt = DEFAULT_FAKE_INSTANT + 10.minutes,
                )
            val a3 =
                aPurchaseIntent(
                    intentId = "a3",
                    sessionId = "s",
                    productId = "snooze_usd_02",
                    createdAt =
                        DEFAULT_FAKE_INSTANT + 5.minutes,
                )
            val b1 = aPurchaseIntent(intentId = "b1", sessionId = "t", productId = "snooze_usd_01")
            commit(ringing, a2, b1, a1, a3)

            val cases: List<Triple<String, Outcome<List<PurchaseIntent>, DomainError>, List<PurchaseIntent>>> =
                listOf(
                    Triple("session s", intents.forSession("s"), listOf(a1, a3, a2)),
                    Triple("session t", intents.forSession("t"), listOf(b1)),
                    Triple("unknown session", intents.forSession("u"), emptyList()),
                    Triple("s, product 02", intents.forProduct("s", "snooze_usd_02"), listOf(a3, a2)),
                    Triple("s, product 01", intents.forProduct("s", "snooze_usd_01"), listOf(a1)),
                    Triple("t, product 02", intents.forProduct("t", "snooze_usd_02"), emptyList()),
                )
            cases.forEach { (name, actual, expected) -> assertEquals(Outcome.Success(expected), actual, name) }
            assertEquals(Outcome.Success(a2), intents.get(PurchaseIntentId("a2")))
            assertEquals(Outcome.Failure(DomainError.NotFound("zz")), intents.get(PurchaseIntentId("zz")))
        }

    @Test
    fun `purgeOlderThan deletes only intents created strictly before the instant`() =
        runTest {
            val cut = DEFAULT_FAKE_INSTANT
            val ages = listOf(8.days, 1.milliseconds, 0.milliseconds, (-1).days)
            val rows = ages.mapIndexed { i, age -> aPurchaseIntent(intentId = "i$i", sessionId = "s", createdAt = cut - age) }
            commit(ringing, *rows.toTypedArray())

            assertEquals(Outcome.Success(2), intents.purgeOlderThan(cut))

            assertEquals(Outcome.Success(rows.drop(2)), intents.forSession("s"))
            assertEquals(Outcome.Success(0), intents.purgeOlderThan(cut), "nothing left to purge")
        }

    @Test
    fun `a damaged row is a storage failure for get and left out of the lists`() =
        runTest {
            val good = aPurchaseIntent(intentId = "good", sessionId = "s")
            commit(ringing, good)
            database.activeSessionDao().commit(
                ActiveSessionEntity("s", "{}", 0),
                listOf(PurchaseIntentEntity.of(good).copy(intentId = "bad", currency = "eur")),
            )

            assertEquals(Outcome.Failure(DomainError.StorageFailure("unreadable purchase intent")), intents.get(PurchaseIntentId("bad")))
            assertEquals(Outcome.Success(listOf(good)), intents.forSession("s"))
        }
}
