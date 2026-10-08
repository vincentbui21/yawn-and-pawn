package com.yawnandpawn.app.ui.editor

import com.yawnandpawn.app.core.alarm.RequestCodes
import com.yawnandpawn.app.core.alarm.orderedEntries
import com.yawnandpawn.app.core.checks.CheckEntry
import com.yawnandpawn.app.core.checks.qr.CodeFormat
import com.yawnandpawn.app.core.checks.qr.RegisteredCode
import com.yawnandpawn.app.core.session.ScheduleTestAlarm
import com.yawnandpawn.app.testing.AlarmUseCasesFixture
import com.yawnandpawn.app.testing.FakeAlarmRepository
import com.yawnandpawn.app.testing.FakeAlarmScheduler
import com.yawnandpawn.app.testing.FakeCheckConfigRepository
import com.yawnandpawn.app.testing.FakeClock
import com.yawnandpawn.app.testing.FakeLogger
import com.yawnandpawn.app.testing.FakeNotificationPermission
import com.yawnandpawn.app.testing.FakeRequestCodeSequence
import com.yawnandpawn.app.testing.FakeSoundLibrary
import com.yawnandpawn.app.testing.FakeSoundPreview
import com.yawnandpawn.app.testing.FakeTestAlarmStore
import com.yawnandpawn.app.testing.FakeTimeZoneProvider
import com.yawnandpawn.app.testing.anAlarm
import com.yawnandpawn.app.testing.checkConfigsOf
import com.yawnandpawn.app.ui.checks.CheckType
import com.yawnandpawn.app.ui.checks.PickableCheckTypes
import com.yawnandpawn.app.ui.checksetup.CheckSetupIntent
import com.yawnandpawn.app.ui.home.AlarmActions
import com.yawnandpawn.app.ui.qr.CameraProblem
import com.yawnandpawn.app.ui.qr.ScanEvent
import com.yawnandpawn.app.ui.qr.ScanResult
import com.yawnandpawn.app.ui.qr.TestCameraPermission
import com.yawnandpawn.app.ui.wake.CheckContent
import com.yawnandpawn.app.ui.wake.WakeIntent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.datetime.LocalTime
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant
import com.yawnandpawn.app.core.checks.CheckType as CoreCheckType
import com.yawnandpawn.app.core.checks.Difficulty as CoreDifficulty

/**
 * Story 3.10 on Story 3.5's editor: picking QR/Barcode asks for the camera, "Your code" registers a code into the form,
 * Save refuses QR/Barcode without a code, "Try it" checks the camera's codes against it, and Home's "Re-register" opens
 * straight on QR registration and saves the new code.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AlarmEditorQrTest {
    private val dispatcher = StandardTestDispatcher()
    private val clock = FakeClock(Instant.parse("2027-03-03T06:00:00Z"))
    private val repository = FakeAlarmRepository()
    private val logger = FakeLogger()
    private val checkRows = FakeCheckConfigRepository(repository)
    private val scheduler = FakeAlarmScheduler()
    private val alarms =
        AlarmUseCasesFixture(
            scheduler = scheduler,
            repository = repository,
            clock = clock,
            requestCodes = FakeRequestCodeSequence(lastUsed = RequestCodes.FIRST_ALARM),
            checkConfigs = checkRows,
        )
    private val toothpaste = ScanResult(CodeFormat.Ean13, "4006381333931")
    private val cereal = ScanResult(CodeFormat.Ean13, "5901234123457")
    private val code = toothpaste.code!!

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel(
        permission: TestCameraPermission = TestCameraPermission(),
        alarmId: String? = null,
        scanCode: Boolean = false,
    ) = AlarmEditorViewModel(
        alarmId = alarmId,
        repository = repository,
        checkConfigs = checkRows,
        saveAlarm = alarms.save,
        clock = clock,
        timeZoneProvider = FakeTimeZoneProvider(),
        actions = AlarmActions(alarms.setEnabled, alarms.delete, clock, logger),
        soundLibrary = FakeSoundLibrary(),
        soundPreview = FakeSoundPreview(),
        notificationPermission = FakeNotificationPermission(),
        testAlarm = ScheduleTestAlarm(FakeAlarmScheduler(), FakeTestAlarmStore(), clock, logger),
        cameraPermission = permission,
        scanCode = scanCode,
        reRegisterCode = alarms.reRegisterCode,
    )

    private fun TestScope.effectsOf(viewModel: AlarmEditorViewModel): List<EditorEffect> {
        val effects = mutableListOf<EditorEffect>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.effects.toList(effects) }
        return effects
    }

    private fun AlarmEditorViewModel.qr(): CheckChip? =
        state.value.form.checks
            .firstOrNull { it.type == CheckType.QrBarcode }

    @Test
    fun `QR-Barcode is offered, and picking it asks for the camera only when missing, then Fix opens the settings`() =
        runTest(dispatcher) {
            assertTrue(CheckType.QrBarcode in PickableCheckTypes, "Story 3.10 makes it pickable")

            val granted = TestCameraPermission(granted = true)
            val allowed = viewModel(granted)
            allowed.onIntent(EditorIntent.CheckToggled(CheckType.QrBarcode, selected = true))
            assertNotNull(allowed.qr())
            assertEquals(0, granted.requests)

            val denied = TestCameraPermission(granted = false, answer = false)
            val refused = viewModel(denied)
            refused.onIntent(EditorIntent.CheckToggled(CheckType.QrBarcode, selected = true))
            advanceUntilIdle()
            assertEquals(null, refused.qr(), "the card stays unselected")
            assertEquals(
                true,
                refused.state.value.full
                    ?.cameraUnavailable,
                "Camera isn't available. with Fix",
            )
            assertEquals(1, denied.requests)
            refused.onIntent(EditorIntent.FixCamera)
            assertEquals(1, denied.settingsOpened)

            val answered = TestCameraPermission(granted = false, answer = true)
            val asked = viewModel(answered)
            asked.onIntent(EditorIntent.CheckToggled(CheckType.QrBarcode, selected = true))
            advanceUntilIdle()
            assertNotNull(asked.qr(), "allowed in the dialog: selected")
            assertEquals(
                false,
                asked.state.value.full
                    ?.cameraUnavailable,
            )
        }

    @Test
    fun `Save without a code opens Check setup, Your code registers one into the form, and Save stores it`() =
        runTest(dispatcher) {
            val viewModel = viewModel()
            val effects = effectsOf(viewModel)
            viewModel.onIntent(EditorIntent.CheckToggled(CheckType.QrBarcode, selected = true))

            viewModel.onIntent(EditorIntent.SaveClicked)
            advanceUntilIdle()
            assertEquals(EditorPane.CheckSetup, viewModel.state.value.pane, "Scan a code to use this check.")
            assertEquals(CheckType.QrBarcode, viewModel.state.value.setupType)
            assertTrue(repository.current.isEmpty(), "nothing stored")
            assertEquals(
                false,
                viewModel.state.value
                    .checkSetupState()
                    ?.codeSaved,
            )
            assertEquals(
                false,
                viewModel.state.value
                    .checkSetupState()
                    ?.printable,
                "the printable QR waits for Epic 7",
            )

            viewModel.onIntent(EditorIntent.CheckSetup(CheckSetupIntent.ScanCodeClicked))
            assertEquals(EditorPane.ScanCode, viewModel.state.value.pane)
            viewModel.onIntent(EditorIntent.BackRequested)
            assertEquals(EditorPane.CheckSetup, viewModel.state.value.pane, "Back from registration returns to Check setup")
            viewModel.onIntent(EditorIntent.CheckSetup(CheckSetupIntent.ScanCodeClicked))
            viewModel.onIntent(EditorIntent.CodeRegistered(code))

            assertEquals(EditorPane.CheckSetup, viewModel.state.value.pane)
            assertEquals(code, viewModel.qr()?.code)
            assertEquals(
                true,
                viewModel.state.value.full
                    ?.qrCodeSaved,
            )
            assertEquals(
                true,
                viewModel.state.value
                    .checkSetupState()
                    ?.codeSaved,
                "Code saved",
            )

            viewModel.onIntent(EditorIntent.SaveClicked)
            advanceUntilIdle()
            assertEquals(listOf<EditorEffect>(EditorEffect.Close), effects)
            val stored = checkRows.current.values.single()
            assertEquals(
                listOf(
                    CheckEntry(CoreCheckType.Math, CoreDifficulty.Easy, 3),
                    CheckEntry(CoreCheckType.QrBarcode, CoreDifficulty.Medium, 1, code = code),
                ),
                stored.orderedEntries(),
            )
            assertEquals(clock.now(), stored.single { it.entry.code != null }.codeRegisteredAt, "registered now")
        }

    @Test
    fun `Try it checks the camera's codes against the form's code, sending nothing`() =
        runTest(dispatcher) {
            val viewModel = viewModel()
            viewModel.onIntent(EditorIntent.CheckToggled(CheckType.QrBarcode, selected = true))
            viewModel.onIntent(EditorIntent.CheckSetupClicked(CheckType.QrBarcode))
            viewModel.onIntent(EditorIntent.CodeRegistered(code))
            viewModel.onIntent(EditorIntent.CheckSetup(CheckSetupIntent.TryItClicked))
            assertEquals(EditorPane.TryIt, viewModel.state.value.pane)
            assertIs<CheckContent.QrBarcode>(
                viewModel.state.value.tryIt
                    ?.content,
            )

            viewModel.onIntent(EditorIntent.TryItScanned(ScanEvent.Detected(cereal)))
            val wrong =
                assertIs<CheckContent.QrBarcode>(
                    viewModel.state.value.tryIt
                        ?.content,
                )
            assertTrue(wrong.wrongCode)
            assertEquals(1, wrong.wrongAttempts)
            viewModel.onIntent(EditorIntent.TryItScanned(ScanEvent.Detected(cereal)))
            assertEquals(
                1,
                assertIs<CheckContent.QrBarcode>(
                    viewModel.state.value.tryIt
                        ?.content,
                ).wrongAttempts,
                "held up: once",
            )
            viewModel.onIntent(EditorIntent.TryIt(WakeIntent.TorchToggled))
            assertTrue(
                assertIs<CheckContent.QrBarcode>(
                    viewModel.state.value.tryIt
                        ?.content,
                ).torchOn,
            )

            viewModel.onIntent(EditorIntent.TryItScanned(ScanEvent.Detected(listOf(cereal, toothpaste))))
            assertEquals(
                true,
                viewModel.state.value.tryIt
                    ?.done,
                "the registered code among two in view",
            )
            assertTrue(repository.current.isEmpty(), "nothing stored")
        }

    @Test
    fun `Try it without the camera says so`() =
        runTest(dispatcher) {
            val viewModel = viewModel()
            viewModel.onIntent(EditorIntent.CheckToggled(CheckType.QrBarcode, selected = true))
            viewModel.onIntent(EditorIntent.CheckSetupClicked(CheckType.QrBarcode))
            viewModel.onIntent(EditorIntent.CodeRegistered(code))
            viewModel.onIntent(EditorIntent.CheckSetup(CheckSetupIntent.TryItClicked))

            viewModel.onIntent(EditorIntent.TryItScanned(ScanEvent.CameraUnavailable(CameraProblem.Disconnected)))

            assertFalse(
                assertIs<CheckContent.QrBarcode>(
                    viewModel.state.value.tryIt
                        ?.content,
                ).cameraAvailable,
            )
        }

    /** The alarm "a", stored with Math and QR/Barcode (its code registered 30 days ago), [enabled] or not. */
    private suspend fun storedWithCode(enabled: Boolean = true): Instant {
        repository.upsert(anAlarm(id = "a", requestCode = 1001, enabled = enabled))
        val registered = clock.now() - 30.days
        val entries =
            listOf(
                CheckEntry(CoreCheckType.Math, CoreDifficulty.Medium, 3),
                CheckEntry(CoreCheckType.QrBarcode, CoreDifficulty.Medium, 1, code = code),
            )
        checkRows.saveWithAlarm(anAlarm(id = "a", requestCode = 1001, enabled = enabled), checkConfigsOf("a", entries, at = registered))
        return registered
    }

    private fun storedQr() = checkRows.current.getValue("a").single { it.entry.type == CoreCheckType.QrBarcode }

    @Test
    fun `Re-register opens on QR registration, and Back leaves the alarm unchanged`() =
        runTest(dispatcher) {
            val registered = storedWithCode()

            val back = viewModel(alarmId = "a", scanCode = true)
            val backEffects = effectsOf(back)
            advanceUntilIdle()
            assertEquals(EditorPane.ScanCode, back.state.value.pane)
            back.onIntent(EditorIntent.BackRequested)
            advanceUntilIdle()

            assertEquals(listOf<EditorEffect>(EditorEffect.Close), backEffects)
            assertEquals(registered, storedQr().codeRegisteredAt)
        }

    @Test
    fun `Re-register of the same code restarts the count (review fix)`() =
        runTest(dispatcher) {
            storedWithCode()
            val viewModel = viewModel(alarmId = "a", scanCode = true)
            val effects = effectsOf(viewModel)
            advanceUntilIdle()

            viewModel.onIntent(EditorIntent.CodeRegistered(code))
            advanceUntilIdle()

            assertEquals(listOf<EditorEffect>(EditorEffect.Close), effects)
            assertEquals(code, storedQr().entry.code)
            assertEquals(clock.now(), storedQr().codeRegisteredAt, "the same sticker scanned again counts as registered now")
        }

    @Test
    fun `Re-register stores the code alone, so a disabled alarm stays disabled and nothing is armed (review fix)`() =
        runTest(dispatcher) {
            storedWithCode(enabled = false)
            val viewModel = viewModel(alarmId = "a", scanCode = true)
            advanceUntilIdle()
            scheduler.clearCalls()
            val newCode = assertNotNull(RegisteredCode.of(CodeFormat.QrCode, "hallway"))

            viewModel.onIntent(EditorIntent.CodeRegistered(newCode))
            advanceUntilIdle()

            assertEquals(newCode, storedQr().entry.code)
            assertEquals(clock.now(), storedQr().codeRegisteredAt, "a new code restarts the count")
            assertFalse(repository.current.single().enabled, "still disabled")
            assertEquals(emptyList(), scheduler.calls, "no arming")
        }

    @Test
    fun `a stale Re-register on an alarm without QR-Barcode is an ordinary edit (review fix)`() =
        runTest(dispatcher) {
            repository.upsert(anAlarm(id = "b", requestCode = 1002))
            val viewModel = viewModel(alarmId = "b", scanCode = true)
            val effects = effectsOf(viewModel)
            advanceUntilIdle()
            assertEquals(EditorPane.Main, viewModel.state.value.pane)
            viewModel.onIntent(EditorIntent.TimeChanged(LocalTime(9, 30)))

            viewModel.onIntent(EditorIntent.BackRequested)

            assertTrue(viewModel.state.value.showDiscardDialog, "Back still asks Discard changes?")
            assertEquals(emptyList(), effects)
        }
}
