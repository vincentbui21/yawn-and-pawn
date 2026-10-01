package com.yawnandpawn.app.data.history

import android.content.Context
import androidx.room3.Room
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import com.yawnandpawn.app.core.error.DomainError
import com.yawnandpawn.app.core.error.Outcome
import com.yawnandpawn.app.core.history.SessionOutcome
import com.yawnandpawn.app.data.db.AppDatabase
import com.yawnandpawn.app.data.db.AppDatabaseConstructor
import com.yawnandpawn.app.testing.DEFAULT_FAKE_INSTANT
import com.yawnandpawn.app.testing.aSessionHistoryRow
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.seconds

@RunWith(RobolectricTestRunner::class)
class RoomSessionHistoryRepositoryTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val database =
        Room
            .inMemoryDatabaseBuilder<AppDatabase>(context, factory = { AppDatabaseConstructor.initialize() })
            .setDriver(AndroidSQLiteDriver())
            .build()
    private val dao = database.sessionHistoryDao()
    private val repository = RoomSessionHistoryRepository(dao)

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `a session with no row reads as null`() =
        runTest {
            assertEquals(Outcome.Success(null), repository.find("missing"))
        }

    @Test
    fun `every outcome and the running state round trip through the table`() =
        runTest {
            (SessionOutcome.entries + listOf(null)).forEachIndexed { i, outcome ->
                val row = aSessionHistoryRow(sessionId = "s$i").copy(outcome = outcome)
                assertEquals(Outcome.Success(Unit), repository.upsert(row))
                assertEquals(Outcome.Success(row), repository.find(row.sessionId), "$outcome")
            }
        }

    @Test
    fun `a start row with no end, no time and no check types round trips`() =
        runTest {
            val start =
                aSessionHistoryRow().copy(
                    firstRingAt = DEFAULT_FAKE_INSTANT + 5.seconds,
                    endedAt = null,
                    checkTypes = emptyList(),
                    timeToCompleteMs = null,
                    fallbackUsed = true,
                    directBoot = true,
                    outcome = null,
                )

            repository.upsert(start)

            assertEquals(Outcome.Success(start), repository.find(start.sessionId))
            assertEquals("", dao.findById(start.sessionId)?.checkTypes)
        }

    @Test
    fun `an upsert with the same session id replaces the row, so a replayed write leaves one row`() =
        runTest {
            val row = aSessionHistoryRow()
            repository.upsert(row.copy(outcome = null, endedAt = null))
            repository.upsert(row)
            repository.upsert(row)

            assertEquals(1, dao.count())
            assertEquals(Outcome.Success(row), repository.find(row.sessionId))
        }

    @Test
    fun `instants are stored as epoch millis and outcomes and check types as stable strings`() =
        runTest {
            val row = aSessionHistoryRow().copy(checkTypes = listOf("Placeholder", "Placeholder"), outcome = SessionOutcome.Snoozed)

            repository.upsert(row)

            val stored = dao.findById(row.sessionId)!!
            assertEquals(DEFAULT_FAKE_INSTANT.toEpochMilliseconds(), stored.scheduledAt)
            assertEquals(row.endedAt?.toEpochMilliseconds(), stored.endedAt)
            assertEquals("Placeholder,Placeholder", stored.checkTypes)
            assertEquals("Snoozed", stored.outcome)
            assertEquals(
                listOf("OnTime", "Snoozed", "Missed", "Skipped", "Test"),
                SessionOutcome.entries.map { it.storedName() },
                "stored names never change",
            )
        }

    @Test
    fun `a row with an unknown outcome reads as a storage failure`() =
        runTest {
            val row = aSessionHistoryRow()
            dao.upsertRow(row.toEntity().copy(outcome = "Dozed"))

            assertIs<DomainError.StorageFailure>(assertIs<Outcome.Failure<DomainError>>(repository.find(row.sessionId)).error)
        }

    @Test
    fun `a check type name that is empty or holds a comma is refused as a storage failure and nothing is written`() =
        runTest {
            listOf(listOf("a,b"), listOf("Placeholder", "")).forEach { names ->
                val written = repository.upsert(aSessionHistoryRow().copy(checkTypes = names))
                assertIs<DomainError.StorageFailure>(assertIs<Outcome.Failure<DomainError>>(written).error, "$names")
            }
            assertEquals(0, dao.count())
        }

    @Test
    fun `a closed database is a storage failure for every call, not an exception`() =
        runTest {
            database.close()

            listOf(repository.upsert(aSessionHistoryRow()), repository.find("s"))
                .forEach { assertIs<DomainError.StorageFailure>(assertIs<Outcome.Failure<DomainError>>(it).error) }
        }
}
