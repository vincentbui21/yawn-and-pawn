package com.yawnandpawn.app.data.session

import android.content.Context
import androidx.room3.Room
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import com.yawnandpawn.app.core.billing.LedgerStatus
import com.yawnandpawn.app.core.error.DomainError
import com.yawnandpawn.app.core.error.Outcome
import com.yawnandpawn.app.core.session.PurchaseToken
import com.yawnandpawn.app.core.session.RuntimeWrite
import com.yawnandpawn.app.core.session.SessionState
import com.yawnandpawn.app.core.session.StoredSession
import com.yawnandpawn.app.data.db.RuntimeDatabase
import com.yawnandpawn.app.data.db.RuntimeDatabaseConstructor
import com.yawnandpawn.app.testing.DEFAULT_FAKE_INSTANT
import com.yawnandpawn.app.testing.FakeClock
import com.yawnandpawn.app.testing.aGrant
import com.yawnandpawn.app.testing.aPurchaseIntent
import com.yawnandpawn.app.testing.aSession
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.minutes

/** Story 4.10: `grant_ledger` rows are inserted only with the Snoozed state, then settled and deleted by the store. */
@RunWith(RobolectricTestRunner::class)
class RoomGrantLedgerStoreTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val database =
        Room
            .inMemoryDatabaseBuilder<RuntimeDatabase>(context, factory = { RuntimeDatabaseConstructor.initialize() })
            .setDriver(AndroidSQLiteDriver())
            .build()
    private val sessions = RoomActiveSessionStore(database.activeSessionDao(), FakeClock())
    private val ledger = RoomGrantLedgerStore(database.grantLedgerDao())

    private val snoozed = SessionState.Snoozed(aSession(sessionId = "session-1").copy(snoozesGranted = 1, interactionDeadline = null))

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `a grant committed with the Snoozed state reads back exactly`() =
        runTest {
            val grant = aGrant(orderId = "GPA.1", snoozeNumber = 2)

            assertEquals(Outcome.Success(Unit), sessions.commit(snoozed, listOf(RuntimeWrite.PutGrant(grant))))

            assertEquals(Outcome.Success(StoredSession.Found(snoozed)), sessions.load())
            assertEquals(Outcome.Success(grant), ledger.get(grant.token))
            assertEquals(Outcome.Success(listOf(grant)), ledger.all())
        }

    @Test
    fun `a token already in the ledger fails the whole commit, so the state, the intent and the grant are not written`() =
        runTest {
            val first = aGrant()
            sessions.commit(snoozed, listOf(RuntimeWrite.PutGrant(first)))
            val next = SessionState.Ringing(aSession(sessionId = "session-1"))
            val intent = aPurchaseIntent()

            val writes = listOf(RuntimeWrite.PutPurchaseIntent(intent), RuntimeWrite.PutGrant(aGrant(snoozeNumber = 2)))
            val again = sessions.commit(next, writes)

            assertIs<Outcome.Failure<DomainError>>(again)
            assertEquals(Outcome.Success(StoredSession.Found(snoozed)), sessions.load(), "the previous state stays")
            assertEquals(Outcome.Success(listOf(first)), ledger.all())
            assertEquals(0, database.purchaseIntentDao().count())
        }

    @Test
    fun `rows read oldest first, are marked consumed, deleted, and an Idle commit keeps them`() =
        runTest {
            val later = aGrant(token = "t-2", createdAt = DEFAULT_FAKE_INSTANT + 5.minutes)
            val earlier = aGrant(token = "t-1")
            sessions.commit(snoozed, listOf(RuntimeWrite.PutGrant(later), RuntimeWrite.PutGrant(earlier)))
            // The session ends: the ledger outlives it until each payment is settled.
            sessions.commit(SessionState.Idle)

            assertEquals(Outcome.Success(listOf(earlier, later)), ledger.all())
            assertEquals(Outcome.Success(Unit), ledger.markConsumed(earlier.token))
            assertEquals(Outcome.Success(earlier.copy(status = LedgerStatus.Consumed)), ledger.get(earlier.token))
            assertEquals(Outcome.Success(Unit), ledger.delete(earlier.token))
            assertEquals(Outcome.Success(null), ledger.get(earlier.token))
            assertEquals(Outcome.Success(Unit), ledger.markConsumed(PurchaseToken("none")), "no row: nothing to do")
            assertEquals(Outcome.Success(Unit), ledger.delete(PurchaseToken("none")))
            assertEquals(Outcome.Success(listOf(later)), ledger.all())
        }

    @Test
    fun `a row with an unknown status is unreadable for get, left out of all, and the entity never prints its token`() =
        runTest {
            database.activeSessionDao().commit(
                null,
                grants = listOf(GrantLedgerEntity.of(aGrant(token = "secret-token")).copy(status = "refunded")),
            )

            val damaged = ledger.get(PurchaseToken("secret-token"))
            assertEquals(Outcome.Failure(DomainError.StorageFailure("unreadable grant ledger row")), damaged)
            assertEquals(Outcome.Success(emptyList()), ledger.all())
            assertFalse("secret-token" in GrantLedgerEntity.of(aGrant(token = "secret-token")).toString())
        }

    @Test
    fun `a closed database is a storage failure naming no token`() =
        runTest {
            database.close()

            val failure = assertIs<Outcome.Failure<DomainError>>(ledger.get(PurchaseToken("secret-token")))
            assertFalse("secret-token" in failure.error.toString())
            assertIs<Outcome.Failure<DomainError>>(ledger.all())
            assertIs<Outcome.Failure<DomainError>>(ledger.markConsumed(PurchaseToken("x")))
            assertIs<Outcome.Failure<DomainError>>(ledger.delete(PurchaseToken("x")))
        }
}
