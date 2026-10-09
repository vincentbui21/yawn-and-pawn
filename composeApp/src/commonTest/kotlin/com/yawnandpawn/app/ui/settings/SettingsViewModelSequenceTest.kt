package com.yawnandpawn.app.ui.settings

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.yawnandpawn.app.core.alarm.AlarmWriteLock
import com.yawnandpawn.app.core.config.GlobalSettingsRepository
import com.yawnandpawn.app.core.config.Occurrence
import com.yawnandpawn.app.core.config.PendingChange
import com.yawnandpawn.app.core.config.SaveGlobalSetting
import com.yawnandpawn.app.core.config.SetBaseFee
import com.yawnandpawn.app.core.config.SetMaxSnoozes
import com.yawnandpawn.app.core.config.SettingValue
import com.yawnandpawn.app.core.error.DomainError
import com.yawnandpawn.app.core.error.Outcome
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
import kotlinx.coroutines.flow.first
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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * The settings store under the test's control (Story 4.5 review): [get] waits while [open] is false (so a save holds at
 * its first read), fails the next [failNext] saves, every base fee write is kept in [writes], and while [lagging] is set
 * the screen sees that flow instead of the store (a store whose echo reaches the screen late).
 */
private class GatedSettings(
    private val inner: FakeGlobalSettingsRepository,
) : GlobalSettingsRepository by inner {
    val open = MutableStateFlow(true)
    var failNext = 0
    val writes = mutableListOf<Int>()
    var lagging: MutableStateFlow<GlobalSettings>? = null

    override fun observe(): Flow<GlobalSettings> = lagging ?: inner.observe()

    override suspend fun get(): Outcome<GlobalSettings, DomainError> {
        open.first { it }
        if (failNext > 0) {
            failNext--
            return Outcome.Failure(DomainError.StorageFailure("disk full"))
        }
        return inner.get()
    }

    override suspend fun setBaseFeeTier(tier: Int): Outcome<Unit, DomainError> = inner.setBaseFeeTier(tier).also { writes += tier }
}

/**
 * Story 4.5 review fixes: the order of saves and echoes (no flicker back, no wrong step), saves held in flight, failed
 * saves with a newer one queued, the notes during a save, today and tomorrow away from UTC and on a DST night, and saves
 * dropped when the screen closes.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelSequenceTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val clock = FakeClock(Instant.parse("2027-03-08T23:40:00Z"))
    private val zone = FakeTimeZoneProvider(TimeZone.UTC)
    private val logger = FakeLogger()
    private val alarms = FakeAlarmRepository()
    private val pending = FakePendingChangeRepository()
    private val inner = FakeGlobalSettingsRepository(GlobalSettings(baseFeeTier = 3, maxSnoozes = 2), pending)
    private val settings = GatedSettings(inner)
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

    private fun newViewModel(): SettingsViewModel {
        val save = SaveGlobalSetting(settings, pending, alarms, clock, zone, AlarmWriteLock(), sessionLock)
        return SettingsViewModel(
            globalSettings = settings,
            pendingChanges = pending,
            priceCatalog = FakePriceCatalog(aPriceSnapshot(currency = "EUR")),
            setBaseFee = SetBaseFee(save),
            setMaxSnoozes = SetMaxSnoozes(save),
            clock = clock,
            timeZoneProvider = zone,
            timeChanges = FakeTimeChangeSignal(),
            moneyFormatter = FakeMoneyFormatter(),
            logger = logger,
        )
    }

    /** A collected ViewModel; every tier it showed is kept in [shownTiers]. */
    private val shownTiers = mutableListOf<Int>()

    private fun TestScope.viewModel(): SettingsViewModel =
        newViewModel().also { viewModel ->
            backgroundScope.launch(dispatcher) { viewModel.state.collect { state -> state?.let { shownTiers += it.baseFeeTier } } }
        }

    private val SettingsViewModel.shown: SettingsUiState
        get() = assertNotNull(state.value, "the stored settings were read")

    @Test
    fun `a saved step never shows the old value while the store's echo is late, and the next step starts from it`() =
        runTest {
            val late = MutableStateFlow(inner.current)
            settings.lagging = late
            val viewModel = viewModel()

            viewModel.onIntent(SettingsIntent.RaiseBaseFee)

            assertEquals(4, inner.current.baseFeeTier, "saved")
            assertEquals(4, viewModel.shown.baseFeeTier, "held while the screen's copy of the store still says 3")

            late.value = inner.current
            assertEquals(4, viewModel.shown.baseFeeTier)
            viewModel.onIntent(SettingsIntent.RaiseBaseFee)

            assertEquals(listOf(4, 5), settings.writes, "the step after the echo starts from 4")
            assertEquals(5, viewModel.shown.baseFeeTier)
            assertEquals(shownTiers.sorted(), shownTiers, "the shown value never went back: $shownTiers")
        }

    @Test
    fun `quick steps show at once while the first save is held, then are saved in order`() =
        runTest {
            val viewModel = viewModel()
            settings.open.value = false

            repeat(4) { viewModel.onIntent(SettingsIntent.RaiseBaseFee) }

            assertEquals(7, viewModel.shown.baseFeeTier, "shown before any save finished")
            assertTrue(settings.writes.isEmpty())

            settings.open.value = true

            assertEquals(listOf(4, 5, 6, 7), settings.writes)
            assertEquals(7, viewModel.shown.baseFeeTier)
        }

    @Test
    fun `a failed save with a newer one queued keeps the newer value, which is saved`() =
        runTest {
            val viewModel = viewModel()
            settings.open.value = false
            settings.failNext = 1

            viewModel.onIntent(SettingsIntent.RaiseBaseFee)
            viewModel.onIntent(SettingsIntent.RaiseBaseFee)
            assertEquals(5, viewModel.shown.baseFeeTier)

            settings.open.value = true

            assertEquals(listOf(5), settings.writes, "4 failed, 5 was saved")
            assertEquals(5, inner.current.baseFeeTier)
            assertEquals(shownTiers.sorted(), shownTiers, "never back to 3: $shownTiers")
            assertTrue(logger.events.any { it is LogEvent.OperationFailed && it.operation == SettingsViewModel.SAVE_SETTING })
        }

    @Test
    fun `a failed save moves the stepper at once, then back to the stored value`() =
        runTest {
            val viewModel = viewModel()
            settings.open.value = false
            settings.failNext = 1

            viewModel.onIntent(SettingsIntent.RaiseBaseFee)
            assertEquals(4, viewModel.shown.baseFeeTier, "moved at once")

            settings.open.value = true

            assertEquals(3, viewModel.shown.baseFeeTier, "reverted")
            assertTrue(settings.writes.isEmpty())
        }

    @Test
    fun `the note of the stored pending change is hidden while another value is being saved`() =
        runTest {
            alarms.upsert(anAlarm(id = "a", time = LocalTime(7, 30)))
            val sevenThirty = Occurrence("a", Instant.parse("2027-03-09T07:30:00Z"))
            pending.put(PendingChange(null, SettingValue.BaseFeeTier(2), sevenThirty))
            val viewModel = viewModel()
            assertEquals(2, viewModel.shown.baseFeeTier)
            assertNotNull(viewModel.shown.baseFeeNote)
            settings.open.value = false

            viewModel.onIntent(SettingsIntent.LowerBaseFee)

            assertEquals(1, viewModel.shown.baseFeeTier)
            assertNull(viewModel.shown.baseFeeNote, "the note about 2 is not shown under 1")

            settings.open.value = true

            assertEquals(1, viewModel.shown.baseFeeTier)
            assertEquals(WeakeningNote(LocalTime(7, 30)), viewModel.shown.baseFeeNote)
        }

    /** Lowers the fee at [now] in [timeZone] before a 07:30 alarm (local) and returns the note shown. */
    private suspend fun TestScope.noteAfterLowering(
        timeZone: TimeZone,
        now: Instant,
    ): WeakeningNote? {
        zone.set(timeZone)
        clock.set(now)
        alarms.upsert(anAlarm(id = "a", time = LocalTime(7, 30)))
        val viewModel = viewModel()
        viewModel.onIntent(SettingsIntent.LowerBaseFee)
        assertEquals(3, inner.current.baseFeeTier, "the lower fee waits")
        return viewModel.shown.baseFeeNote
    }

    @Test
    fun `at 23 40 in UTC+7 the alarm is tomorrow's`() =
        runTest {
            val note = noteAfterLowering(TimeZone.of("UTC+07:00"), Instant.parse("2027-03-08T16:40:00Z"))
            assertEquals(WeakeningNote(LocalTime(7, 30), today = false), note)
        }

    @Test
    fun `at 01 00 in UTC+7 the alarm is today's`() =
        runTest {
            val note = noteAfterLowering(TimeZone.of("UTC+07:00"), Instant.parse("2027-03-08T18:00:00Z"))
            assertEquals(WeakeningNote(LocalTime(7, 30), today = true), note)
        }

    @Test
    fun `at 23 40 before the Helsinki spring-forward night the alarm is tomorrow's`() =
        runTest {
            // 23:40 EET on 2027-03-27; the 07:30 alarm on the 28th is EEST, 6 h 50 min later.
            val note = noteAfterLowering(TimeZone.of("Europe/Helsinki"), Instant.parse("2027-03-27T21:40:00Z"))
            assertEquals(WeakeningNote(LocalTime(7, 30), today = false), note)
        }

    @Test
    fun `at 01 00 on the Helsinki spring-forward night the alarm is today's`() =
        runTest {
            // 01:00 EET on 2027-03-28, before the 03:00 jump; the alarm rings 5 h 30 min later.
            val note = noteAfterLowering(TimeZone.of("Europe/Helsinki"), Instant.parse("2027-03-27T23:00:00Z"))
            assertEquals(WeakeningNote(LocalTime(7, 30), today = true), note)
        }

    @Test
    fun `saves still queued when the screen closes are logged as dropped`() =
        runTest {
            val store = ViewModelStore()
            val viewModel = ViewModelProvider.create(store, viewModelFactory { initializer { newViewModel() } })[SettingsViewModel::class]
            backgroundScope.launch(dispatcher) { viewModel.state.collect {} }
            settings.open.value = false
            repeat(3) { viewModel.onIntent(SettingsIntent.RaiseBaseFee) }

            store.clear()

            val dropped =
                logger.events
                    .filterIsInstance<LogEvent.OperationFailed>()
                    .single { it.operation == SettingsViewModel.SAVE_SETTING }
            assertEquals("dropped 3 on close", dropped.cause)
        }
}
