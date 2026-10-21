package com.yawnandpawn.app.core.reliability

/**
 * What [ReliabilityProbe] found (Story 1.19: notifications, the full-screen intent and exact alarms). Story 5.3 adds the
 * values the reliability checklist reads ([ReliabilityChecklist]); each defaults to its good value, the one a phone below
 * the API level that has the setting always reads. Since Story 5.4 the Home and Settings banner follows
 * [ReliabilityChecklist.alarmBlocking] over these values.
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

    companion object {
        val ALL_OK = ReliabilityStatus(notificationsAllowed = true, fullScreenIntentAllowed = true, exactAlarmsAllowed = true)
    }
}

/** Reads the settings an alarm needs (FR-ALM-12, Story 1.19). Cheap and synchronous; Home asks on every start. */
fun interface ReliabilityProbe {
    fun check(): ReliabilityStatus
}

/**
 * Opens the system screen where the user can fix a [ChecklistRow] (Story 5.4), only ever from a user tap ("Fix") on the
 * screen in front. A screen this phone does not resolve falls back to the app's details page, then to the system
 * settings; a target that does not resolve is never started.
 */
fun interface ReliabilitySettings {
    /** Opens [row]'s screen, or a fallback; false when nothing could be opened (logged, never thrown). */
    fun open(row: ChecklistRow): Boolean
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
