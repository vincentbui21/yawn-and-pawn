package com.yawnandpawn.app.ui.nav

import androidx.navigation3.runtime.NavKey
import com.yawnandpawn.app.ui.shell.AppTab
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Story 6.8: the weekly summary notification asks the app to open Progress. The navigation selects the tab when a tab is
 * on top, and the request is dropped under the session lock, onboarding or the editor.
 */
class AppOpenRequestsTest {
    @Test
    fun `a request is held until handled, and handling another tab leaves it`() {
        val requests = AppOpenRequests()
        assertNull(requests.tab.value)

        requests.open(AppTab.Progress)
        requests.handled(AppTab.Settings)
        assertEquals(AppTab.Progress, requests.tab.value)

        requests.handled(AppTab.Progress)
        assertNull(requests.tab.value)
    }

    @Test
    fun `on a tab the request opens Progress`() {
        listOf(
            listOf<NavKey>(Route.Alarms),
            listOf<NavKey>(Route.Alarms, Route.Settings),
            listOf<NavKey>(Route.Alarms, Route.Progress),
        ).forEach { start ->
            val stack = start.toMutableList()

            assertTrue(stack.openRequested(AppTab.Progress), "shown from $start")

            assertEquals(listOf<NavKey>(Route.Alarms, Route.Progress), stack, "from $start")
        }
    }

    @Test
    fun `under the session lock, onboarding or the editor the request changes nothing`() {
        listOf(
            listOf<NavKey>(Route.SessionInProgress),
            listOf<NavKey>(Route.Onboarding),
            listOf<NavKey>(Route.Alarms, Route.AlarmEditor(alarmId = null)),
        ).forEach { start ->
            val stack = start.toMutableList()

            // Story 7.11: false, so a skip request taken here is dropped, never shown later.
            assertFalse(stack.openRequested(AppTab.Progress), "not shown from $start")

            assertEquals(start, stack)
        }
    }

    @Test
    fun `a screen pushed over a tab closes first, but an open editor or charge form keeps the request from changing anything`() {
        listOf(
            listOf<NavKey>(Route.Alarms, Route.Reliability),
            listOf<NavKey>(Route.Alarms, Route.You, Route.PurchaseHistory),
            listOf<NavKey>(Route.Alarms, Route.You, Route.Payments),
        ).forEach { start ->
            val stack = start.toMutableList()

            assertTrue(stack.openRequested(AppTab.Progress), "shown from $start")

            assertEquals(listOf<NavKey>(Route.Alarms, Route.Progress), stack, "from $start")
        }
        listOf(
            listOf<NavKey>(Route.Alarms, Route.AlarmEditor(alarmId = "a")),
            // The "Problem with a charge?" form keeps what the user typed.
            listOf<NavKey>(Route.Alarms, Route.You, Route.PurchaseHistory, Route.ProblemWithCharge),
            listOf<NavKey>(Route.Alarms, Route.You, Route.Payments, Route.ProblemWithCharge),
        ).forEach { start ->
            val stack = start.toMutableList()

            // Story 7.11: false, so a skip request taken here is dropped, never shown later.
            assertFalse(stack.openRequested(AppTab.Progress), "not shown from $start")

            assertEquals(start, stack)
        }
    }
}
