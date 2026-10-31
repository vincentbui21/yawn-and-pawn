package com.yawnandpawn.app.ui.qr

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.yawnandpawn.app.core.checks.qr.PrintableCode
import com.yawnandpawn.app.core.checks.qr.RegisteredCode
import com.yawnandpawn.app.core.id.IdGenerator
import com.yawnandpawn.app.ui.resources.Res
import com.yawnandpawn.app.ui.resources.app_name
import com.yawnandpawn.app.ui.resources.qr_pdf_line
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject

/**
 * The printable QR's state (Story 7.10, FR-PWK-13): one new code per opening ([payload], never stored or logged), drawn
 * from [QrPrinter.encode]. "Print or save as PDF" opens the print dialog, and once the print job went to print it
 * registers the code exactly as "Use this code" does ([onCodeChosen] with [PrintableCode.registered]); with a code already
 * saved ([codeSaved]) it asks "Replace your QR code?" / "The old one stops working." first, and "Keep it" closes the dialog
 * with nothing printed or changed. A cancelled print dialog registers nothing, so the old code and its sheet keep
 * working (review pass 1). When the code cannot be made, Print is disabled; when there is no screen to print from,
 * nothing is registered. While a print is under way, further Print and Replace taps are ignored (one print per tap).
 */
class PrintableQrModel(
    private val printer: QrPrinter,
    private val payload: String,
    private val codeSaved: () -> Boolean,
    private val line: () -> String,
    private val jobName: () -> String,
    private val onCodeChosen: (RegisteredCode) -> Unit,
    private val onBack: () -> Unit,
) {
    private val matrix: QrMatrix? = printer.encode(payload)

    /** A print dialog is open (or opening): set on a Print or Replace tap, cleared when the print is done or failed. */
    private var printing = false

    var state: PrintableQrUiState by mutableStateOf(PrintableQrUiState(codeSaved = codeSaved(), code = matrix))
        private set

    fun onIntent(intent: QrIntent) {
        when (intent) {
            QrIntent.Back -> {
                onBack()
            }

            QrIntent.PrintClicked -> {
                if (matrix == null || printing) return
                if (codeSaved()) state = state.copy(codeSaved = true, showReplaceDialog = true) else printAndRegister(matrix)
            }

            QrIntent.ReplaceConfirmed -> {
                state = state.copy(showReplaceDialog = false)
                if (!printing) matrix?.let(::printAndRegister)
            }

            QrIntent.ReplaceCancelled -> {
                state = state.copy(showReplaceDialog = false)
            }

            // Registration's intents; the printable screen never sends them.
            QrIntent.TorchToggled, QrIntent.UseCode, QrIntent.ScanAgain, QrIntent.PrintableClicked, QrIntent.FixCamera -> {
                Unit
            }
        }
    }

    /**
     * Prints, then registers once the print job went to print (Design Notes, review pass 1): registering moves the editor
     * away from this screen (and in Home's Re-register mode stores the code at once), so a cancelled dialog must leave
     * the old code in place. A print that could not open registers nothing and stays here.
     */
    private fun printAndRegister(matrix: QrMatrix) {
        printing = true
        val opened =
            printer.print(
                matrix = matrix,
                line = line(),
                jobName = jobName(),
                onPrinted = {
                    printing = false
                    onCodeChosen(PrintableCode.registered(payload))
                },
                onCancelled = { printing = false },
            )
        if (!opened) printing = false
    }
}

/**
 * "Make a printable QR" as a pushed screen (Story 7.10): [PrintableQrScreen] with a new code, made when the screen opens.
 * Opened from Check setup or QR registration; [codeSaved] says a code is already registered (the replace dialog).
 * [onCodeChosen] gets the printed code's [RegisteredCode] once its print job went to print, as a scan's "Use this code"
 * would; the screen stays while the print dialog is open. [onBack] goes back.
 */
@Composable
fun PrintableQrRoute(
    codeSaved: Boolean,
    onCodeChosen: (RegisteredCode) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    printer: QrPrinter = koinInject(),
    ids: IdGenerator = koinInject(),
) {
    val saved by rememberUpdatedState(codeSaved)
    val chosen by rememberUpdatedState(onCodeChosen)
    val back by rememberUpdatedState(onBack)
    val line by rememberUpdatedState(stringResource(Res.string.qr_pdf_line))
    val jobName by rememberUpdatedState(stringResource(Res.string.app_name))
    val model =
        remember(printer, ids) {
            PrintableQrModel(
                printer = printer,
                payload = PrintableCode.newPayload(ids),
                codeSaved = { saved },
                line = { line },
                jobName = { jobName },
                onCodeChosen = { chosen(it) },
                onBack = { back() },
            )
        }
    PrintableQrScreen(state = model.state, onIntent = model::onIntent, modifier = modifier)
}
