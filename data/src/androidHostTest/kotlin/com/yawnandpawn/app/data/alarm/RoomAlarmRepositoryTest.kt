package com.yawnandpawn.app.data.alarm

import android.content.Context
import androidx.room3.Room
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import com.yawnandpawn.app.core.alarm.Alarm
import com.yawnandpawn.app.core.alarm.AlarmDraft
import com.yawnandpawn.app.core.error.DomainError
import com.yawnandpawn.app.core.error.Outcome
import com.yawnandpawn.app.data.db.AppDatabase
import com.yawnandpawn.app.data.db.AppDatabaseConstructor
import com.yawnandpawn.app.testing.AlarmUseCasesFixture
import com.yawnandpawn.app.testing.DEFAULT_FAKE_INSTANT
import com.yawnandpawn.app.testing.anAlarm
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalTime
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.minutes

@RunWith(RobolectricTestRunner::class)
class RoomAlarmRepositoryTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val database =
        Room
            .inMemoryDatabaseBuilder<AppDatabase>(context, factory = { AppDatabaseConstructor.initialize() })
            .setDriver(AndroidSQLiteDriver())
            .build()
    private val dao = database.alarmDao()
    private val repository = RoomAlarmRepository(dao)

    @After
    fun tearDown() {
        database.close()
    }

    private suspend fun stored(): List<Alarm> = repository.observeAll().first()

    @Test
    fun `an inserted alarm reads back with every field intact`() =
        runTest(timeout = ROOM_IO_TIMEOUT) {
            val alarm =
                anAlarm(
                    time = LocalTime(6, 45, 30, 123_456_789),
                    repeatDays = setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.SUNDAY),
                    label = "Gym 💪",
                    enabled = false,
                ).copy(
                    soundRef = "builtin:birds",
                    volumePercent = 55,
                    gradualVolume = false,
                    rampStartPercent = 0,
                    vibration = false,
                    snoozeLengthMinutes = 15,
                    graceSeconds = 30,
                    updatedAt = DEFAULT_FAKE_INSTANT + 3.minutes,
                )

            assertEquals(Outcome.Success(Unit), repository.upsert(alarm))

            assertEquals(Outcome.Success(alarm), repository.get(alarm.id))
            assertEquals(listOf(alarm), stored())
        }

    @Test
    fun `a one-time alarm without a label reads back with no repeat days and no label`() =
        runTest(timeout = ROOM_IO_TIMEOUT) {
            val alarm = anAlarm()

            repository.upsert(alarm)

            assertEquals(Outcome.Success(alarm), repository.get(alarm.id))
        }

    @Test
    fun `upserting an existing id updates the row and keeps a single alarm`() =
        runTest(timeout = ROOM_IO_TIMEOUT) {
            val original = anAlarm(time = LocalTime(7, 0))
            repository.upsert(original)
            val edited = original.copy(time = LocalTime(8, 15), label = "Later", updatedAt = DEFAULT_FAKE_INSTANT + 1.minutes)

            assertEquals(Outcome.Success(Unit), repository.upsert(edited))

            assertEquals(listOf(edited), stored())
        }

    @Test
    fun `deleting removes the alarm and a missing id is NotFound`() =
        runTest(timeout = ROOM_IO_TIMEOUT) {
            val keep = anAlarm(id = "keep", requestCode = 1000)
            val remove = anAlarm(id = "remove", requestCode = 1001)
            repository.upsert(keep)
            repository.upsert(remove)

            assertEquals(Outcome.Success(Unit), repository.delete(remove.id))
            assertEquals(Outcome.Failure(DomainError.NotFound(remove.id)), repository.delete(remove.id))
            assertEquals(Outcome.Failure(DomainError.NotFound(remove.id)), repository.get(remove.id))
            assertEquals(listOf(keep), stored())
        }

    @Test
    fun `observeAll orders by time of day, then creation time, and emits after each change`() =
        runTest(timeout = ROOM_IO_TIMEOUT) {
            repository.upsert(anAlarm(id = "22:00", time = LocalTime(22, 0), requestCode = 1000))
            repository.upsert(anAlarm(id = "06:30", time = LocalTime(6, 30), requestCode = 1001))
            repository.upsert(anAlarm(id = "07:15", time = LocalTime(7, 15), requestCode = 1002))

            assertEquals(listOf("06:30", "07:15", "22:00"), stored().map { it.id })

            val quarterPast = LocalTime(7, 15)
            repository.upsert(
                anAlarm(
                    id = "07:15 newer",
                    time = quarterPast,
                    requestCode = 1003,
                    createdAt =
                        DEFAULT_FAKE_INSTANT + 1.minutes,
                ),
            )
            repository.upsert(
                anAlarm(
                    id = "07:15 older",
                    time = quarterPast,
                    requestCode = 1004,
                    createdAt =
                        DEFAULT_FAKE_INSTANT - 1.minutes,
                ),
            )

            assertEquals(listOf("06:30", "07:15 older", "07:15", "07:15 newer", "22:00"), stored().map { it.id })
        }

    @Test
    fun `a second alarm with the same request code is rejected as a storage failure`() =
        runTest(timeout = ROOM_IO_TIMEOUT) {
            val first = anAlarm(id = "first", requestCode = 1000)
            repository.upsert(first)

            val outcome = repository.upsert(anAlarm(id = "second", requestCode = 1000))

            assertIs<DomainError.StorageFailure>(assertIs<Outcome.Failure<DomainError>>(outcome).error)
            assertEquals(listOf(first), stored())
        }

    @Test
    fun `editing an alarm onto another alarm's request code is rejected and changes nothing`() =
        runTest(timeout = ROOM_IO_TIMEOUT) {
            val first = anAlarm(id = "first", requestCode = 1000)
            val second = anAlarm(id = "second", requestCode = 1001)
            repository.upsert(first)
            repository.upsert(second)

            val outcome = repository.upsert(second.copy(requestCode = 1000, label = "clash"))

            assertIs<DomainError.StorageFailure>(assertIs<Outcome.Failure<DomainError>>(outcome).error)
            assertEquals(setOf(first, second), stored().toSet())
        }

    @Test
    fun `the unique index rejects a duplicate request code at the table level`() =
        runTest(timeout = ROOM_IO_TIMEOUT) {
            dao.insert(anAlarm(id = "first", requestCode = 1000).toEntity())

            assertFailsWith<Exception> { dao.insert(anAlarm(id = "second", requestCode = 1000).toEntity()) }
        }

    @Test
    fun `a closed database is a storage failure, not an exception`() =
        runTest(timeout = ROOM_IO_TIMEOUT) {
            database.close()

            assertIs<Outcome.Failure<DomainError>>(repository.get("a"))
            assertIs<Outcome.Failure<DomainError>>(repository.upsert(anAlarm()))
            assertIs<Outcome.Failure<DomainError>>(repository.delete("a"))
            assertIs<Outcome.Failure<DomainError>>(repository.listAll())
        }

    @Test
    fun `on a closed database SaveAlarm and DuplicateAlarm return a storage failure instead of throwing`() =
        runTest(timeout = ROOM_IO_TIMEOUT) {
            val alarm = anAlarm()
            repository.upsert(alarm)
            val alarms =
                AlarmUseCasesFixture(repository = repository, requestCodes = RoomRequestCodeSequence(database.requestCodeSequenceDao()))
            val save = alarms.save
            val duplicate = alarms.duplicate
            database.close()

            val saved = assertIs<Outcome.Failure<DomainError>>(save(AlarmDraft(time = LocalTime(6, 0))))
            val duplicated = assertIs<Outcome.Failure<DomainError>>(duplicate(alarm.id))

            assertIs<DomainError.StorageFailure>(saved.error)
            assertIs<DomainError.StorageFailure>(duplicated.error)
        }

    @Test
    fun `a corrupt row is a storage failure on get and listAll`() =
        runTest(timeout = ROOM_IO_TIMEOUT) {
            dao.insert(anAlarm(id = "corrupt").toEntity().copy(timeNanoOfDay = -1))

            assertIs<DomainError.StorageFailure>(assertIs<Outcome.Failure<DomainError>>(repository.get("corrupt")).error)
            assertIs<DomainError.StorageFailure>(assertIs<Outcome.Failure<DomainError>>(repository.listAll()).error)
        }

    @Test
    fun `listAll returns the same order as observeAll`() =
        runTest(timeout = ROOM_IO_TIMEOUT) {
            repository.upsert(anAlarm(id = "22:00", time = LocalTime(22, 0), requestCode = 1000))
            repository.upsert(anAlarm(id = "06:30", time = LocalTime(6, 30), requestCode = 1001))

            assertEquals(Outcome.Success(stored()), repository.listAll())
            assertEquals(listOf("06:30", "22:00"), stored().map { it.id })
        }

    @Test
    fun `the core use cases work end to end over Room`() =
        runTest(timeout = ROOM_IO_TIMEOUT) {
            val save =
                AlarmUseCasesFixture(
                    repository = repository,
                    requestCodes = RoomRequestCodeSequence(database.requestCodeSequenceDao()),
                ).save

            val first = assertIs<Outcome.Success<Alarm>>(save(AlarmDraft(time = LocalTime(7, 0)))).value
            val second = assertIs<Outcome.Success<Alarm>>(save(AlarmDraft(time = LocalTime(6, 0)))).value

            assertEquals(listOf(1000, 1001), listOf(first.requestCode, second.requestCode))
            assertEquals(listOf(second, first), stored())
        }
}

/**
 * runTest's default 1-minute limit counts real time, and these tests do real Room IO: under a loaded gate run (many
 * Gradle workers on a company laptop) one of them once ran past it. A generous limit keeps them deterministic; a hang
 * still fails.
 */
private val ROOM_IO_TIMEOUT = 5.minutes
