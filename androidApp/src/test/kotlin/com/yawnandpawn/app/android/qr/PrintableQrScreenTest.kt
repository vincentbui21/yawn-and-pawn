package com.yawnandpawn.app.android.qr

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.yawnandpawn.app.StopAppRule
import com.yawnandpawn.app.core.checks.qr.PrintableCode
import com.yawnandpawn.app.core.checks.qr.RegisteredCode
import com.yawnandpawn.app.testing.FakeLogger
import com.yawnandpawn.app.ui.qr.PrintableQrRoute
import com.yawnandpawn.app.ui.qr.QrMatrix
import com.yawnandpawn.app.ui.qr.QrPrinter
import com.yawnandpawn.app.ui.theme.PpsThemeMode
import com.yawnandpawn.app.ui.withScreen
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Story 7.10 in the app: the printable QR draws a real code from the app's own printer (ZXing), and its route prints,
 * then registers the printed code once its job went to print; with a code saved it asks first, and "Keep it" changes
 * nothing.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class PrintableQrScreenTest {
    @get:Rule(order = 0)
    val stopApp = StopAppRule()

    @get:Rule(order = 1)
    val composeRule = createEmptyComposeRule()

    /** The app's encoder, with printing recorded instead of the system print dialog, whose job goes to print at once. */
    private class RecordingPrinter : QrPrinter {
        private val encoder = ZxingQrEncoder(FakeLogger())
        val payloads = mutableListOf<String>()
        val printed = mutableListOf<QrMatrix>()

        override fun encode(payload: String): QrMatrix? = encoder.encode(payload).also { payloads += payload }

        override fun print(
            matrix: QrMatrix,
            line: String,
            jobName: String,
            onPrinted: () -> Unit,
            onCancelled: () -> Unit,
        ): Boolean {
            assertEquals("Scan this to stop your alarm.", line)
            assertEquals("Yawn & Pawn", jobName)
            printed += matrix
            onPrinted()
            return true
        }
    }

    @Test
    fun `the app's printer draws a real code and Print is enabled`() {
        withScreen(PpsThemeMode.Light, content = { PrintableQrRoute(codeSaved = false, onCodeChosen = {}, onBack = {}) }) {
            assertIs<AndroidQrPrinter>(GlobalContext.get().get<QrPrinter>())
            composeRule.onNode(hasContentDescription("QR code for your alarm")).assertExists()
            composeRule.onNodeWithText("Print or save as PDF").assertIsEnabled()
        }
    }

    @Test
    fun `Print prints, then registers the printed code`() {
        val printer = RecordingPrinter()
        val chosen = mutableListOf<RegisteredCode>()
        withScreen(PpsThemeMode.Light, content = {
            PrintableQrRoute(codeSaved = false, onCodeChosen = { chosen += it }, onBack = {}, printer = printer)
        }) {
            composeRule.onNodeWithText("Print or save as PDF").performClick()
            composeRule.waitForIdle()
        }
        assertEquals(1, printer.printed.size)
        assertEquals(listOf(PrintableCode.registered(printer.payloads.single())), chosen.toList())
        assertTrue(printer.payloads.single().startsWith("pps:"))
    }

    @Test
    fun `with a code saved, Keep it changes nothing and Replace prints and registers`() {
        val printer = RecordingPrinter()
        val chosen = mutableListOf<RegisteredCode>()
        withScreen(PpsThemeMode.Dark, content = {
            PrintableQrRoute(codeSaved = true, onCodeChosen = { chosen += it }, onBack = {}, printer = printer)
        }) {
            composeRule.onNodeWithText("Print or save as PDF").performClick()
            composeRule.onNodeWithText("Keep it").performClick()
            composeRule.waitForIdle()
            composeRule.onNodeWithText("Replace your QR code?").assertDoesNotExist()
            assertTrue(printer.printed.isEmpty())
            assertTrue(chosen.isEmpty())

            composeRule.onNodeWithText("Print or save as PDF").performClick()
            composeRule.onNodeWithText("Replace").performClick()
            composeRule.waitForIdle()
        }
        assertEquals(1, printer.printed.size)
        assertEquals(listOf(PrintableCode.registered(printer.payloads.single())), chosen.toList())
    }
}
