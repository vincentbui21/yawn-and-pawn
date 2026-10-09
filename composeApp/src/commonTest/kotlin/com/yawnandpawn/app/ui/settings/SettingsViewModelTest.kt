package com.yawnandpawn.app.ui.settings

import com.yawnandpawn.app.core.alarm.AlarmWriteLock
import com.yawnandpawn.app.core.billing.PriceCatalogSnapshot
import com.yawnandpawn.app.core.billing.SnoozeProducts
import com.yawnandpawn.app.core.config.Occurrence
import com.yawnandpawn.app.core.config.PendingChange
import com.yawnandpawn.app.core.config.PendingChangeRepository
import com.yawnandpawn.app.core.config.SaveGlobalSetting
import com.yawnandpawn.app.core.config.SetBaseFee
import com.yawnandpawn.app.core.config.SetMaxSnoozes
import com.yawnandpawn.app.core.config.SettingValue
import com.yawnandpawn.app.core.error.DomainError
import com.yawnandpawn.app.core.log.LogEvent
import com.yawnandpawn.app.core.session.GlobalSettings
import com.yawnandpawn.app.core.session.SessionLockGuard
import com.yawnandpawn.app.core.session.SessionState
import com.yawnandpawn.app.testing.FakeAlarmRepository
import com.yawnandpawn.app.testing.FakeClock
import com.yawnandpawn.app.testing.FakeGlobalSettingsRepository
import com.yawnandpawn.app.testing.FakeLogger
import com.yawnandpawn.app.testing.FakeMoneyFormatter
import com.yawnandpawn.app.testing.FakePendingChangeRepository
import com.yawnandpawn.app.testing.FakePriceCatalog
import com.yawnandpawn.app.testing.FakeTimeChangeSignal
import com.yawnandpawn.app.testing.FakeTimeZoneProvider
import com.yawnandpawn.app.testing.aPriceSnapshot
import com.yawnandpawn.app.testing.anAlarm
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * Story 4.5: Settings › Snooze with the real 4.4 use cases on fakes: immediate and pending saves, the lock notes, offline
 * prices and the stepper bounds. Tests use 23:40 and a 07:30 alarm (7 h 50 min), never F6's 23:10 (UX note).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()

    /** Monday 2027-03-08, 23:40 UTC. */
    private val lateEvening = Instant.parse("2027-03-08T23:40:00Z")
    private val sevenThirty = Instant.parse("2027-03-09T07:30:00Z")
    private val clock = FakeClock(lateEvening)
    private val zone = FakeTimeZoneProvider(TimeZone.UTC)
    private val signal = FakeTimeChangeSignal()
    private val logger = FakeLogger()
    private val alarms = FakeAlarmRepository()
    private val pending = FakePendingChangeRepository()
    private val settings = FakeGlobalSettingsRepository(GlobalSettings(baseFeeTier = 3, maxSnoozes = 2), pending)
    private val prices = FakePriceCatalog(aPriceSnapshot(currency = "EUR"))
    private val formatter = FakeMoneyFormatter()
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

    private fun TestScope.viewModel(): SettingsViewModel {
        val save = SaveGlobalSetting(settings, pending, alarms, clock, zone, AlarmWriteLock(), sessionLock)
        val viewModel =
            SettingsViewModel(
                globalSettings = settings,
                pendingChanges = pending,
                priceCatalog = prices,
                setBaseFee = SetBaseFee(save),
                setMaxSnoozes = SetMaxSnoozes(save),
                clock = clock,
                timeZoneProvider = zone,
                timeChanges = signal,
                moneyFormatter = formatter,
                logger = logger,
            )
        backgroundScope.launch(dispatcher) { viewModel.state.collect {} }
        return viewModel
    }

    private val SettingsViewModel.shown: SettingsUiState
        get() = assertNotNull(state.value, "the stored settings were read")

    private suspend fun alarmAtSevenThirty() {
        alarms.upsert(anAlarm(id = "a", time = LocalTime(7, 30)))
    }

    @Test
    fun `loaded prices show Play's strings for the tier, a ladder of min 3 and max snoozes, and the bounds`() =
        runTest {
            settings.setBaseFeeTier(1)
            settings.setMaxSnoozes(5)
            val viewModel = viewModel()

            val state = viewModel.shown
            assertEquals("EUR 1.00", state.baseFee)
            assertEquals(listOf("EUR 1.00", "EUR 2.00", "EUR 3.00"), state.feeLadder)
            assertFalse(state.pricesApproximate)
            assertFalse(state.canLowerFee, "− is disabled at tier 1")
            assertTrue(state.canRaiseFee)
            assertNull(state.baseFeeNote)

            settings.setBaseFeeTier(10)
            assertEquals("EUR 10.00", viewModel.shown.baseFee)
            assertEquals(listOf("EUR 10.00", "EUR 20.00", "EUR 30.00"), viewModel.shown.feeLadder)
            assertFalse(viewModel.shown.canRaiseFee, "+ is disabled at tier 10")
        }

    @Test
    fun `the ladder has one entry per allowed snooze below 3`() =
        runTest {
            val viewModel = viewModel()
            assertEquals(listOf("EUR 3.00", "EUR 6.00"), viewModel.shown.feeLadder, "max snoozes 2")

            viewModel.onIntent(SettingsIntent.MaxSnoozesChanged(1))

            assertEquals(1, viewModel.shown.maxSnoozes)
            assertEquals(listOf("EUR 3.00"), viewModel.shown.feeLadder)
        }

    @Test
    fun `never online shows USD amounts from the MoneyFormatter, approximate, and saving still works`() =
        runTest {
            prices.snapshot = PriceCatalogSnapshot.EMPTY
            val viewModel = viewModel()

            assertEquals("USD 3.000000", viewModel.shown.baseFee)
            assertEquals(listOf("USD 3.000000", "USD 6.000000"), viewModel.shown.feeLadder)
            assertTrue(viewModel.shown.pricesApproximate)

            viewModel.onIntent(SettingsIntent.RaiseBaseFee)

            assertEquals(4, settings.current.baseFeeTier)
            assertEquals("USD 4.000000", viewModel.shown.baseFee)
        }

    @Test
    fun `one missing or expired tier makes the whole line approximate, so it never mixes currencies`() =
        runTest {
            prices.snapshot = PriceCatalogSnapshot(aPriceSnapshot(currency = "EUR").entries - SnoozeProducts.idOf(6))
            val viewModel = viewModel()
            assertTrue(viewModel.shown.pricesApproximate)
            assertEquals(listOf("USD 3.000000", "USD 6.000000"), viewModel.shown.feeLadder)

            prices.snapshot = aPriceSnapshot(fetchedAt = lateEvening - 31.days, currency = "EUR")
            assertTrue(viewModel.shown.pricesApproximate, "an expired cache is not shown")

            prices.snapshot = aPriceSnapshot(currency = "EUR")
            assertFalse(viewModel.shown.pricesApproximate, "back online, the local prices show")
            assertEquals("EUR 3.00", viewModel.shown.baseFee)
        }

    @Test
    fun `with no alarm inside 8 h every change applies at once with no note`() =
        runTest {
            val viewModel = viewModel()

            viewModel.onIntent(SettingsIntent.LowerBaseFee)
            viewModel.onIntent(SettingsIntent.MaxSnoozesChanged(3))

            assertEquals(GlobalSettings(baseFeeTier = 2, maxSnoozes = 3), settings.current)
            assertTrue(pending.current.isEmpty())
            assertEquals(2, viewModel.shown.baseFeeTier)
            assertNull(viewModel.shown.baseFeeNote)
            assertNull(viewModel.shown.maxSnoozesNote)
        }

    @Test
    fun `lowering the fee at 23 40 before a 07 30 alarm shows the new value as saved with tomorrow's note`() =
        runTest {
            alarmAtSevenThirty()
            val viewModel = viewModel()

            viewModel.onIntent(SettingsIntent.LowerBaseFee)

            assertEquals(3, settings.current.baseFeeTier, "the live fee stays until after the alarm")
            assertEquals(listOf(PendingChange(null, SettingValue.BaseFeeTier(2), Occurrence("a", sevenThirty))), pending.current)
            val state = viewModel.shown
            assertEquals(2, state.baseFeeTier)
            assertEquals("EUR 2.00", state.baseFee)
            assertEquals(WeakeningNote(LocalTime(7, 30), today = false), state.baseFeeNote)
        }

    @Test
    fun `after midnight the waited-for alarm is today's`() =
        runTest {
            clock.set(Instant.parse("2027-03-09T01:00:00Z"))
            alarmAtSevenThirty()
            val viewModel = viewModel()

            viewModel.onIntent(SettingsIntent.LowerBaseFee)

            assertEquals(WeakeningNote(LocalTime(7, 30), today = true), viewModel.shown.baseFeeNote)
        }

    @Test
    fun `raising the fee or lowering max snoozes under the lock applies at once with no note`() =
        runTest {
            alarmAtSevenThirty()
            val viewModel = viewModel()

            viewModel.onIntent(SettingsIntent.RaiseBaseFee)
            viewModel.onIntent(SettingsIntent.MaxSnoozesChanged(1))

            assertEquals(GlobalSettings(baseFeeTier = 4, maxSnoozes = 1), settings.current)
            assertTrue(pending.current.isEmpty())
            assertNull(viewModel.shown.baseFeeNote)
            assertNull(viewModel.shown.maxSnoozesNote)
        }

    @Test
    fun `raising max snoozes under the lock waits, and its note shows on its sub-screen`() =
        runTest {
            alarmAtSevenThirty()
            val viewModel = viewModel()

            viewModel.onIntent(SettingsIntent.MaxSnoozesChanged(3))

            assertEquals(2, settings.current.maxSnoozes)
            assertEquals(3, viewModel.shown.maxSnoozes)
            assertEquals(WeakeningNote(LocalTime(7, 30)), viewModel.shown.maxSnoozesNote)
            assertNull(viewModel.shown.baseFeeNote)
        }

    @Test
    fun `a stored pending change shows its value and note on open, and the note goes once that alarm has rung`() =
        runTest {
            pending.put(PendingChange(null, SettingValue.BaseFeeTier(1), Occurrence("a", sevenThirty)))
            val viewModel = viewModel()

            assertEquals(1, viewModel.shown.baseFeeTier)
            assertNotNull(viewModel.shown.baseFeeNote)

            clock.set(sevenThirty + 1.minutes)
            signal.emit()

            assertNull(viewModel.shown.baseFeeNote, "the lower fee is effective now")
            assertEquals(1, viewModel.shown.baseFeeTier)
        }

    @Test
    fun `the steppers ignore steps past their ends and write nothing`() =
        runTest {
            settings.setBaseFeeTier(1)
            val viewModel = viewModel()

            viewModel.onIntent(SettingsIntent.LowerBaseFee)
            viewModel.onIntent(SettingsIntent.MaxSnoozesChanged(0))
            viewModel.onIntent(SettingsIntent.MaxSnoozesChanged(6))

            assertEquals(GlobalSettings(baseFeeTier = 1, maxSnoozes = 2), settings.current)
            assertEquals(1, viewModel.shown.baseFeeTier)
            assertEquals(2, viewModel.shown.maxSnoozes)

            settings.setBaseFeeTier(10)
            viewModel.onIntent(SettingsIntent.RaiseBaseFee)
            assertEquals(10, settings.current.baseFeeTier)
        }

    @Test
    fun `quick steps add up and are saved in order`() =
        runTest {
            val viewModel = viewModel()

            repeat(4) { viewModel.onIntent(SettingsIntent.RaiseBaseFee) }

            assertEquals(7, settings.current.baseFeeTier)
            assertEquals(7, viewModel.shown.baseFeeTier)
        }

    @Test
    fun `a failed save is logged and the stepper returns to the stored value`() =
        runTest {
            val viewModel = viewModel()
            settings.failure = DomainError.StorageFailure("disk full")

            viewModel.onIntent(SettingsIntent.RaiseBaseFee)

            assertEquals(3, viewModel.shown.baseFeeTier)
            assertEquals("EUR 3.00", viewModel.shown.baseFee)
            assertTrue(
                logger.events.any { it is LogEvent.OperationFailed && it.operation == SettingsViewModel.SAVE_SETTING },
                "the failure is logged",
            )
        }

    @Test
    fun `panes open and Back returns to the main screen`() =
        runTest {
            val viewModel = viewModel()

            viewModel.onIntent(SettingsIntent.OpenPane(SettingsPane.BaseFee))
            assertEquals(SettingsPane.BaseFee, viewModel.shown.pane)

            viewModel.onIntent(SettingsIntent.Back)
            assertEquals(SettingsPane.Main, viewModel.shown.pane)
        }

    @Test
    fun `an unreadable pending store is logged and shows the live values`() =
        runTest {
            val failing =
                object : PendingChangeRepository by pending {
                    override fun observe(): Flow<List<PendingChange>> = flow { error("database closed") }
                }
            val save = SaveGlobalSetting(settings, pending, alarms, clock, zone, AlarmWriteLock(), sessionLock)
            val viewModel =
                SettingsViewModel(settings, failing, prices, SetBaseFee(save), SetMaxSnoozes(save), clock, zone, signal, formatter, logger)
            backgroundScope.launch(dispatcher) { viewModel.state.collect {} }

            assertEquals(3, viewModel.shown.baseFeeTier)
            assertTrue(logger.events.any { it is LogEvent.OperationFailed && it.operation == SettingsViewModel.LOAD_PENDING })
        }
}
