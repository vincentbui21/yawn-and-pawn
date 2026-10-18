package com.yawnandpawn.app.ui.you

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yawnandpawn.app.core.time.MonotonicClock
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
 * it itself (before Android 13), never when the copy failed ([ExternalLinks.copyText] false).
 *
 * Links open only on the user's tap. A repeat of the link (or the email) that just opened, within [REPEAT_MILLIS] on
 * [clock], is a double tap and is ignored, so one tap never opens two browser tasks or two emails. Navigation itself is
 * the route's: this only clears the note when the user moves on. "Delete all data" (Story 5.9) and "About" are not
 * shown in production, so their intents do nothing here.
 */
class YouViewModel(
    private val links: ExternalLinks,
    private val appLinks: AppLinks,
    private val clock: MonotonicClock,
) : ViewModel() {
    private val mutableState = MutableStateFlow(YouUiState(appVersion = ""))
    val state: StateFlow<YouUiState> = mutableState.asStateFlow()

    private var snackbarJob: Job? = null

    /** The link (or `mailto:` address) that opened last, and when ([clock]), to ignore a double tap on it. */
    private var lastOpened: Pair<String, Long>? = null

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
        if (isRepeat(url)) return
        if (links.openUrl(url)) opened(url) else show(YouSnackbar.NoBrowser)
    }

    /** The support email; with no email app the note with the address stays (not a 4 s snackbar), so it can be copied. */
    private fun emailSupport() {
        val target = MAILTO + appLinks.supportEmail
        if (isRepeat(target)) return
        val sent = links.sendEmail(appLinks.supportEmail, subject = "", body = "")
        if (sent) opened(target)
        mutableState.update { it.copy(noEmailAppAddress = if (sent) null else appLinks.supportEmail) }
    }

    /** True when [target] opened less than [REPEAT_MILLIS] ago: a double tap. A link that failed to open never counts. */
    private fun isRepeat(target: String): Boolean {
        val (last, at) = lastOpened ?: return false
        return last == target && clock.elapsedMillis() - at in 0 until REPEAT_MILLIS
    }

    private fun opened(target: String) {
        lastOpened = target to clock.elapsedMillis()
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

        /** A second tap on the link that just opened, within this time, is a double tap (review fix 5). */
        const val REPEAT_MILLIS = 1_000L

        private const val MAILTO = "mailto:"
    }
}
