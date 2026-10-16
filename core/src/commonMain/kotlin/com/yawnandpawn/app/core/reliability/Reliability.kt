package com.yawnandpawn.app.core.reliability

/** A phone setting an alarm needs to ring reliably (Story 1.19); Epic 5 adds DND, battery and OEM items. */
enum class ReliabilityItem {
    /** The ringing notification and its full-screen intent can be shown (`POST_NOTIFICATIONS` on API 33+). */
    Notifications,

    /** The full-screen intent may open the wake screen over the lock screen (revocable on API 34+). */
    FullScreenIntent,

    /** Exact alarms may be scheduled (revocable on API 31-32). */
    ExactAlarms,
}

/**
 * What [ReliabilityProbe] found; [firstFailing] is the one Home's "Fix" opens. Story 5.3 adds the values the reliability
 * checklist reads ([ReliabilityChecklist]); each defaults to its good value, the one a phone below the API level that has
 * the setting always reads.
 *
 * @property dndAllowsAlarms Do Not Disturb lets alarms through: not total silence, and unless it is "alarms only", on API
 * 28+ the default policy has the alarms category (a schedule rule or mode with its own policy is not seen, Story 5.3).
 * @property batteryOptimizationIgnored the app is exempt from battery optimisation (Doze).
 * @property backgroundRestricted the user restricted the app's background work (API 28+).
 * @property standbyBucketRestricted the app sits in the restricted standby bucket (API 30+).
 * @property cameraGranted the camera permission is granted (the Camera row shows only for an alarm with a camera check).
 */
data class ReliabilityStatus(
    val notificationsAllowed: Boolean,
    val fullScreenIntentAllowed: Boolean,
    val exactAlarmsAllowed: Boolean,
    val dndAllowsAlarms: Boolean = true,
    val batteryOptimizationIgnored: Boolean = true,
    val backgroundRestricted: Boolean = false,
    val standbyBucketRestricted: Boolean = false,
    val cameraGranted: Boolean = true,
) {
    /** The checklist's Battery row: exempt from optimisation, not background restricted, not in the restricted bucket. */
    val batteryOk: Boolean
        get() = batteryOptimizationIgnored && !backgroundRestricted && !standbyBucketRestricted

    /**
     * The first of the three Story 1.19 items that fails, in the order notifications, full-screen intent, exact alarms;
     * null when all three are OK. The Story 5.3 values are not part of it: Home's banner stays on these three until Story
     * 5.4 moves it to [ReliabilityChecklist.alarmBlocking].
     */
    val firstFailing: ReliabilityItem?
        get() =
            when {
                !notificationsAllowed -> ReliabilityItem.Notifications
                !fullScreenIntentAllowed -> ReliabilityItem.FullScreenIntent
                !exactAlarmsAllowed -> ReliabilityItem.ExactAlarms
                else -> null
            }

    /** The three Story 1.19 items are OK (Home's banner). */
    val allOk: Boolean
        get() = firstFailing == null

    companion object {
        val ALL_OK = ReliabilityStatus(notificationsAllowed = true, fullScreenIntentAllowed = true, exactAlarmsAllowed = true)
    }
}

/** Reads the settings an alarm needs (FR-ALM-12, Story 1.19). Cheap and synchronous; Home asks on every start. */
fun interface ReliabilityProbe {
    fun check(): ReliabilityStatus
}

/** Opens the system screen where the user can turn [ReliabilityItem] on; only ever from a user tap ("Fix"). */
fun interface ReliabilitySettings {
    fun open(item: ReliabilityItem)
}

/**
 * The notification permission (`POST_NOTIFICATIONS`, API 33+), asked once, from the editor, after the first save of an
 * enabled alarm. Later the Home banner covers a missing permission.
 */
interface NotificationPermission {
    /** True when the system dialog should be shown: API 33+, not granted, never asked before. */
    fun shouldRequest(): Boolean

    /** Shows the system dialog from the screen in front; does nothing when no screen is in front (never from the background). */
    fun request()
}
