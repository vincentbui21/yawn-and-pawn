package com.yawnandpawn.app.ui.nav

import androidx.navigation3.runtime.NavKey
import com.yawnandpawn.app.ui.shell.AppTab
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

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

            stack.openRequested(AppTab.Progress)

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

            stack.openRequested(AppTab.Progress)

            assertEquals(start, stack)
        }
    }

    @Test
    fun `a screen pushed over a tab closes first, but an open editor keeps the request from changing anything`() {
        listOf(
            listOf<NavKey>(Route.Alarms, Route.Reliability),
            listOf<NavKey>(Route.Alarms, Route.You, Route.PurchaseHistory, Route.ProblemWithCharge),
            listOf<NavKey>(Route.Alarms, Route.You, Route.Payments),
        ).forEach { start ->
            val stack = start.toMutableList()

            stack.openRequested(AppTab.Progress)

            assertEquals(listOf<NavKey>(Route.Alarms, Route.Progress), stack, "from $start")
        }
        val editing = mutableListOf<NavKey>(Route.Alarms, Route.AlarmEditor(alarmId = "a"))
        editing.openRequested(AppTab.Progress)
        assertEquals(listOf<NavKey>(Route.Alarms, Route.AlarmEditor(alarmId = "a")), editing)
    }
}
