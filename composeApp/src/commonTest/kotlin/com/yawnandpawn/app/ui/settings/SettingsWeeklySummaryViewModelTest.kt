package com.yawnandpawn.app.ui.settings

import com.yawnandpawn.app.core.alarm.AlarmWriteLock
import com.yawnandpawn.app.core.config.SaveDefaultSetting
import com.yawnandpawn.app.core.config.SaveGlobalSetting
import com.yawnandpawn.app.core.config.SetBaseFee
import com.yawnandpawn.app.core.config.SetMaxSnoozes
import com.yawnandpawn.app.core.config.SetQuietTime
import com.yawnandpawn.app.core.error.DomainError
import com.yawnandpawn.app.core.log.LogEvent
import com.yawnandpawn.app.core.session.GlobalSettings
import com.yawnandpawn.app.core.session.SessionLockGuard
import com.yawnandpawn.app.core.session.SessionState
import com.yawnandpawn.app.core.summary.SetWeeklySummary
import com.yawnandpawn.app.core.summary.WeeklySummaryPrefs
import com.yawnandpawn.app.core.summary.WeeklySummaryScheduler
import com.yawnandpawn.app.core.summary.WeeklySummaryStore
import com.yawnandpawn.app.core.telemetry.SetAnalyticsConsent
import com.yawnandpawn.app.core.work.SchedulePolicy
import com.yawnandpawn.app.testing.FakeAlarmRepository
import com.yawnandpawn.app.testing.FakeBackgroundWork
import com.yawnandpawn.app.testing.FakeClock
import com.yawnandpawn.app.testing.FakeConsentStore
import com.yawnandpawn.app.testing.FakeGlobalSettingsRepository
import com.yawnandpawn.app.testing.FakeLogger
import com.yawnandpawn.app.testing.FakeMoneyFormatter
import com.yawnandpawn.app.testing.FakePendingChangeRepository
import com.yawnandpawn.app.testing.FakePriceCatalog
import com.yawnandpawn.app.testing.FakeTimeChangeSignal
import com.yawnandpawn.app.testing.FakeTimeZoneProvider
import com.yawnandpawn.app.testing.FakeUserLockState
import com.yawnandpawn.app.testing.FakeWeeklySummaryStore
import com.yawnandpawn.app.testing.aPriceSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.datetime.TimeZone
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * Story 6.8: the Settings "Weekly summary" switch follows the stored choice (on by default); a tap stores it at once and
 * aligns the work through `SetWeeklySummary`; a failed write is logged and the switch stays as stored.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsWeeklySummaryViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val clock = FakeClock(Instant.parse("2027-03-06T18:00:00Z"))
    private val zone = FakeTimeZoneProvider(TimeZone.UTC)
    private val logger = FakeLogger()
    private val alarms = FakeAlarmRepository()
    private val pending = FakePendingChangeRepository()
    private val settings = FakeGlobalSettingsRepository(GlobalSettings(), pending)
    private val consents = FakeConsentStore()
    private val store = FakeWeeklySummaryStore()
    private val work = FakeBackgroundWork()
    private val sessionLock =
        SessionLockGuard(MutableStateFlow<SessionState>(SessionState.Idle), MutableStateFlow(true), MutableStateFlow(false))

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun TestScope.viewModel(weekly: WeeklySummaryStore? = store): SettingsViewModel {
        val save = SaveGlobalSetting(settings, pending, alarms, clock, zone, AlarmWriteLock(), sessionLock)
        val scheduler = WeeklySummaryScheduler(work, store, clock, zone, FakeUserLockState(unlocked = true), logger)
        val viewModel =
            SettingsViewModel(
                globalSettings = settings,
                pendingChanges = pending,
                priceCatalog = FakePriceCatalog(aPriceSnapshot()),
                setBaseFee = SetBaseFee(save),
                setMaxSnoozes = SetMaxSnoozes(save),
                setQuietTime = SetQuietTime(save),
                clock = clock,
                timeZoneProvider = zone,
                timeChanges = FakeTimeChangeSignal(),
                moneyFormatter = FakeMoneyFormatter(),
                logger = logger,
                defaults = DefaultSettingSetters(SaveDefaultSetting(settings, sessionLock)),
                consents = consents,
                setAnalyticsConsent = SetAnalyticsConsent(consents, sessionLock),
                weeklySummary = weekly?.let { WeeklySummarySwitch(it, SetWeeklySummary(store, scheduler, sessionLock)) },
            )
        backgroundScope.launch(dispatcher) { viewModel.state.collect {} }
        return viewModel
    }

    private val SettingsViewModel.shown: SettingsUiState
        get() = assertNotNull(state.value, "the stored settings were read")

    @Test
    fun `the switch shows on by default, and a tap stores the choice and aligns the work at once`() =
        runTest {
            val viewModel = viewModel()
            assertTrue(viewModel.shown.weeklySummary)

            viewModel.onIntent(SettingsIntent.WeeklySummaryToggled(false))

            assertEquals(WeeklySummaryPrefs(enabled = false), store.prefs)
            assertFalse(viewModel.shown.weeklySummary)
            assertEquals(1, work.weeklyCancels)

            viewModel.onIntent(SettingsIntent.WeeklySummaryToggled(true))

            assertTrue(viewModel.shown.weeklySummary)
            assertEquals(listOf(SchedulePolicy.Replace), work.weeklySchedules.map { it.second })
            assertEquals(emptyList(), work.enqueued)
        }

    @Test
    fun `the switch shows a choice stored elsewhere`() =
        runTest {
            val viewModel = viewModel()

            store.prefs = WeeklySummaryPrefs(enabled = false)

            assertFalse(viewModel.shown.weeklySummary)
            assertFalse(viewModel.shown.usageStats, "the usage stats switch is left as stored")
        }

    @Test
    fun `a failed write is logged and the switch stays at the stored value`() =
        runTest {
            val viewModel = viewModel()
            store.failWrite = DomainError.StorageFailure("disk full")

            viewModel.onIntent(SettingsIntent.WeeklySummaryToggled(false))

            assertTrue(viewModel.shown.weeklySummary)
            assertEquals(0, work.weeklyCancels)
            assertEquals(
                listOf<LogEvent>(LogEvent.OperationFailed(SettingsViewModel.SAVE_WEEKLY_SUMMARY, "storage failure: disk full")),
                logger.events,
            )
        }

    @Test
    fun `an unreadable store is logged and shows on`() =
        runTest {
            store.prefs = WeeklySummaryPrefs(enabled = false)
            store.failRead = DomainError.StorageFailure("disk unreadable")

            val viewModel = viewModel()

            assertTrue(viewModel.shown.weeklySummary)
            assertTrue(logger.events.any { it is LogEvent.OperationFailed && it.operation == SettingsViewModel.LOAD_WEEKLY_SUMMARY })
        }

    @Test
    fun `without the switch's sources it shows on and a tap changes nothing`() =
        runTest {
            val viewModel = viewModel(weekly = null)

            viewModel.onIntent(SettingsIntent.WeeklySummaryToggled(false))

            assertTrue(viewModel.shown.weeklySummary)
            assertEquals(WeeklySummaryPrefs(), store.prefs)
        }
}
