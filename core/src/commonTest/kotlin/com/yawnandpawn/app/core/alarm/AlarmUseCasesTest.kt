package com.yawnandpawn.app.core.alarm

import com.yawnandpawn.app.core.error.DomainError
import com.yawnandpawn.app.core.error.Outcome
import com.yawnandpawn.app.core.error.valueOrNull
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

class AlarmUseCasesTest {
    // Sub-millisecond part: stored alarms keep whole milliseconds, so the use cases truncate "now".
    private val start = Instant.parse("2027-03-03T06:00:00.123456Z")
    private val startMillis = Instant.parse("2027-03-03T06:00:00.123Z")
    private val clock = TestClock(start)
    private val ids = SequentialIds()
    private val repository = InMemoryAlarms()
    private val lock = AlarmWriteLock()
    private val sequence = InMemorySequence()
    private val scheduler = RecordingScheduler()
    private val scheduling = AlarmScheduling(repository, scheduler, clock, TestZone(TimeZone.UTC), lock, RecordingLogger())
    private val save = SaveAlarm(repository, ids, clock, lock, sequence, scheduling)
    private val setEnabled = SetAlarmEnabled(repository, clock, lock, scheduling)
    private val delete = DeleteAlarm(repository, lock, scheduling)
    private val duplicate = DuplicateAlarm(repository, ids, clock, lock, sequence, scheduling)

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
    fun `each new alarm gets the next code from the sequence, and a deleted alarm's code is never reused`() =
        runTest {
            val first = saved()
            val second = saved(draft.copy(time = LocalTime(8, 0)))
            delete(second.id)
            val third = saved(draft.copy(time = LocalTime(9, 0)))

            assertEquals(listOf(1000, 1001, 1002), listOf(first.requestCode, second.requestCode, third.requestCode))
            assertEquals(1002, sequence.lastUsed)
        }

    @Test
    fun `an invalid new alarm burns no code, and a failed store burns the code it was given`() =
        runTest {
            save(draft.copy(graceSeconds = 99))
            assertEquals(RequestCodes.INITIAL_HIGH_WATER_MARK, sequence.lastUsed)

            repository.failure = DomainError.StorageFailure("disk full")
            save(draft)
            repository.failure = null

            assertEquals(1001, saved().requestCode)
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
    fun `a sequence failure while allocating a request code is returned by SaveAlarm and DuplicateAlarm`() =
        runTest {
            val alarm = saved()
            val failure = DomainError.StorageFailure("corrupt")
            sequence.failure = failure

            assertEquals(Outcome.Failure(failure), save(draft.copy(time = LocalTime(8, 0))))
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
            val remove = saved(draft.copy(time = LocalTime(8, 0)))

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
    fun `a new alarm identical to a stored one switches that one on instead of storing a second`() =
        runTest {
            val original = saved()
            setEnabled(original.id, enabled = false)
            val codes = sequence.lastUsed
            clock.advanceBy(10.minutes)
            scheduler.calls.clear()

            val merged = assertIs<Outcome.Success<Alarm>>(save(draft)).value

            assertEquals(original.copy(enabled = true, updatedAt = startMillis + 10.minutes), merged)
            assertEquals(listOf(merged), stored(), "no second alarm")
            assertEquals(codes, sequence.lastUsed, "no request code allocated")
            // Monday 2027-03-08 07:00 UTC, its next occurrence.
            assertEquals(
                listOf<Call>(Call.Schedule(original.id, original.requestCode, Instant.parse("2027-03-08T07:00:00Z"))),
                scheduler.calls,
            )
        }

    @Test
    fun `a new alarm saved off never switches an identical stored alarm on, it is stored as its own alarm`() =
        runTest {
            val original = saved()
            setEnabled(original.id, enabled = false)

            val added = saved(draft.copy(enabled = false))

            assertNotEquals(original.id, added.id)
            assertTrue(stored().none { it.enabled }, "nothing switched on")
            assertEquals(2, stored().size)
        }

    @Test
    fun `a new alarm that differs in any one setting is stored as a second alarm`() =
        runTest {
            val original = saved()
            val differing =
                listOf(
                    draft.copy(time = LocalTime(7, 1)),
                    draft.copy(repeatDays = setOf(DayOfWeek.TUESDAY)),
                    draft.copy(label = "Run"),
                    draft.copy(soundRef = "builtin:chimes"),
                    draft.copy(volumePercent = 50),
                    draft.copy(gradualVolume = false),
                    draft.copy(vibration = false),
                    draft.copy(snoozeLengthMinutes = 5),
                    draft.copy(graceSeconds = 30),
                )

            val added = differing.map { saved(it) }

            assertTrue(added.none { it.id == original.id })
            assertEquals(differing.size + 1, stored().size)
        }

    @Test
    fun `a new one-time alarm identical to one that already rang switches it on for its next date`() =
        runTest {
            // A one-time 07:00 alarm that rang this morning (06:00 now, so 07:00 today had not come yet: ring first).
            val once = saved(draft.copy(repeatDays = emptySet()))
            clock.advanceBy(90.minutes)
            setEnabled(once.id, enabled = false)
            scheduler.calls.clear()

            // 07:30: the same alarm again; it rings tomorrow at 07:00.
            val merged = assertIs<Outcome.Success<Alarm>>(save(draft.copy(repeatDays = emptySet()))).value

            assertEquals(once.id, merged.id)
            assertTrue(merged.enabled)
            assertEquals(listOf(merged), stored())
            assertEquals(
                listOf<Call>(Call.Schedule(once.id, once.requestCode, Instant.parse("2027-03-04T07:00:00Z"))),
                scheduler.calls,
            )
        }

    @Test
    fun `identical settings ignore the id, request code, on-off state, timestamps and the fixed ramp start`() {
        val a = Alarm(id = "a", time = LocalTime(7, 0), requestCode = 1000, createdAt = start, updatedAt = start)
        val b =
            a.copy(
                id = "b",
                requestCode = 1001,
                enabled = false,
                rampStartPercent = 30,
                createdAt = start + 1.minutes,
                updatedAt = start + 2.minutes,
            )

        assertTrue(a.hasSameSettingsAs(b))
        assertTrue(!a.hasSameSettingsAs(b.copy(label = "Gym")))
        assertTrue(!a.hasSameSettingsAs(b.copy(graceSeconds = 30)))
    }

    @Test
    fun `the saved alarm is what the repository returns`() =
        runTest {
            val alarm = saved()

            assertEquals(alarm, repository.get(alarm.id).valueOrNull())
        }
}
