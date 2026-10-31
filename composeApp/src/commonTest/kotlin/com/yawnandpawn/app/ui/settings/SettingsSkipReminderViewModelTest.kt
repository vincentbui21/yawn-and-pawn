package com.yawnandpawn.app.ui.settings

import com.yawnandpawn.app.core.alarm.AlarmWriteLock
import com.yawnandpawn.app.core.config.SaveDefaultSetting
import com.yawnandpawn.app.core.config.SaveGlobalSetting
import com.yawnandpawn.app.core.config.SetBaseFee
import com.yawnandpawn.app.core.config.SetMaxSnoozes
import com.yawnandpawn.app.core.config.SetQuietTime
import com.yawnandpawn.app.core.error.DomainError
import com.yawnandpawn.app.core.log.LogEvent
import com.yawnandpawn.app.core.reminder.SetSkipReminder
import com.yawnandpawn.app.core.reminder.SkipReminderPrefs
import com.yawnandpawn.app.core.reminder.SkipReminderScheduler
import com.yawnandpawn.app.core.reminder.SkipReminderStore
import com.yawnandpawn.app.core.session.GlobalSettings
import com.yawnandpawn.app.core.session.SessionLockGuard
import com.yawnandpawn.app.core.session.SessionState
import com.yawnandpawn.app.core.telemetry.SetAnalyticsConsent
import com.yawnandpawn.app.testing.FakeAlarmRepository
import com.yawnandpawn.app.testing.FakeBackgroundWork
import com.yawnandpawn.app.testing.FakeClock
import com.yawnandpawn.app.testing.FakeConsentStore
import com.yawnandpawn.app.testing.FakeGlobalSettingsRepository
import com.yawnandpawn.app.testing.FakeLogger
import com.yawnandpawn.app.testing.FakeMoneyFormatter
import com.yawnandpawn.app.testing.FakePendingChangeRepository
import com.yawnandpawn.app.testing.FakePriceCatalog
import com.yawnandpawn.app.testing.FakeSkipReminderNotifier
import com.yawnandpawn.app.testing.FakeSkipReminderStore
import com.yawnandpawn.app.testing.FakeTimeChangeSignal
import com.yawnandpawn.app.testing.FakeTimeZoneProvider
import com.yawnandpawn.app.testing.FakeUserLockState
import com.yawnandpawn.app.testing.aPriceSnapshot
import com.yawnandpawn.app.testing.anAlarm
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

/**
 * 0.1.8: the Settings "Skip reminder" switch follows the stored choice (on by default); a tap stores it at once (never a
 * locked change, so no lock note) and aligns the reminder through `SetSkipReminder`; a failed write is logged and the
 * switch stays as stored.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsSkipReminderViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()

    // Saturday 18:00 UTC; the daily 07:00 alarm's reminder is Sunday 05:00, 11 h away.
    private val clock = FakeClock(Instant.parse("2027-03-06T18:00:00Z"))
    private val zone = FakeTimeZoneProvider(TimeZone.UTC)
    private val logger = FakeLogger()
    private val alarms = FakeAlarmRepository(listOf(anAlarm(id = "a", time = LocalTime(7, 0), repeatDays = DayOfWeek.entries.toSet())))
    private val pending = FakePendingChangeRepository()
    private val settings = FakeGlobalSettingsRepository(GlobalSettings(), pending)
    private val consents = FakeConsentStore()
    private val store = FakeSkipReminderStore()
    private val notifier = FakeSkipReminderNotifier()
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

    private fun TestScope.viewModel(reminder: SkipReminderStore? = store): SettingsViewModel {
        val save = SaveGlobalSetting(settings, pending, alarms, clock, zone, AlarmWriteLock(), sessionLock)
        val scheduler =
            SkipReminderScheduler(alarms, store, notifier, work, clock, zone, FakeUserLockState(unlocked = true), logger) {
                sessionLock.isLocked
            }
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
                skipReminder = reminder?.let { SkipReminderSwitch(it, SetSkipReminder(store, scheduler, sessionLock)) },
            )
        backgroundScope.launch(dispatcher) { viewModel.state.collect {} }
        return viewModel
    }

    private val SettingsViewModel.shown: SettingsUiState
        get() = assertNotNull(state.value, "the stored settings were read")

    @Test
    fun `the switch shows on by default, and a tap stores the choice and aligns the reminder at once, with no lock note`() =
        runTest {
            val viewModel = viewModel()
            assertTrue(viewModel.shown.skipReminder)

            viewModel.onIntent(SettingsIntent.SkipReminderToggled(false))

            assertEquals(SkipReminderPrefs(enabled = false), store.prefs)
            assertFalse(viewModel.shown.skipReminder)
            assertNull(work.skipReminder, "the job is cancelled")
            assertTrue(pending.current.isEmpty(), "never a locked change")
            assertNull(viewModel.shown.baseFeeNote)

            viewModel.onIntent(SettingsIntent.SkipReminderToggled(true))

            assertTrue(viewModel.shown.skipReminder)
            assertEquals(11.hours, work.skipReminder)
            assertTrue(pending.current.isEmpty())
        }

    @Test
    fun `the switch shows a choice stored elsewhere, and leaves the other switches as stored`() =
        runTest {
            val viewModel = viewModel()

            store.prefs = SkipReminderPrefs(enabled = false)

            assertFalse(viewModel.shown.skipReminder)
            assertTrue(viewModel.shown.weeklySummary)
            assertFalse(viewModel.shown.usageStats)
        }

    @Test
    fun `a failed write is logged and the switch stays at the stored value`() =
        runTest {
            val viewModel = viewModel()
            store.failWrite = DomainError.StorageFailure("disk full")

            viewModel.onIntent(SettingsIntent.SkipReminderToggled(false))

            assertTrue(viewModel.shown.skipReminder)
            assertEquals(
                listOf<LogEvent>(LogEvent.OperationFailed(SettingsViewModel.SAVE_SKIP_REMINDER, "storage failure: disk full")),
                logger.events,
            )
        }

    @Test
    fun `an unreadable store is logged and shows on`() =
        runTest {
            store.prefs = SkipReminderPrefs(enabled = false)
            store.failRead = DomainError.StorageFailure("disk unreadable")

            val viewModel = viewModel()

            assertTrue(viewModel.shown.skipReminder)
            assertTrue(logger.events.any { it is LogEvent.OperationFailed && it.operation == SettingsViewModel.LOAD_SKIP_REMINDER })
        }

    @Test
    fun `without the switch's sources it shows on and a tap changes nothing`() =
        runTest {
            val viewModel = viewModel(reminder = null)

            viewModel.onIntent(SettingsIntent.SkipReminderToggled(false))

            assertTrue(viewModel.shown.skipReminder)
            assertEquals(SkipReminderPrefs(), store.prefs)
        }
}
