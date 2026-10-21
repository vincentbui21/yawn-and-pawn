package com.yawnandpawn.app.ui.reliability

import com.yawnandpawn.app.core.alarm.Alarm
import com.yawnandpawn.app.core.alarm.CheckConfig
import com.yawnandpawn.app.core.checks.CheckEntry
import com.yawnandpawn.app.core.checks.CheckMode
import com.yawnandpawn.app.core.checks.CheckType
import com.yawnandpawn.app.core.checks.Difficulty
import com.yawnandpawn.app.core.error.DomainError
import com.yawnandpawn.app.core.log.LogEvent
import com.yawnandpawn.app.core.reliability.ChecklistEntry
import com.yawnandpawn.app.core.reliability.DeviceInfo
import com.yawnandpawn.app.core.reliability.DeviceMaker
import com.yawnandpawn.app.core.reliability.MakerConfirmation
import com.yawnandpawn.app.core.reliability.ReliabilityStatus
import com.yawnandpawn.app.core.reliability.RowStatus
import com.yawnandpawn.app.core.reliability.StoredReliability
import com.yawnandpawn.app.core.session.GlobalSettings
import com.yawnandpawn.app.core.session.ScheduleTestAlarm
import com.yawnandpawn.app.testing.FakeAlarmRepository
import com.yawnandpawn.app.testing.FakeAlarmScheduler
import com.yawnandpawn.app.testing.FakeCheckConfigRepository
import com.yawnandpawn.app.testing.FakeClock
import com.yawnandpawn.app.testing.FakeGlobalSettingsRepository
import com.yawnandpawn.app.testing.FakeLogger
import com.yawnandpawn.app.testing.FakeReliabilityProbe
import com.yawnandpawn.app.testing.FakeReliabilityRecords
import com.yawnandpawn.app.testing.FakeReliabilitySettings
import com.yawnandpawn.app.testing.FakeTestAlarmStore
import com.yawnandpawn.app.testing.FakeTimeZoneProvider
import com.yawnandpawn.app.testing.SchedulerCall
import com.yawnandpawn.app.testing.aPlainPhone
import com.yawnandpawn.app.testing.anAlarm
import com.yawnandpawn.app.testing.anEvaluateReliability
import com.yawnandpawn.app.testing.checkConfigsOf
import com.yawnandpawn.app.ui.qr.TestCameraPermission
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant
import com.yawnandpawn.app.core.reliability.ChecklistRow as CoreRow

/**
 * Story 5.4: the reliability checklist screen over the real `EvaluateReliability` (fakes underneath): the row mapping,
 * the re-read on every start and resume, each row's "Fix" target, the camera's ask-then-details, and "Ring a test
 * alarm" with the soonest enabled alarm or the defaults.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReliabilityViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()

    /** Monday 2027-03-08, 05:00 UTC. */
    private val now = Instant.parse("2027-03-08T05:00:00Z")
    private val clock = FakeClock(now)
    private val zone = FakeTimeZoneProvider(TimeZone.UTC)
    private val logger = FakeLogger()
    private val probe = FakeReliabilityProbe()
    private val records = FakeReliabilityRecords()
    private val settings = FakeReliabilitySettings()
    private val camera = TestCameraPermission(granted = false, answer = false)
    private val globalSettings = FakeGlobalSettingsRepository()
    private val scheduler = FakeAlarmScheduler()
    private val testStore = FakeTestAlarmStore()
    private var alarms = FakeAlarmRepository()
    private var checks = FakeCheckConfigRepository(alarms)
    private var device: DeviceInfo = aPlainPhone

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun alarmsOf(
        vararg alarm: Alarm,
        configs: List<CheckConfig> = emptyList(),
    ) {
        alarms = FakeAlarmRepository(alarm.toList())
        checks = FakeCheckConfigRepository(alarms, configs)
    }

    private fun testAlarm() =
        ChecklistTestAlarm(checks, globalSettings, ScheduleTestAlarm(scheduler, testStore, clock, logger), clock, zone, logger)

    private fun screen(): ReliabilityViewModel =
        ReliabilityViewModel(
            evaluate = anEvaluateReliability(probe = probe, checks = checks, records = records, device = device, logger = logger),
            settings = settings,
            camera = camera,
            ringTest = testAlarm(),
            logger = logger,
        )

    private fun TestScope.effectsOf(viewModel: ReliabilityViewModel): List<ReliabilityEffect> {
        val effects = mutableListOf<ReliabilityEffect>()
        backgroundScope.launch(dispatcher) { viewModel.effects.toList(effects) }
        return effects
    }

    private fun ReliabilityViewModel.shown(): List<Pair<ChecklistItem, ItemStatus>> = state.value.rows.map { it.item to it.status }

    private val qrCheck = CheckEntry(CheckType.QrBarcode, Difficulty.Easy, 1)

    @Test
    fun `core rows map to the approved rows, Hidden ones are left out and the order is kept`() {
        val entries =
            listOf(
                ChecklistEntry(CoreRow.Notifications, RowStatus.Ok),
                ChecklistEntry(CoreRow.FullScreenAlarm, RowStatus.NeedsFix),
                ChecklistEntry(CoreRow.ExactAlarms, RowStatus.Hidden),
                ChecklistEntry(CoreRow.Battery, RowStatus.Revoked),
                ChecklistEntry(CoreRow.LockedScreenTest, RowStatus.NeedsFix),
            )

        assertEquals(
            listOf(
                ChecklistRow(ChecklistItem.Notifications, ItemStatus.Ok),
                ChecklistRow(ChecklistItem.FullScreen, ItemStatus.Missing),
                ChecklistRow(ChecklistItem.Battery, ItemStatus.Revoked),
                ChecklistRow(ChecklistItem.TestAlarm, ItemStatus.Missing),
            ),
            entries.toChecklistRows(),
        )
        CoreRow.entries.forEach { row -> assertEquals(row, row.item.row, "$row") }
        assertEquals(ChecklistItem.entries.toSet(), CoreRow.entries.map { it.item }.toSet(), "one approved row each")
    }

    @Test
    fun `nothing shows before the first start, then every row that applies with OK or Fix`() =
        runTest(dispatcher) {
            val viewModel = screen()
            assertEquals(emptyList(), viewModel.state.value.rows)
            assertEquals(0, probe.checks)

            viewModel.refresh()

            assertEquals(
                listOf(
                    ChecklistItem.Notifications to ItemStatus.Ok,
                    ChecklistItem.FullScreen to ItemStatus.Ok,
                    ChecklistItem.DoNotDisturb to ItemStatus.Ok,
                    ChecklistItem.Battery to ItemStatus.Ok,
                    ChecklistItem.TestAlarm to ItemStatus.Missing,
                ),
                viewModel.shown(),
            )
            assertFalse(viewModel.state.value.allOk)
        }

    @Test
    fun `all OK on API 34 with a covered maker and a passed locked test`() =
        runTest(dispatcher) {
            device = DeviceInfo(sdkInt = 34, maker = "Xiaomi", fingerprint = "xiaomi/1", deviceMaker = DeviceMaker.Xiaomi)
            records.stored = StoredReliability(lockedTestPassedAt = now, makerConfirmation = null)
            val viewModel = screen()

            viewModel.refresh()
            assertEquals(ChecklistItem.Manufacturer to ItemStatus.Missing, viewModel.shown()[4])

            records.stored = records.stored.copy(makerConfirmation = MakerConfirmation("Xiaomi", "xiaomi/1"))
            viewModel.refresh()

            assertEquals(6, viewModel.shown().size)
            assertTrue(viewModel.state.value.allOk)
        }

    @Test
    fun `several rows that need fixing read Fix, and a row fixed elsewhere reads OK on the next start or resume`() =
        runTest(dispatcher) {
            probe.status = ReliabilityStatus.ALL_OK.copy(notificationsAllowed = false, dndAllowsAlarms = false)
            val viewModel = screen()
            viewModel.refresh()
            assertEquals(ItemStatus.Missing, viewModel.shown().toMap()[ChecklistItem.Notifications])
            assertEquals(ItemStatus.Missing, viewModel.shown().toMap()[ChecklistItem.DoNotDisturb])

            probe.status = ReliabilityStatus.ALL_OK.copy(dndAllowsAlarms = false)
            viewModel.refresh()

            assertEquals(ItemStatus.Ok, viewModel.shown().toMap()[ChecklistItem.Notifications])
            assertEquals(ItemStatus.Missing, viewModel.shown().toMap()[ChecklistItem.DoNotDisturb])
        }

    @Test
    fun `a battery setting seen OK before and now off reads Revoked`() =
        runTest(dispatcher) {
            val viewModel = screen()
            viewModel.refresh()
            probe.status = ReliabilityStatus.ALL_OK.copy(batteryOptimizationIgnored = false)

            viewModel.refresh()

            assertEquals(ItemStatus.Revoked, viewModel.shown().toMap()[ChecklistItem.Battery])
        }

    @Test
    fun `the camera row shows only while an enabled alarm has a camera check, and API 31 shows exact alarms`() =
        runTest(dispatcher) {
            alarmsOf(anAlarm(id = "qr", time = LocalTime(7, 0)), configs = checkConfigsOf("qr", listOf(qrCheck)))
            device = DeviceInfo(sdkInt = 31, maker = "Google", fingerprint = "google/31")
            probe.status = ReliabilityStatus.ALL_OK.copy(cameraGranted = false, exactAlarmsAllowed = false)
            val viewModel = screen()

            viewModel.refresh()

            assertEquals(ItemStatus.Missing, viewModel.shown().toMap()[ChecklistItem.Camera])
            assertEquals(ItemStatus.Missing, viewModel.shown().toMap()[ChecklistItem.ExactAlarms])
        }

    @Test
    fun `unreadable records still show every row`() =
        runTest(dispatcher) {
            records.readFailure = DomainError.StorageFailure("disk I/O error")
            val viewModel = screen()

            viewModel.refresh()

            assertEquals(5, viewModel.shown().size)
        }

    @Test
    fun `Fix opens each setting's own screen, and Manufacturer opens the maker guidance`() =
        runTest(dispatcher) {
            val viewModel = screen()
            val effects = effectsOf(viewModel)
            val settingsRows =
                listOf(
                    ChecklistItem.Notifications,
                    ChecklistItem.FullScreen,
                    ChecklistItem.ExactAlarms,
                    ChecklistItem.DoNotDisturb,
                    ChecklistItem.Battery,
                    ChecklistItem.Microphone,
                )

            settingsRows.forEach { viewModel.onIntent(ReliabilityIntent.FixClicked(it)) }
            viewModel.onIntent(ReliabilityIntent.FixClicked(ChecklistItem.Manufacturer))

            assertEquals(settingsRows.map { it.row }, settings.opened)
            assertEquals(listOf<ReliabilityEffect>(ReliabilityEffect.OpenManufacturerGuidance), effects)
        }

    @Test
    fun `Camera asks the permission first, then after a no opens the app details`() =
        runTest(dispatcher) {
            val viewModel = screen()

            viewModel.onIntent(ReliabilityIntent.FixClicked(ChecklistItem.Camera))
            assertEquals(1, camera.requests)
            assertEquals(emptyList(), settings.opened)

            viewModel.onIntent(ReliabilityIntent.FixClicked(ChecklistItem.Camera))
            assertEquals(1, camera.requests, "asked once")
            assertEquals(listOf(CoreRow.Camera), settings.opened)
        }

    @Test
    fun `Camera granted from the dialog re-reads the rows`() =
        runTest(dispatcher) {
            val granting = TestCameraPermission(granted = false, answer = true)
            val evaluate = anEvaluateReliability(probe = probe, checks = checks, records = records)
            val viewModel = ReliabilityViewModel(evaluate, settings, granting, testAlarm(), logger)

            viewModel.onIntent(ReliabilityIntent.FixClicked(ChecklistItem.Camera))
            viewModel.onIntent(ReliabilityIntent.FixClicked(ChecklistItem.Camera))

            assertEquals(2, granting.requests, "a yes asks again next time instead of opening the details")
            assertEquals(2, probe.checks, "each answer re-reads the rows")
            assertEquals(emptyList(), settings.opened)
        }

    @Test
    fun `Ring a test alarm rings the soonest enabled alarm's settings and shows the snackbar for 4 seconds`() =
        runTest(dispatcher) {
            val soon =
                anAlarm(id = "soon", time = LocalTime(6, 0), requestCode = 1001).copy(
                    label = "Gym",
                    soundRef = "builtin:bell",
                    volumePercent = 60,
                    vibration = false,
                    snoozeLengthMinutes = 5,
                    graceSeconds = 25,
                    checkMode = CheckMode.All,
                )
            val later = anAlarm(id = "later", time = LocalTime(23, 0), requestCode = 1002)
            val off = anAlarm(id = "off", time = LocalTime(5, 30), enabled = false, requestCode = 1003)
            alarmsOf(later, soon, off, configs = checkConfigsOf("soon", listOf(qrCheck)))
            val viewModel = screen()

            viewModel.onIntent(ReliabilityIntent.RingTestAlarm)

            assertEquals(listOf<SchedulerCall>(SchedulerCall.ScheduleTest(now.toEpochMilliseconds() + 10_000)), scheduler.calls)
            val config = testStore.pending!!
            assertEquals("soon", config.alarmId)
            assertEquals("builtin:bell", config.soundRef)
            assertEquals(60, config.volumePercent)
            assertFalse(config.vibration)
            assertEquals(5, config.snoozeLengthMinutes)
            assertEquals(25, config.graceSeconds)
            assertEquals(CheckMode.All, config.checkPlan.mode)
            assertEquals(listOf(CheckType.QrBarcode), config.checkPlan.entries.map { it.type })
            assertTrue(config.testMode)
            assertTrue(viewModel.state.value.testScheduled)

            advanceTimeBy(ReliabilityViewModel.SNACKBAR_MILLIS - 1)
            assertTrue(viewModel.state.value.testScheduled)
            advanceTimeBy(2)
            assertFalse(viewModel.state.value.testScheduled)
        }

    @Test
    fun `with no enabled alarm the test rings the built-in alarm with the Settings defaults`() =
        runTest(dispatcher) {
            globalSettings.setDefaultSnoozeLength(15)
            globalSettings.setDefaultGraceSeconds(30)
            globalSettings.setDefaultVibrateInGrace(false)
            alarmsOf(anAlarm(id = "off", time = LocalTime(7, 0), enabled = false))

            val draft = testAlarm().draft()

            assertEquals(null, draft.id)
            assertEquals(LocalTime(5, 0), draft.time)
            assertEquals(Alarm.DEFAULT_SOUND_REF, draft.soundRef)
            assertEquals(15, draft.snoozeLengthMinutes)
            assertEquals(30, draft.graceSeconds)
            assertFalse(draft.vibrateInGrace)
            assertEquals(CheckConfig.DEFAULT_ENTRIES, draft.checks)
        }

    @Test
    fun `unreadable alarms or settings ring the built-in defaults, logged`() =
        runTest(dispatcher) {
            checks.failure = DomainError.StorageFailure("disk I/O error")
            globalSettings.failure = DomainError.StorageFailure("disk I/O error")

            val draft = testAlarm().draft()

            assertEquals(GlobalSettings().defaultGraceSeconds, draft.graceSeconds)
            assertEquals(GlobalSettings().defaultSnoozeLengthMinutes, draft.snoozeLengthMinutes)
            assertEquals(
                listOf(ChecklistTestAlarm.READ_ALARMS, ChecklistTestAlarm.READ_SETTINGS),
                logger.events.map { (it as LogEvent.OperationFailed).operation },
            )
        }

    @Test
    fun `a test that cannot be armed is logged and shows no snackbar`() =
        runTest(dispatcher) {
            scheduler.failure = DomainError.ExactAlarmNotPermitted
            val viewModel = screen()

            viewModel.onIntent(ReliabilityIntent.RingTestAlarm)

            assertFalse(viewModel.state.value.testScheduled)
            assertEquals(ReliabilityViewModel.SCHEDULE_TEST, (logger.events.last() as LogEvent.OperationFailed).operation)
        }
}
