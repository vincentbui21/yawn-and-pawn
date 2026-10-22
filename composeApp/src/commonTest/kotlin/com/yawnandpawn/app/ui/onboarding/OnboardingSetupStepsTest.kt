package com.yawnandpawn.app.ui.onboarding

import com.yawnandpawn.app.core.alarm.Alarm
import com.yawnandpawn.app.core.alarm.AlarmDraft
import com.yawnandpawn.app.core.error.DomainError
import com.yawnandpawn.app.core.error.Outcome
import com.yawnandpawn.app.core.reliability.ReliabilityStatus
import com.yawnandpawn.app.core.telemetry.AnalyticsConsent
import com.yawnandpawn.app.testing.FakeAlarmScheduler
import com.yawnandpawn.app.ui.checkpicker.CheckPickerIntent
import com.yawnandpawn.app.ui.checks.CheckType
import com.yawnandpawn.app.ui.checksetup.CheckSetupIntent
import com.yawnandpawn.app.ui.editor.CheckMode
import com.yawnandpawn.app.ui.editor.EditorIntent
import com.yawnandpawn.app.ui.editor.EditorPane
import com.yawnandpawn.app.ui.editor.RepeatChoice
import com.yawnandpawn.app.ui.format.Weekdays
import com.yawnandpawn.app.ui.qr.TestCameraPermission
import com.yawnandpawn.app.ui.reliability.ChecklistItem
import com.yawnandpawn.app.ui.reliability.ChecklistRow
import com.yawnandpawn.app.ui.reliability.ItemStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.datetime.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import com.yawnandpawn.app.core.checks.CheckType as CoreCheckType
import com.yawnandpawn.app.core.reliability.ChecklistRow as CoreRow

/**
 * Story 5.11: onboarding steps 4 to 7 on fakes: the first alarm through the app's editor and `SaveAlarm` (exactly one
 * alarm, even after Back or a process death), Check setup over step 5, Story 5.4's checklist and its "Fix" targets,
 * the usage stats choice (asked once) leading to step 8 (Story 5.12), the dwell, and the logged failures.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OnboardingSetupStepsTest : OnboardingViewModelFixture() {
    @Test
    fun `step 4 opens the editor's new alarm at 7 00 with the Settings defaults, and a passed time rings tomorrow`() =
        runTest {
            settings.setDefaultSnoozeLength(10)
            onStep(OnboardingStep.FirstAlarm)

            val viewModel = viewModel()

            val shown = viewModel.shown
            assertEquals(OnboardingStep.FirstAlarm, shown.step)
            assertEquals(LocalTime(7, 0), shown.alarmTime)
            assertEquals(RepeatChoice.Once, shown.repeatChoice)
            assertEquals(LocalTime(7, 0), shown.ringsTomorrowAt, "at 23:40 a one-time 7:00 rings tomorrow")
            assertEquals(listOf(CheckType.Math), shown.checks.checks.map { it.type })
            assertEquals(1, editorsOpened)
        }

    @Test
    fun `steps 3 to 7 with No thanks store exactly one enabled alarm with the choices and the defaults, then open step 8`() =
        runTest {
            settings.setDefaultSnoozeLength(10)
            settings.setDefaultGraceSeconds(25)
            settings.setDefaultVibrateInGrace(false)
            onStep(OnboardingStep.BaseFee)
            val viewModel = viewModel()

            viewModel.next()
            assertEquals(OnboardingStep.FirstAlarm, viewModel.shown.step)
            viewModel.onIntent(OnboardingIntent.TimeChanged(LocalTime(6, 30)))
            viewModel.onIntent(OnboardingIntent.RepeatChosen(RepeatChoice.Weekdays))
            assertEquals(LocalTime(6, 30), viewModel.shown.alarmTime)
            assertEquals(Weekdays, viewModel.shown.repeatDays)
            assertNull(viewModel.shown.ringsTomorrowAt)

            viewModel.next()
            assertEquals(OnboardingStep.Checks, viewModel.shown.step)
            assertEquals("Checks", onboarding.current.step)
            viewModel.onIntent(OnboardingIntent.Checks(CheckPickerIntent.Toggled(CheckType.Math, selected = false)))
            assertTrue(
                viewModel.shown.checks.checks
                    .isEmpty(),
            )
            assertTrue(viewModel.shown.checks.noCheckError, "Pick at least one check.")
            viewModel.next()
            assertEquals(OnboardingStep.Checks, viewModel.shown.step, "no check: nothing is saved")
            assertTrue(alarms.current.isEmpty())

            viewModel.onIntent(OnboardingIntent.Checks(CheckPickerIntent.Toggled(CheckType.Math, selected = true)))
            assertFalse(viewModel.shown.checks.noCheckError)
            viewModel.next()

            assertEquals(OnboardingStep.Reliability, viewModel.shown.step)
            val saved = alarms.current.single()
            assertEquals(saved.id, onboarding.current.alarmId)
            assertEquals("Reliability", onboarding.current.step)
            assertEquals(1, notifications.requests, "the notification ask after the first save")

            // Back to the checks and the time edits the same alarm.
            viewModel.onIntent(OnboardingIntent.Back)
            assertEquals(OnboardingStep.Checks, viewModel.shown.step)
            viewModel.onIntent(OnboardingIntent.Back)
            assertEquals(OnboardingStep.FirstAlarm, viewModel.shown.step)
            assertEquals(LocalTime(6, 30), viewModel.shown.alarmTime, "the stored alarm opens")
            viewModel.onIntent(OnboardingIntent.TimeChanged(LocalTime(6, 15)))
            viewModel.next()
            viewModel.next()
            assertEquals(OnboardingStep.Reliability, viewModel.shown.step)

            viewModel.next()
            assertEquals(OnboardingStep.Analytics, viewModel.shown.step)
            assertEquals("Analytics", onboarding.current.step)
            settle()
            viewModel.onIntent(OnboardingIntent.UsageStatsChosen(share = false))

            assertEquals(OnboardingStep.TestAlarm, viewModel.shown.step, "Story 5.12: step 8, not Home")
            assertFalse(onboarding.current.completed)
            assertEquals(AnalyticsConsent(consent = false, asked = true), consents.current)
            assertOneAlarmWithTheChoices(saved.id)
            assertEquals(1, notifications.requests, "asked once")
        }

    /** Exactly one enabled alarm [id] is stored: 6:15 on weekdays, Math, and the Settings defaults set above. */
    private fun assertOneAlarmWithTheChoices(id: String) {
        val stored = alarms.current.single()
        assertEquals(id, stored.id)
        assertTrue(stored.enabled)
        assertEquals(LocalTime(6, 15), stored.time)
        assertEquals(Weekdays, stored.repeatDays)
        assertEquals(10, stored.snoozeLengthMinutes)
        assertEquals(25, stored.graceSeconds)
        assertFalse(stored.vibrateInGrace)
        assertEquals(listOf(CoreCheckType.Math), checkRows.current.getValue(stored.id).map { it.entry.type })
    }

    @Test
    fun `an alarm saved before its id was stored is adopted on restore, so Back and Continue keep one alarm`() =
        runTest {
            onStep(OnboardingStep.Reliability)
            val saved = assertIs<Outcome.Success<Alarm>>(fixture.save(AlarmDraft(time = LocalTime(6, 45)))).value

            val viewModel = viewModel()

            assertEquals(OnboardingStep.Reliability, viewModel.shown.step, "the saved alarm counts as the first alarm")
            assertEquals(saved.id, onboarding.current.alarmId, "and its id is remembered")
            viewModel.onIntent(OnboardingIntent.Back)
            assertEquals(LocalTime(6, 45), viewModel.shown.alarmTime)
            viewModel.next()

            assertEquals(OnboardingStep.Reliability, viewModel.shown.step)
            assertEquals(saved.id, alarms.current.single().id)
        }

    @Test
    fun `a first alarm that could not be read once keeps its id, and the next Continue edits it`() =
        runTest {
            onStep(OnboardingStep.Checks)
            val viewModel = viewModel()
            viewModel.next()
            val id = alarms.current.single().id

            // Back to step 5 while the store cannot be read: nothing is forgotten, nothing new opens.
            alarms.failure = DomainError.StorageFailure("busy")
            viewModel.onIntent(OnboardingIntent.Back)
            assertEquals(OnboardingStep.Checks, viewModel.shown.step)
            assertEquals(id, onboarding.current.alarmId)
            alarms.failure = null

            viewModel.next()
            assertTrue(viewModel.shown.saveFailed, "Couldn't save. Try again., and the alarm is read again")
            viewModel.next()

            assertEquals(OnboardingStep.Reliability, viewModel.shown.step)
            assertEquals(id, alarms.current.single().id)
            assertEquals(id, onboarding.current.alarmId)
        }

    @Test
    fun `a plain close of the first alarm's editor opens it again and never reports it gone`() =
        runTest(StandardTestDispatcher()) {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            onStep(OnboardingStep.FirstAlarm)
            val stored = assertIs<Outcome.Success<Alarm>>(fixture.save(AlarmDraft(time = LocalTime(6, 45)))).value
            val gone = mutableListOf<String?>()
            val firstAlarm =
                OnboardingFirstAlarm(
                    editors = ::editor,
                    // The test's own scope (not backgroundScope), so advanceUntilIdle runs what the close starts.
                    scope = this,
                    events =
                        object : OnboardingFirstAlarm.Events {
                            override fun saved(id: String) = Unit

                            override fun gone(id: String?) {
                                gone += id
                            }

                            override fun saveFailed() = Unit
                        },
                )
            firstAlarm.open(stored.id)
            advanceUntilIdle()
            val opened = editorsOpened

            // Back on the editor's main screen with nothing changed: the editor closes, the alarm is untouched.
            firstAlarm.send(EditorIntent.BackRequested)
            advanceUntilIdle()

            assertEquals(emptyList<String?>(), gone, "a plain close is not a deleted alarm")
            assertEquals(opened + 1, editorsOpened, "the same alarm opens again")
            assertEquals(
                LocalTime(6, 45),
                firstAlarm.state.value
                    ?.form
                    ?.time,
            )
            firstAlarm.close()
        }

    @Test
    fun `QR-Barcode without a code opens Check setup over step 5 and saves nothing, and with its code the alarm saves`() =
        runTest {
            onStep(OnboardingStep.Checks)
            val viewModel = viewModel()
            viewModel.onIntent(OnboardingIntent.Checks(CheckPickerIntent.Toggled(CheckType.QrBarcode, selected = true)))
            assertNull(viewModel.shown.checkSetup)

            viewModel.next()

            val setup = assertNotNull(viewModel.shown.checkSetup, "Scan a code to use this check.")
            assertEquals(EditorPane.CheckSetup, setup.pane)
            assertEquals(CheckType.QrBarcode, setup.setupType)
            assertEquals(OnboardingStep.Checks, viewModel.shown.step)
            assertTrue(alarms.current.isEmpty())

            viewModel.onIntent(OnboardingIntent.CheckSetup(EditorIntent.CheckSetup(CheckSetupIntent.ScanCodeClicked)))
            assertEquals(EditorPane.ScanCode, viewModel.shown.checkSetup?.pane)
            viewModel.onIntent(OnboardingIntent.CheckSetup(EditorIntent.CodeRegistered(code)))
            assertEquals(EditorPane.CheckSetup, viewModel.shown.checkSetup?.pane)
            viewModel.onIntent(OnboardingIntent.CheckSetup(EditorIntent.BackRequested))
            assertNull(viewModel.shown.checkSetup, "Back returns to the checks")
            assertTrue(viewModel.shown.checks.qrCodeSaved)

            viewModel.next()

            assertEquals(OnboardingStep.Reliability, viewModel.shown.step)
            val qr = checkRows.current.getValue(alarms.current.single().id).single { it.entry.type == CoreCheckType.QrBarcode }
            assertEquals(code, qr.entry.code)
        }

    @Test
    fun `a selected check's row opens its Check setup, Random-All shows, and an editor tap with no overlay does nothing`() =
        runTest {
            onStep(OnboardingStep.Checks)
            val viewModel = viewModel()
            viewModel.onIntent(OnboardingIntent.Checks(CheckPickerIntent.Toggled(CheckType.QrBarcode, selected = true)))

            viewModel.onIntent(OnboardingIntent.Checks(CheckPickerIntent.ModeSelected(CheckMode.All)))
            assertEquals(CheckMode.All, viewModel.shown.checks.mode)

            // A late tap on an overlay that has slid out never reaches the editor's main screen.
            viewModel.onIntent(OnboardingIntent.CheckSetup(EditorIntent.BackRequested))
            viewModel.onIntent(OnboardingIntent.CheckSetup(EditorIntent.BackRequested))
            assertNull(viewModel.shown.checkSetup)
            assertNull(onboarding.current.alarmId, "the editor was never closed as if its alarm were gone")

            viewModel.onIntent(OnboardingIntent.Checks(CheckPickerIntent.SetupClicked(CheckType.Math)))
            val setup = assertNotNull(viewModel.shown.checkSetup)
            assertEquals(EditorPane.CheckSetup, setup.pane)
            assertEquals(CheckType.Math, setup.setupType)

            viewModel.onIntent(OnboardingIntent.CheckSetup(EditorIntent.BackRequested))
            assertNull(viewModel.shown.checkSetup)
            assertEquals(OnboardingStep.Checks, viewModel.shown.step)
            assertEquals(CheckMode.All, viewModel.shown.checks.mode, "the form is kept")
        }

    @Test
    fun `picking QR-Barcode asks for the camera, and refused it shows Camera isn't available with Fix`() =
        runTest {
            camera = TestCameraPermission(granted = false, answer = false)
            onStep(OnboardingStep.Checks)
            val viewModel = viewModel()

            viewModel.onIntent(OnboardingIntent.Checks(CheckPickerIntent.Toggled(CheckType.QrBarcode, selected = true)))

            assertEquals(1, camera.requests)
            assertTrue(viewModel.shown.checks.cameraUnavailable)
            assertTrue(
                viewModel.shown.checks.checks
                    .none { it.type == CheckType.QrBarcode },
            )
            viewModel.onIntent(OnboardingIntent.Checks(CheckPickerIntent.FixCamera))
            assertEquals(1, camera.settingsOpened)
        }

    @Test
    fun `a failed save stays on step 5 with Couldn't save, and a second Continue saves`() =
        runTest {
            onStep(OnboardingStep.Checks)
            val viewModel = viewModel()
            alarms.failure = DomainError.StorageFailure("disk full")

            viewModel.next()

            assertEquals(OnboardingStep.Checks, viewModel.shown.step)
            assertTrue(viewModel.shown.saveFailed, "Couldn't save. Try again.")
            assertNull(onboarding.current.alarmId)

            alarms.failure = null
            viewModel.next()

            assertEquals(OnboardingStep.Reliability, viewModel.shown.step)
            assertEquals(1, alarms.current.size)
        }

    @Test
    fun `after a process death on step 6 Back edits the stored first alarm, still one alarm`() =
        runTest {
            onStep(OnboardingStep.Checks)
            viewModel().next()
            val id = alarms.current.single().id
            assertTrue((fixture.scheduler as FakeAlarmScheduler).armed.isNotEmpty(), "the first alarm is armed")
            assertEquals("Reliability", onboarding.current.step)
            assertEquals(id, onboarding.current.alarmId)

            val reopened = viewModel()

            assertEquals(OnboardingStep.Reliability, reopened.shown.step)
            reopened.onIntent(OnboardingIntent.Back)
            assertEquals(OnboardingStep.Checks, reopened.shown.step)
            reopened.onIntent(OnboardingIntent.Back)
            assertEquals(LocalTime(7, 0), reopened.shown.alarmTime, "the stored alarm opens")
            reopened.onIntent(OnboardingIntent.TimeChanged(LocalTime(6, 15)))
            reopened.next()
            reopened.next()

            assertEquals(OnboardingStep.Reliability, reopened.shown.step)
            val stored = alarms.current.single()
            assertEquals(id, stored.id)
            assertEquals(LocalTime(6, 15), stored.time)
        }

    @Test
    fun `a first alarm deleted meanwhile, with no alarm stored, opens a new alarm and forgets the id`() =
        runTest {
            onStep(OnboardingStep.Checks, alarmId = "gone")

            val viewModel = viewModel()

            assertEquals(OnboardingStep.Checks, viewModel.shown.step)
            assertNull(onboarding.current.alarmId)
            assertEquals(2, editorsOpened, "a new alarm opened in its place")
            viewModel.next()
            assertEquals(OnboardingStep.Reliability, viewModel.shown.step)
            assertEquals(alarms.current.single().id, onboarding.current.alarmId)
        }

    @Test
    fun `step 6 shows the checklist screen's rows that apply, Fix opens each row's screen, and every resume re-checks`() =
        runTest {
            probe.status =
                ReliabilityStatus(
                    notificationsAllowed = false,
                    fullScreenIntentAllowed = true,
                    exactAlarmsAllowed = true,
                    batteryOptimizationIgnored = false,
                )
            onStep(OnboardingStep.Reliability, alarmId = "alarm-1")
            val viewModel = viewModel()

            assertEquals(
                listOf(
                    ChecklistRow(ChecklistItem.Notifications, ItemStatus.Missing),
                    ChecklistRow(ChecklistItem.FullScreen, ItemStatus.Ok),
                    ChecklistRow(ChecklistItem.DoNotDisturb, ItemStatus.Ok),
                    ChecklistRow(ChecklistItem.Battery, ItemStatus.Missing),
                ),
                viewModel.shown.reliability,
                "no Hidden row (exact alarms, maker, camera, microphone) and no test row",
            )

            // Before the dwell a tap on "Fix" counts for nothing (a double tap from step 5 never lands here).
            viewModel.onIntent(OnboardingIntent.FixClicked(ChecklistItem.Notifications))
            assertEquals(emptyList<CoreRow>(), systemScreens.opened)

            settle()
            viewModel.onIntent(OnboardingIntent.FixClicked(ChecklistItem.Notifications))
            viewModel.onIntent(OnboardingIntent.FixClicked(ChecklistItem.Battery))
            viewModel.onIntent(OnboardingIntent.FixClicked(ChecklistItem.DoNotDisturb))
            assertEquals(listOf(CoreRow.Notifications, CoreRow.Battery, CoreRow.DoNotDisturb), systemScreens.opened)

            probe.status = ReliabilityStatus.ALL_OK
            viewModel.onIntent(OnboardingIntent.Resumed)

            assertTrue(viewModel.shown.reliability.all { it.status == ItemStatus.Ok })
        }

    @Test
    fun `Fix on the maker row opens the guidance over step 6, and closing it re-checks`() =
        runTest {
            onStep(OnboardingStep.Reliability, alarmId = "alarm-1")
            val viewModel = viewModel()

            settle()
            viewModel.onIntent(OnboardingIntent.FixClicked(ChecklistItem.Manufacturer))
            assertTrue(viewModel.shown.manufacturerGuidance)

            val checked = probe.checks
            viewModel.onIntent(OnboardingIntent.GuidanceClosed)

            assertFalse(viewModel.shown.manufacturerGuidance)
            assertEquals(checked + 1, probe.checks)
        }

    @Test
    fun `Fix on the camera row asks for the permission first, and after a refusal opens the app's settings`() =
        runTest {
            camera = TestCameraPermission(granted = false, answer = false)
            onStep(OnboardingStep.Reliability, alarmId = "alarm-1")
            val viewModel = viewModel()
            val checked = probe.checks

            settle()
            viewModel.onIntent(OnboardingIntent.FixClicked(ChecklistItem.Camera))
            assertEquals(1, camera.requests)
            assertEquals(emptyList<CoreRow>(), systemScreens.opened)
            assertEquals(checked + 1, probe.checks, "re-checked after the ask")

            viewModel.onIntent(OnboardingIntent.FixClicked(ChecklistItem.Camera))
            assertEquals(1, camera.requests)
            assertEquals(listOf(CoreRow.Camera), systemScreens.opened, "the app's settings, through the row's own target")
        }

    @Test
    fun `a user already asked about usage stats skips step 7 and the test alarm opens`() =
        runTest {
            consents.setConsent(true)
            onStep(OnboardingStep.Reliability, alarmId = "alarm-1")
            val viewModel = viewModel()

            viewModel.next()

            assertEquals(OnboardingStep.TestAlarm, viewModel.shown.step)
            assertEquals("TestAlarm", onboarding.current.step)
            assertFalse(onboarding.current.completed)
            assertEquals(listOf(true), consents.requests, "the choice is not asked again")
            assertEquals(AnalyticsConsent(consent = true, asked = true), consents.current)
        }

    @Test
    fun `a step 7 restored after the choice was stored opens step 8 without asking again`() =
        runTest {
            consents.setConsent(false)
            onStep(OnboardingStep.Analytics, alarmId = "alarm-1")

            val viewModel = viewModel()

            assertEquals(OnboardingStep.TestAlarm, viewModel.shown.step)
            assertEquals(listOf(false), consents.requests, "the choice is not asked again")
        }

    @Test
    fun `Share turns usage stats on and opens step 8, and a failed choice stays on step 7, logged`() =
        runTest {
            onStep(OnboardingStep.Analytics, alarmId = "alarm-1")
            val viewModel = viewModel()
            assertEquals(OnboardingStep.Analytics, viewModel.shown.step)

            consents.setFailure = DomainError.StorageFailure("disk full")
            settle()
            viewModel.onIntent(OnboardingIntent.UsageStatsChosen(share = true))
            assertEquals(OnboardingStep.Analytics, viewModel.shown.step)
            assertEquals(1, failed(OnboardingViewModel.SAVE_CONSENT).size)
            assertTrue(viewModel.shown.saveFailed, "Couldn't save. Try again.")

            consents.setFailure = null
            viewModel.onIntent(OnboardingIntent.UsageStatsChosen(share = true))

            assertEquals(AnalyticsConsent(consent = true, asked = true), consents.current)
            assertEquals(OnboardingStep.TestAlarm, viewModel.shown.step)
            assertFalse(viewModel.shown.saveFailed)
            assertFalse(onboarding.current.completed)
        }

    @Test
    fun `steps 4 to 7 wait the dwell time too, so a double tap never lands on the next step's action`() =
        runTest {
            onStep(OnboardingStep.Reliability, alarmId = "alarm-1")
            val viewModel = viewModel()

            // "Continue" on step 6, then a second tap on step 7 before it has shown for the dwell time.
            viewModel.next()
            assertEquals(OnboardingStep.Analytics, viewModel.shown.step)
            viewModel.onIntent(OnboardingIntent.UsageStatsChosen(share = true))
            viewModel.onIntent(OnboardingIntent.Next(OnboardingStep.Analytics))

            assertEquals(OnboardingStep.Analytics, viewModel.shown.step)
            assertEquals(AnalyticsConsent(), consents.current, "no choice was made for the user")
            assertFalse(onboarding.current.completed)

            // Step 4 to 5 and the checks: "Continue" right after the step opened does nothing.
            onStep(OnboardingStep.FirstAlarm)
            val first = viewModel()
            first.onIntent(OnboardingIntent.Next(OnboardingStep.FirstAlarm))
            assertEquals(OnboardingStep.FirstAlarm, first.shown.step)
            first.next()
            first.onIntent(OnboardingIntent.Next(OnboardingStep.Checks))
            assertEquals(OnboardingStep.Checks, first.shown.step)
            assertTrue(alarms.current.isEmpty(), "nothing saved before step 5 settled")
        }
}
