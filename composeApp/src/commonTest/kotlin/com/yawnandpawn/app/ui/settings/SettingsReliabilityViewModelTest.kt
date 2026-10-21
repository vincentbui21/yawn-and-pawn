package com.yawnandpawn.app.ui.settings

import com.yawnandpawn.app.core.alarm.AlarmWriteLock
import com.yawnandpawn.app.core.config.SaveDefaultSetting
import com.yawnandpawn.app.core.config.SaveGlobalSetting
import com.yawnandpawn.app.core.config.SetBaseFee
import com.yawnandpawn.app.core.config.SetMaxSnoozes
import com.yawnandpawn.app.core.reliability.ChecklistRow
import com.yawnandpawn.app.core.reliability.EvaluateReliability
import com.yawnandpawn.app.core.reliability.ReliabilityStatus
import com.yawnandpawn.app.core.reliability.StoredReliability
import com.yawnandpawn.app.core.session.SessionLockGuard
import com.yawnandpawn.app.core.session.SessionState
import com.yawnandpawn.app.core.telemetry.SetAnalyticsConsent
import com.yawnandpawn.app.testing.FakeAlarmRepository
import com.yawnandpawn.app.testing.FakeClock
import com.yawnandpawn.app.testing.FakeConsentStore
import com.yawnandpawn.app.testing.FakeGlobalSettingsRepository
import com.yawnandpawn.app.testing.FakeLogger
import com.yawnandpawn.app.testing.FakeMoneyFormatter
import com.yawnandpawn.app.testing.FakePendingChangeRepository
import com.yawnandpawn.app.testing.FakePriceCatalog
import com.yawnandpawn.app.testing.FakeReliabilityProbe
import com.yawnandpawn.app.testing.FakeReliabilityRecords
import com.yawnandpawn.app.testing.FakeTimeChangeSignal
import com.yawnandpawn.app.testing.FakeTimeZoneProvider
import com.yawnandpawn.app.testing.aPriceSnapshot
import com.yawnandpawn.app.testing.anEvaluateReliability
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Story 5.4: the Settings reliability banner follows `ReliabilityChecklist.alarmBlocking`, evaluated on every start, and
 * its "Fix" and the "Reliability checklist" row open the checklist.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsReliabilityViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val clock = FakeClock()
    private val zone = FakeTimeZoneProvider()
    private val pending = FakePendingChangeRepository()
    private val settings = FakeGlobalSettingsRepository(pending = pending)
    private val probe = FakeReliabilityProbe()
    private val records = FakeReliabilityRecords()
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

    private val consents = FakeConsentStore()

    private fun TestScope.viewModel(
        reliability: EvaluateReliability? = anEvaluateReliability(probe = probe, records = records),
    ): SettingsViewModel {
        val save = SaveGlobalSetting(settings, pending, FakeAlarmRepository(), clock, zone, AlarmWriteLock(), sessionLock)
        val viewModel =
            SettingsViewModel(
                globalSettings = settings,
                pendingChanges = pending,
                priceCatalog = FakePriceCatalog(aPriceSnapshot()),
                setBaseFee = SetBaseFee(save),
                setMaxSnoozes = SetMaxSnoozes(save),
                clock = clock,
                timeZoneProvider = zone,
                timeChanges = FakeTimeChangeSignal(),
                moneyFormatter = FakeMoneyFormatter(),
                logger = FakeLogger(),
                defaults = DefaultSettingSetters(SaveDefaultSetting(settings, sessionLock)),
                consents = consents,
                setAnalyticsConsent = SetAnalyticsConsent(consents, sessionLock),
                reliability = reliability,
            )
        backgroundScope.launch(dispatcher) { viewModel.state.collect {} }
        return viewModel
    }

    private val SettingsViewModel.banner: Boolean
        get() = assertNotNull(state.value, "the stored settings were read").reliabilityProblem

    private fun TestScope.effectsOf(viewModel: SettingsViewModel): List<SettingsEffect> {
        val effects = mutableListOf<SettingsEffect>()
        backgroundScope.launch(dispatcher) { viewModel.effects.toList(effects) }
        return effects
    }

    @Test
    fun `the Reliability checklist row is built`() {
        assertTrue(SettingsRow.Reliability in BuiltSettingsRows)
    }

    @Test
    fun `a blocking row raises the banner on start, and the next start clears it once fixed`() =
        runTest(dispatcher) {
            probe.status = ReliabilityStatus.ALL_OK.copy(dndAllowsAlarms = false)
            val viewModel = viewModel()
            assertFalse(viewModel.banner, "nothing evaluated before Settings starts")
            assertEquals(0, probe.checks)

            viewModel.onIntent(SettingsIntent.Started)
            assertTrue(viewModel.banner)

            probe.status = ReliabilityStatus.ALL_OK
            viewModel.onIntent(SettingsIntent.Started)
            assertFalse(viewModel.banner)
        }

    @Test
    fun `a revoked battery setting raises the banner too`() =
        runTest(dispatcher) {
            records.stored = StoredReliability(lastOk = setOf(ChecklistRow.Battery))
            probe.status = ReliabilityStatus.ALL_OK.copy(backgroundRestricted = true)
            val viewModel = viewModel()

            viewModel.onIntent(SettingsIntent.Started)

            assertTrue(viewModel.banner)
        }

    @Test
    fun `only non-blocking rows failing shows no banner`() =
        runTest(dispatcher) {
            probe.status = ReliabilityStatus.ALL_OK.copy(cameraGranted = false, exactAlarmsAllowed = false)
            val viewModel = viewModel()

            viewModel.onIntent(SettingsIntent.Started)

            assertFalse(viewModel.banner)
        }

    @Test
    fun `Fix on the banner and the Reliability checklist row open the checklist`() =
        runTest(dispatcher) {
            val viewModel = viewModel()
            val effects = effectsOf(viewModel)

            viewModel.onIntent(SettingsIntent.FixSettings)
            viewModel.onIntent(SettingsIntent.ReliabilityClicked)

            assertEquals(listOf<SettingsEffect>(SettingsEffect.OpenReliability, SettingsEffect.OpenReliability), effects)
        }

    @Test
    fun `without a checklist a start shows no banner`() =
        runTest(dispatcher) {
            probe.status = ReliabilityStatus.ALL_OK.copy(notificationsAllowed = false)
            val viewModel = viewModel(reliability = null)

            viewModel.onIntent(SettingsIntent.Started)

            assertFalse(viewModel.banner)
            assertEquals(0, probe.checks)
        }
}
