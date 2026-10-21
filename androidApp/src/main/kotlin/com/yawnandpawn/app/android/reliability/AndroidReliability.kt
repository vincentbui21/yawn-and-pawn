package com.yawnandpawn.app.android.reliability

import android.Manifest
import android.annotation.SuppressLint
import android.app.ActivityManager
import android.app.AlarmManager
import android.app.NotificationManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import androidx.core.app.NotificationManagerCompat
import com.yawnandpawn.app.android.wake.WakeNotifier
import com.yawnandpawn.app.core.log.LogEvent
import com.yawnandpawn.app.core.log.Logger
import com.yawnandpawn.app.core.reliability.DeviceInfo
import com.yawnandpawn.app.core.reliability.DeviceInfoSource
import com.yawnandpawn.app.core.reliability.DeviceMaker
import com.yawnandpawn.app.core.reliability.NotificationPermission
import com.yawnandpawn.app.core.reliability.ReliabilityProbe
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
 * - Do Not Disturb: `NotificationManager.getCurrentInterruptionFilter()` decides alone when it is total silence
 *   (blocks alarms) or "alarms only" (lets them through), whatever the policy says. Otherwise (DND off, or priority
 *   only) on API 28+ the policy must have the alarms category; no policy counts as allowed. Known limit (review fix
 *   13): `getNotificationPolicy()` needs no permission but returns only the default policy, the one manual DND and a
 *   schedule rule without its own policy use (on API 35+ this app's own, which it never sets). A schedule rule with its
 *   own `ZenPolicy` (API 29+) or a mode with its own policy (API 35+) is invisible without `ACCESS_NOTIFICATION_POLICY`,
 *   which the app never asks for: such a rule that blocks alarms reads as allowed, and one that allows them while the
 *   default policy does not reads as blocked;
 * - battery: `PowerManager.isIgnoringBatteryOptimizations`, `ActivityManager.isBackgroundRestricted()` (API 28+) and
 *   the standby bucket `STANDBY_BUCKET_RESTRICTED` (API 30+, `UsageStatsManager.getAppStandbyBucket()`, the app's own);
 * - camera: `checkSelfPermission(CAMERA)`.
 *
 * The probe runs when Home starts and resumes, so a Story 5.3 read that throws (a missing service, an OEM's
 * SecurityException) never crashes Home: it is logged and counts as the good value (review fix 12), because a "Fix"
 * the user cannot clear is worse than a value the probe cannot read. The Story 1.19 reads are unchanged.
 */
class AndroidReliabilityProbe(
    private val context: Context,
    private val sdkInt: Int = Build.VERSION.SDK_INT,
    /** The API 34+ full-screen intent grant; a seam because Robolectric has no shadow for it. */
    private val canUseFullScreenIntent: () -> Boolean = { platformCanUseFullScreenIntent(context) },
    private val logger: Logger = Logger {},
) : ReliabilityProbe {
    @SuppressLint("NewApi", "InlinedApi") // Each call is behind its own SDK check (sdkInt is injectable for tests).
    override fun check(): ReliabilityStatus =
        ReliabilityStatus(
            notificationsAllowed = NotificationManagerCompat.from(context).areNotificationsEnabled() && alarmChannelOn(),
            fullScreenIntentAllowed = sdkInt < Build.VERSION_CODES.UPSIDE_DOWN_CAKE || canUseFullScreenIntent(),
            exactAlarmsAllowed =
                sdkInt !in Build.VERSION_CODES.S..Build.VERSION_CODES.S_V2 ||
                    context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms(),
            dndAllowsAlarms = guarded("Do Not Disturb", good = true) { dndAllowsAlarms() },
            batteryOptimizationIgnored =
                guarded("battery optimisation", good = true) {
                    context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)
                },
            backgroundRestricted =
                sdkInt >= Build.VERSION_CODES.P &&
                    guarded("background restriction", good = false) {
                        context.getSystemService(ActivityManager::class.java).isBackgroundRestricted
                    },
            standbyBucketRestricted =
                sdkInt >= Build.VERSION_CODES.R &&
                    guarded("standby bucket", good = false) { standbyBucket() == UsageStatsManager.STANDBY_BUCKET_RESTRICTED },
            cameraGranted =
                guarded("camera permission", good = true) {
                    context.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
                },
        )

    /** [read], or [good] (logged) when the platform throws: a phone's broken service never crashes Home. */
    @Suppress("TooGenericExceptionCaught") // Any platform failure: a SecurityException, a null service, a dead system.
    private inline fun guarded(
        what: String,
        good: Boolean,
        read: () -> Boolean,
    ): Boolean =
        try {
            read()
        } catch (e: RuntimeException) {
            logger.log(LogEvent.OperationFailed("read $what", e::class.simpleName.orEmpty()))
            good
        }

    /** The app's own standby bucket (no permission needed for its own); read on API 30+ only. */
    @SuppressLint("NewApi")
    private fun standbyBucket(): Int = context.getSystemService(UsageStatsManager::class.java).appStandbyBucket

    /**
     * Alarms get through Do Not Disturb. The interruption filter decides alone when DND is total silence (false) or
     * "alarms only" (true). Otherwise (off or priority only) on API 28+ the default policy needs the alarms category: a
     * manual DND and a schedule rule without its own policy use it (the class KDoc lists what it cannot see). Below 28 a
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
 * The Android [DeviceInfoSource] (Story 5.3): the API level, `Build.MANUFACTURER` and `Build.FINGERPRINT`, and the
 * maker the guidance knows the phone as, from the manufacturer, then `Build.BRAND` ([DeviceMaker.of], Story 5.5).
 */
class AndroidDeviceInfoSource(
    private val sdkInt: Int = Build.VERSION.SDK_INT,
    private val maker: String = Build.MANUFACTURER.orEmpty(),
    private val fingerprint: String = Build.FINGERPRINT.orEmpty(),
    private val brand: String = Build.BRAND.orEmpty(),
) : DeviceInfoSource {
    override fun current(): DeviceInfo =
        DeviceInfo(sdkInt = sdkInt, maker = maker, fingerprint = fingerprint, deviceMaker = DeviceMaker.of(maker, brand))
}
