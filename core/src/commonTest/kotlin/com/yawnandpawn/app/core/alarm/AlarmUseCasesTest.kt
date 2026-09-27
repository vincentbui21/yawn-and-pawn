package com.yawnandpawn.app.core.alarm

import com.yawnandpawn.app.core.error.DomainError
import com.yawnandpawn.app.core.error.Outcome
import com.yawnandpawn.app.core.error.valueOrNull
import com.yawnandpawn.app.core.id.IdGenerator
import com.yawnandpawn.app.core.time.Clock
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/** Core cannot depend on `:testing` (AD-1), so these tests use small local doubles; `:testing` has the shared fakes. */
class AlarmUseCasesTest {
    private class TestClock(
        var now: Instant,
    ) : Clock {
        override fun now(): Instant = now

        fun advanceBy(duration: Duration) {
            now += duration
        }
    }

    private class SequentialIds : IdGenerator {
        private var next = 1

        override fun newId(): String = "id-${next++}"
    }

    private class InMemoryAlarms : AlarmRepository {
        val alarms = MutableStateFlow<Map<String, Alarm>>(emptyMap())

        /** Fails every upsert. */
        var failure: DomainError.StorageFailure? = null

        /** Fails every listAll. */
        var listFailure: DomainError.StorageFailure? = null
        var upserts = 0

        override fun observeAll(): Flow<List<Alarm>> = alarms.map { it.values.sortedWith(AlarmListOrder) }

        override suspend fun listAll(): Outcome<List<Alarm>, DomainError> {
            // Suspends like a real database read, so concurrent callers can interleave here.
            yield()
            listFailure?.let { return Outcome.Failure(it) }
            return Outcome.Success(alarms.value.values.sortedWith(AlarmListOrder))
        }

        override suspend fun get(id: String): Outcome<Alarm, DomainError> =
            alarms.value[id]?.let { Outcome.Success(it) } ?: Outcome.Failure(DomainError.NotFound(id))

        override suspend fun upsert(alarm: Alarm): Outcome<Unit, DomainError> {
            yield()
            upserts++
            failure?.let { return Outcome.Failure(it) }
            alarms.value += alarm.id to alarm
            return Outcome.Success(Unit)
        }

        override suspend fun delete(id: String): Outcome<Unit, DomainError> {
            if (id !in alarms.value) return Outcome.Failure(DomainError.NotFound(id))
            alarms.value -= id
            return Outcome.Success(Unit)
        }
    }

    // Sub-millisecond part: stored alarms keep whole milliseconds, so the use cases truncate "now".
    private val start = Instant.parse("2027-03-03T06:00:00.123456Z")
    private val startMillis = Instant.parse("2027-03-03T06:00:00.123Z")
    private val clock = TestClock(start)
    private val ids = SequentialIds()
    private val repository = InMemoryAlarms()
    private val lock = AlarmWriteLock()
    private val save = SaveAlarm(repository, ids, clock, lock)
    private val setEnabled = SetAlarmEnabled(repository, clock, lock)
    private val delete = DeleteAlarm(repository, lock)
    private val duplicate = DuplicateAlarm(repository, ids, clock, lock)

    private val draft = AlarmDraft(time = LocalTime(7, 0), repeatDays = setOf(DayOfWeek.MONDAY), label = "Gym")

    private suspend fun saved(draft: AlarmDraft = this.draft): Alarm = assertIs<Outcome.Success<Alarm>>(save(draft)).value

    private suspend fun stored(): List<Alarm> = repository.observeAll().first()

    @Test
    fun `saving a new alarm stores it with a new id, the first alarm code and createdAt equal to updatedAt`() =
        runTest {
            val alarm = saved()

            assertEquals("id-1", alarm.id)
            assertEquals(RequestCodes.FIRST_ALARM, alarm.requestCode)
            assertEquals(startMillis, alarm.createdAt)
            assertEquals(startMillis, alarm.updatedAt)
            assertEquals(LocalTime(7, 0), alarm.time)
            assertEquals("Gym", alarm.label)
            assertEquals(listOf(alarm), stored())
        }

    @Test
    fun `each new alarm gets the next code above the highest in use`() =
        runTest {
            val first = saved()
            val second = saved()
            delete(first.id)
            val third = saved()

            assertEquals(listOf(1000, 1001, 1002), listOf(first.requestCode, second.requestCode, third.requestCode))
        }

    @Test
    fun `editing keeps id, request code and createdAt and moves updatedAt to now`() =
        runTest {
            val original = saved()
            clock.advanceBy(5.minutes)

            val edited = assertIs<Outcome.Success<Alarm>>(save(draft.copy(id = original.id, time = LocalTime(7, 30)))).value

            assertEquals(original.id, edited.id)
            assertEquals(original.requestCode, edited.requestCode)
            assertEquals(original.createdAt, edited.createdAt)
            assertEquals(startMillis + 5.minutes, edited.updatedAt)
            assertEquals(LocalTime(7, 30), edited.time)
            assertEquals(listOf(edited), stored())
        }

    @Test
    fun `editing an alarm that does not exist is NotFound`() =
        runTest {
            assertEquals(Outcome.Failure(DomainError.NotFound("missing")), save(draft.copy(id = "missing")))
        }

    private data class InvalidCase(
        val name: String,
        val draft: AlarmDraft,
        val field: AlarmField,
    )

    private val invalidCases =
        listOf(
            InvalidCase("label of 41 characters", draft.copy(label = "x".repeat(41)), AlarmField.Label),
            InvalidCase("snooze of 7 min", draft.copy(snoozeLengthMinutes = 7), AlarmField.SnoozeLengthMinutes),
            InvalidCase("grace of 14 s", draft.copy(graceSeconds = 14), AlarmField.GraceSeconds),
            InvalidCase("grace of 31 s", draft.copy(graceSeconds = 31), AlarmField.GraceSeconds),
            InvalidCase("volume of 101 %", draft.copy(volumePercent = 101), AlarmField.VolumePercent),
            InvalidCase("ramp start of -1 %", draft.copy(rampStartPercent = -1), AlarmField.RampStartPercent),
        )

    @Test
    fun `saving an invalid new alarm stores nothing and names the field`() =
        runTest {
            invalidCases.forEach { case ->
                assertEquals(Outcome.Failure(DomainError.InvalidAlarm(case.field)), save(case.draft), case.name)
            }
            assertEquals(emptyList(), stored())
            assertEquals(0, repository.upserts)
        }

    @Test
    fun `saving an invalid edit leaves the stored alarm unchanged`() =
        runTest {
            val original = saved()

            invalidCases.forEach { case ->
                assertEquals(Outcome.Failure(DomainError.InvalidAlarm(case.field)), save(case.draft.copy(id = original.id)), case.name)
            }
            assertEquals(listOf(original), stored())
        }

    @Test
    fun `a storage failure on save is returned, not thrown`() =
        runTest {
            val failure = DomainError.StorageFailure("disk full")
            repository.failure = failure

            assertEquals(Outcome.Failure(failure), save(draft))
        }

    @Test
    fun `enabling and disabling flips enabled and moves updatedAt to now`() =
        runTest {
            val original = saved()
            clock.advanceBy(1.minutes)

            val disabled = assertIs<Outcome.Success<Alarm>>(setEnabled(original.id, enabled = false)).value
            assertEquals(false, disabled.enabled)
            assertEquals(startMillis + 1.minutes, disabled.updatedAt)
            assertEquals(original.copy(enabled = false, updatedAt = startMillis + 1.minutes), stored().single())

            clock.advanceBy(1.minutes)
            val enabled = assertIs<Outcome.Success<Alarm>>(setEnabled(original.id, enabled = true)).value
            assertEquals(true, enabled.enabled)
            assertEquals(startMillis + 2.minutes, enabled.updatedAt)
        }

    @Test
    fun `enabling an alarm that does not exist is NotFound`() =
        runTest {
            assertEquals(Outcome.Failure(DomainError.NotFound("missing")), setEnabled("missing", enabled = true))
        }

    @Test
    fun `a stored alarm with out-of-range values can still be disabled`() =
        runTest {
            val bad = saved().copy(graceSeconds = 99)
            repository.alarms.value += bad.id to bad

            val disabled = assertIs<Outcome.Success<Alarm>>(setEnabled(bad.id, enabled = false)).value

            assertEquals(false, disabled.enabled)
            assertEquals(listOf(disabled), stored())
        }

    @Test
    fun `an upsert failure is returned by SetAlarmEnabled and DuplicateAlarm`() =
        runTest {
            val alarm = saved()
            val failure = DomainError.StorageFailure("disk full")
            repository.failure = failure

            assertEquals(Outcome.Failure(failure), setEnabled(alarm.id, enabled = false))
            assertEquals(Outcome.Failure(failure), duplicate(alarm.id))
            assertEquals(listOf(alarm), stored())
        }

    @Test
    fun `a listing failure while allocating a request code is returned by SaveAlarm and DuplicateAlarm`() =
        runTest {
            val alarm = saved()
            val failure = DomainError.StorageFailure("corrupt")
            repository.listFailure = failure

            assertEquals(Outcome.Failure(failure), save(draft))
            assertEquals(Outcome.Failure(failure), duplicate(alarm.id))
            assertEquals(listOf(alarm), stored())
        }

    @Test
    fun `two concurrent saves both succeed with different request codes`() =
        runTest {
            val first = async { save(draft) }
            val second = async { save(draft.copy(time = LocalTime(8, 0))) }

            val codes = listOf(first.await(), second.await()).map { assertIs<Outcome.Success<Alarm>>(it).value.requestCode }

            assertEquals(setOf(1000, 1001), codes.toSet())
            assertEquals(2, stored().size)
        }

    @Test
    fun `the time is stored in whole minutes`() =
        runTest {
            val alarm = saved(draft.copy(time = LocalTime(7, 15, 42, 123_000_000)))

            assertEquals(LocalTime(7, 15), alarm.time)
            assertEquals(LocalTime(7, 15), stored().single().time)
        }

    @Test
    fun `labels are trimmed and a blank label is stored as no label`() =
        runTest {
            assertEquals("Gym", saved(draft.copy(label = "  Gym \n")).label)
            assertEquals(null, saved(draft.copy(label = "   ")).label)
            assertEquals(null, saved(draft.copy(label = "")).label)
            assertEquals(null, saved(draft.copy(label = null)).label)
        }

    @Test
    fun `a label is checked for length after trimming`() =
        runTest {
            assertEquals("x".repeat(40), saved(draft.copy(label = " " + "x".repeat(40) + " ")).label)
        }

    @Test
    fun `deleting removes an existing alarm`() =
        runTest {
            val keep = saved()
            val remove = saved()

            assertEquals(Outcome.Success(Unit), delete(remove.id))
            assertEquals(listOf(keep), stored())
        }

    @Test
    fun `deleting a missing alarm is NotFound and changes nothing`() =
        runTest {
            val keep = saved()

            assertEquals(Outcome.Failure(DomainError.NotFound("missing")), delete("missing"))
            assertEquals(listOf(keep), stored())
        }

    @Test
    fun `duplicating copies every setting with a new id, a new code and fresh timestamps`() =
        runTest {
            val original = saved(draft.copy(snoozeLengthMinutes = 15, graceSeconds = 30, enabled = false))
            clock.advanceBy(10.minutes)

            val copy = assertIs<Outcome.Success<Alarm>>(duplicate(original.id)).value

            assertNotEquals(original.id, copy.id)
            assertEquals(original.requestCode + 1, copy.requestCode)
            assertEquals(startMillis + 10.minutes, copy.createdAt)
            assertEquals(startMillis + 10.minutes, copy.updatedAt)
            assertEquals(
                original.copy(id = copy.id, requestCode = copy.requestCode, createdAt = copy.createdAt, updatedAt = copy.updatedAt),
                copy,
            )
            assertEquals(setOf(original, copy), stored().toSet())
        }

    @Test
    fun `duplicating a missing alarm is NotFound`() =
        runTest {
            assertEquals(Outcome.Failure(DomainError.NotFound("missing")), duplicate("missing"))
            assertTrue(stored().isEmpty())
        }

    @Test
    fun `the saved alarm is what the repository returns`() =
        runTest {
            val alarm = saved()

            assertEquals(alarm, repository.get(alarm.id).valueOrNull())
        }
}
