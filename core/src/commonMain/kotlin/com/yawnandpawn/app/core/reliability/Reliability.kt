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

/** What [ReliabilityProbe] found; [firstFailing] is the one "Fix" opens. */
data class ReliabilityStatus(
    val notificationsAllowed: Boolean,
    val fullScreenIntentAllowed: Boolean,
    val exactAlarmsAllowed: Boolean,
) {
    /** The first item that fails, in the order notifications, full-screen intent, exact alarms; null when all are OK. */
    val firstFailing: ReliabilityItem?
        get() =
            when {
                !notificationsAllowed -> ReliabilityItem.Notifications
                !fullScreenIntentAllowed -> ReliabilityItem.FullScreenIntent
                !exactAlarmsAllowed -> ReliabilityItem.ExactAlarms
                else -> null
            }

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
