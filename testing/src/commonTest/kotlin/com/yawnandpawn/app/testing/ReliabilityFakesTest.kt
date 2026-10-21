package com.yawnandpawn.app.testing

import com.yawnandpawn.app.core.error.DomainError
import com.yawnandpawn.app.core.error.Outcome
import com.yawnandpawn.app.core.reliability.ChecklistRow
import com.yawnandpawn.app.core.reliability.DeviceMaker
import com.yawnandpawn.app.core.reliability.MakerConfirmation
import com.yawnandpawn.app.core.reliability.ReliabilityChecklist
import com.yawnandpawn.app.core.reliability.ReliabilityStatus
import com.yawnandpawn.app.core.reliability.StoredReliability
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant

class ReliabilityFakesTest {
    @Test
    fun `the probe returns its status and counts checks`() {
        val probe = FakeReliabilityProbe()
        assertEquals(ReliabilityStatus.ALL_OK, probe.check())
        probe.status = probe.status.copy(notificationsAllowed = false)
        assertFalse(probe.check().notificationsAllowed)
        assertEquals(2, probe.checks)
    }

    @Test
    fun `settings record what was opened and the permission is asked once`() {
        val settings = FakeReliabilitySettings()
        assertTrue(settings.open(ChecklistRow.ExactAlarms))
        settings.opens = false
        assertFalse(settings.open(ChecklistRow.Battery))
        assertEquals(listOf(ChecklistRow.ExactAlarms, ChecklistRow.Battery), settings.opened)

        val maker = FakeManufacturerSettings()
        maker.open(DeviceMaker.Vivo)
        assertEquals(listOf(DeviceMaker.Vivo), maker.opened)

        val permission = FakeNotificationPermission(needed = true)
        assertTrue(permission.shouldRequest())
        permission.request()
        assertFalse(permission.shouldRequest())
        assertEquals(1, permission.requests)

        val failing = FakeNotificationPermission(needed = true, failure = IllegalStateException("no activity"))
        assertFailsWith<IllegalStateException> { failing.request() }
        assertTrue(failing.shouldRequest(), "still to ask")
    }

    @Test
    fun `the records keep what was written and fail without changing anything`() =
        runTest {
            val records = FakeReliabilityRecords()
            val at = Instant.parse("2027-03-03T06:00:00Z")
            val maker = MakerConfirmation("Maker", "build-1")

            assertEquals(Outcome.Success(Unit), records.saveLastOk(setOf(ChecklistRow.Battery)))
            assertEquals(Outcome.Success(Unit), records.lockedTestPassed(at))
            assertEquals(Outcome.Success(Unit), records.confirmMaker(maker))
            assertEquals(Outcome.Success(StoredReliability(setOf(ChecklistRow.Battery), at, maker)), records.read())
            assertEquals(3, records.writes)

            val failure = DomainError.StorageFailure("disk full")
            records.writeFailure = failure
            records.readFailure = failure
            assertEquals(Outcome.Failure(failure), records.saveLastOk(emptySet()))
            assertEquals(Outcome.Failure(failure), records.read())
            assertEquals(setOf(ChecklistRow.Battery), records.stored.lastOk)
            assertEquals(3, records.writes)
        }

    @Test
    fun `the evaluation over fakes blocks nothing by default and follows the probe`() =
        runTest {
            val probe = FakeReliabilityProbe()
            val evaluate = anEvaluateReliability(probe = probe)
            assertFalse(ReliabilityChecklist.alarmBlocking(evaluate()))

            probe.status = ReliabilityStatus.ALL_OK.copy(dndAllowsAlarms = false)
            assertTrue(ReliabilityChecklist.alarmBlocking(evaluate()))
            assertEquals(2, probe.checks)
        }
}
