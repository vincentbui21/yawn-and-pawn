package com.yawnandpawn.app.android.reliability

import android.Manifest
import android.annotation.SuppressLint
import android.app.ActivityManager
import android.app.AlarmManager
import android.app.NotificationManager
import android.app.usage.UsageStatsManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import com.yawnandpawn.app.android.wake.WakeNotifier
import com.yawnandpawn.app.core.log.LogEvent
import com.yawnandpawn.app.core.log.Logger
import com.yawnandpawn.app.core.reliability.DeviceInfo
import com.yawnandpawn.app.core.reliability.DeviceInfoSource
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
 *
 * Story 5.3 adds, each read only on the API level that has it (below it the value is the good one):
 * - Do Not Disturb: `NotificationManager.getCurrentInterruptionFilter()`; total silence blocks alarms, "alarms only"
 *   lets them through, and otherwise on API 28+ the policy (`getNotificationPolicy()`, no permission needed; a scheduled
 *   rule's too) must have the alarms category. No policy counts as allowed;
 * - battery: `PowerManager.isIgnoringBatteryOptimizations`, `ActivityManager.isBackgroundRestricted()` (API 28+) and
 *   the standby bucket `STANDBY_BUCKET_RESTRICTED` (API 30+, `UsageStatsManager.getAppStandbyBucket()`, the app's own);
 * - camera: `checkSelfPermission(CAMERA)`.
 */
class AndroidReliabilityProbe(
    private val context: Context,
    private val sdkInt: Int = Build.VERSION.SDK_INT,
    /** The API 34+ full-screen intent grant; a seam because Robolectric has no shadow for it. */
    private val canUseFullScreenIntent: () -> Boolean = { platformCanUseFullScreenIntent(context) },
) : ReliabilityProbe {
    @SuppressLint("NewApi", "InlinedApi") // Each call is behind its own SDK check (sdkInt is injectable for tests).
    override fun check(): ReliabilityStatus =
        ReliabilityStatus(
            notificationsAllowed = NotificationManagerCompat.from(context).areNotificationsEnabled() && alarmChannelOn(),
            fullScreenIntentAllowed = sdkInt < Build.VERSION_CODES.UPSIDE_DOWN_CAKE || canUseFullScreenIntent(),
            exactAlarmsAllowed =
                sdkInt !in Build.VERSION_CODES.S..Build.VERSION_CODES.S_V2 ||
                    context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms(),
            dndAllowsAlarms = dndAllowsAlarms(),
            batteryOptimizationIgnored =
                context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName),
            backgroundRestricted =
                sdkInt >= Build.VERSION_CODES.P && context.getSystemService(ActivityManager::class.java).isBackgroundRestricted,
            standbyBucketRestricted = sdkInt >= Build.VERSION_CODES.R && standbyBucket() == UsageStatsManager.STANDBY_BUCKET_RESTRICTED,
            cameraGranted = context.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED,
        )

    /** The app's own standby bucket (no permission needed for its own); read on API 30+ only. */
    @SuppressLint("NewApi")
    private fun standbyBucket(): Int = context.getSystemService(UsageStatsManager::class.java).appStandbyBucket

    /**
     * Alarms get through Do Not Disturb: not total silence, and "alarms only" always lets them through. Otherwise (off or
     * priority only) on API 28+ the policy needs the alarms category: a scheduled rule uses it when it starts. Below 28 a
     * priority mode always let alarms through.
     */
    @SuppressLint("NewApi") // PRIORITY_CATEGORY_ALARMS is read on API 28+ only.
    private fun dndAllowsAlarms(): Boolean {
        val notifications = context.getSystemService(NotificationManager::class.java)
        return when (notifications.currentInterruptionFilter) {
            NotificationManager.INTERRUPTION_FILTER_NONE -> false
            NotificationManager.INTERRUPTION_FILTER_ALARMS -> true
            else -> sdkInt < Build.VERSION_CODES.P || notifications.notificationPolicy.allowsAlarms()
        }
    }

    /**
     * The ringing notification's channel ("Alarms", created on the first ring) is not blocked: a blocked channel shows
     * no notification and no full-screen intent even with app notifications on. Not created yet counts as on.
     */
    private fun alarmChannelOn(): Boolean =
        context.getSystemService(NotificationManager::class.java).getNotificationChannel(WakeNotifier.CHANNEL_ID)?.importance !=
            NotificationManager.IMPORTANCE_NONE
}

/** The policy lets alarms through; no policy (some phones return none) counts as allowed. */
@SuppressLint("InlinedApi") // Read on API 28+ only (AndroidReliabilityProbe.dndAllowsAlarms).
private fun NotificationManager.Policy?.allowsAlarms(): Boolean =
    this == null || (priorityCategories and NotificationManager.Policy.PRIORITY_CATEGORY_ALARMS) != 0

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
    /** Opens [item]'s screen, else the app details; a phone with neither (or that refuses) logs it, never crashes. */
    override fun open(item: ReliabilityItem) {
        if (!tryStart(intentFor(item))) tryStart(appDetails())
    }

    private fun tryStart(intent: Intent): Boolean =
        try {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        } catch (e: ActivityNotFoundException) {
            logger.log(LogEvent.OperationFailed("open reliability setting", e::class.simpleName.orEmpty()))
            false
        } catch (e: SecurityException) {
            logger.log(LogEvent.OperationFailed("open reliability setting", e::class.simpleName.orEmpty()))
            false
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
    // Device-protected like every app file (Story 2.3, detekt CredentialStorageAccess); read only with the app open.
    private val prefs = context.createDeviceProtectedStorageContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @Volatile
    private var launcher: (() -> Unit)? = null

    /** The screen in front can show the dialog through [launch] (the newest attach wins). */
    @Synchronized
    fun attach(launch: () -> Unit) {
        launcher = launch
    }

    /** The screen that attached [launch] stopped; a newer screen's launcher stays. */
    @Synchronized
    fun detach(launch: () -> Unit) {
        if (launcher === launch) launcher = null
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
        // Remembered only once the dialog was launched: a launch that throws leaves it to be asked again.
        launch()
        prefs.edit().putBoolean(KEY_ASKED, true).apply()
    }

    private companion object {
        const val PREFS = "reliability"
        const val KEY_ASKED = "notification_permission_asked"
    }
}

/**
 * The Android [DeviceInfoSource] (Story 5.3): the API level, `Build.MANUFACTURER` and `Build.FINGERPRINT`. No maker is
 * covered until Story 5.5 brings the maker guidance.
 */
class AndroidDeviceInfoSource(
    private val sdkInt: Int = Build.VERSION.SDK_INT,
    private val maker: String = Build.MANUFACTURER.orEmpty(),
    private val fingerprint: String = Build.FINGERPRINT.orEmpty(),
) : DeviceInfoSource {
    override fun current(): DeviceInfo = DeviceInfo(sdkInt = sdkInt, maker = maker, fingerprint = fingerprint, makerCovered = false)
}
