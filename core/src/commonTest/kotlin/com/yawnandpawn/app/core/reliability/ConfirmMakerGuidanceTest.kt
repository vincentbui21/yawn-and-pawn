package com.yawnandpawn.app.core.reliability

import com.yawnandpawn.app.core.error.DomainError
import com.yawnandpawn.app.core.error.Outcome
import com.yawnandpawn.app.core.log.LogEvent
import com.yawnandpawn.app.core.log.Logger
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Instant

/** Story 5.5: "I've done this" stores the maker and build, and marks the Manufacturer row seen OK. */
class ConfirmMakerGuidanceTest {
    private val phone = DeviceInfo(sdkInt = 34, maker = "OPPO", fingerprint = "oppo/build-1", deviceMaker = DeviceMaker.OppoRealme)
    private val logger = Logs()

    @Test
    fun `it stores the maker and fingerprint and adds Manufacturer to the rows seen OK`() =
        runTest {
            val records = Records(StoredReliability(lastOk = setOf(ChecklistRow.Battery)))

            assertEquals(Outcome.Success(Unit), ConfirmMakerGuidance({ phone }, records, logger)())

            assertEquals(MakerConfirmation("OPPO", "oppo/build-1"), records.stored.makerConfirmation)
            assertEquals(setOf(ChecklistRow.Battery, ChecklistRow.Manufacturer), records.stored.lastOk)
            assertEquals(emptyList(), logger.events)

            val rows = ReliabilityChecklist.evaluate(ReliabilityStatus.ALL_OK, emptyList(), phone, records.stored)
            assertEquals(RowStatus.Ok, rows.single { it.row == ChecklistRow.Manufacturer }.status)
            val updatedPhone = phone.copy(fingerprint = "oppo/build-2")
            val updated = ReliabilityChecklist.evaluate(ReliabilityStatus.ALL_OK, emptyList(), updatedPhone, records.stored)
            assertEquals(RowStatus.Revoked, updated.single { it.row == ChecklistRow.Manufacturer }.status, "after a system update")
        }

    @Test
    fun `a second confirmation leaves the rows seen OK unwritten`() =
        runTest {
            val records = Records(StoredReliability(lastOk = setOf(ChecklistRow.Manufacturer)))

            ConfirmMakerGuidance({ phone }, records, logger)()

            assertEquals(0, records.lastOkSaves)
        }

    @Test
    fun `a failed confirmation is logged and returned, and nothing else is written`() =
        runTest {
            val failure = DomainError.StorageFailure("disk")
            val records = Records(confirmFailure = failure)

            assertEquals(Outcome.Failure(failure), ConfirmMakerGuidance({ phone }, records, logger)())

            assertNull(records.stored.makerConfirmation)
            assertEquals(0, records.lastOkSaves)
            assertEquals(listOf<LogEvent>(LogEvent.OperationFailed(ConfirmMakerGuidance.CONFIRM, "storage failure: disk")), logger.events)
        }

    @Test
    fun `a failed read or write of the rows seen OK is logged and the confirmation still counts`() =
        runTest {
            val unreadable = Records(readFailure = DomainError.StorageFailure("read"))
            assertEquals(Outcome.Success(Unit), ConfirmMakerGuidance({ phone }, unreadable, logger)())
            assertEquals(MakerConfirmation("OPPO", "oppo/build-1"), unreadable.stored.makerConfirmation)
            assertEquals(0, unreadable.lastOkSaves, "a read error never overwrites the rows seen OK")

            val unwritable = Records(saveFailure = DomainError.StorageFailure("write"))
            assertEquals(Outcome.Success(Unit), ConfirmMakerGuidance({ phone }, unwritable, logger)())
            assertEquals(MakerConfirmation("OPPO", "oppo/build-1"), unwritable.stored.makerConfirmation)

            assertEquals(
                listOf<LogEvent>(
                    LogEvent.OperationFailed(ConfirmMakerGuidance.SEEN_OK, "storage failure: read"),
                    LogEvent.OperationFailed(ConfirmMakerGuidance.SEEN_OK, "storage failure: write"),
                ),
                logger.events,
            )
        }

    private class Records(
        var stored: StoredReliability = StoredReliability(),
        private val confirmFailure: DomainError? = null,
        private val readFailure: DomainError? = null,
        private val saveFailure: DomainError? = null,
    ) : ReliabilityRecords {
        var lastOkSaves = 0

        override suspend fun read(): Outcome<StoredReliability, DomainError> =
            readFailure?.let { Outcome.Failure(it) } ?: Outcome.Success(stored)

        override suspend fun saveLastOk(rows: Set<ChecklistRow>): Outcome<Unit, DomainError> {
            saveFailure?.let { return Outcome.Failure(it) }
            lastOkSaves++
            stored = stored.copy(lastOk = rows)
            return Outcome.Success(Unit)
        }

        override suspend fun confirmMaker(confirmation: MakerConfirmation): Outcome<Unit, DomainError> {
            confirmFailure?.let { return Outcome.Failure(it) }
            stored = stored.copy(makerConfirmation = confirmation)
            return Outcome.Success(Unit)
        }

        override suspend fun lockedTestPassed(at: Instant): Outcome<Unit, DomainError> = Outcome.Success(Unit)
    }

    private class Logs : Logger {
        val events = mutableListOf<LogEvent>()

        override fun log(event: LogEvent) {
            events += event
        }
    }
}
