package com.yawnandpawn.app.core.alarm

import com.yawnandpawn.app.core.checks.CheckEntry
import com.yawnandpawn.app.core.checks.CheckMode
import com.yawnandpawn.app.core.checks.CheckType
import com.yawnandpawn.app.core.checks.Difficulty
import com.yawnandpawn.app.core.checks.qr.CodeFormat
import com.yawnandpawn.app.core.checks.qr.RegisteredCode
import com.yawnandpawn.app.core.config.InMemoryPendingChanges
import com.yawnandpawn.app.core.error.DomainError
import com.yawnandpawn.app.core.error.Outcome
import com.yawnandpawn.app.core.session.SessionLockGuard
import com.yawnandpawn.app.core.session.SessionState
import com.yawnandpawn.app.core.stats.ConfiguredCheck
import com.yawnandpawn.app.core.stats.StoredCheckRegistrations
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/** Story 3.5: the alarm use cases store, copy and remove each alarm's checks, and validate them. */
class CheckConfigUseCasesTest {
    // 07:30: the 07:00 alarm next rings tomorrow, outside the commitment lock window (Story 4.4), so edits apply at once.
    private val start = Instant.parse("2027-03-03T07:30:00Z")
    private val clock = TestClock(start)
    private val ids = SequentialIds()
    private val repository = InMemoryAlarms()
    private val checkConfigs = InMemoryCheckConfigs(repository)
    private val lock = AlarmWriteLock()
    private val sequence = InMemorySequence()
    private val scheduler = RecordingScheduler()
    private val scheduling = AlarmScheduling(repository, scheduler, clock, TestZone(TimeZone.UTC), lock, RecordingLogger())
    private val sessionLock =
        SessionLockGuard(MutableStateFlow<SessionState>(SessionState.Idle), MutableStateFlow(true), MutableStateFlow(false))
    private val save =
        SaveAlarm(
            repository,
            ids,
            clock,
            lock,
            sequence,
            scheduling,
            sessionLock,
            checkConfigs,
            InMemoryPendingChanges(),
            TestZone(TimeZone.UTC),
        )
    private val delete = DeleteAlarm(repository, lock, scheduling, sessionLock, checkConfigs)
    private val duplicate = DuplicateAlarm(repository, ids, clock, lock, sequence, scheduling, sessionLock, checkConfigs)

    private val mathHard = CheckEntry(CheckType.Math, Difficulty.Hard, count = 5)
    private val draft = AlarmDraft(time = LocalTime(7, 0), checks = listOf(mathHard), checkMode = CheckMode.All)

    private suspend fun saved(draft: AlarmDraft = this.draft): Alarm = assertIs<Outcome.Success<Alarm>>(save(draft)).value

    private fun rowsOf(alarm: Alarm): List<CheckConfig> = checkConfigs.rows.value[alarm.id].orEmpty()

    @Test
    fun `a new alarm starts with Random Math Easy 3 (Medium until the owner decision of 2026-10-08)`() {
        assertEquals(listOf(CheckEntry(CheckType.Math, Difficulty.Easy, 3)), AlarmDraft(time = LocalTime(7, 0)).checks)
        assertEquals(CheckMode.Random, AlarmDraft(time = LocalTime(7, 0)).checkMode)
        assertEquals(
            listOf(CheckType.Math, CheckType.WordUnscramble, CheckType.MemorySequence(), CheckType.QrBarcode),
            CheckConfig.PICKABLE_TYPES,
        )
    }

    @Test
    fun `saving stores the alarm with its mode and one row per check, at position 0`() =
        runTest {
            val alarm = saved()

            assertEquals(CheckMode.All, alarm.checkMode)
            assertEquals(
                CheckMode.All,
                repository.alarms.value
                    .getValue(alarm.id)
                    .checkMode,
            )
            assertEquals(
                listOf(CheckConfig(CheckConfig.idFor(alarm.id, CheckType.Math), alarm.id, 0, mathHard, start, start)),
                rowsOf(alarm),
            )
        }

    @Test
    fun `an edit keeps the row id and creation time of a type that stays and updates the rest`() =
        runTest {
            val alarm = saved()
            clock.advanceBy(5.minutes)
            val easier = CheckEntry(CheckType.Math, Difficulty.Easy, count = 2)

            saved(draft.copy(id = alarm.id, checks = listOf(easier), checkMode = CheckMode.Random))

            val row = rowsOf(alarm).single()
            assertEquals(CheckConfig.idFor(alarm.id, CheckType.Math), row.id)
            assertEquals(start, row.createdAt)
            assertEquals(start + 5.minutes, row.updatedAt)
            assertEquals(easier, row.entry)
            assertEquals(
                CheckMode.Random,
                repository.alarms.value
                    .getValue(alarm.id)
                    .checkMode,
            )
        }

    @Test
    fun `no check, a type twice, a count outside the range or the placeholder is InvalidAlarm Checks and stores nothing`() =
        runTest {
            val invalid =
                listOf(
                    emptyList(),
                    listOf(mathHard, mathHard.copy(difficulty = Difficulty.Easy)),
                    listOf(mathHard.copy(count = 0)),
                    listOf(mathHard.copy(count = 11)),
                    listOf(CheckEntry(CheckType.Placeholder, Difficulty.Medium, 1)),
                )

            invalid.forEach { checks ->
                assertEquals(Outcome.Failure(DomainError.InvalidAlarm(AlarmField.Checks)), save(draft.copy(checks = checks)), "$checks")
            }

            assertTrue(repository.alarms.value.isEmpty(), "no alarm stored")
            assertTrue(checkConfigs.rows.value.isEmpty(), "no row stored")
            assertEquals(RequestCodes.INITIAL_HIGH_WATER_MARK, sequence.lastUsed, "no request code allocated")
        }

    @Test
    fun `an edit with invalid checks keeps the stored alarm and its rows`() =
        runTest {
            val alarm = saved()
            val before = rowsOf(alarm)

            assertEquals(
                Outcome.Failure(DomainError.InvalidAlarm(AlarmField.Checks)),
                save(draft.copy(id = alarm.id, checks = emptyList())),
            )

            assertEquals(before, rowsOf(alarm))
        }

    @Test
    fun `validateChecks accepts every count in the type's range`() {
        CheckType.Math.countRange.forEach { count ->
            assertNull(validateChecks(listOf(mathHard.copy(count = count))), "count $count")
        }
    }

    @Test
    fun `when storing fails neither the alarm nor its rows are stored`() =
        runTest {
            repository.failure = DomainError.StorageFailure("disk full")

            assertEquals(Outcome.Failure(DomainError.StorageFailure("disk full")), save(draft))

            assertTrue(checkConfigs.rows.value.isEmpty())
        }

    @Test
    fun `a new alarm identical to a stored one, checks included, switches that one on`() =
        runTest {
            val alarm = saved(draft.copy(enabled = false))

            val again = saved()

            assertEquals(alarm.id, again.id)
            assertEquals(1, repository.alarms.value.size)
        }

    @Test
    fun `a stored alarm without rows counts as having the default checks when a new alarm is compared to it`() =
        runTest {
            val alarm = saved(AlarmDraft(time = LocalTime(7, 0), enabled = false))
            checkConfigs.rows.value -= alarm.id

            val again = saved(AlarmDraft(time = LocalTime(7, 0)))

            assertEquals(alarm.id, again.id, "switched on, not stored twice")
            assertEquals(1, repository.alarms.value.size)
        }

    @Test
    fun `a new alarm that differs only in its checks or its mode is stored as a second alarm`() =
        runTest {
            val alarm = saved(draft.copy(enabled = false))

            val otherChecks = saved(draft.copy(checks = listOf(mathHard.copy(count = 4))))
            val otherMode = saved(draft.copy(checkMode = CheckMode.Random))

            assertEquals(3, setOf(alarm.id, otherChecks.id, otherMode.id).size)
        }

    @Test
    fun `duplicate copies the checks in the same order to the copy`() =
        runTest {
            val alarm = saved()
            clock.advanceBy(1.minutes)

            val copy = assertIs<Outcome.Success<Alarm>>(duplicate(alarm.id)).value

            assertEquals(listOf(mathHard), rowsOf(copy).orderedEntries())
            assertEquals(CheckConfig.idFor(copy.id, CheckType.Math), rowsOf(copy).single().id)
            assertEquals(start + 1.minutes, rowsOf(copy).single().createdAt)
            assertEquals(CheckMode.All, copy.checkMode)
            assertEquals(listOf(mathHard), rowsOf(alarm).orderedEntries(), "the original keeps its checks")
        }

    @Test
    fun `duplicating an alarm stored without rows gives the copy the default checks`() =
        runTest {
            val alarm = saved()
            checkConfigs.rows.value -= alarm.id

            val copy = assertIs<Outcome.Success<Alarm>>(duplicate(alarm.id)).value

            assertEquals(CheckConfig.DEFAULT_ENTRIES, rowsOf(copy).orderedEntries())
        }

    @Test
    fun `delete removes the alarm with its checks in one step, and a failed delete keeps both and the alarm armed`() =
        runTest {
            val kept = saved(draft.copy(time = LocalTime(8, 0)))
            val alarm = saved()
            checkConfigs.failure = DomainError.StorageFailure("disk full")
            scheduler.calls.clear()

            assertEquals(Outcome.Failure(DomainError.StorageFailure("disk full")), delete(alarm.id))
            assertTrue(alarm.id in repository.alarms.value, "the alarm stays")
            assertEquals(listOf(mathHard), rowsOf(alarm).orderedEntries(), "with its checks")
            assertEquals(emptyList(), scheduler.calls, "nothing cancelled")

            checkConfigs.failure = null
            assertEquals(Outcome.Success(Unit), delete(alarm.id))

            assertNull(checkConfigs.rows.value[alarm.id])
            assertEquals(listOf(mathHard), rowsOf(kept).orderedEntries(), "other alarms keep theirs")
        }

    @Test
    fun `when the alarm itself cannot be deleted its checks stay too (review fix, one transaction)`() =
        runTest {
            val alarm = saved()
            repository.deleteFailure = DomainError.StorageFailure("locked")
            scheduler.calls.clear()

            assertEquals(Outcome.Failure(DomainError.StorageFailure("locked")), delete(alarm.id))

            assertTrue(alarm.id in repository.alarms.value, "the alarm stays")
            assertEquals(listOf(mathHard), rowsOf(alarm).orderedEntries(), "an armed alarm never loses its checks")
            assertEquals(emptyList(), scheduler.calls, "nothing cancelled")
        }

    @Test
    fun `orderedEntries sorts rows by position`() {
        val second = CheckConfig("b", "a1", 1, mathHard.copy(count = 2), start, start)
        val first = CheckConfig("a", "a1", 0, mathHard, start, start)

        assertEquals(listOf(mathHard, mathHard.copy(count = 2)), listOf(second, first).orderedEntries())
    }

    private val code = RegisteredCode.of(CodeFormat.Ean13, "4006381333931")!!
    private val qr = CheckEntry(CheckType.QrBarcode, Difficulty.Medium, count = 1, code = code)

    @Test
    fun `a QR-Barcode entry without a code is refused with CheckCode, and nothing is stored (Story 3_10)`() =
        runTest {
            assertEquals(AlarmField.CheckCode, validateChecks(listOf(mathHard, qr.copy(code = null))))
            assertNull(validateChecks(listOf(mathHard, qr)))
            val refused = save(draft.copy(checks = listOf(qr.copy(code = null))))

            assertEquals(Outcome.Failure(DomainError.InvalidAlarm(AlarmField.CheckCode)), refused)
            assertTrue(repository.alarms.value.isEmpty())
        }

    @Test
    fun `a code keeps its registration time while saved again unchanged, and a new code restarts it (Story 3_10)`() =
        runTest {
            val alarm = saved(draft.copy(checks = listOf(mathHard, qr)))
            assertEquals(start, rowsOf(alarm).single { it.entry.type == CheckType.QrBarcode }.codeRegisteredAt)
            assertNull(rowsOf(alarm).single { it.entry.type == CheckType.Math }.codeRegisteredAt, "no code, no time")

            clock.advanceBy(5.minutes)
            saved(draft.copy(id = alarm.id, checks = listOf(qr, mathHard), label = "Gym"))
            assertEquals(start, rowsOf(alarm).single { it.entry.type == CheckType.QrBarcode }.codeRegisteredAt, "same code")

            clock.advanceBy(5.minutes)
            val other = qr.copy(code = RegisteredCode.of(CodeFormat.QrCode, "hallway"))
            saved(draft.copy(id = alarm.id, checks = listOf(other)))
            assertEquals(start + 10.minutes, rowsOf(alarm).single().codeRegisteredAt, "a new code")
        }

    @Test
    fun `the stored registrations are the checks with a code, at their registration time (Story 3_10)`() =
        runTest {
            val first = saved(draft.copy(checks = listOf(mathHard, qr)))
            saved(draft.copy(time = LocalTime(8, 0)))

            assertEquals(
                listOf(ConfiguredCheck(first.id, CheckType.QrBarcode, start)),
                StoredCheckRegistrations(checkConfigs).observe().first(),
            )
        }

    private val reRegister = ReRegisterCode(repository, clock, lock, sessionLock, checkConfigs)

    @Test
    fun `re-registering stores the code alone at a new time, and refuses an alarm without QR-Barcode (review fix)`() =
        runTest {
            val alarm = saved(draft.copy(checks = listOf(mathHard, qr), enabled = false))
            val calls = scheduler.calls.size
            clock.advanceBy(5.minutes)

            assertEquals(Outcome.Success(Unit), reRegister(alarm.id, code))

            assertEquals(start + 5.minutes, rowsOf(alarm).single { it.entry.type == CheckType.QrBarcode }.codeRegisteredAt)
            assertEquals(listOf(mathHard, qr), rowsOf(alarm).orderedEntries(), "the other check and the order stay")
            assertEquals(alarm, repository.alarms.value.getValue(alarm.id), "the alarm itself is unchanged")
            assertEquals(calls, scheduler.calls.size, "nothing re-armed")

            val plain = saved(draft.copy(time = LocalTime(8, 0)))
            assertEquals(Outcome.Failure(DomainError.InvalidAlarm(AlarmField.CheckCode)), reRegister(plain.id, code))
            assertEquals(Outcome.Failure(DomainError.NotFound("missing")), reRegister("missing", code))
        }
}
