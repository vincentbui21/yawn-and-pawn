package com.yawnandpawn.app.ui.about

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

/** The app's own facts About shows, from the build: [versionName] ("0.1.7"), as the release workflow passed it. */
data class AppInfo(
    val versionName: String,
)

/**
 * About (0.1.7 polish), pushed over Settings. "Website", "Privacy policy" and "Terms" open [AppLinks.websiteUrl],
 * [AppLinks.privacyUrl] and [AppLinks.termsUrl] in the browser; with no app to open them the "No browser found." snackbar
 * shows for 4 s, and a second tap starts the 4 s again. "Contact support" opens a new email to [AppLinks.supportEmail]
 * with no subject or body; with no email app the note with the address and "Copy address" shows until an email opens.
 * "Copy address" puts the address on the clipboard and says "Address copied." for 4 s where the phone does not confirm
 * it itself (before Android 13), never when the copy failed ([ExternalLinks.copyText] false). The same rules as the You
 * tab's links.
 *
 * Links open only on the user's tap. A repeat of the link (or the email) that just opened, within [REPEAT_MILLIS] on
 * [clock], is a double tap and is ignored, so one tap never opens two browser tasks or two emails.
 */
class AboutViewModel(
    private val links: ExternalLinks,
    private val appLinks: AppLinks,
    appInfo: AppInfo,
    private val clock: MonotonicClock,
) : ViewModel() {
    private val mutableState =
        MutableStateFlow(
            AboutUiState(
                version = appInfo.versionName,
                website = displayAddress(appLinks.websiteUrl),
                supportEmail = appLinks.supportEmail,
            ),
        )
    val state: StateFlow<AboutUiState> = mutableState.asStateFlow()

    private var snackbarJob: Job? = null

    /** The link (or `mailto:` address) that opened last, and when ([clock]), to ignore a double tap on it. */
    private var lastOpened: Pair<String, Long>? = null

    fun onIntent(intent: AboutIntent) {
        when (intent) {
            AboutIntent.WebsiteClicked -> open(appLinks.websiteUrl)
            AboutIntent.PrivacyClicked -> open(appLinks.privacyUrl)
            AboutIntent.TermsClicked -> open(appLinks.termsUrl)
            AboutIntent.ContactSupportClicked -> emailSupport()
            AboutIntent.CopyAddressClicked -> if (links.copyText(appLinks.supportEmail)) show(AboutSnackbar.AddressCopied)
        }
    }

    private fun open(url: String) {
        if (isRepeat(url)) return
        if (links.openUrl(url)) opened(url) else show(AboutSnackbar.NoBrowser)
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
    private fun show(snackbar: AboutSnackbar) {
        snackbarJob?.cancel()
        mutableState.update { it.withSnackbar(snackbar) }
        snackbarJob =
            viewModelScope.launch {
                delay(SNACKBAR_MILLIS)
                mutableState.update { it.withSnackbar(null) }
            }
    }

    private fun AboutUiState.withSnackbar(snackbar: AboutSnackbar?): AboutUiState =
        copy(noBrowser = snackbar == AboutSnackbar.NoBrowser, addressCopied = snackbar == AboutSnackbar.AddressCopied)

    /** A snackbar of About; one at a time. */
    private enum class AboutSnackbar { NoBrowser, AddressCopied }

    internal companion object {
        /** How long an app-screen snackbar stays (EXPERIENCE.md `snackbar`: 4 s). */
        const val SNACKBAR_MILLIS = 4_000L

        /** A second tap on the link that just opened, within this time, is a double tap. */
        const val REPEAT_MILLIS = 1_000L

        private const val MAILTO = "mailto:"

        /** "https://yawnandpawn.com/" as the row shows it: "yawnandpawn.com". */
        fun displayAddress(url: String): String = url.substringAfter("://").removePrefix("www.").trimEnd('/')
    }
}
