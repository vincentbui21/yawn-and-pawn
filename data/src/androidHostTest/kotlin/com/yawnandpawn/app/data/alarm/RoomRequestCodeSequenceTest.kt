package com.yawnandpawn.app.data.alarm

import android.content.Context
import androidx.room3.Room
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import com.yawnandpawn.app.core.error.DomainError
import com.yawnandpawn.app.core.error.Outcome
import com.yawnandpawn.app.data.db.AppDatabase
import com.yawnandpawn.app.data.db.AppDatabaseConstructor
import com.yawnandpawn.app.testing.anAlarm
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertIs

@RunWith(RobolectricTestRunner::class)
class RoomRequestCodeSequenceTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val database =
        Room
            .inMemoryDatabaseBuilder<AppDatabase>(context, factory = { AppDatabaseConstructor.initialize() })
            .setDriver(AndroidSQLiteDriver())
            .build()
    private val dao = database.requestCodeSequenceDao()
    private val sequence = RoomRequestCodeSequence(dao)

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `a fresh database hands out codes from 1000 and stores the mark`() =
        runTest {
            assertEquals(Outcome.Success(1000), sequence.next())
            assertEquals(Outcome.Success(1001), sequence.next())

            assertEquals(RequestCodeSequenceEntity(lastUsed = 1001), dao.get())
        }

    @Test
    fun `a database created at v2 with alarms but no mark seeds it above their codes`() =
        runTest {
            RoomAlarmRepository(database.alarmDao()).upsert(anAlarm(requestCode = 1004))

            assertEquals(Outcome.Success(1005), sequence.next())
        }

    @Test
    fun `concurrent calls never hand out the same code`() =
        runTest {
            val codes = (1..20).map { async { sequence.next() } }.awaitAll()

            assertEquals((1000..1019).toList(), codes.map { assertIs<Outcome.Success<Int>>(it).value }.sorted())
        }

    @Test
    fun `a closed database is a storage failure, not an exception`() =
        runTest {
            database.close()

            assertIs<DomainError.StorageFailure>(assertIs<Outcome.Failure<DomainError>>(sequence.next()).error)
        }

    @Test
    fun `an exhausted sequence is a storage failure`() =
        runTest {
            dao.insert(RequestCodeSequenceEntity(lastUsed = Int.MAX_VALUE))

            assertIs<DomainError.StorageFailure>(assertIs<Outcome.Failure<DomainError>>(sequence.next()).error)
        }
}
