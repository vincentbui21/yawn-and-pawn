package com.yawnandpawn.app.core.alarm

import com.yawnandpawn.app.core.checks.CheckEntry
import com.yawnandpawn.app.core.checks.CheckMode
import com.yawnandpawn.app.core.checks.CheckPlan
import com.yawnandpawn.app.core.checks.CheckType
import com.yawnandpawn.app.core.checks.Difficulty
import com.yawnandpawn.app.core.checks.qr.CodeFormat
import com.yawnandpawn.app.core.checks.qr.RegisteredCode
import com.yawnandpawn.app.core.config.InMemoryPendingChanges
import com.yawnandpawn.app.core.config.LockedField
import com.yawnandpawn.app.core.config.Occurrence
import com.yawnandpawn.app.core.config.PendingChange
import com.yawnandpawn.app.core.config.SettingValue
import com.yawnandpawn.app.core.error.DomainError
import com.yawnandpawn.app.core.error.Outcome
import com.yawnandpawn.app.core.session.SessionLockGuard
import com.yawnandpawn.app.core.session.SessionState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

/** Story 4.4: the alarm-field path of SaveAlarm through the commitment lock. Now is 23:40, the alarm rings at 07:30. */
class SaveAlarmLockTest {
    private val now = Instant.parse("2027-03-08T23:40:00Z")
    private val clock = TestClock(now)
    private val repository = InMemoryAlarms()
    private val lock = AlarmWriteLock()
    private val scheduler = RecordingScheduler()
    private val scheduling = AlarmScheduling(repository, scheduler, clock, TestZone(TimeZone.UTC), lock, RecordingLogger())
    private val sessionLock = SessionLockGuard(MutableStateFlow(SessionState.Idle), MutableStateFlow(true), MutableStateFlow(false))
    private val checkConfigs = InMemoryCheckConfigs(repository)
    private val pending = InMemoryPendingChanges()
    private val save =
        SaveAlarm(
            repository,
            SequentialIds(),
            clock,
            lock,
            InMemorySequence(),
            scheduling,
            sessionLock,
            checkConfigs,
            pending,
            TestZone(TimeZone.UTC),
        )

    private val sevenThirty = Occurrence("id-1", Instant.parse("2027-03-09T07:30:00Z"))
    private val math = CheckEntry(CheckType.Math, Difficulty.Medium, 3)
    private val word = CheckEntry(CheckType.WordUnscramble, Difficulty.Medium, 3)
    private val draft = AlarmDraft(time = LocalTime(7, 30), graceSeconds = 20, checks = listOf(math, word), checkMode = CheckMode.All)

    private suspend fun stored(): AlarmSaved = assertIs<Outcome.Success<AlarmSaved>>(save.save(draft)).value

    private suspend fun edit(change: AlarmDraft.() -> AlarmDraft): AlarmSaved =
        assertIs<Outcome.Success<AlarmSaved>>(save.save(draft.copy(id = "id-1").change())).value

    private fun entries(): List<CheckEntry> =
        checkConfigs.rows.value
            .getValue("id-1")
            .orderedEntries()

    @Test
    fun `a new alarm has no commitment yet`() =
        runTest {
            assertNull(stored().pendingUntil)
            assertTrue(pending.changes.value.isEmpty())
        }

    @Test
    fun `a longer grace window waits, the other fields are stored at once`() =
        runTest {
            stored()

            val saved = edit { copy(graceSeconds = 30, label = "Gym", snoozeLengthMinutes = 15) }

            assertEquals(sevenThirty, saved.pendingUntil)
            val alarm = repository.alarms.value.getValue("id-1")
            assertEquals(20, alarm.graceSeconds, "the effective value stays live")
            assertEquals("Gym", alarm.label)
            assertEquals(15, alarm.snoozeLengthMinutes, "snooze length is never locked")
            assertEquals(saved.alarm, alarm)
            assertEquals(listOf(PendingChange("id-1", SettingValue.GraceSeconds(30), sevenThirty)), pending.changes.value)
        }

    @Test
    fun `a weaker check plan waits, a stronger one applies at once and clears it`() =
        runTest {
            stored()

            assertEquals(sevenThirty, edit { copy(checks = listOf(math), checkMode = CheckMode.Random) }.pendingUntil)
            assertEquals(listOf(math, word), entries())
            assertEquals(
                CheckMode.All,
                repository.alarms.value
                    .getValue("id-1")
                    .checkMode,
            )
            assertEquals(
                listOf(PendingChange("id-1", SettingValue.Checks(CheckPlan(CheckMode.Random, listOf(math))), sevenThirty)),
                pending.changes.value,
            )

            val harder = math.copy(difficulty = Difficulty.Hard)
            assertNull(edit { copy(checks = listOf(harder, word)) }.pendingUntil)
            assertEquals(listOf(harder, word), entries())
            assertTrue(pending.changes.value.isEmpty())
        }

    @Test
    fun `a shorter grace window applies at once, and so does any change outside the window`() =
        runTest {
            stored()
            assertNull(edit { copy(graceSeconds = 15) }.pendingUntil)
            assertEquals(
                15,
                repository.alarms.value
                    .getValue("id-1")
                    .graceSeconds,
            )

            clock.now = Instant.parse("2027-03-08T23:29:00Z")
            assertNull(edit { copy(graceSeconds = 30) }.pendingUntil, "8 h 01 min before the alarm")
            assertEquals(
                30,
                repository.alarms.value
                    .getValue("id-1")
                    .graceSeconds,
            )
            assertTrue(pending.changes.value.isEmpty())
        }

    @Test
    fun `saving the value already pending keeps its date, saving the effective value cancels it`() =
        runTest {
            stored()
            edit { copy(graceSeconds = 30) }

            clock.now = now + 1.hours
            assertEquals(sevenThirty, edit { copy(graceSeconds = 30, label = "Later") }.pendingUntil)
            assertEquals(listOf(PendingChange("id-1", SettingValue.GraceSeconds(30), sevenThirty)), pending.changes.value)

            assertNull(edit { copy(graceSeconds = 20) }.pendingUntil)
            assertTrue(pending.changes.value.isEmpty())
        }

    @Test
    fun `moving the alarm locks for the stored occurrence while it is inside the window`() =
        runTest {
            stored()

            // From 07:30 to 06:00: both inside, the change waits for the later one (07:30, which no longer rings).
            assertEquals(sevenThirty, edit { copy(time = LocalTime(6, 0), graceSeconds = 30) }.pendingUntil)

            // From 06:00 to 09:00 (9 h 20 min away): only the stored 06:00 is inside, so the change waits for it.
            pending.changes.value = emptyList()
            assertEquals(
                Occurrence("id-1", Instant.parse("2027-03-09T06:00:00Z")),
                edit { copy(time = LocalTime(9, 0), graceSeconds = 25) }.pendingUntil,
            )
            // Now stored at 09:00 (outside): a weakening applies at once (a nudge, owner decision 4).
            pending.changes.value = emptyList()
            assertNull(edit { copy(time = LocalTime(9, 0), graceSeconds = 30) }.pendingUntil)
        }

    @Test
    fun `an alarm off both before and after the save is never locked`() =
        runTest {
            stored()
            repository.alarms.value += "id-1" to
                repository.alarms.value
                    .getValue("id-1")
                    .copy(enabled = false)

            assertNull(edit { copy(enabled = false, graceSeconds = 30) }.pendingUntil)
            assertEquals(
                30,
                repository.alarms.value
                    .getValue("id-1")
                    .graceSeconds,
            )
        }

    @Test
    fun `a new sticker in a weakening edit still rings at once`() =
        runTest {
            val old = RegisteredCode.of(CodeFormat.QrCode, "old")!!
            val new = RegisteredCode.of(CodeFormat.QrCode, "new")!!
            val qr = CheckEntry(CheckType.QrBarcode, Difficulty.Medium, 1, old)
            assertIs<Outcome.Success<AlarmSaved>>(save.save(draft.copy(checks = listOf(qr, math))))

            val saved = edit { copy(checks = listOf(qr.copy(code = new)), checkMode = CheckMode.Random) }

            assertEquals(sevenThirty, saved.pendingUntil)
            assertEquals(listOf(qr.copy(code = new), math), entries())
            assertEquals(
                LockedField.Checks,
                pending.changes.value
                    .single()
                    .field,
            )
        }

    @Test
    fun `a failed pending write is returned after the alarm is stored with its effective values`() =
        runTest {
            stored()
            pending.writeFailure = DomainError.StorageFailure("disk")

            val moved = draft.copy(id = "id-1", time = LocalTime(7, 0), graceSeconds = 30)
            assertEquals(Outcome.Failure(DomainError.StorageFailure("disk")), save.save(moved))
            assertEquals(
                20,
                repository.alarms.value
                    .getValue("id-1")
                    .graceSeconds,
            )
            // The stored row is armed even so: an alarm is never left stored at one time and armed at another.
            assertEquals(Call.Schedule("id-1", 1_000, Instant.parse("2027-03-09T07:00:00Z")), scheduler.calls.last())

            pending.failure = DomainError.StorageFailure("disk")
            assertEquals(Outcome.Failure(DomainError.StorageFailure("disk")), save(draft.copy(id = "id-1", graceSeconds = 30)))
        }

    private fun storedAs(change: Alarm.() -> Alarm) {
        repository.alarms.value += "id-1" to
            repository.alarms.value
                .getValue("id-1")
                .change()
    }

    private fun storedGrace(): Int =
        repository.alarms.value
            .getValue("id-1")
            .graceSeconds

    @Test
    fun `moving the alarm into the window locks for the new occurrence (review 4)`() =
        runTest {
            stored()
            storedAs { copy(time = LocalTime(9, 0)) }

            val saved = edit { copy(time = LocalTime(7, 0), graceSeconds = 30) }

            assertEquals(Occurrence("id-1", Instant.parse("2027-03-09T07:00:00Z")), saved.pendingUntil)
            assertEquals(20, storedGrace())
            assertEquals(
                LocalTime(7, 0),
                repository.alarms.value
                    .getValue("id-1")
                    .time,
            )
        }

    @Test
    fun `switching an alarm on in the save locks for its new occurrence (review 4)`() =
        runTest {
            stored()
            storedAs { copy(enabled = false) }

            assertEquals(sevenThirty, edit { copy(enabled = true, graceSeconds = 30) }.pendingUntil)
            assertEquals(20, storedGrace())
        }

    @Test
    fun `switching an alarm off in the save still locks for the occurrence it gave up (review 4)`() =
        runTest {
            stored()

            assertEquals(sevenThirty, edit { copy(enabled = false, graceSeconds = 30) }.pendingUntil)
            assertEquals(20, storedGrace())
            assertEquals(
                false,
                repository.alarms.value
                    .getValue("id-1")
                    .enabled,
            )
        }

    @Test
    fun `a pending change past its occurrence is promoted on the way, and the new weakening waits (review 6)`() =
        runTest {
            stored()
            pending.changes.value = listOf(PendingChange("id-1", SettingValue.GraceSeconds(25), Occurrence("id-1", now - 1.hours)))

            assertEquals(sevenThirty, edit { copy(graceSeconds = 30) }.pendingUntil)

            assertEquals(25, storedGrace(), "the due value is live")
            assertEquals(listOf(PendingChange("id-1", SettingValue.GraceSeconds(30), sevenThirty)), pending.changes.value)
        }

    @Test
    fun `a strengthening whose row write fails leaves no weaker pending change behind (review 3)`() =
        runTest {
            stored()
            edit { copy(graceSeconds = 30) }
            repository.failure = DomainError.StorageFailure("disk")

            assertEquals(Outcome.Failure(DomainError.StorageFailure("disk")), save.save(draft.copy(id = "id-1", graceSeconds = 15)))

            assertEquals(20, storedGrace(), "the old live value stays")
            assertTrue(pending.changes.value.isEmpty(), "the weaker 30 can never be promoted later")
        }

    @Test
    fun `a strengthening whose pending removal fails changes nothing (review 3)`() =
        runTest {
            stored()
            edit { copy(graceSeconds = 30) }
            pending.removeFailure = DomainError.StorageFailure("disk")

            assertEquals(Outcome.Failure(DomainError.StorageFailure("disk")), save.save(draft.copy(id = "id-1", graceSeconds = 15)))

            assertEquals(20, storedGrace())
            assertEquals(listOf(PendingChange("id-1", SettingValue.GraceSeconds(30), sevenThirty)), pending.changes.value)
        }

    @Test
    fun `invalid input on an edit stores nothing`() =
        runTest {
            stored()
            assertEquals(
                Outcome.Failure(DomainError.InvalidAlarm(AlarmField.GraceSeconds)),
                save.save(draft.copy(id = "id-1", graceSeconds = 99)),
            )
            assertEquals(
                Outcome.Failure(DomainError.InvalidAlarm(AlarmField.Checks)),
                save.save(draft.copy(id = "id-1", checks = emptyList())),
            )
            assertTrue(pending.changes.value.isEmpty())
        }
}
