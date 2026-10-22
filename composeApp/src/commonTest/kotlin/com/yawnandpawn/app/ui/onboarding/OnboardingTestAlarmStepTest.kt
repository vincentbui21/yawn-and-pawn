package com.yawnandpawn.app.ui.onboarding

import com.yawnandpawn.app.core.alarm.RequestCodes
import com.yawnandpawn.app.core.error.DomainError
import com.yawnandpawn.app.core.onboarding.OnboardingTestAlarm
import com.yawnandpawn.app.core.reliability.StoredReliability
import com.yawnandpawn.app.testing.FakeAlarmScheduler
import com.yawnandpawn.app.testing.OnboardingPresets
import com.yawnandpawn.app.testing.SchedulerCall
import com.yawnandpawn.app.testing.anAlarm
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * Story 5.12: onboarding's step 8 on fakes: "Ring a test alarm" rings the first alarm as a Story 1.18 test through 5.4's
 * `ChecklistTestAlarm`, its ring time is stored, and the result is derived from it and 5.3's locked pass (completed,
 * waiting, not locked); "Skip for now" completes with Home's note; Back skips step 7 for a user already asked; a process
 * death keeps the derived state; failures stay on the step, logged.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OnboardingTestAlarmStepTest : OnboardingViewModelFixture() {
    /** When a test rung at [lateEvening] rings. */
    private val ringsAt: Instant = lateEvening + 10.seconds

    /** A user on step 8 whose first alarm ("alarm-1", 6:30, switched off) and another, sooner alarm are stored. */
    private suspend fun onTestStep() {
        alarms.upsert(anAlarm(id = FIRST, time = LocalTime(6, 30), enabled = false, requestCode = RequestCodes.FIRST_ALARM))
        alarms.upsert(anAlarm(id = "other", time = LocalTime(23, 50), requestCode = RequestCodes.FIRST_ALARM + 1))
        onStep(OnboardingStep.TestAlarm, alarmId = FIRST)
    }

    private fun TestScope.ringOnStep8(): OnboardingViewModel {
        val viewModel = viewModel()
        settle()
        viewModel.onIntent(OnboardingIntent.RingTestAlarm)
        return viewModel
    }

    private val testsArmed: Int
        get() = testArming.calls.count { it is SchedulerCall.ScheduleTest }

    @Test
    fun `Ring rings the first alarm as a test in 10 s, stores its ring time, and is disabled while it waits`() =
        runTest {
            onTestStep()
            val viewModel = viewModel()
            assertEquals(OnboardingStep.TestAlarm, viewModel.shown.step)
            assertEquals(TestAlarmStatus.Ready, viewModel.shown.testStatus)
            assertFalse(viewModel.shown.brightWakeNote, "Bright wake screen arrives with Story 5.2")

            viewModel.onIntent(OnboardingIntent.RingTestAlarm)
            assertNull(testStore.pending, "a tap before the dwell counts for nothing")

            settle()
            viewModel.onIntent(OnboardingIntent.RingTestAlarm)

            val config = assertNotNull(testStore.pending)
            assertEquals(FIRST, config.alarmId, "the first alarm, not the sooner one")
            assertTrue(config.testMode)
            assertEquals(ringsAt.toEpochMilliseconds(), testArming.armed[RequestCodes.TEST_ALARM], "in 10 seconds")
            assertEquals(ringsAt, onboarding.current.testRingsAt)
            assertEquals(TestAlarmStatus.Waiting, viewModel.shown.testStatus)

            viewModel.onIntent(OnboardingIntent.RingTestAlarm)
            assertEquals(1, testsArmed, "no second test while one is pending")
            assertFalse(onboarding.current.completed)
            // Review fix: only the test request code was armed; the first alarm itself is untouched.
            assertEquals(listOf<SchedulerCall>(SchedulerCall.ScheduleTest(ringsAt.toEpochMilliseconds())), testArming.calls)
            val first = alarms.current.single { it.id == FIRST }
            assertFalse(first.enabled, "the test never switches the first alarm on")
            assertEquals(RequestCodes.FIRST_ALARM, first.requestCode)
            assertEquals(emptyList(), (fixture.scheduler as FakeAlarmScheduler).calls, "no real alarm was armed or cancelled")
        }

    @Test
    fun `a ring time that cannot be stored arms nothing and shows Couldn't save, so no pass is ever lost`() =
        runTest {
            onTestStep()
            val viewModel = viewModel()
            settle()
            onboarding.writeFailure = DomainError.StorageFailure("disk full")

            viewModel.onIntent(OnboardingIntent.RingTestAlarm)

            assertTrue(viewModel.shown.saveFailed, "Couldn't save. Try again.")
            assertEquals(0, testsArmed, "no test rings whose result could not be kept")
            assertNull(testStore.pending)
            assertEquals(TestAlarmStatus.Ready, viewModel.shown.testStatus)
            assertEquals(1, failed(OnboardingViewModel.REMEMBER_TEST).size)

            onboarding.writeFailure = null
            viewModel.onIntent(OnboardingIntent.RingTestAlarm)
            assertEquals(ringsAt, onboarding.current.testRingsAt, "stored before the test was armed")
            assertEquals(1, testsArmed)

            // A new screen (the session replaced this one) reads the stored ring time and takes the pass.
            records.stored = StoredReliability(lockedTestPassedAt = ringsAt + 20.seconds)
            viewModel()
            assertTrue(onboarding.current.completed)
        }

    @Test
    fun `a wall clock set back 2 min after Ring still takes the locked pass`() =
        runTest {
            onTestStep()
            ringOnStep8()

            clock.advanceBy((-2).minutes)
            records.stored = StoredReliability(lockedTestPassedAt = clock.now() + 20.seconds)

            assertTrue(onboarding.current.completed)
            assertFalse(onboarding.current.testNoteDue)
        }

    @Test
    fun `a wall clock set back while waiting never keeps the step waiting, and a clock change renders it again`() =
        runTest {
            onTestStep()
            val back = ringOnStep8()
            clock.advanceBy((-1).hours)

            testScheduler.advanceTimeBy(OnboardingTestAlarm.MAX_WAIT + 1.seconds)
            testScheduler.runCurrent()

            assertEquals(TestAlarmStatus.NotLocked, back.shown.testStatus, "the wait is over in elapsed time")

            // A clock set forward past the wait: the change signal renders the step again before the elapsed wait ends.
            clock.set(lateEvening)
            onTestStep()
            val forward = ringOnStep8()
            assertEquals(TestAlarmStatus.Waiting, forward.shown.testStatus)
            clock.advanceBy(1.minutes)

            timeChanges.emit()

            assertEquals(TestAlarmStatus.NotLocked, forward.shown.testStatus)
        }

    @Test
    fun `after a locked pass whose completion failed, Skip for now completes it as passed, without Home's note`() =
        runTest {
            onTestStep()
            val viewModel = ringOnStep8()
            onboarding.writeFailure = DomainError.StorageFailure("disk full")
            records.stored = StoredReliability(lockedTestPassedAt = ringsAt + 20.seconds)
            assertTrue(viewModel.shown.saveFailed)

            onboarding.writeFailure = null
            viewModel.onIntent(OnboardingIntent.SkipTestAlarm)

            assertTrue(onboarding.current.completed)
            assertFalse(onboarding.current.testNoteDue, "the test passed: no note")
        }

    @Test
    fun `a locked pass after the ring completes onboarding, and an older pass does not`() =
        runTest {
            onTestStep()
            records.stored = StoredReliability(lockedTestPassedAt = lateEvening - 1.hours)
            val viewModel = ringOnStep8()
            assertEquals(TestAlarmStatus.Waiting, viewModel.shown.testStatus)
            assertFalse(onboarding.current.completed, "a pass from before the ring does not count")

            // 5.3 records the pass a moment after the test's Completed.
            records.stored = StoredReliability(lockedTestPassedAt = ringsAt + 25.seconds)

            assertTrue(onboarding.current.completed)
            assertFalse(onboarding.current.testNoteDue, "no Home note after a passed test")
        }

    @Test
    fun `a ring over without a locked pass reads not locked, and Ring works again`() =
        runTest {
            onTestStep()
            val viewModel = ringOnStep8()

            clock.advanceBy(19.seconds)
            viewModel.onIntent(OnboardingIntent.Resumed)
            assertEquals(TestAlarmStatus.Waiting, viewModel.shown.testStatus, "until 10 s after the ring time")

            // No resume needed: the step renders again when the wait is over (a phone without a screen lock ends here).
            clock.advanceBy(1.seconds)
            testScheduler.advanceTimeBy(20.seconds)
            testScheduler.runCurrent()
            assertEquals(TestAlarmStatus.NotLocked, viewModel.shown.testStatus)
            assertFalse(onboarding.current.completed)

            settle()
            viewModel.onIntent(OnboardingIntent.RingTestAlarm)

            assertEquals(2, testsArmed)
            assertEquals(lateEvening + 30.seconds, onboarding.current.testRingsAt)
            assertEquals(TestAlarmStatus.Waiting, viewModel.shown.testStatus)
        }

    @Test
    fun `a test that cannot be armed shows Couldn't save, logged, and the button works again`() =
        runTest {
            onTestStep()
            testStore.putFailure = DomainError.StorageFailure("disk full")

            val viewModel = ringOnStep8()

            assertTrue(viewModel.shown.saveFailed, "Couldn't save. Try again.")
            assertEquals(TestAlarmStatus.Ready, viewModel.shown.testStatus)
            assertNull(onboarding.current.testRingsAt)
            assertEquals(1, failed(OnboardingViewModel.RING_TEST).size)

            testStore.putFailure = null
            viewModel.onIntent(OnboardingIntent.RingTestAlarm)

            assertFalse(viewModel.shown.saveFailed)
            assertEquals(TestAlarmStatus.Waiting, viewModel.shown.testStatus)
        }

    @Test
    fun `Skip for now completes with Home's note, also while a test is pending`() =
        runTest {
            onTestStep()
            val viewModel = ringOnStep8()
            assertEquals(TestAlarmStatus.Waiting, viewModel.shown.testStatus)

            viewModel.onIntent(OnboardingIntent.SkipTestAlarm)

            assertTrue(onboarding.current.completed)
            assertTrue(onboarding.current.testNoteDue)
        }

    @Test
    fun `Skip for now waits the dwell, and a failed skip stays on step 8 with Couldn't save`() =
        runTest {
            onTestStep()
            val viewModel = viewModel()
            viewModel.onIntent(OnboardingIntent.SkipTestAlarm)
            assertFalse(onboarding.current.completed, "a double tap from step 7 never skips the test")

            settle()
            onboarding.writeFailure = DomainError.StorageFailure("disk full")
            viewModel.onIntent(OnboardingIntent.SkipTestAlarm)

            assertEquals(OnboardingStep.TestAlarm, viewModel.shown.step)
            assertTrue(viewModel.shown.saveFailed)
            assertEquals(1, failed(OnboardingViewModel.COMPLETE).size)

            onboarding.writeFailure = null
            viewModel.onIntent(OnboardingIntent.SkipTestAlarm)
            assertTrue(onboarding.current.completed)
            assertTrue(onboarding.current.testNoteDue)
        }

    @Test
    fun `a completion after a locked pass that fails stays, and the next resume tries again`() =
        runTest {
            onTestStep()
            val viewModel = ringOnStep8()
            onboarding.writeFailure = DomainError.StorageFailure("disk full")

            records.stored = StoredReliability(lockedTestPassedAt = ringsAt + 20.seconds)

            assertFalse(onboarding.current.completed)
            assertTrue(viewModel.shown.saveFailed)
            assertEquals(1, failed(OnboardingViewModel.COMPLETE).size)

            onboarding.writeFailure = null
            viewModel.onIntent(OnboardingIntent.Resumed)

            assertTrue(onboarding.current.completed)
            assertFalse(onboarding.current.testNoteDue)
        }

    @Test
    fun `Back from step 8 goes to step 6 for a user already asked, and to step 7 otherwise`() =
        runTest {
            onTestStep()
            consents.setConsent(false)
            val asked = viewModel()

            asked.onIntent(OnboardingIntent.Back)

            assertEquals(OnboardingStep.Reliability, asked.shown.step, "step 7 is never asked twice")
            assertEquals("Reliability", onboarding.current.step)

            onStep(OnboardingStep.TestAlarm, alarmId = FIRST)
            consents.observeFailure = IllegalStateException("unreadable")
            val unreadable = viewModel()

            unreadable.onIntent(OnboardingIntent.Back)

            assertEquals(OnboardingStep.Analytics, unreadable.shown.step, "an unreadable choice counts as not asked")
            assertEquals(1, failed(OnboardingViewModel.LOAD_CONSENT).size)
        }

    @Test
    fun `a process death on step 8 reopens it with the state derived from the stored ring time`() =
        runTest {
            onTestStep()
            onboarding.current = onboarding.current.copy(testRingsAt = lateEvening + 5.seconds)
            assertEquals(TestAlarmStatus.Waiting, viewModel().shown.testStatus)

            onboarding.current = onboarding.current.copy(testRingsAt = lateEvening - 1.hours)
            val over = viewModel()
            assertEquals(OnboardingStep.TestAlarm, over.shown.step)
            assertEquals(TestAlarmStatus.NotLocked, over.shown.testStatus)

            // The test passed while the screen was gone (the session replaced it): onboarding completes as it opens.
            records.stored = StoredReliability(lockedTestPassedAt = lateEvening - 1.hours + 30.seconds)
            viewModel()
            assertTrue(onboarding.current.completed)
            assertFalse(onboarding.current.testNoteDue)
        }

    @Test
    fun `a failing locked-pass watch is logged, and a resume reads the pass`() =
        runTest {
            onTestStep()
            records.observeFailure = IllegalStateException("unreadable")
            val viewModel = ringOnStep8()
            assertEquals(1, failed(OnboardingViewModel.LOAD_RECORDS).size)

            records.stored = StoredReliability(lockedTestPassedAt = ringsAt + 20.seconds)
            assertFalse(onboarding.current.completed, "nothing watches the records")

            viewModel.onIntent(OnboardingIntent.Resumed)

            assertTrue(onboarding.current.completed)
        }

    @Test
    fun `a new user walks steps 7 and 8 and lands on a completed onboarding after a locked pass`() =
        runTest {
            alarms.upsert(anAlarm(id = FIRST, requestCode = RequestCodes.FIRST_ALARM))
            onboarding.current = OnboardingPresets.Consented.copy(step = "Analytics", alarmId = FIRST)
            val viewModel = viewModel()
            settle()

            viewModel.onIntent(OnboardingIntent.UsageStatsChosen(share = false))
            assertEquals(OnboardingStep.TestAlarm, viewModel.shown.step)
            settle()
            viewModel.onIntent(OnboardingIntent.RingTestAlarm)
            records.stored = StoredReliability(lockedTestPassedAt = ringsAt + 15.seconds)

            assertTrue(onboarding.current.completed)
            assertFalse(onboarding.current.testNoteDue)
        }

    private companion object {
        const val FIRST = "alarm-1"
    }
}
