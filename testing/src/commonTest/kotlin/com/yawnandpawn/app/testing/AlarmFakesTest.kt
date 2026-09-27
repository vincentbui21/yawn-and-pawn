package com.yawnandpawn.app.testing

import com.yawnandpawn.app.core.alarm.Alarm
import com.yawnandpawn.app.core.alarm.AlarmDraft
import com.yawnandpawn.app.core.alarm.AlarmWriteLock
import com.yawnandpawn.app.core.alarm.DuplicateAlarm
import com.yawnandpawn.app.core.alarm.RequestCodes
import com.yawnandpawn.app.core.alarm.SaveAlarm
import com.yawnandpawn.app.core.alarm.SetAlarmEnabled
import com.yawnandpawn.app.core.error.DomainError
import com.yawnandpawn.app.core.error.Outcome
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.minutes

class AlarmFakesTest {
    private val repository = FakeAlarmRepository()

    @Test
    fun `observeAll orders alarms by time of day, then creation time`() =
        runTest {
            repository.upsert(anAlarm(id = "late", time = LocalTime(22, 0), requestCode = 1000))
            repository.upsert(anAlarm(id = "early", time = LocalTime(6, 30), requestCode = 1001))
            repository.upsert(
                anAlarm(id = "mid-new", time = LocalTime(7, 15), requestCode = 1002, createdAt = DEFAULT_FAKE_INSTANT + 1.minutes),
            )
            repository.upsert(anAlarm(id = "mid-old", time = LocalTime(7, 15), requestCode = 1003))

            assertEquals(listOf("early", "mid-old", "mid-new", "late"), repository.observeAll().first().map { it.id })
            assertEquals(repository.observeAll().first(), repository.current)
        }

    @Test
    fun `upsert replaces an alarm with the same id`() =
        runTest {
            repository.upsert(anAlarm(label = "old"))
            repository.upsert(anAlarm(label = "new"))

            assertEquals(listOf("new"), repository.current.map { it.label })
        }

    @Test
    fun `a request code used by another alarm is a storage failure`() =
        runTest {
            repository.upsert(anAlarm(id = "a", requestCode = 1000))

            val outcome = repository.upsert(anAlarm(id = "b", requestCode = 1000))

            assertIs<DomainError.StorageFailure>(assertIs<Outcome.Failure<DomainError>>(outcome).error)
            assertEquals(listOf("a"), repository.current.map { it.id })
        }

    @Test
    fun `get and delete of a missing id are NotFound`() =
        runTest {
            assertEquals(Outcome.Failure(DomainError.NotFound("x")), repository.get("x"))
            assertEquals(Outcome.Failure(DomainError.NotFound("x")), repository.delete("x"))
        }

    @Test
    fun `get and delete of a stored alarm succeed`() =
        runTest {
            val alarm = anAlarm()
            val seeded = FakeAlarmRepository(listOf(alarm))

            assertEquals(Outcome.Success(alarm), seeded.get(alarm.id))
            assertEquals(Outcome.Success(Unit), seeded.delete(alarm.id))
            assertEquals(emptyList(), seeded.current)
        }

    @Test
    fun `a set failure makes every call fail`() =
        runTest {
            val failure = DomainError.StorageFailure("disk full")
            repository.failure = failure

            assertEquals(Outcome.Failure(failure), repository.upsert(anAlarm()))
            assertEquals(Outcome.Failure(failure), repository.get("a"))
            assertEquals(Outcome.Failure(failure), repository.delete("a"))
        }

    @Test
    fun `a set failure also fails listAll and collecting observeAll`() =
        runTest {
            repository.upsert(anAlarm())
            val failure = DomainError.StorageFailure("disk full")
            repository.failure = failure

            assertEquals(Outcome.Failure(failure), repository.listAll())
            assertFailsWith<IllegalStateException> { repository.observeAll().first() }
        }

    @Test
    fun `listAll returns the alarms in list order`() =
        runTest {
            repository.upsert(anAlarm(id = "late", time = LocalTime(22, 0), requestCode = 1000))
            repository.upsert(anAlarm(id = "early", time = LocalTime(6, 30), requestCode = 1001))

            assertEquals(Outcome.Success(repository.current), repository.listAll())
            assertEquals(listOf("early", "late"), repository.current.map { it.id })
        }

    @Test
    fun `a set failure reaches the caller of SetAlarmEnabled and DuplicateAlarm`() =
        runTest {
            val alarm = anAlarm()
            repository.upsert(alarm)
            val failure = DomainError.StorageFailure("disk full")
            repository.failure = failure
            val clock = FakeClock()
            val lock = AlarmWriteLock()

            assertEquals(Outcome.Failure(failure), SetAlarmEnabled(repository, clock, lock)(alarm.id, enabled = false))
            assertEquals(Outcome.Failure(failure), DuplicateAlarm(repository, FakeIdGenerator(), clock, lock)(alarm.id))
        }

    @Test
    fun `the id generator hands out predictable UUID v4 strings`() {
        val ids = FakeIdGenerator()

        assertEquals("00000000-0000-4000-8000-000000000001", ids.newId())
        assertEquals("00000000-0000-4000-8000-000000000002", ids.newId())
        assertEquals(listOf(FakeIdGenerator.fakeUuid(1), FakeIdGenerator.fakeUuid(2)), ids.generated)
    }

    @Test
    fun `the alarm builder uses the story defaults`() {
        val alarm = anAlarm()

        assertEquals(RequestCodes.FIRST_ALARM, alarm.requestCode)
        assertEquals(Alarm.DEFAULT_SNOOZE_LENGTH_MINUTES, alarm.snoozeLengthMinutes)
        assertEquals(alarm.createdAt, alarm.updatedAt)
    }

    @Test
    fun `the fakes drive the core use cases`() =
        runTest {
            val ids = FakeIdGenerator()
            val save = SaveAlarm(repository, ids, FakeClock(), AlarmWriteLock())

            val saved = assertIs<Outcome.Success<Alarm>>(save(AlarmDraft(time = LocalTime(6, 30)))).value

            assertEquals(FakeIdGenerator.fakeUuid(1), saved.id)
            assertEquals(DEFAULT_FAKE_INSTANT, saved.createdAt)
            assertEquals(listOf(saved), repository.current)
        }
}
