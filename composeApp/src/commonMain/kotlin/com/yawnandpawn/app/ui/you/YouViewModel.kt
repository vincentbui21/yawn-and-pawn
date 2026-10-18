package com.yawnandpawn.app.ui.you

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yawnandpawn.app.ui.links.AppLinks
import com.yawnandpawn.app.ui.links.ExternalLinks
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The You tab (Story 5.8, FR-SET-3, FR-SET-5). "Privacy policy", "Terms" and "Support" open [AppLinks.privacyUrl],
 * [AppLinks.termsUrl] and [AppLinks.supportUrl] in the browser; with no app to open them the "No browser found."
 * snackbar shows for 4 s, and a second tap starts the 4 s again. "Email support" opens a new email to
 * [AppLinks.supportEmail] with no subject or body (no approved copy exists for them); with no email app the note with
 * the address and "Copy address" shows until an email opens or the user moves on to Purchase history or Payments.
 * "Copy address" puts the address on the clipboard and says "Address copied." for 4 s where the phone does not confirm
 * it itself (before Android 13).
 *
 * Links open only on the user's tap. Navigation itself is the route's: this only clears the note when the user moves
 * on. "Delete all data" (Story 5.9) and "About" are not shown in production, so their intents do nothing here.
 */
class YouViewModel(
    private val links: ExternalLinks,
    private val appLinks: AppLinks,
) : ViewModel() {
    private val mutableState = MutableStateFlow(YouUiState(appVersion = ""))
    val state: StateFlow<YouUiState> = mutableState.asStateFlow()

    private var snackbarJob: Job? = null

    fun onIntent(intent: YouIntent) {
        when (intent) {
            YouIntent.PrivacyClicked -> open(appLinks.privacyUrl)
            YouIntent.TermsClicked -> open(appLinks.termsUrl)
            YouIntent.SupportClicked -> open(appLinks.supportUrl)
            YouIntent.EmailSupportClicked -> emailSupport()
            YouIntent.CopyAddressClicked -> if (links.copyText(appLinks.supportEmail)) show(YouSnackbar.AddressCopied)
            YouIntent.PurchaseHistoryClicked, YouIntent.PaymentsClicked -> mutableState.update { it.copy(noEmailAppAddress = null) }
            YouIntent.DeleteAllClicked, YouIntent.DeleteConfirmed, YouIntent.DeleteCancelled, YouIntent.AboutClicked -> Unit
        }
    }

    private fun open(url: String) {
        if (!links.openUrl(url)) show(YouSnackbar.NoBrowser)
    }

    /** The support email; with no email app the note with the address stays (not a 4 s snackbar), so it can be copied. */
    private fun emailSupport() {
        val opened = links.sendEmail(appLinks.supportEmail, subject = "", body = "")
        mutableState.update { it.copy(noEmailAppAddress = if (opened) null else appLinks.supportEmail) }
    }

    /** Shows [snackbar] for [SNACKBAR_MILLIS]; a newer one (or the same one again) replaces it and starts over. */
    private fun show(snackbar: YouSnackbar) {
        snackbarJob?.cancel()
        mutableState.update { it.withSnackbar(snackbar) }
        snackbarJob =
            viewModelScope.launch {
                delay(SNACKBAR_MILLIS)
                mutableState.update { it.withSnackbar(null) }
            }
    }

    private fun YouUiState.withSnackbar(snackbar: YouSnackbar?): YouUiState =
        copy(noBrowser = snackbar == YouSnackbar.NoBrowser, addressCopied = snackbar == YouSnackbar.AddressCopied)

    /** A snackbar of the You tab; one at a time. */
    private enum class YouSnackbar { NoBrowser, AddressCopied }

    internal companion object {
        /** How long an app-screen snackbar stays (EXPERIENCE.md `snackbar`: 4 s). */
        const val SNACKBAR_MILLIS = 4_000L
    }
}
