package com.yawnandpawn.app.android.qr

import android.app.Activity
import android.print.PrintDocumentAdapter
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import com.yawnandpawn.app.MainActivity
import com.yawnandpawn.app.StopAppRule
import com.yawnandpawn.app.TestYawnAndPawnApp
import com.yawnandpawn.app.core.checks.qr.CodeFormat
import com.yawnandpawn.app.core.checks.qr.RegisteredCode
import com.yawnandpawn.app.core.log.LogEvent
import com.yawnandpawn.app.testing.FakeLogger
import com.yawnandpawn.app.ui.checks.CheckType
import com.yawnandpawn.app.ui.checks.Difficulty
import com.yawnandpawn.app.ui.editor.AlarmEditorScreen
import com.yawnandpawn.app.ui.editor.CheckChip
import com.yawnandpawn.app.ui.editor.CheckMode
import com.yawnandpawn.app.ui.editor.EditorForm
import com.yawnandpawn.app.ui.editor.EditorIntent
import com.yawnandpawn.app.ui.editor.EditorPane
import com.yawnandpawn.app.ui.editor.EditorUiState
import com.yawnandpawn.app.ui.editor.FullEditorSections
import com.yawnandpawn.app.ui.qr.CameraPermission
import com.yawnandpawn.app.ui.qr.CodeScanner
import com.yawnandpawn.app.ui.qr.QrMatrix
import com.yawnandpawn.app.ui.theme.PpsThemeMode
import com.yawnandpawn.app.ui.withScreen
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext
import org.koin.core.module.Module
import org.koin.dsl.module
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Story 7.10 review pass 1, in the app: the editor's QR registration sends "Make a printable QR" on, the printable QR
 * with a code saved asks before printing, `MainActivity` lends the printer its screen only while it is started, and a
 * print registers its code only once the print job went to print (a cancelled dialog, a print service that gives no
 * job or throws registers nothing). The system print service is replaced by [FakePrintStarter]; everything else is the
 * app's own (`AndroidQrPrinter`, ZXing, the print adapter).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-xxhdpi", application = PrintableQrAppTest.PrintingApp::class)
class PrintableQrAppTest {
    /** The test app with the print service, the camera and its permission replaced. */
    class PrintingApp : TestYawnAndPawnApp() {
        override val overrideModules: List<Module> =
            super.overrideModules +
                module {
                    single { FakePrintStarter() }
                    single { AndroidQrPrinter(ZxingQrEncoder(get()), get(), get<FakePrintStarter>()) }
                    single<CodeScanner> { FakeCodeScanner() }
                    single<CameraPermission> { FakeCameraPermission(granted = true) }
                }
    }

    /** The system print dialog, recorded: each start keeps its adapter, so a test can end the job ([PrintDocumentAdapter.onFinish]). */
    class FakePrintStarter : PrintStarter {
        var job: StartedPrint? = StartedPrint { true }
        var failure: RuntimeException? = null
        val started = mutableListOf<Pair<Activity, PrintDocumentAdapter>>()

        override fun start(
            activity: Activity,
            jobName: String,
            adapter: PrintDocumentAdapter,
        ): StartedPrint? {
            failure?.let { throw it }
            started += activity to adapter
            return job
        }
    }

    @get:Rule(order = 0)
    val stopApp = StopAppRule()

    @get:Rule(order = 1)
    val composeRule = createEmptyComposeRule()

    private val starter: FakePrintStarter get() = GlobalContext.get().get()

    private val printer: AndroidQrPrinter get() = GlobalContext.get().get()

    private val intents = mutableListOf<EditorIntent>()

    private val saved = RegisteredCode.of(CodeFormat.QrCode, "pps:00000000-0000-4000-8000-000000000001")

    /** The editor with a QR/Barcode check ([code] registered or not) on [pane], as the app's ViewModel builds it. */
    private fun qrEditor(
        pane: EditorPane,
        code: RegisteredCode? = null,
    ): EditorUiState {
        val checks = listOf(CheckChip(CheckType.QrBarcode, Difficulty.Medium, 1, code = code))
        return EditorUiState(
            form = EditorForm(checks = checks, checkMode = CheckMode.Random),
            pane = pane,
            setupType = CheckType.QrBarcode,
            full =
                FullEditorSections(
                    checks = checks,
                    checkMode = CheckMode.Random,
                    qrCodeSaved = code != null,
                    rows = setOf(EditorPane.WakeCheck),
                    types = listOf(CheckType.QrBarcode),
                ),
        )
    }

    private fun editor(
        state: EditorUiState,
        block: () -> Unit,
    ) = withScreen(
        PpsThemeMode.Light,
        content = { AlarmEditorScreen(state = state, is24Hour = false, onIntent = { intents += it }) },
        block,
    )

    private class Callbacks {
        var printed = 0
        var cancelled = 0
    }

    private fun print(
        matrix: QrMatrix,
        callbacks: Callbacks,
    ): Boolean = printer.print(matrix, "Scan this to stop your alarm.", "Yawn & Pawn", { callbacks.printed++ }, { callbacks.cancelled++ })

    private fun aMatrix(): QrMatrix = assertNotNull(printer.encode("pps:00000000-0000-4000-8000-000000000002"))

    @Test
    fun `the editor's QR registration sends Make a printable QR`() {
        editor(qrEditor(EditorPane.ScanCode)) {
            composeRule.waitForIdle()
            composeRule.onNodeWithText("Make a printable QR").performClick()
            composeRule.waitForIdle()
        }
        assertEquals(listOf<EditorIntent>(EditorIntent.PrintableQrClicked), intents.filter { it == EditorIntent.PrintableQrClicked })
    }

    @Test
    fun `the editor's printable QR with a code saved asks first and prints nothing`() {
        editor(qrEditor(EditorPane.PrintableQr, code = saved)) {
            composeRule.waitForIdle()
            composeRule.onNodeWithText("Print or save as PDF").performClick()
            composeRule.waitForIdle()
            composeRule.onNodeWithText("Replace your QR code?").assertExists()
            composeRule.onNodeWithText("The old one stops working.").assertExists()
            assertTrue(starter.started.isEmpty(), "nothing printed before Replace")

            composeRule.onNodeWithText("Keep it").performClick()
            composeRule.waitForIdle()
            composeRule.onNodeWithText("Replace your QR code?").assertDoesNotExist()
        }
        assertTrue(starter.started.isEmpty(), "Keep it prints nothing")
        assertTrue(intents.none { it is EditorIntent.CodeRegistered }, "and changes nothing")
    }

    @Test
    fun `the editor's printable QR with no code prints at once and registers once the job went to print`() {
        editor(qrEditor(EditorPane.PrintableQr)) {
            composeRule.waitForIdle()
            composeRule.onNodeWithText("Print or save as PDF").performClick()
            composeRule.waitForIdle()
            composeRule.onNodeWithText("Replace your QR code?").assertDoesNotExist()
            assertEquals(1, starter.started.size, "the print dialog opens")
            assertTrue(intents.none { it is EditorIntent.CodeRegistered }, "nothing registered while the dialog is open")

            composeRule.runOnUiThread {
                starter.started
                    .single()
                    .second
                    .onFinish()
            }
            composeRule.waitForIdle()
        }
        val registered = intents.filterIsInstance<EditorIntent.CodeRegistered>().single()
        assertEquals(CodeFormat.QrCode, registered.code.format, "registered as a scan of the sheet would be")
    }

    @Test
    fun `MainActivity lends the printer its screen only while it is started`() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val callbacks = Callbacks()
            assertTrue(print(aMatrix(), callbacks), "the print dialog opens while the app is started")
            scenario.onActivity { assertSame(it, starter.started.single().first) }

            scenario.moveToState(Lifecycle.State.CREATED)

            assertFalse(print(aMatrix(), callbacks), "no screen to print from once it stopped")
            assertEquals(1, starter.started.size)
            assertEquals(0, callbacks.printed + callbacks.cancelled)
        }
    }

    @Test
    fun `a job that went to print calls onPrinted once, a cancelled one onCancelled`() {
        ActivityScenario.launch(MainActivity::class.java).use {
            val printed = Callbacks()
            assertTrue(print(aMatrix(), printed))
            assertEquals(0, printed.printed, "nothing until the print service is done")
            starter.started
                .last()
                .second
                .onFinish()
            assertEquals(1, printed.printed)
            assertEquals(0, printed.cancelled)

            starter.job = StartedPrint { false }
            val cancelled = Callbacks()
            assertTrue(print(aMatrix(), cancelled))
            starter.started
                .last()
                .second
                .onFinish()
            assertEquals(0, cancelled.printed)
            assertEquals(1, cancelled.cancelled)
        }
    }

    @Test
    fun `a print service that gives no job or throws prints nothing, and is logged without the code`() {
        ActivityScenario.launch(MainActivity::class.java).use {
            val logger = FakeLogger()
            val printer = AndroidQrPrinter(ZxingQrEncoder(logger), logger, starter)
            it.onActivity { activity -> printer.attach(activity) }
            val matrix = assertNotNull(printer.encode("pps:00000000-0000-4000-8000-000000000003"))
            val callbacks = Callbacks()

            starter.job = null
            assertFalse(
                printer.print(matrix, "Scan this to stop your alarm.", "Yawn & Pawn", { callbacks.printed++ }, { callbacks.cancelled++ }),
            )
            starter.started
                .last()
                .second
                .onFinish()

            starter.failure = SecurityException("print service")
            assertFalse(
                printer.print(matrix, "Scan this to stop your alarm.", "Yawn & Pawn", { callbacks.printed++ }, { callbacks.cancelled++ }),
            )

            assertEquals(0, callbacks.printed + callbacks.cancelled, "no callback after false")
            assertEquals(
                listOf(
                    LogEvent.OperationFailed(AndroidQrPrinter.PRINT, "no print job"),
                    LogEvent.OperationFailed(AndroidQrPrinter.PRINT, "SecurityException"),
                ),
                logger.events,
            )
            assertTrue(logger.events.none { event -> "pps:" in event.toString() })
        }
    }

    @Test
    fun `a job whose state cannot be read counts as cancelled`() {
        ActivityScenario.launch(MainActivity::class.java).use {
            starter.job = StartedPrint { throw IllegalStateException("print service gone") }
            val callbacks = Callbacks()

            assertTrue(print(aMatrix(), callbacks))
            starter.started
                .last()
                .second
                .onFinish()

            assertEquals(0, callbacks.printed)
            assertEquals(1, callbacks.cancelled)
        }
    }
}
