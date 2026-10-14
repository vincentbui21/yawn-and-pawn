package com.yawnandpawn.app.ui.links

/**
 * The addresses the app opens outside itself (Story 4.17), from `config/app-links.properties` through the build.
 *
 * @property supportEmail where "Email support" writes to (a placeholder until a dedicated support address exists).
 * @property orderHistoryUrl Google Play's order history, where the user can request a refund.
 */
data class AppLinks(
    val supportEmail: String,
    val orderHistoryUrl: String,
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
}
