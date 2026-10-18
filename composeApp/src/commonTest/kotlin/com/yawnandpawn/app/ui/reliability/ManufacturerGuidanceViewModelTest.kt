package com.yawnandpawn.app.ui.reliability

import com.yawnandpawn.app.core.error.DomainError
import com.yawnandpawn.app.core.log.LogEvent
import com.yawnandpawn.app.core.reliability.ChecklistRow
import com.yawnandpawn.app.core.reliability.ConfirmMakerGuidance
import com.yawnandpawn.app.core.reliability.DeviceInfo
import com.yawnandpawn.app.core.reliability.DeviceMaker
import com.yawnandpawn.app.core.reliability.MakerConfirmation
import com.yawnandpawn.app.core.reliability.ReliabilityChecklist
import com.yawnandpawn.app.core.reliability.ReliabilityStatus
import com.yawnandpawn.app.core.reliability.RowStatus
import com.yawnandpawn.app.testing.FakeLogger
import com.yawnandpawn.app.testing.FakeManufacturerSettings
import com.yawnandpawn.app.testing.FakeReliabilityRecords
import com.yawnandpawn.app.ui.resources.Res
import com.yawnandpawn.app.ui.resources.reliability_step_recents
import com.yawnandpawn.app.ui.resources.reliability_xiaomi_step_1
import com.yawnandpawn.app.ui.resources.reliability_xiaomi_step_2
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Story 5.5: the maker guidance's steps, "Open settings" and "I've done this". */
@OptIn(ExperimentalCoroutinesApi::class)
class ManufacturerGuidanceViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val settings = FakeManufacturerSettings()
    private val records = FakeReliabilityRecords()
    private val logger = FakeLogger()
    private val phone = DeviceInfo(sdkInt = 34, maker = "OPPO", fingerprint = "oppo/build-1", deviceMaker = DeviceMaker.OppoRealme)

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun screen(device: DeviceInfo = phone): ManufacturerGuidanceViewModel =
        ManufacturerGuidanceViewModel({ device }, settings, ConfirmMakerGuidance({ device }, records, logger))

    @Test
    fun `every covered maker has 2 to 4 steps, ending with Recents, and Other has none`() {
        DeviceMaker.entries.filter { it.covered }.forEach { maker ->
            val steps = maker.guidanceSteps()
            assertTrue(steps.size in 2..4, "$maker: ${steps.size} steps")
            assertEquals(Res.string.reliability_step_recents, steps.last(), "$maker")
            assertEquals(steps.distinct(), steps, "$maker")
        }
        assertEquals(
            listOf(Res.string.reliability_xiaomi_step_1, Res.string.reliability_xiaomi_step_2, Res.string.reliability_step_recents),
            DeviceMaker.Xiaomi.guidanceSteps(),
            "EXPERIENCE.md's Xiaomi steps",
        )
        assertEquals(emptyList(), DeviceMaker.Other.guidanceSteps())
    }

    @Test
    fun `it shows the phone's maker, and Open settings opens that maker's settings`() {
        val viewModel = screen()
        assertEquals(DeviceMaker.OppoRealme, viewModel.state.value.maker)

        viewModel.onIntent(ManufacturerGuidanceIntent.OpenSettings)

        assertEquals(listOf(DeviceMaker.OppoRealme), settings.opened)
        assertEquals(0, records.writes, "opening settings confirms nothing")
    }

    @Test
    fun `another maker opens the screen without steps, and its button still asks for the settings`() {
        val viewModel = screen(phone.copy(maker = "Google", deviceMaker = DeviceMaker.Other))

        viewModel.onIntent(ManufacturerGuidanceIntent.OpenSettings)

        assertEquals(DeviceMaker.Other, viewModel.state.value.maker)
        assertEquals(listOf(DeviceMaker.Other), settings.opened)
    }

    @Test
    fun `I've done this stores the maker and build, marks the row seen OK and closes the screen`() =
        runTest(dispatcher) {
            val viewModel = screen()
            val closed = async { viewModel.effects.first() }

            viewModel.onIntent(ManufacturerGuidanceIntent.Done)

            assertEquals(ManufacturerGuidanceEffect.Close, closed.await())
            assertFalse(viewModel.state.value.confirming)
            assertEquals(MakerConfirmation("OPPO", "oppo/build-1"), records.stored.makerConfirmation)
            assertTrue(ChecklistRow.Manufacturer in records.stored.lastOk)
            val rows = ReliabilityChecklist.evaluate(ReliabilityStatus.ALL_OK, emptyList(), phone, records.stored)
            assertEquals(RowStatus.Ok, rows.single { it.row == ChecklistRow.Manufacturer }.status)
            val updatedPhone = phone.copy(fingerprint = "b2")
            val updated = ReliabilityChecklist.evaluate(ReliabilityStatus.ALL_OK, emptyList(), updatedPhone, records.stored)
            assertEquals(RowStatus.Revoked, updated.single { it.row == ChecklistRow.Manufacturer }.status, "after a system update")
        }

    @Test
    fun `a failed write is logged and leaves the screen open, and a second tap stores it`() =
        runTest(dispatcher) {
            records.writeFailure = DomainError.StorageFailure("disk")
            val viewModel = screen()
            val effects = mutableListOf<ManufacturerGuidanceEffect>()
            val collector = async { viewModel.effects.collect { effects += it } }

            viewModel.onIntent(ManufacturerGuidanceIntent.Done)

            assertEquals(emptyList(), effects, "the screen stays")
            assertFalse(viewModel.state.value.confirming, "the user can tap again")
            assertNull(records.stored.makerConfirmation)
            assertEquals(listOf<LogEvent>(LogEvent.OperationFailed(ConfirmMakerGuidance.CONFIRM, "storage failure: disk")), logger.events)

            records.writeFailure = null
            viewModel.onIntent(ManufacturerGuidanceIntent.Done)

            assertEquals(listOf<ManufacturerGuidanceEffect>(ManufacturerGuidanceEffect.Close), effects)
            assertEquals(MakerConfirmation("OPPO", "oppo/build-1"), records.stored.makerConfirmation)
            collector.cancel()
        }
}
