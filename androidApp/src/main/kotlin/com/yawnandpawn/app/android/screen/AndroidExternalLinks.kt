package com.yawnandpawn.app.android.screen

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.yawnandpawn.app.core.log.LogEvent
import com.yawnandpawn.app.core.log.Logger
import com.yawnandpawn.app.ui.links.ExternalLinks

/**
 * Opens links and the support email in other apps (Story 4.17). Only ever called from the user's tap on the app screen
 * in front, never from the background (AD-5). A phone with no app for it (no browser, no email app) gets false back,
 * logged without the address or any of the email's text, instead of a crash.
 */
class AndroidExternalLinks(
    private val context: Context,
    private val logger: Logger,
) : ExternalLinks {
    override fun openUrl(url: String): Boolean = start(viewIntent(url), "open link")

    override fun sendEmail(
        to: String,
        subject: String,
        body: String,
    ): Boolean = start(emailIntent(to, subject, body), "open support email")

    private fun start(
        intent: Intent,
        operation: String,
    ): Boolean =
        try {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        } catch (e: ActivityNotFoundException) {
            logger.log(LogEvent.OperationFailed(operation, e::class.simpleName.orEmpty()))
            false
        } catch (e: SecurityException) {
            logger.log(LogEvent.OperationFailed(operation, e::class.simpleName.orEmpty()))
            false
        }

    internal companion object {
        /** [url] in the app that handles it: a browser, or Google Play for its own pages. */
        fun viewIntent(url: String): Intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE)

        /**
         * A new email to [to] in an email app only (`ACTION_SENDTO` with `mailto:`, so messaging and sharing apps are not
         * offered). The address, subject and body are both in the `mailto:` URI and in the extras: some email apps read
         * only one of them.
         */
        fun emailIntent(
            to: String,
            subject: String,
            body: String,
        ): Intent =
            Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:${Uri.encode(to, "@")}?subject=${Uri.encode(subject)}&body=${Uri.encode(body)}"))
                .putExtra(Intent.EXTRA_EMAIL, arrayOf(to))
                .putExtra(Intent.EXTRA_SUBJECT, subject)
                .putExtra(Intent.EXTRA_TEXT, body)
    }
}
