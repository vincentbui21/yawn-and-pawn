package com.yawnandpawn.app.android.reliability

import android.annotation.SuppressLint
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import com.yawnandpawn.app.android.wake.WakeNotifier
import com.yawnandpawn.app.core.log.LogEvent
import com.yawnandpawn.app.core.log.Logger
import com.yawnandpawn.app.core.reliability.ChecklistRow
import com.yawnandpawn.app.core.reliability.ReliabilitySettings

/**
 * The Android [ReliabilitySettings] (Story 5.4): the system screen of each checklist row, opened from "Fix" on the
 * checklist (a user tap on the screen in front, so never from the background).
 *
 * Every target is looked up with `resolveActivity` first and is started only when this phone resolves it: the row's own
 * screen ([intentFor]), else the app's details page, else the system settings. A start that throws falls through to the
 * next one. When none opens, it is logged and [open] answers false; it never crashes. The manifest's `<queries>` names
 * each settings action, so API 30+ can resolve them.
 */
class AndroidReliabilitySettings(
    private val context: Context,
    private val logger: Logger,
    private val sdkInt: Int = Build.VERSION.SDK_INT,
    /** Whether the app is exempt from battery optimisation now (a test seam); null reads `PowerManager` at the tap. */
    private val batteryExempt: (() -> Boolean)? = null,
    /** Whether only the Alarms channel is blocked now (a test seam); null reads `NotificationManager` at the tap. */
    private val alarmChannelBlocked: (() -> Boolean)? = null,
) : ReliabilitySettings {
    override fun open(row: ChecklistRow): Boolean {
        val opened = targets(row).any { target -> resolves(target) && tryStart(target) }
        if (!opened) logger.log(LogEvent.OperationFailed(OPEN, "no settings screen resolves"))
        return opened
    }

    /** What "Fix" on [row] tries, in order: its own screen, the app details, the system settings (no repeats). */
    fun targets(row: ChecklistRow): List<Intent> =
        listOf(intentFor(row), appDetails(), Intent(Settings.ACTION_SETTINGS)).distinctBy { it.action }

    /**
     * The screen for [row] on this phone's API level:
     * - Notifications: the Alarms channel's settings when app notifications are on but that channel is blocked, else the
     *   app's notification settings;
     * - Full-screen alarm (API 34+) and Exact alarms (API 31+): their special app access page for this app;
     * - Do Not Disturb: its priority settings, where alarms are allowed;
     * - Battery: from API 31 (Android 12) always this app's App info page, whose Battery page has the optimisation
     *   switch (and background restriction), so the user lands on the app's own page and not the list of all apps
     *   (0.1.7 polish). Below API 31 App info has no such switch, so the battery optimisation list (no permission
     *   needed) while the app is not exempt, and App info once exempt. The app never sends
     *   REQUEST_IGNORE_BATTERY_OPTIMIZATIONS: Google Play allows it only when Doze breaks an app's core function, and
     *   `setAlarmClock` alarms fire in Doze;
     * - everything else (Camera, Manufacturer, Microphone, Test alarm, and the screens an API level lacks): app details.
     */
    @SuppressLint("InlinedApi") // Each action is used only on the API level that has it.
    fun intentFor(row: ChecklistRow): Intent =
        when {
            row == ChecklistRow.Notifications && onlyAlarmChannelBlocked() -> {
                Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                    .putExtra(Settings.EXTRA_CHANNEL_ID, WakeNotifier.CHANNEL_ID)
            }

            row == ChecklistRow.Notifications -> {
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            }

            row == ChecklistRow.FullScreenAlarm && sdkInt >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> {
                Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, packageUri())
            }

            row == ChecklistRow.ExactAlarms && sdkInt >= Build.VERSION_CODES.S -> {
                Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, packageUri())
            }

            row == ChecklistRow.DoNotDisturb -> {
                Intent(Settings.ACTION_ZEN_MODE_PRIORITY_SETTINGS)
            }

            row == ChecklistRow.Battery && sdkInt < Build.VERSION_CODES.S && !(batteryExempt?.invoke() ?: platformBatteryExempt()) -> {
                Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
            }

            else -> {
                appDetails()
            }
        }

    // A settings lookup may throw on an OEM build: counts as not resolved.
    @Suppress("DEPRECATION", "TooGenericExceptionCaught") // The int-flags overload covers API 26+.
    private fun resolves(intent: Intent): Boolean =
        try {
            context.packageManager.resolveActivity(intent, 0) != null
        } catch (e: RuntimeException) {
            logger.log(LogEvent.OperationFailed(RESOLVE, e::class.simpleName.orEmpty()))
            false
        }

    // A resolved screen may still refuse the caller: ActivityNotFound, Security or anything else falls through.
    @Suppress("TooGenericExceptionCaught")
    private fun tryStart(intent: Intent): Boolean =
        try {
            context.startActivity(Intent(intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        } catch (e: RuntimeException) {
            logger.log(LogEvent.OperationFailed(OPEN, e::class.simpleName.orEmpty()))
            false
        }

    /** `PowerManager.isIgnoringBatteryOptimizations` for this app; a read that throws is logged and counts as not exempt. */
    @Suppress("TooGenericExceptionCaught") // An OEM's broken service: the optimisation list is the safe screen to offer.
    private fun platformBatteryExempt(): Boolean =
        try {
            context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)
        } catch (e: RuntimeException) {
            logger.log(LogEvent.OperationFailed(READ_BATTERY, e::class.simpleName.orEmpty()))
            false
        }

    /**
     * App notifications are on but the Alarms channel ([WakeNotifier.CHANNEL_ID]) is blocked; a read that throws is logged
     * and counts as not blocked (the app's notification settings, which also list the channel).
     */
    private fun onlyAlarmChannelBlocked(): Boolean = alarmChannelBlocked?.invoke() ?: platformAlarmChannelBlocked()

    @Suppress("TooGenericExceptionCaught") // An OEM's broken service: the app's notification page is the safe screen.
    private fun platformAlarmChannelBlocked(): Boolean =
        try {
            val notifications = context.getSystemService(NotificationManager::class.java)
            notifications.areNotificationsEnabled() &&
                notifications.getNotificationChannel(WakeNotifier.CHANNEL_ID)?.importance == NotificationManager.IMPORTANCE_NONE
        } catch (e: RuntimeException) {
            logger.log(LogEvent.OperationFailed(READ_CHANNEL, e::class.simpleName.orEmpty()))
            false
        }

    private fun appDetails() = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri())

    private fun packageUri(): Uri = Uri.parse("package:${context.packageName}")

    companion object {
        /** The log operation of a battery exemption read that threw. */
        const val READ_BATTERY = "read battery exemption"

        /** The log operation of an Alarms channel read that threw. */
        const val READ_CHANNEL = "read alarms channel"

        /** The log operation of a settings screen that could not be opened. */
        const val OPEN = "open reliability setting"

        /** The log operation of a settings lookup that threw. */
        const val RESOLVE = "resolve reliability setting"
    }
}
