package com.yawnandpawn.app.ui.home

import com.yawnandpawn.app.core.alarm.AlarmRepository
import com.yawnandpawn.app.core.error.DomainError
import com.yawnandpawn.app.core.error.Outcome
import com.yawnandpawn.app.core.history.MissedNotes
import com.yawnandpawn.app.core.log.LogEvent
import com.yawnandpawn.app.core.stats.CheckKey
import com.yawnandpawn.app.core.stats.ConfiguredCheck
import com.yawnandpawn.app.core.stats.ReRegisterSuggestions
import com.yawnandpawn.app.testing.AlarmUseCasesFixture
import com.yawnandpawn.app.testing.FakeAlarmRepository
import com.yawnandpawn.app.testing.FakeCheckConfigRepository
import com.yawnandpawn.app.testing.FakeClock
import com.yawnandpawn.app.testing.FakeIdGenerator
import com.yawnandpawn.app.testing.FakeLogger
import com.yawnandpawn.app.testing.FakeMissedNoteDismissals
import com.yawnandpawn.app.testing.FakeReRegisterDismissals
import com.yawnandpawn.app.testing.FakeReliabilityProbe
import com.yawnandpawn.app.testing.FakeReliabilitySettings
import com.yawnandpawn.app.testing.FakeSessionHistoryRepository
import com.yawnandpawn.app.testing.FakeTimeChangeSignal
import com.yawnandpawn.app.testing.FakeTimeZoneProvider
import com.yawnandpawn.app.testing.aSessionHistoryRow
import com.yawnandpawn.app.testing.anAlarm
import com.yawnandpawn.app.ui.wake.uiCheckType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
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
import kotlin.test.assertNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant
import com.yawnandpawn.app.core.checks.CheckType as CoreCheckType
import com.yawnandpawn.app.ui.checks.CheckType as UiCheckType

/**
 * Story 3.13: Home's re-register banner. No camera type exists before Story 3.10, so the placeholder stands in for
 * QR/Barcode: the rule treats it as a camera check and [qrStandIn] names it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeReRegisterBannerTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val clock = FakeClock(Instant.parse("2027-03-03T06:00:00Z"))
    private val zone = FakeTimeZoneProvider(TimeZone.UTC)
    private val signal = FakeTimeChangeSignal()
    private val logger = FakeLogger()
    private val missedNotes = MissedNotes(FakeSessionHistoryRepository(), FakeMissedNoteDismissals())
    private val fallbacks = FakeSessionHistoryRepository()
    private val registrations = MutableStateFlow<List<ConfiguredCheck>>(emptyList())
    private val dismissals = FakeReRegisterDismissals()
    private val reRegister =
        ReRegisterSuggestions(fallbacks, { registrations }, dismissals, clock, usesCamera = { it == CoreCheckType.Placeholder })
    private val qrStandIn: (CoreCheckType) -> UiCheckType? = { if (it == CoreCheckType.Placeholder) UiCheckType.QrBarcode else null }
    private val alarmId = FakeIdGenerator.fakeUuid(1)
    private val qrKey = CheckKey(alarmId, CoreCheckType.Placeholder.id)

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun repository() = FakeAlarmRepository(listOf(anAlarm(id = alarmId, time = LocalTime(7, 0), requestCode = 1001)))

    private fun TestScope.home(
        repository: AlarmRepository = repository(),
        uiTypeOf: (CoreCheckType) -> UiCheckType? = qrStandIn,
    ): HomeViewModel {
        val alarms = AlarmUseCasesFixture(repository, clock, zone)
        val actions = AlarmActions(alarms.setEnabled, alarms.delete, clock, logger)
        val probe = FakeReliabilityProbe()
        val viewModel =
            HomeViewModel(
                FakeCheckConfigRepository(repository),
                actions,
                clock,
                zone,
                signal,
                missedNotes,
                probe,
                FakeReliabilitySettings(),
                reRegister,
                uiTypeOf,
            )
        backgroundScope.launch { viewModel.state.collect { } }
        return viewModel
    }

    private fun TestScope.effectsOf(viewModel: HomeViewModel): List<HomeEffect> {
        val effects = mutableListOf<HomeEffect>()
        backgroundScope.launch { viewModel.effects.toList(effects) }
        return effects
    }

    private fun register() {
        registrations.value = listOf(ConfiguredCheck(alarmId, CoreCheckType.Placeholder, registeredAt = clock.now() - 30.days))
    }

    /** A session of the alarm that used the fallback for the stand-in camera check [ago] before the clock. */
    private suspend fun fallbackUsed(
        sessionId: String,
        ago: Duration,
    ) = assertEquals(
        Outcome.Success(Unit),
        fallbacks.upsert(
            aSessionHistoryRow(sessionId = sessionId, alarmId = alarmId).copy(
                firstRingAt = clock.now() - ago,
                fallbackUsed = true,
                fallbackFrom = CoreCheckType.Placeholder.id,
            ),
        ),
    )

    /** Home with the alarm, its registered stand-in camera check and 3 fallbacks for it in the last 3 days. */
    private suspend fun TestScope.homeWithBanner(): HomeViewModel {
        register()
        listOf("f1" to 3.days, "f2" to 2.days, "f3" to 1.days).forEach { (id, ago) -> fallbackUsed(id, ago) }
        return home()
    }

    @Test
    fun `3 fallbacks for a registered camera check in 7 days show the banner naming the check, 2 do not`() =
        runTest(dispatcher) {
            register()
            fallbackUsed("f1", 3.days)
            fallbackUsed("f2", 2.days)
            val viewModel = home()
            assertNull(viewModel.state.value.reregisterCheck)

            fallbackUsed("f3", 1.days)

            assertEquals(UiCheckType.QrBarcode, viewModel.state.value.reregisterCheck)
        }

    @Test
    fun `the banner leaves on the next tick once a fallback is older than 7 days`() =
        runTest(dispatcher) {
            val viewModel = homeWithBanner()
            // The oldest fallback (3 days ago) is now 7 days and 1 hour old; exactly 7 days would still count.
            clock.advanceBy(4.days + 1.hours)
            assertEquals(UiCheckType.QrBarcode, viewModel.state.value.reregisterCheck, "until the next tick")

            signal.emit()

            assertNull(viewModel.state.value.reregisterCheck)
        }

    @Test
    fun `Re-register opens the alarm's editor, and nothing opens without a banner`() =
        runTest(dispatcher) {
            val quiet = home()
            val quietEffects = effectsOf(quiet)
            quiet.onIntent(HomeIntent.ReregisterClicked)
            assertEquals(emptyList(), quietEffects)

            val viewModel = homeWithBanner()
            val effects = effectsOf(viewModel)
            viewModel.onIntent(HomeIntent.ReregisterClicked)

            assertEquals(listOf<HomeEffect>(HomeEffect.OpenEditor(alarmId)), effects)
        }

    @Test
    fun `Dismiss stores the dismissal now and hides the banner until 3 new fallbacks`() =
        runTest(dispatcher) {
            val viewModel = homeWithBanner()

            viewModel.onIntent(HomeIntent.ReregisterDismissed)

            assertEquals(mapOf(qrKey to clock.now()), dismissals.current)
            assertNull(viewModel.state.value.reregisterCheck)
            clock.advanceBy(1.hours)
            fallbackUsed("f4", 0.days)
            fallbackUsed("f5", 0.days)
            assertNull(viewModel.state.value.reregisterCheck)
            fallbackUsed("f6", 0.days)
            assertEquals(UiCheckType.QrBarcode, viewModel.state.value.reregisterCheck)
        }

    @Test
    fun `a failed dismissal is logged and the banner stays`() =
        runTest(dispatcher) {
            val viewModel = homeWithBanner()
            dismissals.dismissFailure = DomainError.StorageFailure("disk full")

            viewModel.onIntent(HomeIntent.ReregisterDismissed)

            assertEquals(UiCheckType.QrBarcode, viewModel.state.value.reregisterCheck)
            val failure = LogEvent.OperationFailed("dismiss re-register banner", "storage failure: disk full")
            assertEquals(listOf<LogEvent>(failure), logger.events)
        }

    @Test
    fun `a failing fallback read is logged and shows no banner, then the banner comes back on a retry`() =
        runTest(dispatcher) {
            fallbacks.observeFailure = IllegalStateException("closed")
            val viewModel = homeWithBanner()
            assertNull(viewModel.state.value.reregisterCheck)
            assertEquals(1, viewModel.state.value.alarms.size)

            fallbacks.observeFailure = null
            advanceTimeBy(1_001)

            assertEquals(UiCheckType.QrBarcode, viewModel.state.value.reregisterCheck)
            assertEquals(listOf<LogEvent>(LogEvent.OperationFailed("load re-register suggestion", "closed")), logger.events)
        }

    @Test
    fun `no banner while the alarms cannot be read, nor for a check type without a screen`() =
        runTest(dispatcher) {
            register()
            listOf("f1" to 3.days, "f2" to 2.days, "f3" to 1.days).forEach { (id, ago) -> fallbackUsed(id, ago) }
            val failing = repository().apply { failure = DomainError.StorageFailure("closed") }
            assertNull(home(failing).state.value.reregisterCheck)

            // The production names: the placeholder has no screen, so it never shows (and Re-register does nothing).
            val unnamed = home(uiTypeOf = ::uiCheckType)
            val effects = effectsOf(unnamed)
            unnamed.onIntent(HomeIntent.ReregisterClicked)
            unnamed.onIntent(HomeIntent.ReregisterDismissed)

            assertNull(unnamed.state.value.reregisterCheck)
            assertEquals(emptyList(), effects)
            assertEquals(emptyMap(), dismissals.current)
        }
}
