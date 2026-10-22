package com.yawnandpawn.app.ui.home

import com.yawnandpawn.app.core.error.DomainError
import com.yawnandpawn.app.core.history.MissedNotes
import com.yawnandpawn.app.core.log.LogEvent
import com.yawnandpawn.app.core.onboarding.OnboardingTestNote
import com.yawnandpawn.app.core.reliability.StoredReliability
import com.yawnandpawn.app.core.session.SessionLockGuard
import com.yawnandpawn.app.core.session.SessionState
import com.yawnandpawn.app.testing.AlarmUseCasesFixture
import com.yawnandpawn.app.testing.FakeAlarmRepository
import com.yawnandpawn.app.testing.FakeCheckConfigRepository
import com.yawnandpawn.app.testing.FakeClock
import com.yawnandpawn.app.testing.FakeLogger
import com.yawnandpawn.app.testing.FakeMissedNoteDismissals
import com.yawnandpawn.app.testing.FakeOnboardingRepository
import com.yawnandpawn.app.testing.FakeReliabilityRecords
import com.yawnandpawn.app.testing.FakeSessionHistoryRepository
import com.yawnandpawn.app.testing.FakeTimeChangeSignal
import com.yawnandpawn.app.testing.FakeTimeZoneProvider
import com.yawnandpawn.app.testing.OnboardingPresets
import com.yawnandpawn.app.testing.anEvaluateReliability
import com.yawnandpawn.app.testing.noReRegisterSuggestions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
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
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * Story 5.12: Home's note after "Skip for now" on onboarding's test alarm, "Ring a test alarm with your phone locked to
 * check it works.": shown while due, hidden once a locked test passes or "Dismiss" is stored; a failed dismissal stays,
 * logged; an unreadable note is logged and hidden.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeTestNoteTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val clock = FakeClock(Instant.parse("2027-03-03T06:00:00Z"))
    private val logger = FakeLogger()
    private val onboarding = FakeOnboardingRepository(OnboardingPresets.Completed.copy(testNoteDue = true))
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

    private fun TestScope.home(): HomeViewModel {
        val repository = FakeAlarmRepository()
        val alarms = AlarmUseCasesFixture(repository = repository, clock = clock, onboarding = onboarding)
        val viewModel =
            HomeViewModel(
                FakeCheckConfigRepository(repository),
                AlarmActions(alarms.setEnabled, alarms.delete, logger),
                clock,
                FakeTimeZoneProvider(),
                FakeTimeChangeSignal(),
                MissedNotes(FakeSessionHistoryRepository(), FakeMissedNoteDismissals()),
                anEvaluateReliability(checks = FakeCheckConfigRepository(repository)),
                noReRegisterSuggestions(clock),
                evaluationContext = dispatcher,
                testNote = OnboardingTestNote(onboarding, records, sessionLock),
            )
        backgroundScope.launch { viewModel.state.collect { } }
        return viewModel
    }

    @Test
    fun `the note shows after a skipped test, and goes once a locked test passes`() =
        runTest(dispatcher) {
            val viewModel = home()
            assertTrue(viewModel.state.value.testSkipped)

            records.stored = StoredReliability(lockedTestPassedAt = clock.now())

            assertFalse(viewModel.state.value.testSkipped)
            assertTrue(onboarding.current.testNoteDue, "the note goes by the pass, not by a write")
        }

    @Test
    fun `Dismiss stores the dismissal and the note goes, and a failed dismissal stays, logged`() =
        runTest(dispatcher) {
            val viewModel = home()
            onboarding.writeFailure = DomainError.StorageFailure("disk full")

            viewModel.onIntent(HomeIntent.TestNoteDismissed)

            assertTrue(viewModel.state.value.testSkipped)
            assertEquals(
                listOf<LogEvent>(LogEvent.OperationFailed.of("dismiss test note", DomainError.StorageFailure("disk full"))),
                logger.events,
            )

            onboarding.writeFailure = null
            viewModel.onIntent(HomeIntent.TestNoteDismissed)

            assertFalse(viewModel.state.value.testSkipped)
            assertFalse(onboarding.current.testNoteDue)
        }

    @Test
    fun `no note without a skipped test, and an unreadable note is logged and hidden`() =
        runTest(dispatcher) {
            onboarding.current = OnboardingPresets.Completed
            assertFalse(home().state.value.testSkipped)

            onboarding.current = OnboardingPresets.Completed.copy(testNoteDue = true)
            records.observeFailure = IllegalStateException("unreadable")
            val unreadable = home()

            assertFalse(unreadable.state.value.testSkipped)
            assertEquals(listOf<LogEvent>(LogEvent.OperationFailed("load test note", "unreadable")), logger.events)
        }
}
