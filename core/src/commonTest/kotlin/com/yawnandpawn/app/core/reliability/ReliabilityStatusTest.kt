package com.yawnandpawn.app.core.reliability

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReliabilityStatusTest {
    @Test
    fun `all allowed is OK with nothing to fix`() {
        assertTrue(ReliabilityStatus.ALL_OK.allOk)
        assertNull(ReliabilityStatus.ALL_OK.firstFailing)
    }

    @Test
    fun `the first failing item is notifications, then the full-screen intent, then exact alarms`() {
        val none = ReliabilityStatus(notificationsAllowed = false, fullScreenIntentAllowed = false, exactAlarmsAllowed = false)

        assertEquals(ReliabilityItem.Notifications, none.firstFailing)
        assertEquals(ReliabilityItem.FullScreenIntent, none.copy(notificationsAllowed = true).firstFailing)
        assertEquals(ReliabilityItem.ExactAlarms, ReliabilityStatus.ALL_OK.copy(exactAlarmsAllowed = false).firstFailing)
        assertFalse(ReliabilityStatus.ALL_OK.copy(exactAlarmsAllowed = false).allOk)
    }
}
