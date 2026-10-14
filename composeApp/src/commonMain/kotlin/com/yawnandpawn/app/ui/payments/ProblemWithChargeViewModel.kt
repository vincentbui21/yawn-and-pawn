package com.yawnandpawn.app.ui.payments

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yawnandpawn.app.core.billing.PurchaseRecordRepository
import com.yawnandpawn.app.core.log.LogEvent
import com.yawnandpawn.app.core.log.Logger
import com.yawnandpawn.app.core.time.TimeZoneProvider
import com.yawnandpawn.app.ui.links.AppLinks
import com.yawnandpawn.app.ui.links.ExternalLinks
import com.yawnandpawn.app.ui.purchases.NewestPurchaseFirst
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * "Problem with a charge?" (Story 4.17, FR-SET-5). "Open Google Play order history" opens [AppLinks.orderHistoryUrl];
 * "Email support" opens the picker of the [SUPPORT_PICKER_CHARGES] most recent charges from [records] (newest picked),
 * whose "Email support" writes about the picked charge, or, with no charge at all, writes straight away without order
 * details. The email goes to [AppLinks.supportEmail] through the user's email app; the user sends it. When no app can
 * open the link, a snackbar says so for 4 s; when no app can open the email, a note shows the address with "Copy
 * address" until the user moves on. "Email support" does nothing until the charges have been read once.
 *
 * The email's text comes from the screen ([SupportEmailWriter], the app's strings resolved where it is shown), so
 * opening it is immediate and never waits on a resource read.
 *
 * The charges follow every change of the records. A failing read is logged and leaves no charge to pick, so the email
 * still opens, without order details. Nothing here writes a record or logs a charge's details.
 */
class ProblemWithChargeViewModel(
    private val records: PurchaseRecordRepository,
    private val timeZoneProvider: TimeZoneProvider,
    private val links: ExternalLinks,
    private val appLinks: AppLinks,
    private val logger: Logger,
) : ViewModel() {
    private val mutableState = MutableStateFlow(ProblemWithChargeUiState(supportEmail = appLinks.supportEmail))
    val state: StateFlow<ProblemWithChargeUiState> = mutableState.asStateFlow()

    private var snackbarJob: Job? = null

    init {
        viewModelScope.launch {
            records
                .observeAll()
                .map { read ->
                    val zone = timeZoneProvider.current()
                    read.records
                        .sortedWith(NewestPurchaseFirst)
                        .take(SUPPORT_PICKER_CHARGES)
                        .map { chargeOptionOf(it, zone) }
                }.catch { cause ->
                    logger.log(LogEvent.OperationFailed("load charges for support", cause.message ?: cause::class.simpleName.orEmpty()))
                    emit(emptyList())
                }.collect { charges ->
                    mutableState.update { it.copy(charges = charges, loaded = true, picking = it.picking && charges.isNotEmpty()) }
                }
        }
    }

    /** Handles [intent]; an email it opens is written by [writer]. */
    fun onIntent(
        intent: PaymentsIntent,
        writer: SupportEmailWriter,
    ) {
        when (intent) {
            PaymentsIntent.Back -> {
                mutableState.update { it.copy(picking = false, noEmailApp = false) }
            }

            PaymentsIntent.OpenOrderHistoryClicked -> {
                if (!links.openUrl(appLinks.orderHistoryUrl)) show(ChargeSnackbar.NoBrowser)
            }

            PaymentsIntent.EmailSupportClicked -> {
                // Before the first read nothing is known yet: an email now could leave out the order it is about.
                if (!state.value.loaded) return
                val newest = state.value.charges.firstOrNull()
                if (newest == null) {
                    send(writer.write(null))
                } else {
                    mutableState.update { it.copy(picking = true, selectedKey = newest.key, snackbar = null, noEmailApp = false) }
                }
            }

            PaymentsIntent.CopyAddressClicked -> {
                if (links.copyText(appLinks.supportEmail)) show(ChargeSnackbar.AddressCopied)
            }

            is PaymentsIntent.ChargePicked -> {
                mutableState.update { it.copy(selectedKey = intent.key) }
            }

            PaymentsIntent.SendEmailClicked -> {
                if (state.value.picking) send(writer.write(state.value.selected))
            }

            PaymentsIntent.ProblemWithChargeClicked, PaymentsIntent.PurchaseHistoryClicked -> {
                Unit
            }
        }
    }

    /**
     * Opens [email]; back on the screen once it opened. With no email app the note with the address and "Copy address"
     * shows and stays (not a 4 s snackbar), so the address can still be used.
     */
    private fun send(email: SupportEmail) {
        if (links.sendEmail(appLinks.supportEmail, email.subject, email.body)) {
            mutableState.update { it.copy(picking = false, noEmailApp = false) }
        } else {
            mutableState.update { it.copy(noEmailApp = true) }
        }
    }

    /** Shows [snackbar] for [SNACKBAR_MILLIS]; a newer one replaces it. */
    private fun show(snackbar: ChargeSnackbar) {
        snackbarJob?.cancel()
        mutableState.update { it.copy(snackbar = snackbar) }
        snackbarJob =
            viewModelScope.launch {
                delay(SNACKBAR_MILLIS)
                mutableState.update { it.copy(snackbar = null) }
            }
    }

    internal companion object {
        const val SNACKBAR_MILLIS = 4_000L
    }
}
