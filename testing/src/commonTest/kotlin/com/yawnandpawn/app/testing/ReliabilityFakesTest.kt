package com.yawnandpawn.app.testing

import com.yawnandpawn.app.core.reliability.ReliabilityItem
import com.yawnandpawn.app.core.reliability.ReliabilityStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReliabilityFakesTest {
    @Test
    fun `the probe returns its status and counts checks`() {
        val probe = FakeReliabilityProbe()
        assertEquals(ReliabilityStatus.ALL_OK, probe.check())
        probe.status = probe.status.copy(notificationsAllowed = false)
        assertFalse(probe.check().notificationsAllowed)
        assertEquals(2, probe.checks)
    }

    @Test
    fun `settings record what was opened and the permission is asked once`() {
        val settings = FakeReliabilitySettings()
        settings.open(ReliabilityItem.ExactAlarms)
        assertEquals(listOf(ReliabilityItem.ExactAlarms), settings.opened)

        val permission = FakeNotificationPermission(needed = true)
        assertTrue(permission.shouldRequest())
        permission.request()
        assertFalse(permission.shouldRequest())
        assertEquals(1, permission.requests)

        val failing = FakeNotificationPermission(needed = true, failure = IllegalStateException("no activity"))
        assertFailsWith<IllegalStateException> { failing.request() }
        assertTrue(failing.shouldRequest(), "still to ask")
    }
}
