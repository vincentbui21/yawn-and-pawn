package com.yawnandpawn.app.ui.onboarding

import com.yawnandpawn.app.core.alarm.AlarmDraft
import com.yawnandpawn.app.core.alarm.AlarmWriteLock
import com.yawnandpawn.app.core.billing.PriceCatalog
import com.yawnandpawn.app.core.billing.PriceCatalogSnapshot
import com.yawnandpawn.app.core.config.SaveGlobalSetting
import com.yawnandpawn.app.core.config.SetBaseFee
import com.yawnandpawn.app.core.config.SettingValue
import com.yawnandpawn.app.core.error.DomainError
import com.yawnandpawn.app.core.error.Outcome
import com.yawnandpawn.app.core.log.LogEvent
import com.yawnandpawn.app.core.onboarding.AcceptDisclosure
import com.yawnandpawn.app.core.onboarding.CompleteOnboarding
import com.yawnandpawn.app.core.onboarding.OnboardingProgress
import com.yawnandpawn.app.core.onboarding.SaveOnboardingStep
import com.yawnandpawn.app.core.session.GlobalSettings
import com.yawnandpawn.app.core.session.SessionLockGuard
import com.yawnandpawn.app.core.session.SessionState
import com.yawnandpawn.app.testing.AlarmUseCasesFixture
import com.yawnandpawn.app.testing.FakeAlarmRepository
import com.yawnandpawn.app.testing.FakeClock
import com.yawnandpawn.app.testing.FakeGlobalSettingsRepository
import com.yawnandpawn.app.testing.FakeLogger
import com.yawnandpawn.app.testing.FakeMoneyFormatter
import com.yawnandpawn.app.testing.FakeOnboardingRepository
import com.yawnandpawn.app.testing.FakePendingChangeRepository
import com.yawnandpawn.app.testing.FakePriceCatalog
import com.yawnandpawn.app.testing.FakeTimeZoneProvider
import com.yawnandpawn.app.testing.OnboardingPresets
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
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * Story 5.10: onboarding steps 1 to 3 with the real core use cases on fakes: the stored step, the consent, the base fee
 * through `SetBaseFee` (Play prices or the USD approximation), completion, and the logged failures.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OnboardingViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()

    /** Monday 2027-03-08, 23:40 UTC (the price cache, fetched on the 3rd, is still fresh). */
    private val lateEvening = Instant.parse("2027-03-08T23:40:00Z")
    private val clock = FakeClock(lateEvening)
    private val zone = FakeTimeZoneProvider(TimeZone.UTC)
    private val logger = FakeLogger()
    private val alarms = FakeAlarmRepository()
    private val pending = FakePendingChangeRepository()
    private val settings = FakeGlobalSettingsRepository(GlobalSettings(baseFeeTier = 1), pending)
    private val prices = FakePriceCatalog(aPriceSnapshot(currency = "EUR"))
    private val onboarding = FakeOnboardingRepository(OnboardingPresets.Fresh)
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

    private fun TestScope.viewModel(): OnboardingViewModel {
        val save = SaveGlobalSetting(settings, pending, alarms, clock, zone, AlarmWriteLock(), sessionLock)
        val viewModel =
            OnboardingViewModel(
                onboarding = onboarding,
                saveStep = SaveOnboardingStep(onboarding, sessionLock),
                acceptDisclosure = AcceptDisclosure(onboarding, clock, sessionLock),
                completeOnboarding = CompleteOnboarding(onboarding, sessionLock),
                globalSettings = settings,
                priceCatalog = prices,
                setBaseFee = SetBaseFee(save),
                clock = clock,
                moneyFormatter = FakeMoneyFormatter(),
                logger = logger,
            )
        backgroundScope.launch(dispatcher) { viewModel.state.collect {} }
        return viewModel
    }

    private val OnboardingViewModel.shown: OnboardingUiState
        get() = assertNotNull(state.value, "the stored progress was read")

    /** The failures logged for [operation]. */
    private fun failed(operation: String): List<LogEvent> =
        logger.events.filter { (it as? LogEvent.OperationFailed)?.operation == operation }

    @Test
    fun `a first launch opens step 1, and Let's set it up opens the disclosure and stores the step`() =
        runTest {
            val viewModel = viewModel()
            assertEquals(OnboardingStep.Mission, viewModel.shown.step)

            viewModel.onIntent(OnboardingIntent.Next(viewModel.shown.step))

            assertEquals(OnboardingStep.Disclosure, viewModel.shown.step)
            assertEquals(listOf("Disclosure"), onboarding.steps)
            assertNull(onboarding.current.disclosureAcceptedAt, "opening the disclosure is not consent")
        }

    @Test
    fun `a process death on step 3 reopens step 3 and attempts a price refresh`() =
        runTest {
            onboarding.current = OnboardingPresets.Consented

            val viewModel = viewModel()

            assertEquals(OnboardingStep.BaseFee, viewModel.shown.step)
            assertEquals(1, prices.refreshes)
        }

    @Test
    fun `an unknown stored step opens step 1, and no stored step skips the consent`() =
        runTest {
            onboarding.current = OnboardingProgress(step = "SomethingNew")
            assertEquals(OnboardingStep.Mission, viewModel().shown.step)

            onboarding.current = OnboardingProgress(step = "BaseFee")
            assertEquals(OnboardingStep.Disclosure, viewModel().shown.step, "the base fee without consent opens the disclosure")

            onboarding.current = OnboardingPresets.Consented.copy(step = "TestAlarm")
            assertEquals(OnboardingStep.BaseFee, viewModel().shown.step, "steps 4 to 8 are not built yet")
        }

    @Test
    fun `an unreadable progress is logged and opens step 1`() =
        runTest {
            onboarding.observeFailure = IllegalStateException("bug")

            assertEquals(OnboardingStep.Mission, viewModel().shown.step)
            assertEquals(1, failed(OnboardingViewModel.LOAD_PROGRESS).size)
        }

    @Test
    fun `I understand stores the consent, opens the base fee, and SaveAlarm stops refusing`() =
        runTest {
            val fixture = AlarmUseCasesFixture(onboarding = onboarding)
            val draft = AlarmDraft(time = LocalTime(7, 30))
            assertEquals(Outcome.Failure(DomainError.DisclosureRequired), fixture.save(draft))
            onboarding.current = OnboardingProgress(step = "Disclosure")
            val viewModel = viewModel()

            viewModel.onIntent(OnboardingIntent.Next(viewModel.shown.step))

            assertEquals(lateEvening, onboarding.current.disclosureAcceptedAt)
            assertEquals(OnboardingStep.BaseFee, viewModel.shown.step)
            assertEquals("BaseFee", onboarding.current.step)
            assertEquals(1, prices.refreshes, "the base fee step refreshes the prices as it opens")
            assertIs<Outcome.Success<*>>(fixture.save(draft))
        }

    @Test
    fun `a failed consent write stays on the disclosure and is logged`() =
        runTest {
            onboarding.current = OnboardingProgress(step = "Disclosure")
            val viewModel = viewModel()
            onboarding.writeFailure = DomainError.StorageFailure("disk full")

            viewModel.onIntent(OnboardingIntent.Next(viewModel.shown.step))

            assertEquals(OnboardingStep.Disclosure, viewModel.shown.step)
            assertNull(onboarding.current.disclosureAcceptedAt)
            assertEquals(1, failed(OnboardingViewModel.ACCEPT_DISCLOSURE).size)

            onboarding.writeFailure = null
            viewModel.onIntent(OnboardingIntent.Next(viewModel.shown.step))
            assertEquals(OnboardingStep.BaseFee, viewModel.shown.step, "a second tap tries again")
        }

    @Test
    fun `Back returns one step and is stored, and step 1 has no in-app Back`() =
        runTest {
            onboarding.current = OnboardingPresets.Consented
            val viewModel = viewModel()

            viewModel.onIntent(OnboardingIntent.Back)
            assertEquals(OnboardingStep.Disclosure, viewModel.shown.step)
            viewModel.onIntent(OnboardingIntent.Back)
            assertEquals(OnboardingStep.Mission, viewModel.shown.step)
            viewModel.onIntent(OnboardingIntent.Back)

            assertEquals(OnboardingStep.Mission, viewModel.shown.step)
            assertEquals(listOf("Disclosure", "Mission"), onboarding.steps)
        }

    @Test
    fun `loaded prices show Play's strings for the tier and a 3-entry ladder, with the bounds`() =
        runTest {
            onboarding.current = OnboardingPresets.Consented
            val viewModel = viewModel()

            val state = viewModel.shown
            assertEquals(1, state.baseFeeTier)
            assertEquals("EUR 1.00", state.baseFee)
            assertEquals(listOf("EUR 1.00", "EUR 2.00", "EUR 3.00"), state.feeLadder)
            assertFalse(state.pricesApproximate)
            assertFalse(state.canLowerFee, "− is disabled at tier 1")
            assertTrue(state.canRaiseFee)

            settings.setBaseFeeTier(10)
            assertEquals("EUR 10.00", viewModel.shown.baseFee)
            assertFalse(viewModel.shown.canRaiseFee, "+ is disabled at tier 10")
        }

    @Test
    fun `offline the step shows USD with the approximate note, the failed refresh is only logged, and Continue works`() =
        runTest {
            prices.snapshot = PriceCatalogSnapshot.EMPTY
            prices.refreshResult = Outcome.Failure(DomainError.ProductDetailsFailed(transient = true, cause = "offline"))
            onboarding.current = OnboardingPresets.Consented
            val viewModel = viewModel()

            assertEquals("USD 1.000000", viewModel.shown.baseFee)
            assertEquals(listOf("USD 1.000000", "USD 2.000000", "USD 3.000000"), viewModel.shown.feeLadder)
            assertTrue(viewModel.shown.pricesApproximate)
            assertEquals(1, failed(OnboardingViewModel.REFRESH_PRICES).size)

            viewModel.onIntent(OnboardingIntent.Next(viewModel.shown.step))

            assertTrue(onboarding.current.completed)
        }

    @Test
    fun `raising and lowering the fee saves through SetBaseFee and shows the value at once`() =
        runTest {
            onboarding.current = OnboardingPresets.Consented
            val viewModel = viewModel()

            viewModel.onIntent(OnboardingIntent.RaiseBaseFee)
            viewModel.onIntent(OnboardingIntent.RaiseBaseFee)

            assertEquals(3, settings.current.baseFeeTier)
            assertEquals(3, viewModel.shown.baseFeeTier)
            assertEquals("EUR 3.00", viewModel.shown.baseFee)
            assertEquals(listOf("EUR 3.00", "EUR 6.00", "EUR 9.00"), viewModel.shown.feeLadder)

            viewModel.onIntent(OnboardingIntent.LowerBaseFee)

            assertEquals(2, settings.current.baseFeeTier)
            assertEquals(2, viewModel.shown.baseFeeTier)
        }

    @Test
    fun `a failed fee save is logged and the stored value comes back`() =
        runTest {
            onboarding.current = OnboardingPresets.Consented
            val viewModel = viewModel()
            settings.failure = DomainError.StorageFailure("disk full")

            viewModel.onIntent(OnboardingIntent.RaiseBaseFee)

            assertEquals(1, settings.current.baseFeeTier)
            assertEquals(1, viewModel.shown.baseFeeTier)
            assertEquals(1, failed(OnboardingViewModel.SAVE_FEE).size)
        }

    @Test
    fun `a lower fee under the commitment lock is stored as pending and stays shown`() =
        runTest {
            settings.setBaseFeeTier(3)
            alarms.upsert(anAlarm(id = "a", time = LocalTime(7, 30)))
            onboarding.current = OnboardingPresets.Consented
            val viewModel = viewModel()

            viewModel.onIntent(OnboardingIntent.LowerBaseFee)

            assertEquals(3, settings.current.baseFeeTier, "the live fee stays until the alarm has rung")
            assertEquals(listOf(SettingValue.BaseFeeTier(2)), pending.current.map { it.value })
            assertEquals(2, viewModel.shown.baseFeeTier)
        }

    @Test
    fun `Continue on step 3 completes onboarding, and a failed write stays and is logged`() =
        runTest {
            onboarding.current = OnboardingPresets.Consented
            val viewModel = viewModel()
            onboarding.writeFailure = DomainError.StorageFailure("disk full")

            viewModel.onIntent(OnboardingIntent.Next(viewModel.shown.step))

            assertFalse(onboarding.current.completed)
            assertEquals(OnboardingStep.BaseFee, viewModel.shown.step)
            assertEquals(1, failed(OnboardingViewModel.COMPLETE).size)

            onboarding.writeFailure = null
            viewModel.onIntent(OnboardingIntent.Next(viewModel.shown.step))

            assertTrue(onboarding.current.completed)
        }

    @Test
    fun `a double tap on Let's set it up opens the disclosure without accepting it`() =
        runTest {
            val viewModel = viewModel()

            // Both taps come from the mission's button: the second lands on a step already left.
            viewModel.onIntent(OnboardingIntent.Next(OnboardingStep.Mission))
            viewModel.onIntent(OnboardingIntent.Next(OnboardingStep.Mission))

            assertEquals(OnboardingStep.Disclosure, viewModel.shown.step)
            assertNull(onboarding.current.disclosureAcceptedAt, "the disclosure was never skipped")
        }

    @Test
    fun `a late tap on I understand never skips the base fee step`() =
        runTest {
            onboarding.current = OnboardingProgress(step = "Disclosure")
            val viewModel = viewModel()

            viewModel.onIntent(OnboardingIntent.Next(OnboardingStep.Disclosure))
            viewModel.onIntent(OnboardingIntent.Next(OnboardingStep.Disclosure))

            assertEquals(OnboardingStep.BaseFee, viewModel.shown.step)
            assertFalse(onboarding.current.completed)
        }

    @Test
    fun `the stepper only saves on the base fee step`() =
        runTest {
            onboarding.current = OnboardingPresets.Consented.copy(step = "Disclosure")
            val viewModel = viewModel()

            viewModel.onIntent(OnboardingIntent.RaiseBaseFee)

            assertEquals(1, settings.current.baseFeeTier)
            assertEquals(OnboardingStep.Disclosure, viewModel.shown.step)
        }

    @Test
    fun `an unreadable price cache is logged and shows the approximate prices`() =
        runTest {
            val broken =
                object : PriceCatalog {
                    override fun observe(): Flow<PriceCatalogSnapshot> = flow { throw IllegalStateException("bug") }

                    override suspend fun refresh(): Outcome<Unit, DomainError> = Outcome.Success(Unit)
                }
            onboarding.current = OnboardingPresets.Consented
            val save = SaveGlobalSetting(settings, pending, alarms, clock, zone, AlarmWriteLock(), sessionLock)
            val viewModel =
                OnboardingViewModel(
                    onboarding = onboarding,
                    saveStep = SaveOnboardingStep(onboarding, sessionLock),
                    acceptDisclosure = AcceptDisclosure(onboarding, clock, sessionLock),
                    completeOnboarding = CompleteOnboarding(onboarding, sessionLock),
                    globalSettings = settings,
                    priceCatalog = broken,
                    setBaseFee = SetBaseFee(save),
                    clock = clock,
                    moneyFormatter = FakeMoneyFormatter(),
                    logger = logger,
                )
            backgroundScope.launch(dispatcher) { viewModel.state.collect {} }

            assertTrue(viewModel.shown.pricesApproximate)
            assertEquals(1, failed(OnboardingViewModel.LOAD_PRICES).size)
        }

    @Test
    fun `the intents of steps 4 to 8 are ignored`() =
        runTest {
            val viewModel = viewModel()

            viewModel.onIntent(OnboardingIntent.TimeChanged(LocalTime(6, 0)))
            viewModel.onIntent(OnboardingIntent.UsageStatsChosen(share = true))
            viewModel.onIntent(OnboardingIntent.SkipTestAlarm)

            assertEquals(OnboardingStep.Mission, viewModel.shown.step)
            assertEquals(OnboardingPresets.Fresh, onboarding.current)
        }
}
