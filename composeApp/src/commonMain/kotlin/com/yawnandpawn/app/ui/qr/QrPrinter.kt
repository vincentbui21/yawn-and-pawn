package com.yawnandpawn.app.ui.qr

/**
 * A QR code as square modules (Story 7.10): [size] x [size], row by row in [modules], `true` for a dark module. It
 * includes the light quiet zone around the code, so drawing it edge to edge leaves the margin a scanner needs.
 */
class QrMatrix(
    val size: Int,
    private val modules: List<Boolean>,
) {
    init {
        require(size > 0 && modules.size == size * size) { "a QR matrix is square" }
    }

    /** Whether the module at [row], [column] is dark. */
    fun isDark(
        row: Int,
        column: Int,
    ): Boolean = modules[row * size + column]

    override fun equals(other: Any?): Boolean = other is QrMatrix && other.size == size && other.modules == modules

    override fun hashCode(): Int = 31 * size + modules.hashCode()

    override fun toString(): String = "QrMatrix(size=$size)"

    companion object {
        /** A matrix of [size] whose module at row, column is dark when [dark] says so. */
        fun of(
            size: Int,
            dark: (row: Int, column: Int) -> Boolean,
        ): QrMatrix = QrMatrix(size, List(size * size) { dark(it / size, it % size) })
    }
}

/**
 * The printable QR's platform side (Story 7.10), so ZXing and the system print service stay out of common code.
 * Neither call ever throws, and neither logs the payload.
 */
interface QrPrinter {
    /** The QR code of [payload] (error correction M, 4-module quiet zone); null when it cannot be made (logged). */
    fun encode(payload: String): QrMatrix?

    /**
     * Opens the system print dialog with one page: [matrix] 14 cm square in the middle, [line] under it, as the print job
     * [jobName]. False when nothing could be shown: no screen to print from, or the print service gave no job or failed
     * (logged); then neither callback is called. True when the dialog opened; then exactly one callback follows, on the
     * main thread, when the print service is done with the page: [onPrinted] when the job went to print (queued, started,
     * blocked or completed, as "Save as PDF" does), [onCancelled] when it was cancelled or failed. Nothing is kept.
     */
    fun print(
        matrix: QrMatrix,
        line: String,
        jobName: String,
        onPrinted: () -> Unit,
        onCancelled: () -> Unit,
    ): Boolean
}
