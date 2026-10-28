package com.yawnandpawn.app.ui.qr

/**
 * A [QrPrinter] under test control (Story 7.10): [encodes] says whether a code can be made, [prints] whether the print
 * dialog opens. It records what was encoded and printed; an opened dialog stays open until the test ends its job with
 * [finish] (it went to print) or [cancel] (cancelled in the dialog).
 */
class TestQrPrinter(
    var encodes: Boolean = true,
    var prints: Boolean = true,
) : QrPrinter {
    val encoded = mutableListOf<String>()
    val printed = mutableListOf<Printed>()
    private val open = mutableListOf<Pair<() -> Unit, () -> Unit>>()

    data class Printed(
        val matrix: QrMatrix,
        val line: String,
        val jobName: String,
    )

    override fun encode(payload: String): QrMatrix? {
        encoded += payload
        return if (encodes) MATRIX else null
    }

    override fun print(
        matrix: QrMatrix,
        line: String,
        jobName: String,
        onPrinted: () -> Unit,
        onCancelled: () -> Unit,
    ): Boolean {
        if (!prints) return false
        printed += Printed(matrix, line, jobName)
        open += onPrinted to onCancelled
        return true
    }

    /** The oldest open print dialog's job went to print (printed, or saved as a PDF). */
    fun finish() = open.removeAt(0).first()

    /** The oldest open print dialog was cancelled. */
    fun cancel() = open.removeAt(0).second()

    companion object {
        /** The code every encode gives: a 2 x 2 checker, enough to tell it from null. */
        val MATRIX: QrMatrix = QrMatrix.of(2) { row, column -> (row + column) % 2 == 0 }
    }
}
