package com.yawnandpawn.app.ui.settings

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.yawnandpawn.app.core.alarm.AlarmWriteLock
import com.yawnandpawn.app.core.config.GlobalSettingsRepository
import com.yawnandpawn.app.core.config.SaveDefaultSetting
import com.yawnandpawn.app.core.config.SaveGlobalSetting
import com.yawnandpawn.app.core.config.SetBaseFee
import com.yawnandpawn.app.core.config.SetMaxSnoozes
import com.yawnandpawn.app.core.error.DomainError
import com.yawnandpawn.app.core.error.Outcome
import com.yawnandpawn.app.core.log.LogEvent
import com.yawnandpawn.app.core.session.GlobalSettings
import com.yawnandpawn.app.core.session.SessionLockGuard
import com.yawnandpawn.app.core.session.SessionState
import com.yawnandpawn.app.core.session.ThemeMode
import com.yawnandpawn.app.core.telemetry.SetAnalyticsConsent
import com.yawnandpawn.app.testing.FakeAlarmRepository
import com.yawnandpawn.app.testing.FakeClock
import com.yawnandpawn.app.testing.FakeConsentStore
import com.yawnandpawn.app.testing.FakeGlobalSettingsRepository
import com.yawnandpawn.app.testing.FakeLogger
import com.yawnandpawn.app.testing.FakeMoneyFormatter
import com.yawnandpawn.app.testing.FakePendingChangeRepository
import com.yawnandpawn.app.testing.FakePriceCatalog
import com.yawnandpawn.app.testing.FakeTimeChangeSignal
import com.yawnandpawn.app.testing.FakeTimeZoneProvider
import com.yawnandpawn.app.testing.aPriceSnapshot
import com.yawnandpawn.app.ui.theme.PpsThemeMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.datetime.TimeZone
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * The settings store under the test's control: every default write waits while [open] is false, fails while [fail] is
 * set, and is kept in [writes] (as "field=value").
 */
private class GatedDefaults(
    private val inner: FakeGlobalSettingsRepository,
) : GlobalSettingsRepository by inner {
    val open = MutableStateFlow(true)
    var fail: DomainError? = null
    val writes = mutableListOf<String>()

    private suspend fun gated(
        write: String,
        block: suspend () -> Outcome<Unit, DomainError>,
    ): Outcome<Unit, DomainError> {
        open.first { it }
        fail?.let { return Outcome.Failure(it) }
        writes += write
        return block()
    }

    override suspend fun setDefaultGraceSeconds(seconds: Int) = gated("grace=$seconds") { inner.setDefaultGraceSeconds(seconds) }

    override suspend fun setDefaultVibrateInGrace(on: Boolean) = gated("vibrate=$on") { inner.setDefaultVibrateInGrace(on) }

    override suspend fun setDefaultSnoozeLength(minutes: Int) = gated("snooze=$minutes") { inner.setDefaultSnoozeLength(minutes) }

    override suspend fun setThemeMode(mode: ThemeMode) = gated("theme=$mode") { inner.setThemeMode(mode) }

    override suspend fun setBrightWakeScreen(on: Boolean) = gated("bright=$on") { inner.setBrightWakeScreen(on) }
}

/**
 * Stories 5.1 and 5.2: Settings shows the stored new-alarm defaults, theme and "Bright wake screen", and saves each
 * change at once through its use case, in order; a failed save is logged and the stored value comes back.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsDefaultsViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val clock = FakeClock(Instant.parse("2027-03-08T23:40:00Z"))
    private val zone = FakeTimeZoneProvider(TimeZone.UTC)
    private val logger = FakeLogger()
    private val pending = FakePendingChangeRepository()
    private val inner = FakeGlobalSettingsRepository(GlobalSettings(), pending)
    private val settings = GatedDefaults(inner)
    private val restored = MutableStateFlow(true)
    private val sessionLock = SessionLockGuard(MutableStateFlow<SessionState>(SessionState.Idle), restored, MutableStateFlow(false))
    private val consents = FakeConsentStore()

    /** Every state the screen showed, in order. */
    private val shown = mutableListOf<SettingsUiState>()

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun TestScope.viewModel(): SettingsViewModel {
        val viewModel = newViewModel()
        backgroundScope.launch(dispatcher) { viewModel.state.collect { state -> state?.let { shown += it } } }
        return viewModel
    }

    /** The ViewModel as the app builds it; [outliving] finishes the default saves left when it is cleared. */
    private fun newViewModel(outliving: CoroutineScope? = null): SettingsViewModel {
        val save = SaveGlobalSetting(settings, pending, FakeAlarmRepository(), clock, zone, AlarmWriteLock(), sessionLock)
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
            defaults = DefaultSettingSetters(SaveDefaultSetting(settings, sessionLock)),
            consents = consents,
            setAnalyticsConsent = SetAnalyticsConsent(consents, sessionLock),
            outlivingScope = outliving,
        )
    }

    private val SettingsViewModel.now: SettingsUiState
        get() = assertNotNull(state.value, "the stored settings were read")

    private fun saveFailed(): Boolean =
        logger.events.any { it is LogEvent.OperationFailed && it.operation == SettingsViewModel.SAVE_DEFAULT }

    @Test
    fun `the stored defaults and theme are shown`() =
        runTest {
            inner.setDefaultSnoozeLength(15)
            inner.setDefaultGraceSeconds(30)
            inner.setDefaultVibrateInGrace(false)
            inner.setThemeMode(ThemeMode.Dark)
            inner.setBrightWakeScreen(false)

            val state = viewModel().now

            assertEquals(15, state.defaultSnoozeMinutes)
            assertEquals(30, state.defaultQuietSeconds)
            assertEquals(false, state.vibrateDuringQuietTime)
            assertEquals(PpsThemeMode.Dark, state.theme)
            assertEquals(false, state.brightWakeScreen)
        }

    @Test
    fun `the built-in defaults show when nothing is stored - 9 min, 20 s, vibration on, System, bright wake on`() =
        runTest {
            val state = viewModel().now

            assertEquals(9, state.defaultSnoozeMinutes)
            assertEquals(20, state.defaultQuietSeconds)
            assertEquals(true, state.vibrateDuringQuietTime)
            assertEquals(PpsThemeMode.System, state.theme)
            assertEquals(true, state.brightWakeScreen)
        }

    @Test
    fun `each control saves through its use case and shows the stored value`() =
        runTest {
            val viewModel = viewModel()

            viewModel.onIntent(SettingsIntent.SnoozeLengthSelected(10))
            viewModel.onIntent(SettingsIntent.QuietTimeChanged(25))
            viewModel.onIntent(SettingsIntent.VibrateDuringQuietTimeToggled(false))
            viewModel.onIntent(SettingsIntent.ThemeSelected(PpsThemeMode.Dark))
            viewModel.onIntent(SettingsIntent.BrightWakeScreenToggled(false))

            assertEquals(
                GlobalSettings(
                    defaultSnoozeLengthMinutes = 10,
                    defaultGraceSeconds = 25,
                    defaultVibrateInGrace = false,
                    themeMode = ThemeMode.Dark,
                    brightWakeScreen = false,
                ),
                inner.current,
            )
            val state = viewModel.now
            assertEquals(10, state.defaultSnoozeMinutes)
            assertEquals(25, state.defaultQuietSeconds)
            assertEquals(false, state.vibrateDuringQuietTime)
            assertEquals(PpsThemeMode.Dark, state.theme)
            assertEquals(false, state.brightWakeScreen)
            assertEquals(listOf("snooze=10", "grace=25", "vibrate=false", "theme=Dark", "bright=false"), settings.writes)
        }

    @Test
    fun `Bright wake screen moves at once, is stored, and comes back on when its save fails (Story 5_2)`() =
        runTest {
            val viewModel = viewModel()
            viewModel.onIntent(SettingsIntent.BrightWakeScreenToggled(true))
            assertTrue(settings.writes.isEmpty(), "on is already shown: nothing saved")

            settings.open.value = false
            viewModel.onIntent(SettingsIntent.BrightWakeScreenToggled(false))
            assertEquals(false, viewModel.now.brightWakeScreen, "the switch moves at once")
            settings.open.value = true
            assertEquals(false, inner.current.brightWakeScreen)
            assertEquals(false, viewModel.now.brightWakeScreen)

            settings.fail = DomainError.StorageFailure("disk full")
            viewModel.onIntent(SettingsIntent.BrightWakeScreenToggled(true))
            assertEquals(false, viewModel.now.brightWakeScreen, "back to the stored value")
            assertTrue(saveFailed())
        }

    @Test
    fun `before the session is restored Bright wake screen is refused by the session lock and stays as stored (Story 5_2)`() =
        runTest {
            val viewModel = viewModel()
            restored.value = false

            viewModel.onIntent(SettingsIntent.BrightWakeScreenToggled(false))

            assertTrue(settings.writes.isEmpty())
            assertEquals(true, inner.current.brightWakeScreen)
            assertEquals(true, viewModel.now.brightWakeScreen)
        }

    @Test
    fun `a value outside its range, or the one already shown, is not saved`() =
        runTest {
            val viewModel = viewModel()

            viewModel.onIntent(SettingsIntent.QuietTimeChanged(14))
            viewModel.onIntent(SettingsIntent.QuietTimeChanged(31))
            viewModel.onIntent(SettingsIntent.SnoozeLengthSelected(7))
            viewModel.onIntent(SettingsIntent.QuietTimeChanged(20))
            viewModel.onIntent(SettingsIntent.ThemeSelected(PpsThemeMode.System))

            assertTrue(settings.writes.isEmpty(), "${settings.writes}")
            assertEquals(20, viewModel.now.defaultQuietSeconds)
            assertEquals(GlobalSettings(), inner.current)
            // Ignored, not refused by the use case: nothing failed, and the screen never showed 14, 31 or 7 (review fix).
            assertTrue(!saveFailed(), "${logger.events}")
            assertTrue(shown.none { it.defaultQuietSeconds !in 15..30 || it.defaultSnoozeMinutes == 7 }, "$shown")
        }

    @Test
    fun `slider steps show at once, and a drag saves the running step and then only the latest one`() =
        runTest {
            val viewModel = viewModel()
            settings.open.value = false

            listOf(21, 21, 22, 22, 22, 23).forEach { viewModel.onIntent(SettingsIntent.QuietTimeChanged(it)) }
            assertEquals(23, viewModel.now.defaultQuietSeconds, "shown before any save finished")
            assertTrue(settings.writes.isEmpty())

            settings.open.value = true

            // 21 was running; 22 was superseded by 23 in the queue, so it is never written (review fix: coalesced).
            assertEquals(listOf("grace=21", "grace=23"), settings.writes)
            assertEquals(23, inner.current.defaultGraceSeconds)
            assertEquals(23, viewModel.now.defaultQuietSeconds)
        }

    @Test
    fun `a switch toggled back and forth during a save ends on the last value without flicking back`() =
        runTest {
            val viewModel = viewModel()
            settings.open.value = false

            viewModel.onIntent(SettingsIntent.VibrateDuringQuietTimeToggled(false))
            viewModel.onIntent(SettingsIntent.VibrateDuringQuietTimeToggled(true))
            viewModel.onIntent(SettingsIntent.VibrateDuringQuietTimeToggled(false))
            val before = shown.size
            settings.open.value = true

            // The running false, then the latest false; the true between them was superseded in the queue.
            assertEquals(listOf("vibrate=false", "vibrate=false"), settings.writes)
            assertEquals(false, inner.current.defaultVibrateInGrace)
            assertTrue(shown.drop(before).none { it.vibrateDuringQuietTime }, "never back on while the saves ran")
            assertEquals(false, viewModel.now.vibrateDuringQuietTime)
        }

    @Test
    fun `a failed save shows the new value at once, then the stored value again, and is logged`() =
        runTest {
            val viewModel = viewModel()
            settings.open.value = false
            settings.fail = DomainError.StorageFailure("disk full")

            viewModel.onIntent(SettingsIntent.VibrateDuringQuietTimeToggled(false))
            assertEquals(false, viewModel.now.vibrateDuringQuietTime, "the switch moves at once")

            settings.open.value = true

            assertEquals(true, viewModel.now.vibrateDuringQuietTime, "back to the stored value")
            assertEquals(true, inner.current.defaultVibrateInGrace)
            assertTrue(saveFailed(), "the failure is logged")
        }

    @Test
    fun `during a session a set is refused, nothing is written, and the stored theme comes back`() =
        runTest {
            val viewModel = viewModel()
            restored.value = false // locked: the stored session is not restored yet, as during a session

            viewModel.onIntent(SettingsIntent.ThemeSelected(PpsThemeMode.Dark))

            assertTrue(settings.writes.isEmpty())
            assertEquals(PpsThemeMode.System, viewModel.now.theme)
            assertTrue(saveFailed())
        }

    @Test
    fun `changing a default leaves the fee settings alone`() =
        runTest {
            inner.setBaseFeeTier(3)
            val viewModel = viewModel()

            viewModel.onIntent(SettingsIntent.SnoozeLengthSelected(5))

            assertEquals(3, inner.current.baseFeeTier)
            assertEquals(3, viewModel.now.baseFeeTier)
            assertEquals(5, viewModel.now.defaultSnoozeMinutes)
        }

    @Test
    fun `a tab switch during a slider drag still saves the last value, once, after the running one`() =
        runTest {
            val store = ViewModelStore()
            val viewModel =
                ViewModelProvider.create(
                    store,
                    viewModelFactory {
                        initializer { newViewModel(outliving = this@runTest) }
                    },
                )[SettingsViewModel::class]
            backgroundScope.launch(dispatcher) { viewModel.state.collect {} }
            settings.open.value = false

            (21..30).forEach { viewModel.onIntent(SettingsIntent.QuietTimeChanged(it)) }
            store.clear() // the user taps another tab: the Settings entry and its ViewModel go
            settings.open.value = true
            advanceUntilIdle() // the app scope's drain (a child of the test, so it runs here)

            assertEquals(30, inner.current.defaultGraceSeconds)
            assertEquals(listOf("grace=21", "grace=30"), settings.writes)
            assertTrue(logger.events.none { it is LogEvent.OperationFailed }, "nothing dropped: ${logger.events}")
        }

    @Test
    fun `without an outliving scope a default left when the screen closes is logged as dropped`() =
        runTest {
            val store = ViewModelStore()
            val viewModel = ViewModelProvider.create(store, viewModelFactory { initializer { newViewModel() } })[SettingsViewModel::class]
            backgroundScope.launch(dispatcher) { viewModel.state.collect {} }
            settings.open.value = false

            viewModel.onIntent(SettingsIntent.ThemeSelected(PpsThemeMode.Dark))
            viewModel.onIntent(SettingsIntent.QuietTimeChanged(25))
            store.clear()
            settings.open.value = true

            assertEquals(ThemeMode.Dark, inner.current.themeMode, "the running save finishes")
            val dropped =
                logger.events.filterIsInstance<LogEvent.OperationFailed>().single {
                    it.operation == SettingsViewModel.SAVE_DEFAULT
                }
            assertEquals("dropped 1 on close", dropped.cause)
        }
}
