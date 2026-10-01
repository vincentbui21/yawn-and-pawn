package com.yawnandpawn.app.android.reliability

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.NotificationManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import com.yawnandpawn.app.core.log.LogEvent
import com.yawnandpawn.app.core.log.Logger
import com.yawnandpawn.app.core.reliability.NotificationPermission
import com.yawnandpawn.app.core.reliability.ReliabilityItem
import com.yawnandpawn.app.core.reliability.ReliabilityProbe
import com.yawnandpawn.app.core.reliability.ReliabilitySettings
import com.yawnandpawn.app.core.reliability.ReliabilityStatus

/**
 * The Android [ReliabilityProbe] (Story 1.19):
 * - notifications: `NotificationManagerCompat.areNotificationsEnabled()` (false on API 33+ without `POST_NOTIFICATIONS`,
 *   when the ringing notification and its full-screen intent cannot show);
 * - full-screen intent: `NotificationManager.canUseFullScreenIntent()` on API 34+, always allowed below;
 * - exact alarms: `AlarmManager.canScheduleExactAlarms()` on API 31-32 only; API 33+ holds `USE_EXACT_ALARM`, and 30 and
 *   below need no grant.
 */
class AndroidReliabilityProbe(
    private val context: Context,
    private val sdkInt: Int = Build.VERSION.SDK_INT,
    /** The API 34+ full-screen intent grant; a seam because Robolectric has no shadow for it. */
    private val canUseFullScreenIntent: () -> Boolean = { platformCanUseFullScreenIntent(context) },
) : ReliabilityProbe {
    @SuppressLint("NewApi") // Each call is behind its own SDK check (sdkInt is injectable for tests).
    override fun check(): ReliabilityStatus =
        ReliabilityStatus(
            notificationsAllowed = NotificationManagerCompat.from(context).areNotificationsEnabled(),
            fullScreenIntentAllowed = sdkInt < Build.VERSION_CODES.UPSIDE_DOWN_CAKE || canUseFullScreenIntent(),
            exactAlarmsAllowed =
                sdkInt !in Build.VERSION_CODES.S..Build.VERSION_CODES.S_V2 ||
                    context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms(),
        )
}

/** `NotificationManager.canUseFullScreenIntent()`; [AndroidReliabilityProbe] calls it on API 34+ only. */
@SuppressLint("NewApi") // The only caller checks the SDK level first (AndroidReliabilityProbe.check).
private fun platformCanUseFullScreenIntent(context: Context): Boolean =
    context.getSystemService(NotificationManager::class.java).canUseFullScreenIntent()

/**
 * The Android [ReliabilitySettings]: the system screen for each item, opened from "Fix" on Home (a user tap, so never
 * from the background). A screen this phone does not have falls back to the app's details page.
 */
class AndroidReliabilitySettings(
    private val context: Context,
    private val logger: Logger,
    private val sdkInt: Int = Build.VERSION.SDK_INT,
) : ReliabilitySettings {
    override fun open(item: ReliabilityItem) {
        try {
            context.startActivity(intentFor(item).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: ActivityNotFoundException) {
            logger.log(LogEvent.OperationFailed("open reliability setting", e::class.simpleName.orEmpty()))
            context.startActivity(appDetails().addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    /** The settings screen for [item] on this phone's API level. */
    @SuppressLint("InlinedApi") // Each action is used only on the API level that has it.
    fun intentFor(item: ReliabilityItem): Intent =
        when {
            item == ReliabilityItem.Notifications -> {
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            }

            item == ReliabilityItem.FullScreenIntent && sdkInt >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> {
                Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, packageUri())
            }

            item == ReliabilityItem.ExactAlarms && sdkInt >= Build.VERSION_CODES.S -> {
                Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, packageUri())
            }

            else -> {
                appDetails()
            }
        }

    private fun appDetails() = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri())

    private fun packageUri(): Uri = Uri.parse("package:${context.packageName}")
}

/**
 * The Android [NotificationPermission]: asked once, ever (remembered in the app's preferences), on API 33+ while the
 * permission is not granted. The system dialog is launched by the screen in front, which `MainActivity` attaches
 * ([attach]) while it is started; with no screen attached nothing is requested and nothing is remembered.
 */
class AndroidNotificationPermission(
    private val context: Context,
    private val logger: Logger,
    private val sdkInt: Int = Build.VERSION.SDK_INT,
) : NotificationPermission {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @Volatile
    private var launcher: (() -> Unit)? = null

    /** The screen in front can show the dialog through [launch]; null when it stops. */
    fun attach(launch: (() -> Unit)?) {
        launcher = launch
    }

    override fun shouldRequest(): Boolean =
        sdkInt >= Build.VERSION_CODES.TIRAMISU &&
            !prefs.getBoolean(KEY_ASKED, false) &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED

    override fun request() {
        val launch = launcher
        if (launch == null) {
            logger.log(LogEvent.OperationFailed("request notification permission", "no screen in front"))
            return
        }
        prefs.edit().putBoolean(KEY_ASKED, true).apply()
        launch()
    }

    private companion object {
        const val PREFS = "reliability"
        const val KEY_ASKED = "notification_permission_asked"
    }
}
