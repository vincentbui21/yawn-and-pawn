package com.yawnandpawn.app.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.test.core.app.ApplicationProvider
import com.yawnandpawn.app.core.error.DomainError
import com.yawnandpawn.app.core.error.Outcome
import com.yawnandpawn.app.core.reliability.ChecklistRow
import com.yawnandpawn.app.core.reliability.MakerConfirmation
import com.yawnandpawn.app.core.reliability.StoredReliability
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/** Story 5.3: the reliability checklist's records in their own device-protected DataStore (review fix 14). */
@RunWith(RobolectricTestRunner::class)
class DataStoreReliabilityRecordsTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private var dataStore = ReliabilityDataStore(context)
    private val passedAt = Instant.parse("2027-03-03T06:00:00Z")
    private val maker = MakerConfirmation("Maker", "build-1")

    @After
    fun tearDown() = dataStore.close()

    @Test
    fun `nothing at first, then every record is kept and survives a new DataStore`() =
        runTest {
            val records = DataStoreReliabilityRecords(dataStore.store)
            assertEquals(Outcome.Success(StoredReliability()), records.read())

            assertEquals(Outcome.Success(Unit), records.saveLastOk(setOf(ChecklistRow.Battery, ChecklistRow.DoNotDisturb)))
            assertEquals(Outcome.Success(Unit), records.lockedTestPassed(passedAt))
            assertEquals(Outcome.Success(Unit), records.confirmMaker(maker))
            assertEquals(Outcome.Success(Unit), records.recordTapped())
            dataStore.close()

            dataStore = ReliabilityDataStore(context)

            assertEquals(
                Outcome.Success(
                    StoredReliability(setOf(ChecklistRow.Battery, ChecklistRow.DoNotDisturb), passedAt, maker, recordTapped = true),
                ),
                DataStoreReliabilityRecords(dataStore.store).read(),
            )
        }

    @Test
    fun `saving the rows replaces them, and a later pass and confirmation replace the earlier ones`() =
        runTest {
            val records = DataStoreReliabilityRecords(dataStore.store)
            records.saveLastOk(setOf(ChecklistRow.Battery))
            records.saveLastOk(emptySet())
            records.lockedTestPassed(passedAt)
            records.lockedTestPassed(passedAt.plusMillis(1))
            records.confirmMaker(maker)
            records.confirmMaker(MakerConfirmation("Maker", "build-2"))

            assertEquals(
                Outcome.Success(StoredReliability(emptySet(), passedAt.plusMillis(1), MakerConfirmation("Maker", "build-2"))),
                records.read(),
            )
        }

    @Test
    fun `stored keys are the documented ones, an unknown row is skipped and half a confirmation is none`() =
        runTest {
            dataStore.store.edit {
                it[DataStoreReliabilityRecords.LAST_OK] = setOf("Battery", "Teleporter")
                it[DataStoreReliabilityRecords.MAKER] = "Maker"
            }
            val records = DataStoreReliabilityRecords(dataStore.store)

            assertEquals(Outcome.Success(StoredReliability(lastOk = setOf(ChecklistRow.Battery))), records.read())
            assertEquals("reliability_last_ok", DataStoreReliabilityRecords.LAST_OK.name)
            assertEquals("locked_test_passed_at", DataStoreReliabilityRecords.LOCKED_TEST_PASSED_AT.name)
            assertEquals("maker_confirmed_maker", DataStoreReliabilityRecords.MAKER.name)
            assertEquals("maker_confirmed_fingerprint", DataStoreReliabilityRecords.FINGERPRINT.name)
        }

    @Test
    fun `read and write errors are storage failures`() =
        runTest {
            val broken = DataStoreReliabilityRecords(BrokenStore())

            assertIs<DomainError.StorageFailure>(assertIs<Outcome.Failure<DomainError>>(broken.read()).error)
            assertIs<DomainError.StorageFailure>(assertIs<Outcome.Failure<DomainError>>(broken.saveLastOk(emptySet())).error)
            assertIs<DomainError.StorageFailure>(assertIs<Outcome.Failure<DomainError>>(broken.lockedTestPassed(passedAt)).error)
            assertIs<DomainError.StorageFailure>(assertIs<Outcome.Failure<DomainError>>(broken.confirmMaker(maker)).error)
        }

    @Test
    fun `observe emits the records, then a locked pass as it is written (Story 5_12)`() =
        runTest {
            val records = DataStoreReliabilityRecords(dataStore.store)
            val seen = MutableStateFlow<StoredReliability?>(null)
            backgroundScope.launch(Dispatchers.Default) { records.observe().collect { seen.value = it } }
            withContext(Dispatchers.Default) { withTimeout(WAIT) { seen.first { it == StoredReliability() } } }

            records.lockedTestPassed(passedAt)

            withContext(Dispatchers.Default) { withTimeout(WAIT) { seen.first { it?.lockedTestPassedAt == passedAt } } }
        }

    @Test
    fun `a read error fails the observed flow, for the caller to log`() =
        runTest {
            assertFailsWith<IOException> { DataStoreReliabilityRecords(BrokenStore()).observe().first() }
        }

    private fun Instant.plusMillis(millis: Long): Instant = Instant.fromEpochMilliseconds(toEpochMilliseconds() + millis)

    private companion object {
        val WAIT = 10.seconds
    }

    /** A DataStore whose reads and writes fail. */
    private class BrokenStore : DataStore<Preferences> {
        override val data: Flow<Preferences> = flow { throw IOException("disk unreadable") }

        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences = throw IOException("disk full")
    }
}
