package com.yawnandpawn.app.ui.qr

import com.yawnandpawn.app.core.checks.qr.CodeFormat
import com.yawnandpawn.app.core.checks.qr.PrintableCode
import com.yawnandpawn.app.core.checks.qr.RegisteredCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Story 7.10: the printable QR prints first and registers its code like "Use this code" once the print job went to print;
 * with a code saved it asks first; a cancelled print, no screen to print from or no code registers nothing; a double tap
 * prints once.
 */
class PrintableQrModelTest {
    private val payload = "pps:00000000-0000-4000-8000-000000000001"
    private val printer = TestQrPrinter()
    private val chosen = mutableListOf<RegisteredCode>()
    private var backs = 0

    private fun model(codeSaved: Boolean = false) =
        PrintableQrModel(
            printer = printer,
            payload = payload,
            codeSaved = { codeSaved },
            line = { "Scan this to stop your alarm." },
            jobName = { "Yawn & Pawn" },
            onCodeChosen = { chosen += it },
            onBack = { backs++ },
        )

    @Test
    fun `the code is made once when the screen opens and drawn on the sheet`() {
        val model = model()

        assertEquals(listOf(payload), printer.encoded)
        assertEquals(TestQrPrinter.MATRIX, model.state.code)
        assertFalse(model.state.showReplaceDialog)
    }

    @Test
    fun `a first code prints, then registers the QR code a scan of the sheet gives`() {
        val model = model()

        model.onIntent(QrIntent.PrintClicked)
        assertTrue(chosen.isEmpty(), "nothing registered while the print dialog is open")
        printer.finish()

        assertEquals(listOf(TestQrPrinter.Printed(TestQrPrinter.MATRIX, "Scan this to stop your alarm.", "Yawn & Pawn")), printer.printed)
        assertEquals(listOf(RegisteredCode.of(CodeFormat.QrCode, payload)), chosen.toList())
        assertEquals(PrintableCode.registered(payload), chosen.single())
    }

    @Test
    fun `with a code saved, Print asks first, and Keep it closes the dialog with nothing printed or changed`() {
        val model = model(codeSaved = true)

        model.onIntent(QrIntent.PrintClicked)
        assertTrue(model.state.showReplaceDialog, "Replace your QR code? The old one stops working.")
        assertTrue(printer.printed.isEmpty())

        model.onIntent(QrIntent.ReplaceCancelled)
        assertFalse(model.state.showReplaceDialog)
        assertTrue(printer.printed.isEmpty())
        assertTrue(chosen.isEmpty())
        assertEquals(0, backs, "stays on the printable QR")
    }

    @Test
    fun `Replace prints and registers the new code`() {
        val model = model(codeSaved = true)
        model.onIntent(QrIntent.PrintClicked)

        model.onIntent(QrIntent.ReplaceConfirmed)
        printer.finish()

        assertFalse(model.state.showReplaceDialog)
        assertEquals(1, printer.printed.size)
        assertEquals(listOf(PrintableCode.registered(payload)), chosen.toList())
    }

    @Test
    fun `a cancelled print dialog registers nothing, and Print works again`() {
        val model = model()

        model.onIntent(QrIntent.PrintClicked)
        printer.cancel()

        assertTrue(chosen.isEmpty(), "the old code and its sheet keep working")
        assertEquals(0, backs, "stays on the printable QR")

        model.onIntent(QrIntent.PrintClicked)
        assertEquals(2, printer.printed.size, "Print opens the dialog again")
        printer.finish()
        assertEquals(listOf(PrintableCode.registered(payload)), chosen.toList())
    }

    @Test
    fun `a cancelled replacement keeps the saved code, and Replace works again`() {
        val model = model(codeSaved = true)
        model.onIntent(QrIntent.PrintClicked)
        model.onIntent(QrIntent.ReplaceConfirmed)

        printer.cancel()
        assertTrue(chosen.isEmpty())

        model.onIntent(QrIntent.PrintClicked)
        assertTrue(model.state.showReplaceDialog, "asks again")
        model.onIntent(QrIntent.ReplaceConfirmed)
        printer.finish()
        assertEquals(2, printer.printed.size)
        assertEquals(listOf(PrintableCode.registered(payload)), chosen.toList())
    }

    @Test
    fun `a double tap prints once`() {
        val model = model()

        model.onIntent(QrIntent.PrintClicked)
        model.onIntent(QrIntent.PrintClicked)
        assertEquals(1, printer.printed.size, "the second tap is ignored while the dialog is open")
        printer.finish()

        assertEquals(listOf(PrintableCode.registered(payload)), chosen.toList())
    }

    @Test
    fun `a double tap on Replace prints once`() {
        val model = model(codeSaved = true)
        model.onIntent(QrIntent.PrintClicked)

        model.onIntent(QrIntent.ReplaceConfirmed)
        model.onIntent(QrIntent.ReplaceConfirmed)
        model.onIntent(QrIntent.PrintClicked)

        assertEquals(1, printer.printed.size)
        assertFalse(model.state.showReplaceDialog, "Print is ignored while the dialog is open")
        printer.finish()
        assertEquals(1, chosen.size)
    }

    @Test
    fun `with no screen to print from, nothing is registered and the screen stays`() {
        printer.prints = false
        val model = model()

        model.onIntent(QrIntent.PrintClicked)

        assertTrue(chosen.isEmpty())
        assertEquals(0, backs)
        assertEquals(TestQrPrinter.MATRIX, model.state.code)

        printer.prints = true
        model.onIntent(QrIntent.PrintClicked)
        assertEquals(1, printer.printed.size, "a print that could not open leaves Print working")
    }

    @Test
    fun `a code that cannot be made disables Print`() {
        printer.encodes = false
        val model = model(codeSaved = true)

        assertNull(model.state.code, "Print is disabled")
        model.onIntent(QrIntent.PrintClicked)
        model.onIntent(QrIntent.ReplaceConfirmed)

        assertFalse(model.state.showReplaceDialog)
        assertTrue(printer.printed.isEmpty())
        assertTrue(chosen.isEmpty())
    }

    @Test
    fun `Back goes back, and registration's intents do nothing`() {
        val model = model()

        listOf(QrIntent.TorchToggled, QrIntent.UseCode, QrIntent.ScanAgain, QrIntent.PrintableClicked, QrIntent.FixCamera)
            .forEach(model::onIntent)
        model.onIntent(QrIntent.Back)

        assertEquals(1, backs)
        assertTrue(printer.printed.isEmpty())
        assertTrue(chosen.isEmpty())
    }

    @Test
    fun `QR registration's Make a printable QR opens the printable QR`() {
        var opened = 0
        val model = QrRegistrationModel(TestCameraPermission(), onCodeChosen = {}, onBack = {}, onPrintable = { opened++ })

        model.onIntent(QrIntent.PrintableClicked)

        assertEquals(1, opened)
    }

    @Test
    fun `a QR matrix is square and reads row by row`() {
        val matrix = QrMatrix.of(3) { row, column -> row == 1 && column == 2 }

        assertTrue(matrix.isDark(1, 2))
        assertFalse(matrix.isDark(2, 1))
        assertEquals(QrMatrix.of(3) { row, column -> row == 1 && column == 2 }, matrix)
        assertFailsWith<IllegalArgumentException> { QrMatrix(2, listOf(true)) }
        assertEquals(25, PreviewQrMatrix.size, "the preview's made-up code")
    }
}
