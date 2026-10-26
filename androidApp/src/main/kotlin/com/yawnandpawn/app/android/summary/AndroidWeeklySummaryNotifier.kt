package com.yawnandpawn.app.android.summary

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationManagerCompat
import com.yawnandpawn.app.MainActivity
import com.yawnandpawn.app.R
import com.yawnandpawn.app.core.billing.MoneyFormatter
import com.yawnandpawn.app.core.billing.formatTotals
import com.yawnandpawn.app.core.error.DomainError
import com.yawnandpawn.app.core.error.Outcome
import com.yawnandpawn.app.core.log.LogEvent
import com.yawnandpawn.app.core.log.Logger
import com.yawnandpawn.app.core.stats.WeeklySummary
import com.yawnandpawn.app.core.stats.WeeklySummaryText
import com.yawnandpawn.app.core.stats.text
import com.yawnandpawn.app.core.summary.WeeklySummaryNotifier

/**
 * The weekly summary notification (Story 6.8, FR-PRG-5): channel [CHANNEL_ID] "Weekly summary" at default importance,
 * the app name as title, the EXPERIENCE.md text of [WeeklySummary.text] (amounts per currency through [moneyFormatter],
 * sorted by currency code and joined with " + ", as everywhere else), the monochrome alarm glyph with no tint, and a tap
 * that opens Progress ([MainActivity.EXTRA_OPEN_TAB]) and clears it.
 *
 * It posts only with notifications enabled and, on Android 13+, `POST_NOTIFICATIONS` granted ([canPost]); it never asks
 * for the permission (it runs in the background, with no activity). A post the system refuses, or a notification
 * service that throws, is logged and returned as a failure, never thrown.
 */
class AndroidWeeklySummaryNotifier(
    private val context: Context,
    private val moneyFormatter: MoneyFormatter,
    private val logger: Logger,
) : WeeklySummaryNotifier {
    override fun canPost(): Boolean =
        NotificationManagerCompat.from(context).areNotificationsEnabled() &&
            (
                Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                    context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            )

    // A refused post (SecurityException) or a broken notification service is a failure value, never a crash (AD-12).
    @Suppress("TooGenericExceptionCaught")
    override fun post(summary: WeeklySummary): Outcome<Unit, DomainError> =
        try {
            val manager = context.getSystemService(NotificationManager::class.java)
            ensureChannel(manager)
            manager.notify(NOTIFICATION_ID, build(textOf(summary)))
            Outcome.Success(Unit)
        } catch (e: RuntimeException) {
            val error = DomainError.BackgroundWorkFailure("$POST: ${e::class.simpleName}")
            logger.log(LogEvent.OperationFailed.of(POST, error))
            Outcome.Failure(error)
        }

    /** The EXPERIENCE.md Key strings text of [summary], with the singular for one on-time morning. */
    internal fun textOf(summary: WeeklySummary): String =
        when (val text = summary.text()) {
            is WeeklySummaryText.NothingPaid -> {
                if (text.onTime == 1) {
                    context.getString(R.string.weekly_summary_nothing_paid_one)
                } else {
                    context.getString(R.string.weekly_summary_nothing_paid, text.onTime)
                }
            }

            is WeeklySummaryText.Paid -> {
                val paid = moneyFormatter.formatTotals(text.paid)
                if (text.onTime == 1) {
                    context.getString(R.string.weekly_summary_paid_one, paid)
                } else {
                    context.getString(R.string.weekly_summary_paid, text.onTime, paid)
                }
            }

            WeeklySummaryText.NoOnTime -> {
                context.getString(R.string.weekly_summary_none)
            }
        }

    private fun build(text: String): Notification =
        Notification
            .Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_alarm)
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(openProgress())
            .build()

    /** Opens the app on Progress: the existing MainActivity gets it as a new intent, else a new one starts. */
    private fun openProgress(): PendingIntent =
        PendingIntent.getActivity(
            context,
            REQUEST_OPEN_PROGRESS,
            Intent(context, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_OPEN_TAB, MainActivity.OPEN_PROGRESS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    /** Creates the "Weekly summary" channel at default importance if missing (the user may change it afterwards). */
    private fun ensureChannel(manager: NotificationManager) {
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val name = context.getString(R.string.notification_channel_weekly_summary)
        manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, name, NotificationManager.IMPORTANCE_DEFAULT))
    }

    companion object {
        const val CHANNEL_ID = "weekly_summary"
        const val NOTIFICATION_ID = 1_015

        /** The request code of the tap's PendingIntent. */
        const val REQUEST_OPEN_PROGRESS = 1_015

        private const val POST = "post weekly summary"
    }
}
