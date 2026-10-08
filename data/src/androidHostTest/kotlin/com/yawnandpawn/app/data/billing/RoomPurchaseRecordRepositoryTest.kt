package com.yawnandpawn.app.data.billing

import android.content.Context
import androidx.room3.Room
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import com.yawnandpawn.app.core.billing.GrantLedgerEntry
import com.yawnandpawn.app.core.billing.GrantLedgerStore
import com.yawnandpawn.app.core.billing.LedgerStatus
import com.yawnandpawn.app.core.billing.Money
import com.yawnandpawn.app.core.billing.PriceSnapshotLookup
import com.yawnandpawn.app.core.billing.PurchaseLedger
import com.yawnandpawn.app.core.billing.PurchaseRecord
import com.yawnandpawn.app.core.billing.PurchaseRecordRepository
import com.yawnandpawn.app.core.billing.RecordStatus
import com.yawnandpawn.app.core.billing.SettleResult
import com.yawnandpawn.app.core.billing.hash
import com.yawnandpawn.app.core.error.DomainError
import com.yawnandpawn.app.core.error.Outcome
import com.yawnandpawn.app.core.session.ConsumeResult
import com.yawnandpawn.app.core.session.PurchaseToken
import com.yawnandpawn.app.core.session.RuntimeWrite
import com.yawnandpawn.app.core.session.SessionState
import com.yawnandpawn.app.data.db.AppDatabase
import com.yawnandpawn.app.data.db.AppDatabaseConstructor
import com.yawnandpawn.app.data.db.RuntimeDatabase
import com.yawnandpawn.app.data.db.RuntimeDatabaseConstructor
import com.yawnandpawn.app.data.session.RoomActiveSessionStore
import com.yawnandpawn.app.data.session.RoomGrantLedgerStore
import com.yawnandpawn.app.data.session.RoomPurchaseIntentStore
import com.yawnandpawn.app.testing.DEFAULT_FAKE_INSTANT
import com.yawnandpawn.app.testing.FakeBilling
import com.yawnandpawn.app.testing.FakeClock
import com.yawnandpawn.app.testing.FakeLogger
import com.yawnandpawn.app.testing.RecordingBackgroundWork
import com.yawnandpawn.app.testing.aGrant
import com.yawnandpawn.app.testing.aPurchaseIntent
import com.yawnandpawn.app.testing.aPurchaseRecord
import com.yawnandpawn.app.testing.aSession
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/**
 * Story 4.10: `purchase_record` in `app.db` holds every charge by token hash, and `PurchaseLedger` settles a grant across
 * the two real databases (`runtime.db`'s ledger and `app.db`'s records) wherever the process dies in between.
 */
@RunWith(RobolectricTestRunner::class)
class RoomPurchaseRecordRepositoryTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val appDb =
        Room
            .inMemoryDatabaseBuilder<AppDatabase>(context, factory = { AppDatabaseConstructor.initialize() })
            .setDriver(AndroidSQLiteDriver())
            .build()
    private val runtimeDb =
        Room
            .inMemoryDatabaseBuilder<RuntimeDatabase>(context, factory = { RuntimeDatabaseConstructor.initialize() })
            .setDriver(AndroidSQLiteDriver())
            .build()
    private val records = RoomPurchaseRecordRepository(appDb.purchaseRecordDao())
    private val ledger = RoomGrantLedgerStore(runtimeDb.grantLedgerDao())
    private val sessions = RoomActiveSessionStore(runtimeDb.activeSessionDao(), FakeClock())
    private val intents = RoomPurchaseIntentStore(runtimeDb.purchaseIntentDao())
    private val billing = FakeBilling()
    private val logger = FakeLogger()

    @After
    fun tearDown() {
        appDb.close()
        runtimeDb.close()
    }

    @Test
    fun `every status and nullable field round-trips, newest purchase first, keyed by the token hash only`() =
        runTest {
            val rows =
                listOf(
                    aPurchaseRecord(token = "a", status = RecordStatus.Granted, price = Money(1_190_000, "EUR"), orderId = "GPA.1"),
                    aPurchaseRecord(token = "b", status = RecordStatus.Consumed, purchasedAt = DEFAULT_FAKE_INSTANT + 1.minutes),
                    aPurchaseRecord(
                        token = "c",
                        status = RecordStatus.Stranded,
                        sessionId = null,
                        alarmId = null,
                        snoozeNumber = null,
                        purchasedAt = DEFAULT_FAKE_INSTANT + 2.minutes,
                    ),
                    aPurchaseRecord(
                        token = "d",
                        status = RecordStatus.Reused,
                        price = Money(150, "JPY"),
                        purchasedAt = DEFAULT_FAKE_INSTANT + 3.minutes,
                    ),
                )
            rows.forEach { assertEquals(Outcome.Success(Unit), records.put(it)) }

            assertEquals(Outcome.Success(rows.reversed()), records.all())
            rows.forEach { assertEquals(Outcome.Success(it), records.get(it.tokenHash)) }
            assertEquals(Outcome.Success(null), records.get(PurchaseToken("none").hash()))
            assertEquals(
                PurchaseToken("a").hash(),
                appDb
                    .purchaseRecordDao()
                    .all()
                    .last()
                    .tokenHash,
            )
        }

    @Test
    fun `a put replaces the record of the same token hash`() =
        runTest {
            val granted = aPurchaseRecord(status = RecordStatus.Granted)
            records.put(granted)
            records.put(granted.copy(status = RecordStatus.Consumed))

            assertEquals(Outcome.Success(listOf(granted.copy(status = RecordStatus.Consumed))), records.all())
        }

    @Test
    fun `a damaged row is a storage failure for get and left out of the list, and a closed database fails`() =
        runTest {
            val good = aPurchaseRecord(token = "good")
            records.put(good)
            appDb.purchaseRecordDao().upsertRecord(PurchaseRecordEntity.of(aPurchaseRecord(token = "bad")).copy(status = "voided"))
            appDb.purchaseRecordDao().upsertRecord(PurchaseRecordEntity.of(aPurchaseRecord(token = "eur")).copy(currency = "eur"))

            val bad = records.get(PurchaseToken("bad").hash())
            assertEquals(Outcome.Failure(DomainError.StorageFailure("unreadable purchase record")), bad)
            assertEquals(Outcome.Success(listOf(good)), records.all())

            appDb.close()
            assertIs<Outcome.Failure<DomainError>>(records.put(good))
            assertIs<Outcome.Failure<DomainError>>(records.get(good.tokenHash))
        }

    /** The process dies right after this write. Not an `Exception`, so nothing in the ledger catches it. */
    private class ProcessDied : Throwable("process died")

    private enum class Crash { AfterCommit, AfterRecordUpsert, AfterConsume, AfterLedgerConsumed, AfterRecordConsumed }

    private class CrashingRecords(
        private val real: PurchaseRecordRepository,
        private val at: Crash,
    ) : PurchaseRecordRepository by real {
        override suspend fun put(record: PurchaseRecord): Outcome<Unit, DomainError> {
            val put = real.put(record)
            val dies = if (record.status == RecordStatus.Consumed) at == Crash.AfterRecordConsumed else at == Crash.AfterRecordUpsert
            if (dies) throw ProcessDied()
            return put
        }
    }

    private class CrashingLedger(
        private val real: GrantLedgerStore,
        private val at: Crash,
    ) : GrantLedgerStore by real {
        override suspend fun markConsumed(token: PurchaseToken): Outcome<Unit, DomainError> =
            real.markConsumed(token).also { if (at == Crash.AfterLedgerConsumed) throw ProcessDied() }
    }

    private fun purchaseLedger(
        records: PurchaseRecordRepository = this.records,
        ledger: GrantLedgerStore = this.ledger,
        billing: FakeBilling = this.billing,
    ) = PurchaseLedger(ledger, records, intents, billing, RecordingBackgroundWork(), PriceSnapshotLookup.None, FakeClock(), logger)

    @Test
    fun `a grant settles across the two databases to one consumed record wherever the process dies`() =
        runTest {
            Crash.entries.forEach { crash ->
                val token = PurchaseToken("token-$crash")
                // The Pay's intent and the grant, each committed with its state in runtime.db.
                val intent = aPurchaseIntent(intentId = "i-$crash", sessionId = "session-1", price = Money(1_190_000, "EUR"))
                val snoozed = SessionState.Snoozed(aSession(sessionId = "session-1").copy(snoozesGranted = 1, interactionDeadline = null))
                sessions.commit(snoozed, listOf(RuntimeWrite.PutPurchaseIntent(intent)))
                val grant: GrantLedgerEntry = aGrant(token = token.value, sessionId = "session-1")
                assertEquals(Outcome.Success(Unit), sessions.commit(snoozed, listOf(RuntimeWrite.PutGrant(grant))))
                val play =
                    object : FakeBilling() {
                        override suspend fun consume(token: PurchaseToken): ConsumeResult =
                            super.consume(token).also { if (crash == Crash.AfterConsume && consumed.size == 1) throw ProcessDied() }
                    }

                if (crash != Crash.AfterCommit) {
                    val dying = purchaseLedger(CrashingRecords(records, crash), CrashingLedger(ledger, crash), play)
                    assertFailsWith<ProcessDied>("$crash") { dying.settle(token) }
                }
                assertEquals(SettleResult.Settled, purchaseLedger(billing = play).settleAll(), "$crash: the next start replays")

                val record = assertIs<Outcome.Success<PurchaseRecord?>>(records.get(token.hash())).value
                assertEquals(RecordStatus.Consumed, record?.status, "$crash")
                assertEquals(Money(1_190_000, "EUR"), record?.price, "$crash: priced by the intent")
                assertEquals(Outcome.Success(null), ledger.get(token), "$crash: the ledger row is gone")
                assertTrue(play.consumed.size in 1..2, "$crash: ${play.consumed.size} consumes")
            }
            assertEquals(Crash.entries.size, appDb.purchaseRecordDao().count(), "one record per payment, no duplicate")
            assertEquals(0, runtimeDb.grantLedgerDao().count())
        }

    @Test
    fun `a consume that fails leaves both rows granted, and the charge is already in history`() =
        runTest {
            val grant = aGrant()
            sessions.commit(SessionState.Snoozed(aSession()), listOf(RuntimeWrite.PutGrant(grant)))
            billing.consumeResult = ConsumeResult.Failed("SERVICE_UNAVAILABLE")

            assertEquals(SettleResult.RetryLater, purchaseLedger().settle(grant.token))

            assertEquals(Outcome.Success(listOf(grant)), ledger.all())
            assertEquals(RecordStatus.Granted, assertIs<Outcome.Success<PurchaseRecord?>>(records.get(grant.token.hash())).value?.status)
            assertEquals(
                LedgerStatus.Granted,
                runtimeDb
                    .grantLedgerDao()
                    .get(grant.token.value)
                    ?.toEntry()
                    ?.status,
            )
        }
}
