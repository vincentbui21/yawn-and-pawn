package com.yawnandpawn.app.ui.links

/**
 * The addresses the app opens outside itself (Stories 4.17 and 5.7), from `config/app-links.properties` through the
 * build.
 *
 * @property supportEmail where "Email support" writes to (a placeholder until a dedicated support address exists).
 * @property orderHistoryUrl Google Play's order history, where the user can request a refund.
 * @property privacyUrl the privacy policy on the project's GitHub Pages site (`docs/site/privacy.md`).
 * @property termsUrl the terms on the site (`docs/site/terms.md`).
 * @property supportUrl the support page on the site (`docs/site/support.md`).
 * @property websiteUrl the site's home page (`docs/site/index.md`), About's "Website" row.
 */
data class AppLinks(
    val supportEmail: String,
    val orderHistoryUrl: String,
    val privacyUrl: String,
    val termsUrl: String,
    val supportUrl: String,
    val websiteUrl: String,
)

/**
 * Opens other apps from the app screen in front, only ever on the user's tap (never from the background, AD-5). Each
 * call is false when no app on the phone can handle it, so the screen can say so instead of doing nothing.
 */
interface ExternalLinks {
    /** Opens [url] in the app that handles it (a browser, or Google Play for its own pages). */
    fun openUrl(url: String): Boolean

    /** Opens an email app on a new email to [to] with [subject] and [body] filled in; the user sends it. */
    fun sendEmail(
        to: String,
        subject: String,
        body: String,
    ): Boolean

    /**
     * Puts [text] on the clipboard. True when the app should confirm it itself: from Android 13 the phone shows its
     * own confirmation, so a second one from the app would repeat it. False too when nothing was copied (no clipboard),
     * so the caller never claims a copy that did not happen.
     */
    fun copyText(text: String): Boolean
}
