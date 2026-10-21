package com.yawnandpawn.app.ui.onboarding

import com.yawnandpawn.app.core.alarm.AlarmDraft
import com.yawnandpawn.app.core.billing.PriceCatalog
import com.yawnandpawn.app.core.billing.PriceCatalogSnapshot
import com.yawnandpawn.app.core.config.SetBaseFee
import com.yawnandpawn.app.core.config.SettingValue
import com.yawnandpawn.app.core.error.DomainError
import com.yawnandpawn.app.core.error.Outcome
import com.yawnandpawn.app.core.onboarding.OnboardingProgress
import com.yawnandpawn.app.core.telemetry.AnalyticsConsent
import com.yawnandpawn.app.testing.AlarmUseCasesFixture
import com.yawnandpawn.app.testing.OnboardingPresets
import com.yawnandpawn.app.testing.anAlarm
import com.yawnandpawn.app.ui.checkpicker.CheckPickerIntent
import com.yawnandpawn.app.ui.checks.CheckType
import com.yawnandpawn.app.ui.reliability.ChecklistItem
import com.yawnandpawn.app.ui.settings.WeakeningNote
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import com.yawnandpawn.app.core.reliability.ChecklistRow as CoreRow

/**
 * Story 5.10: onboarding steps 1 to 3 with the real core use cases on fakes: the stored step, the consent, the base fee
 * through `SetBaseFee` (Play prices or the USD approximation), the dwell before a step's action, and the logged
 * failures; and that the intents of a step not shown are ignored.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OnboardingViewModelTest : OnboardingViewModelFixture() {
    @Test
    fun `a first launch opens step 1, and Let's set it up opens the disclosure and stores the step`() =
        runTest {
            val viewModel = viewModel()
            assertEquals(OnboardingStep.Mission, viewModel.shown.step)

            viewModel.next()

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

            onboarding.current = OnboardingPresets.Consented.copy(step = "Reliability")
            assertEquals(OnboardingStep.FirstAlarm, viewModel().shown.step, "past the checks without a first alarm: step 4")

            onboarding.current = OnboardingPresets.Consented.copy(step = "TestAlarm", alarmId = "alarm-1")
            assertEquals(OnboardingStep.Analytics, viewModel().shown.step, "step 8 is not built yet")
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

            viewModel.next()

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

            viewModel.next()

            assertEquals(OnboardingStep.Disclosure, viewModel.shown.step)
            assertNull(onboarding.current.disclosureAcceptedAt)
            assertEquals(1, failed(OnboardingViewModel.ACCEPT_DISCLOSURE).size)

            onboarding.writeFailure = null
            viewModel.next()
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

            viewModel.next()

            assertEquals(OnboardingStep.FirstAlarm, viewModel.shown.step)
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
            assertEquals(WeakeningNote(LocalTime(7, 30), today = false), viewModel.shown.baseFeeNote, "Settings' pending note")

            viewModel.onIntent(OnboardingIntent.RaiseBaseFee)
            assertNull(viewModel.shown.baseFeeNote, "raising applies at once")
        }

    @Test
    fun `a fee saved at once shows no pending note`() =
        runTest {
            onboarding.current = OnboardingPresets.Consented
            val viewModel = viewModel()

            viewModel.onIntent(OnboardingIntent.RaiseBaseFee)

            assertNull(viewModel.shown.baseFeeNote)
        }

    @Test
    fun `Continue on step 3 opens the first alarm and stores the step, without completing onboarding`() =
        runTest {
            onboarding.current = OnboardingPresets.Consented
            val viewModel = viewModel()

            viewModel.next()

            assertEquals(OnboardingStep.FirstAlarm, viewModel.shown.step)
            assertEquals("FirstAlarm", onboarding.current.step)
            assertFalse(onboarding.current.completed)
        }

    @Test
    fun `a double tap on Let's set it up opens the disclosure without accepting it`() =
        runTest {
            val viewModel = viewModel()

            // The first tap opens the disclosure; the second lands 100 ms later, from the mission's button (a step already
            // left) or from the disclosure's identical button sliding in: neither accepts it.
            viewModel.next()
            ticks.advanceBy(100.milliseconds)
            viewModel.onIntent(OnboardingIntent.Next(OnboardingStep.Mission))
            viewModel.onIntent(OnboardingIntent.Next(OnboardingStep.Disclosure))

            assertEquals(OnboardingStep.Disclosure, viewModel.shown.step)
            assertNull(onboarding.current.disclosureAcceptedAt, "the disclosure was never skipped")

            ticks.advanceBy(OnboardingViewModel.STEP_DWELL_MILLIS.milliseconds)
            viewModel.onIntent(OnboardingIntent.Next(OnboardingStep.Disclosure))
            assertEquals(OnboardingStep.BaseFee, viewModel.shown.step, "a tap once the disclosure has shown counts")
        }

    @Test
    fun `a late tap on I understand never skips the base fee step`() =
        runTest {
            onboarding.current = OnboardingProgress(step = "Disclosure")
            val viewModel = viewModel()

            viewModel.next()
            ticks.advanceBy(100.milliseconds)
            viewModel.onIntent(OnboardingIntent.Next(OnboardingStep.Disclosure))
            viewModel.onIntent(OnboardingIntent.Next(OnboardingStep.BaseFee))

            assertEquals(OnboardingStep.BaseFee, viewModel.shown.step)
            assertFalse(onboarding.current.completed, "the fee step was never skipped")
        }

    @Test
    fun `a restored step also waits the dwell time before its action counts`() =
        runTest {
            onboarding.current = OnboardingPresets.Consented
            val viewModel = viewModel()

            viewModel.onIntent(OnboardingIntent.Next(OnboardingStep.BaseFee))
            assertEquals(OnboardingStep.BaseFee, viewModel.shown.step)

            viewModel.next()
            assertEquals(OnboardingStep.FirstAlarm, viewModel.shown.step)
        }

    @Test
    fun `a failed I understand shows Couldn't save, and the next attempt clears it`() =
        runTest {
            onboarding.current = OnboardingProgress(step = "Disclosure")
            val viewModel = viewModel()
            onboarding.writeFailure = DomainError.StorageFailure("disk full")

            viewModel.next()
            assertTrue(viewModel.shown.saveFailed)

            onboarding.writeFailure = null
            viewModel.next()
            assertFalse(viewModel.shown.saveFailed)
            assertEquals(OnboardingStep.BaseFee, viewModel.shown.step)
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
            val viewModel = viewModel(priceCatalog = broken)

            assertTrue(viewModel.shown.pricesApproximate)
            assertEquals(1, failed(OnboardingViewModel.LOAD_PRICES).size)
        }

    @Test
    fun `the intents of another step, and of step 8, are ignored`() =
        runTest {
            val viewModel = viewModel()

            viewModel.onIntent(OnboardingIntent.TimeChanged(LocalTime(6, 0)))
            viewModel.onIntent(OnboardingIntent.Checks(CheckPickerIntent.Toggled(CheckType.Math, selected = false)))
            viewModel.onIntent(OnboardingIntent.FixClicked(ChecklistItem.Notifications))
            viewModel.onIntent(OnboardingIntent.UsageStatsChosen(share = true))
            viewModel.onIntent(OnboardingIntent.SkipTestAlarm)

            assertEquals(OnboardingStep.Mission, viewModel.shown.step)
            assertEquals(OnboardingPresets.Fresh, onboarding.current)
            assertEquals(emptyList<CoreRow>(), systemScreens.opened)
            assertEquals(AnalyticsConsent(), consents.current)
            assertEquals(0, editorsOpened, "the editor opens with step 4")
        }
}
