package com.yawnandpawn.app.testing

import com.yawnandpawn.app.core.reliability.NotificationPermission
import com.yawnandpawn.app.core.reliability.ReliabilityItem
import com.yawnandpawn.app.core.reliability.ReliabilityProbe
import com.yawnandpawn.app.core.reliability.ReliabilitySettings
import com.yawnandpawn.app.core.reliability.ReliabilityStatus

/** [ReliabilityProbe] returning [status]; [checks] counts the calls. */
class FakeReliabilityProbe(
    var status: ReliabilityStatus = ReliabilityStatus.ALL_OK,
) : ReliabilityProbe {
    var checks = 0
        private set

    override fun check(): ReliabilityStatus {
        checks++
        return status
    }
}

/** [ReliabilitySettings] that records every item it was asked to open. */
class FakeReliabilitySettings : ReliabilitySettings {
    val opened = mutableListOf<ReliabilityItem>()

    override fun open(item: ReliabilityItem) {
        opened += item
    }
}

/**
 * [NotificationPermission] that needs asking while [needed]; a request clears it, like the real "asked once". With
 * [failure] set, a request throws it (the system dialog could not be launched) and stays needed.
 */
class FakeNotificationPermission(
    var needed: Boolean = false,
    var failure: Exception? = null,
) : NotificationPermission {
    var requests = 0
        private set

    override fun shouldRequest(): Boolean = needed

    override fun request() {
        requests++
        failure?.let { throw it }
        needed = false
    }
}
