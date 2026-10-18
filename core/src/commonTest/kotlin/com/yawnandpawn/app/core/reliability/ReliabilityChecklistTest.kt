package com.yawnandpawn.app.core.reliability

import com.yawnandpawn.app.core.alarm.Alarm
import com.yawnandpawn.app.core.alarm.AlarmWithChecks
import com.yawnandpawn.app.core.alarm.CheckConfig
import com.yawnandpawn.app.core.alarm.CheckConfigRepository
import com.yawnandpawn.app.core.checks.CheckEntry
import com.yawnandpawn.app.core.checks.CheckType
import com.yawnandpawn.app.core.checks.Difficulty
import com.yawnandpawn.app.core.error.DomainError
import com.yawnandpawn.app.core.error.Outcome
import com.yawnandpawn.app.core.log.LogEvent
import com.yawnandpawn.app.core.log.Logger
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant

/** Story 5.3: the reliability checklist's rows, `alarmBlocking`, the Revoked rule and [EvaluateReliability]. */
class ReliabilityChecklistTest {
    private val at = Instant.parse("2027-03-03T06:00:00Z")
    private val api34 = DeviceInfo(sdkInt = 34, maker = "Maker", fingerprint = "build-1")
    private val ok = ReliabilityStatus.ALL_OK
    private val none = StoredReliability()

    private val allFailing =
        ReliabilityStatus(
            notificationsAllowed = false,
            fullScreenIntentAllowed = false,
            exactAlarmsAllowed = false,
            dndAllowsAlarms = false,
            batteryOptimizationIgnored = false,
            backgroundRestricted = true,
            standbyBucketRestricted = true,
            cameraGranted = false,
        )

    private fun alarm(
        enabled: Boolean,
        vararg types: CheckType,
    ): AlarmWithChecks {
        val alarm = Alarm(id = "alarm-$enabled", time = LocalTime(7, 0), enabled = enabled, requestCode = 1, createdAt = at, updatedAt = at)
        val checks =
            types.mapIndexed { position, type ->
                CheckConfig(
                    CheckConfig.idFor(alarm.id, type),
                    alarm.id,
                    position,
                    CheckEntry(type, Difficulty.Easy, type.defaultCount),
                    at,
                    at,
                )
            }
        return AlarmWithChecks(alarm, checks)
    }

    private fun List<ChecklistEntry>.statusOf(row: ChecklistRow): RowStatus = single { it.row == row }.status

    @Test
    fun `all nine rows come in the epic's order, Hidden ones included`() {
        val rows = ReliabilityChecklist.evaluate(ok, emptyList(), api34, none)

        assertEquals(ChecklistRow.entries.toList(), rows.map { it.row })
        assertEquals(
            listOf(
                RowStatus.Ok,
                RowStatus.Ok,
                RowStatus.Hidden,
                RowStatus.Ok,
                RowStatus.Ok,
                RowStatus.Hidden,
                RowStatus.Hidden,
                RowStatus.Hidden,
                RowStatus.NeedsFix,
            ),
            rows.map { it.status },
        )
        assertFalse(ReliabilityChecklist.alarmBlocking(rows), "a locked test not rung yet never blocks")
    }

    @Test
    fun `exact alarms show on API 31 and 32 only`() {
        listOf(26 to RowStatus.Hidden, 30 to RowStatus.Hidden, 31 to RowStatus.NeedsFix, 32 to RowStatus.NeedsFix, 33 to RowStatus.Hidden)
            .forEach { (sdk, expected) ->
                val rows = ReliabilityChecklist.evaluate(ok.copy(exactAlarmsAllowed = false), emptyList(), api34.copy(sdkInt = sdk), none)
                assertEquals(expected, rows.statusOf(ChecklistRow.ExactAlarms), "API $sdk")
                assertEquals(expected != RowStatus.Hidden, ReliabilityChecklist.alarmBlocking(rows), "API $sdk")
            }
    }

    @Test
    fun `battery is OK only when exempt, not background restricted and not in the restricted bucket`() {
        assertTrue(ok.batteryOk)
        listOf(
            ok.copy(batteryOptimizationIgnored = false),
            ok.copy(backgroundRestricted = true),
            ok.copy(standbyBucketRestricted = true),
        ).forEach { status ->
            assertFalse(status.batteryOk, "$status")
            assertTrue(status.allOk, "Home's banner keeps the three Story 1.19 items")
            val rows = ReliabilityChecklist.evaluate(status, emptyList(), api34, none)
            assertEquals(RowStatus.NeedsFix, rows.statusOf(ChecklistRow.Battery))
            assertTrue(ReliabilityChecklist.alarmBlocking(rows))
        }
    }

    @Test
    fun `only notifications, full-screen, exact alarms, DND and battery block an alarm`() {
        val failing = ReliabilityChecklist.evaluate(allFailing, emptyList(), api34.copy(sdkInt = 31), none)
        val blocking = failing.filter { it.status == RowStatus.NeedsFix }.map { it.row }.toSet()
        assertEquals(ReliabilityChecklist.BLOCKING_ROWS + ChecklistRow.LockedScreenTest, blocking)

        listOf(
            ok.copy(notificationsAllowed = false),
            ok.copy(fullScreenIntentAllowed = false),
            ok.copy(dndAllowsAlarms = false),
            ok.copy(batteryOptimizationIgnored = false),
        ).forEach { status ->
            assertTrue(ReliabilityChecklist.alarmBlocking(ReliabilityChecklist.evaluate(status, emptyList(), api34, none)), "$status")
        }

        // Camera, Manufacturer and the locked test fail here, and none of them blocks.
        val covered = api34.copy(deviceMaker = DeviceMaker.Xiaomi)
        val rows = ReliabilityChecklist.evaluate(ok.copy(cameraGranted = false), listOf(alarm(true, CheckType.QrBarcode)), covered, none)
        assertEquals(RowStatus.NeedsFix, rows.statusOf(ChecklistRow.Camera))
        assertEquals(RowStatus.NeedsFix, rows.statusOf(ChecklistRow.Manufacturer))
        assertEquals(RowStatus.NeedsFix, rows.statusOf(ChecklistRow.LockedScreenTest))
        assertFalse(ReliabilityChecklist.alarmBlocking(rows))
    }

    @Test
    fun `camera shows only while an enabled alarm has a camera check`() {
        val denied = ok.copy(cameraGranted = false)
        assertEquals(
            RowStatus.Hidden,
            ReliabilityChecklist
                .evaluate(denied, listOf(alarm(true, CheckType.Math), alarm(false, CheckType.QrBarcode)), api34, none)
                .statusOf(ChecklistRow.Camera),
        )
        val withCamera = listOf(alarm(true, CheckType.Math, CheckType.QrBarcode))
        assertEquals(RowStatus.NeedsFix, ReliabilityChecklist.evaluate(denied, withCamera, api34, none).statusOf(ChecklistRow.Camera))
        assertEquals(RowStatus.Ok, ReliabilityChecklist.evaluate(ok, withCamera, api34, none).statusOf(ChecklistRow.Camera))
    }

    @Test
    fun `the manufacturer row shows for a covered maker and is OK for the confirmed maker and build only`() {
        val covered = api34.copy(deviceMaker = DeviceMaker.Xiaomi)
        val confirmed = none.copy(makerConfirmation = MakerConfirmation("Maker", "build-1"))

        assertEquals(RowStatus.Hidden, ReliabilityChecklist.evaluate(ok, emptyList(), api34, confirmed).statusOf(ChecklistRow.Manufacturer))
        assertEquals(RowStatus.Ok, ReliabilityChecklist.evaluate(ok, emptyList(), covered, confirmed).statusOf(ChecklistRow.Manufacturer))
        listOf(covered.copy(fingerprint = "build-2"), covered.copy(maker = "Other")).forEach { device ->
            assertEquals(
                RowStatus.NeedsFix,
                ReliabilityChecklist.evaluate(ok, emptyList(), device, confirmed).statusOf(ChecklistRow.Manufacturer),
            )
        }
    }

    @Test
    fun `a confirmed maker guidance reads Revoked after a system update changed the build fingerprint`() {
        val covered = api34.copy(deviceMaker = DeviceMaker.OppoRealme)
        val confirmed = none.copy(makerConfirmation = MakerConfirmation("Maker", "build-1"), lastOk = setOf(ChecklistRow.Manufacturer))

        assertEquals(RowStatus.Ok, ReliabilityChecklist.evaluate(ok, emptyList(), covered, confirmed).statusOf(ChecklistRow.Manufacturer))
        val updated = ReliabilityChecklist.evaluate(ok, emptyList(), covered.copy(fingerprint = "build-2"), confirmed)
        assertEquals(RowStatus.Revoked, updated.statusOf(ChecklistRow.Manufacturer))
        assertFalse(ReliabilityChecklist.alarmBlocking(updated), "the Manufacturer row never blocks")
        assertTrue(ChecklistRow.Manufacturer in ReliabilityChecklist.lastOkAfter(updated, confirmed.lastOk), "stays Revoked")
    }

    @Test
    fun `microphone is always hidden and the locked test is OK once a pass is stored`() {
        val passed = none.copy(lockedTestPassedAt = at)
        val rows = ReliabilityChecklist.evaluate(allFailing, emptyList(), api34, passed)

        assertEquals(RowStatus.Hidden, rows.statusOf(ChecklistRow.Microphone))
        assertEquals(RowStatus.Ok, rows.statusOf(ChecklistRow.LockedScreenTest))
    }

    @Test
    fun `a row that was OK and now fails is Revoked, and stays Revoked until it is fixed`() {
        val first = ReliabilityChecklist.evaluate(ok, emptyList(), api34, none)
        val lastOk = ReliabilityChecklist.lastOkAfter(first, emptySet())
        assertTrue(ChecklistRow.DoNotDisturb in lastOk)

        val dndOff = ok.copy(dndAllowsAlarms = false)
        val revoked = ReliabilityChecklist.evaluate(dndOff, emptyList(), api34, none.copy(lastOk = lastOk))
        assertEquals(RowStatus.Revoked, revoked.statusOf(ChecklistRow.DoNotDisturb))
        assertTrue(ReliabilityChecklist.alarmBlocking(revoked))

        val kept = ReliabilityChecklist.lastOkAfter(revoked, lastOk)
        assertEquals(lastOk, kept, "a Revoked row stays in the set")
        assertEquals(
            RowStatus.Revoked,
            ReliabilityChecklist.evaluate(dndOff, emptyList(), api34, none.copy(lastOk = kept)).statusOf(ChecklistRow.DoNotDisturb),
        )

        val fixed = ReliabilityChecklist.evaluate(ok, emptyList(), api34, none.copy(lastOk = kept))
        assertEquals(RowStatus.Ok, fixed.statusOf(ChecklistRow.DoNotDisturb))
    }

    @Test
    fun `a row never OK is NeedsFix and leaves the set, a Hidden row keeps its previous membership`() {
        val rows = ReliabilityChecklist.evaluate(ok.copy(batteryOptimizationIgnored = false), emptyList(), api34, none)
        assertEquals(RowStatus.NeedsFix, rows.statusOf(ChecklistRow.Battery))

        val previous = setOf(ChecklistRow.ExactAlarms, ChecklistRow.Camera, ChecklistRow.Microphone)
        val after = ReliabilityChecklist.lastOkAfter(rows, previous)

        assertTrue(ChecklistRow.Battery !in after)
        assertTrue(ChecklistRow.LockedScreenTest !in after)
        assertTrue(after.containsAll(previous), "Hidden rows keep their membership: $after")
        assertEquals(
            setOf(ChecklistRow.Notifications, ChecklistRow.FullScreenAlarm, ChecklistRow.DoNotDisturb) + previous,
            after,
        )
        assertEquals(setOf(ChecklistRow.Camera), ReliabilityChecklist.lastOkAfter(emptyList(), setOf(ChecklistRow.Camera)))
        assertEquals(
            emptySet(),
            ReliabilityChecklist.lastOkAfter(listOf(ChecklistEntry(ChecklistRow.Camera, RowStatus.NeedsFix)), setOf(ChecklistRow.Camera)),
        )
    }

    @Test
    fun `evaluating reads everything, keeps the rows seen OK and writes only when they change`() =
        runTest {
            val records = Records()
            val logger = Logs()
            val evaluate =
                EvaluateReliability(
                    { ok.copy(cameraGranted = false) },
                    { api34 },
                    Checks(listOf(alarm(true, CheckType.QrBarcode))),
                    records,
                    logger,
                )

            val rows = evaluate()

            assertEquals(RowStatus.NeedsFix, rows.statusOf(ChecklistRow.Camera))
            assertEquals(
                setOf(ChecklistRow.Notifications, ChecklistRow.FullScreenAlarm, ChecklistRow.DoNotDisturb, ChecklistRow.Battery),
                records.stored.lastOk,
            )
            assertEquals(1, records.saves)

            evaluate()
            assertEquals(1, records.saves, "nothing changed, nothing written")
            assertEquals(emptyList(), logger.events)
        }

    @Test
    fun `a read error evaluates as nothing stored and writes nothing, a write error and unreadable alarms are logged`() =
        runTest {
            val logger = Logs()
            val unreadable = Records(readFailure = DomainError.StorageFailure("disk"))
            val brokenAlarms = Checks(failure = IllegalStateException("db"))

            val rows = EvaluateReliability({ ok.copy(cameraGranted = false) }, { api34 }, brokenAlarms, unreadable, logger)()

            assertEquals(RowStatus.Hidden, rows.statusOf(ChecklistRow.Camera), "no alarms read: no camera row")
            assertEquals(0, unreadable.saves, "a read error never erases the rows seen OK")
            assertEquals(
                listOf<LogEvent>(
                    LogEvent.OperationFailed("read reliability records", "storage failure: disk"),
                    LogEvent.OperationFailed("read alarms for the reliability checklist", "IllegalStateException"),
                ),
                logger.events,
            )

            val unwritable = Records(saveFailure = DomainError.StorageFailure("full"))
            EvaluateReliability({ ok }, { api34 }, Checks(emptyList()), unwritable, logger)()
            assertEquals(LogEvent.OperationFailed("save reliability rows", "storage failure: full"), logger.events.last())
        }

    @Test
    fun `unreadable alarms hide the Camera row and keep it among the rows seen OK, with nothing written (review item 9)`() =
        runTest {
            val seenOk = setOf(ChecklistRow.Notifications, ChecklistRow.FullScreenAlarm, ChecklistRow.DoNotDisturb, ChecklistRow.Battery)
            val records = Records(StoredReliability(lastOk = seenOk + ChecklistRow.Camera))
            val brokenAlarms = Checks(failure = IllegalStateException("db"))

            val rows = EvaluateReliability({ ok }, { api34 }, brokenAlarms, records, Logs())()

            assertEquals(RowStatus.Hidden, rows.statusOf(ChecklistRow.Camera))
            assertEquals(seenOk + ChecklistRow.Camera, records.stored.lastOk)
            assertEquals(0, records.saves)
        }

    /**
     * Review item 15 pins today's Revoked rules (DEFAULT TAKEN, owner can change; listed in the spec): a Hidden row keeps
     * its old membership, so it comes back Revoked when it shows again failing; Revoked stays until the row is OK; and a
     * records read that fails shows the locked test as NeedsFix ("Fix") although a pass may be stored.
     */
    @Test
    fun `the Revoked rules, row by row`() =
        runTest {
            /** [row] on [device] with [status], [wasOk] or not before: the [expected] status, and whether it stays [kept]. */
            data class Case(
                val name: String,
                val status: ReliabilityStatus,
                val device: DeviceInfo,
                val row: ChecklistRow,
                val wasOk: Boolean,
                val expected: RowStatus,
                val kept: Boolean,
            )
            val api31 = api34.copy(sdkInt = 31)
            val exact = ChecklistRow.ExactAlarms
            val dnd = ChecklistRow.DoNotDisturb
            val exactOff = ok.copy(exactAlarmsAllowed = false)
            val dndOff = ok.copy(dndAllowsAlarms = false)
            listOf(
                Case("Hidden keeps its OK membership", exactOff, api34, exact, true, RowStatus.Hidden, true),
                Case("Hidden never seen OK stays out", ok, api34, exact, false, RowStatus.Hidden, false),
                Case("shown again and failing is Revoked", exactOff, api31, exact, true, RowStatus.Revoked, true),
                Case("Revoked stays Revoked", dndOff, api34, dnd, true, RowStatus.Revoked, true),
                Case("fixed is OK again", ok, api34, dnd, true, RowStatus.Ok, true),
                Case("never seen OK is NeedsFix", dndOff, api34, dnd, false, RowStatus.NeedsFix, false),
            ).forEach { case ->
                val lastOk = if (case.wasOk) setOf(case.row) else emptySet()
                val rows = ReliabilityChecklist.evaluate(case.status, emptyList(), case.device, none.copy(lastOk = lastOk))
                assertEquals(case.expected, rows.statusOf(case.row), case.name)
                assertEquals(case.kept, case.row in ReliabilityChecklist.lastOkAfter(rows, lastOk), case.name)
            }

            val passed = Records(StoredReliability(lockedTestPassedAt = at))
            val unreadable = Records(passed.stored, readFailure = DomainError.StorageFailure("disk"))
            val lockedTest = { records: Records ->
                EvaluateReliability({ ok }, { api34 }, Checks(), records, Logs())
            }
            assertEquals(RowStatus.Ok, lockedTest(passed)().statusOf(ChecklistRow.LockedScreenTest))
            assertEquals(
                RowStatus.NeedsFix,
                lockedTest(unreadable)().statusOf(ChecklistRow.LockedScreenTest),
                "a records read error shows the locked test as Fix",
            )
        }

    private class Records(
        var stored: StoredReliability = StoredReliability(),
        private val readFailure: DomainError? = null,
        private val saveFailure: DomainError? = null,
    ) : ReliabilityRecords {
        var saves = 0

        override suspend fun read(): Outcome<StoredReliability, DomainError> =
            readFailure?.let { Outcome.Failure(it) } ?: Outcome.Success(stored)

        override suspend fun saveLastOk(rows: Set<ChecklistRow>): Outcome<Unit, DomainError> {
            saveFailure?.let { return Outcome.Failure(it) }
            saves++
            stored = stored.copy(lastOk = rows)
            return Outcome.Success(Unit)
        }

        override suspend fun confirmMaker(confirmation: MakerConfirmation): Outcome<Unit, DomainError> = Outcome.Success(Unit)

        override suspend fun lockedTestPassed(at: Instant): Outcome<Unit, DomainError> = Outcome.Success(Unit)
    }

    private class Checks(
        private val alarms: List<AlarmWithChecks> = emptyList(),
        private val failure: Exception? = null,
    ) : CheckConfigRepository {
        override fun observeAlarmsWithChecks(): Flow<List<AlarmWithChecks>> =
            failure?.let { error -> flow { throw error } } ?: flowOf(alarms)

        override suspend fun forAlarm(alarmId: String): Outcome<List<CheckConfig>, DomainError> = error("not used")

        override suspend fun saveWithAlarm(
            alarm: Alarm,
            configs: List<CheckConfig>,
        ): Outcome<Unit, DomainError> = error("not used")

        override suspend fun deleteWithAlarm(alarmId: String): Outcome<Unit, DomainError> = error("not used")
    }

    private class Logs : Logger {
        val events = mutableListOf<LogEvent>()

        override fun log(event: LogEvent) {
            events += event
        }
    }
}
