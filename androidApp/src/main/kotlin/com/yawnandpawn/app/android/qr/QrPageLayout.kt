package com.yawnandpawn.app.android.qr

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import com.yawnandpawn.app.ui.qr.QrMatrix

/**
 * Where the printable QR sits on a page (Story 7.10), in PostScript points (1/72 inch, the PDF page unit) from the
 * page's top left: the code itself (its dark modules, without the quiet zone) is exactly 6 cm square in the middle of
 * the page, the white quiet zone around it is extra, and the line "Scan this to stop your alarm." is centred under it,
 * below the quiet zone. Any page size works; A4 and Letter are the usual ones.
 */
data class QrPageLayout(
    val pageWidth: Float,
    val pageHeight: Float,
    /** The code's left and top edges and its side, without the quiet zone. */
    val left: Float,
    val top: Float,
    val side: Float,
    /** The line's centre and its baseline. */
    val lineCenter: Float,
    val lineBaseline: Float,
) {
    companion object {
        const val POINTS_PER_INCH = 72f
        const val CM_PER_INCH = 2.54f

        /** The code's side without the quiet zone: 6 cm. */
        const val SIDE_CM = 6f

        /**
         * The line's size and its gap under the code, in points (print only, not a screen type token). The gap is wider
         * than the 4-module quiet zone of any QR version (version 1: 4 of 21 modules of 6 cm, about 32 points), so the
         * text never enters it.
         */
        const val LINE_TEXT_SIZE = 14f
        const val LINE_GAP = 36f

        private const val MILS_PER_INCH = 1000f

        /** [centimetres] in points. */
        fun points(centimetres: Float): Float = centimetres / CM_PER_INCH * POINTS_PER_INCH

        /** A page length in thousandths of an inch (`PrintAttributes.MediaSize`) in points. */
        fun pointsOfMils(mils: Int): Float = mils / MILS_PER_INCH * POINTS_PER_INCH

        /** The layout on a [pageWidth] x [pageHeight] page, in points. */
        fun of(
            pageWidth: Float,
            pageHeight: Float,
        ): QrPageLayout {
            val side = points(SIDE_CM)
            val left = (pageWidth - side) / 2
            val top = (pageHeight - side) / 2
            return QrPageLayout(
                pageWidth = pageWidth,
                pageHeight = pageHeight,
                left = left,
                top = top,
                side = side,
                lineCenter = pageWidth / 2,
                lineBaseline = top + side + LINE_GAP + LINE_TEXT_SIZE,
            )
        }
    }
}

/**
 * Draws the page of [layout] on [canvas] (in points, page coordinates): [matrix] black on the white page, one square
 * per dark module, with the code (the matrix without its [ZxingQrEncoder.QUIET_ZONE]) filling the layout's square and the
 * quiet zone left white around it, then [line] under it. Anti-aliasing is off for the modules so their edges stay sharp.
 */
fun drawQrPage(
    canvas: Canvas,
    layout: QrPageLayout,
    matrix: QrMatrix,
    line: String,
) {
    val ink =
        Paint().apply {
            color = Color.BLACK
            style = Paint.Style.FILL
            isAntiAlias = false
        }
    val quiet = ZxingQrEncoder.QUIET_ZONE
    val cell = layout.side / (matrix.size - 2 * quiet)
    for (row in 0 until matrix.size) {
        for (column in 0 until matrix.size) {
            if (matrix.isDark(row, column)) {
                val x = layout.left + (column - quiet) * cell
                val y = layout.top + (row - quiet) * cell
                canvas.drawRect(x, y, x + cell, y + cell, ink)
            }
        }
    }
    val text =
        Paint().apply {
            color = Color.BLACK
            isAntiAlias = true
            textAlign = Paint.Align.CENTER
            textSize = QrPageLayout.LINE_TEXT_SIZE
            typeface = Typeface.DEFAULT
        }
    canvas.drawText(line, layout.lineCenter, layout.lineBaseline, text)
}
