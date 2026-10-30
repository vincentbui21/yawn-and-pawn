package com.yawnandpawn.app.android.qr

import android.app.Activity
import android.content.Context
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import android.print.PrintJobInfo
import android.print.PrintManager
import android.print.pdf.PrintedPdfDocument
import androidx.annotation.VisibleForTesting
import com.yawnandpawn.app.core.log.LogEvent
import com.yawnandpawn.app.core.log.Logger
import com.yawnandpawn.app.ui.qr.QrMatrix
import com.yawnandpawn.app.ui.qr.QrPrinter
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream

/**
 * Opens the system print dialog from [activity] for [adapter], as the print job [jobName] (Story 7.10). It gives the
 * started job, or null when the print service started none. The app uses [SystemPrintStarter]; tests replace it.
 */
fun interface PrintStarter {
    fun start(
        activity: Activity,
        jobName: String,
        adapter: PrintDocumentAdapter,
    ): StartedPrint?
}

/** A print job the print service started: whether it went to print, rather than being cancelled or failing. */
fun interface StartedPrint {
    fun wentToPrint(): Boolean
}

/**
 * The system print service ([PrintManager]). A job went to print only when it is queued, started, blocked or completed
 * ("Save as PDF" completes); anything else, a job still created (it never left the dialog), cancelled or failed, counts
 * as cancelled (review).
 */
val SystemPrintStarter =
    PrintStarter { activity, jobName, adapter ->
        activity.getSystemService(PrintManager::class.java)?.print(jobName, adapter, null)?.let { job ->
            StartedPrint { job.info?.state in WENT_TO_PRINT }
        }
    }

/** The print job states that mean the page went to print. */
private val WENT_TO_PRINT =
    setOf(PrintJobInfo.STATE_QUEUED, PrintJobInfo.STATE_STARTED, PrintJobInfo.STATE_BLOCKED, PrintJobInfo.STATE_COMPLETED)

/**
 * The Android [QrPrinter] (Story 7.10): [ZxingQrEncoder] makes the code, and the system print service prints it or saves
 * it as a PDF. The print dialog needs an activity, so `MainActivity` attaches itself while it is started ([attach],
 * [detach]), like the camera permission's launcher; with none attached nothing is printed. The page is a one-page
 * [PrintedPdfDocument] on the paper the user picks in the dialog, written straight to the print service's file, so the
 * app keeps no file.
 *
 * Registration waits for the print job (review pass 1): when the print service is done with the page (the adapter's
 * `onFinish`, on the main thread), exactly one of `onPrinted` (the job went to print) or `onCancelled` (cancelled or
 * failed) is called. A job the service did not start, or a print service that throws, gives false and no callback.
 * Failures are logged without the payload or the code.
 */
class AndroidQrPrinter(
    private val encoder: ZxingQrEncoder,
    private val logger: Logger,
    private val starter: PrintStarter = SystemPrintStarter,
) : QrPrinter {
    @Volatile
    private var host: Activity? = null

    /** The activity in front can show the print dialog (the newest attach wins). */
    @Synchronized
    fun attach(activity: Activity) {
        host = activity
    }

    /** The activity that attached stopped; a newer one stays. */
    @Synchronized
    fun detach(activity: Activity) {
        if (host === activity) host = null
    }

    override fun encode(payload: String): QrMatrix? = encoder.encode(payload)

    override fun print(
        matrix: QrMatrix,
        line: String,
        jobName: String,
        onPrinted: () -> Unit,
        onCancelled: () -> Unit,
    ): Boolean {
        val activity = host ?: return failed(PRINT, "no screen in front")
        val adapter = QrPrintAdapter(activity.applicationContext, matrix, line, jobName, logger)
        val job = start(activity, jobName, adapter)
        job?.let { adapter.whenFinished { if (wentToPrint(it)) onPrinted() else onCancelled() } }
        return job != null
    }

    /** The started print job, or null (logged) when the print service started none or threw. */
    @Suppress("TooGenericExceptionCaught") // Any print service failure means nothing was printed (review pass 1).
    private fun start(
        activity: Activity,
        jobName: String,
        adapter: PrintDocumentAdapter,
    ): StartedPrint? =
        try {
            starter.start(activity, jobName, adapter).also { if (it == null) failed(PRINT, "no print job") }
        } catch (e: RuntimeException) {
            failed(PRINT, e::class.simpleName.orEmpty())
            null
        }

    /** Whether [job] went to print; a print service that cannot say counts as cancelled, so nothing is registered. */
    @Suppress("TooGenericExceptionCaught") // The job's state is read from the print service; any failure is "not printed".
    private fun wentToPrint(job: StartedPrint): Boolean =
        try {
            job.wentToPrint()
        } catch (e: RuntimeException) {
            failed(STATE, e::class.simpleName.orEmpty())
        }

    private fun failed(
        operation: String,
        reason: String,
    ): Boolean {
        logger.log(LogEvent.OperationFailed(operation, reason))
        return false
    }

    companion object {
        const val PRINT = "print printable QR"
        const val STATE = "read printable QR print job"
        const val WRITE = "write printable QR"
    }
}

/**
 * Writes the printable QR's one-page PDF to [out] (Story 7.10): [matrix] 14 cm square in the middle of the paper in
 * [attributes] (its size, resolution and the printer's margins), [line] under it ([QrPageLayout], [drawQrPage]). Only
 * [QrPrintAdapter] writes it, to the print service's own descriptor; the device test writes it to a temporary file.
 */
@VisibleForTesting
internal fun writeQrPdf(
    context: Context,
    attributes: PrintAttributes,
    matrix: QrMatrix,
    line: String,
    out: OutputStream,
) {
    val document = PrintedPdfDocument(context, attributes)
    try {
        val page = document.startPage(0)
        val info = page.info
        val layout = QrPageLayout.of(info.pageWidth.toFloat(), info.pageHeight.toFloat())
        // The page's canvas starts at the printable area (inside the printer's margins): draw in page coordinates.
        page.canvas.translate(-info.contentRect.left.toFloat(), -info.contentRect.top.toFloat())
        drawQrPage(page.canvas, layout, matrix, line)
        document.finishPage(page)
        document.writeTo(out)
    } finally {
        document.close()
    }
}

/** One page: [matrix] 14 cm square in the middle of the paper the user picked, [line] under it ([writeQrPdf]). */
internal class QrPrintAdapter(
    private val context: Context,
    private val matrix: QrMatrix,
    private val line: String,
    private val documentName: String,
    private val logger: Logger,
) : PrintDocumentAdapter() {
    private var attributes: PrintAttributes? = null

    // The print service is done with the page, and what to do then (both on the main thread).
    private var finished = false
    private var onFinished: (() -> Unit)? = null

    /** Runs [action] once when the print service is done with the page (at once if it already is). */
    fun whenFinished(action: () -> Unit) {
        if (finished) action() else onFinished = action
    }

    override fun onLayout(
        oldAttributes: PrintAttributes?,
        newAttributes: PrintAttributes,
        cancellationSignal: CancellationSignal?,
        callback: LayoutResultCallback,
        extras: Bundle?,
    ) {
        if (cancellationSignal?.isCanceled == true) {
            callback.onLayoutCancelled()
            return
        }
        attributes = newAttributes
        val info =
            PrintDocumentInfo
                .Builder(documentName)
                .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
                .setPageCount(1)
                .build()
        callback.onLayoutFinished(info, newAttributes != oldAttributes)
    }

    @Suppress("TooGenericExceptionCaught") // A failed write is reported to the print service, never thrown (review pass 1).
    override fun onWrite(
        pages: Array<out PageRange>?,
        destination: ParcelFileDescriptor,
        cancellationSignal: CancellationSignal?,
        callback: WriteResultCallback,
    ) {
        val printAttributes = attributes
        if (printAttributes == null || cancellationSignal?.isCanceled == true) {
            callback.onWriteCancelled()
            return
        }
        // All of it, the PrintedPdfDocument constructor included, reports a failure instead of throwing (review pass 1).
        try {
            FileOutputStream(destination.fileDescriptor).use { writeQrPdf(context, printAttributes, matrix, line, it) }
            callback.onWriteFinished(arrayOf(PageRange.ALL_PAGES))
        } catch (e: IOException) {
            failed(e, callback)
        } catch (e: RuntimeException) {
            failed(e, callback)
        }
    }

    override fun onFinish() {
        finished = true
        val action = onFinished
        onFinished = null
        action?.invoke()
    }

    private fun failed(
        cause: Exception,
        callback: WriteResultCallback,
    ) {
        logger.log(LogEvent.OperationFailed(AndroidQrPrinter.WRITE, cause::class.simpleName.orEmpty()))
        callback.onWriteFailed(null)
    }
}
