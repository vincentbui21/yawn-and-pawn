package com.yawnandpawn.app.ui.nav

import androidx.navigation3.runtime.NavKey
import com.yawnandpawn.app.ui.shell.AppTab
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Story 5.10 (FR-ONB-1): until onboarding is completed the back stack is only [Route.Onboarding], whatever was open,
 * with no nav capsule; once completed it becomes Home; the session lock still wins over it.
 */
class OnboardingNavigationTest {
    private val onboarding = listOf<NavKey>(Route.Onboarding)
    private val stacks: List<List<NavKey>> =
        listOf(
            listOf(Route.Alarms),
            listOf(Route.Alarms, Route.Settings),
            listOf(Route.Alarms, Route.AlarmEditor(alarmId = null)),
            listOf(Route.Alarms, Route.You, Route.PurchaseHistory, Route.ProblemWithCharge),
            listOf(Route.Alarms, Route.ManufacturerGuidance),
            listOf(Route.Alarms, Route.You, Route.Payments),
        )

    @Test
    fun `while onboarding is required any stack is replaced by onboarding alone`() {
        stacks.forEach { start ->
            val stack = start.toMutableList()

            assertTrue(stack.applyOnboarding(required = true), "$start")

            assertEquals(onboarding, stack, "from $start")
        }
        assertFalse(onboarding.toMutableList().applyOnboarding(required = true), "already onboarding")
    }

    @Test
    fun `nothing reachable from onboarding opens a tab or the editor`() {
        val stack = onboarding.toMutableList()

        AppTab.entries.forEach { stack.selectTab(it) }
        stack.openEditor(alarmId = null)
        stack.openPurchaseHistory(from = Route.You)
        stack.openPayments(from = Route.You)
        stack.openProblemWithCharge(from = Route.PurchaseHistory)
        stack.pop()

        assertEquals(onboarding, stack)
    }

    @Test
    fun `completing onboarding opens Home, and a completed app's stack is left alone`() {
        val stack = onboarding.toMutableList()

        assertTrue(stack.applyOnboarding(required = false))
        assertEquals(listOf<NavKey>(Route.Alarms), stack)

        stacks.forEach { start ->
            val unchanged = start.toMutableList()
            assertFalse(unchanged.applyOnboarding(required = false), "$start")
            assertEquals(start, unchanged)
        }
    }

    @Test
    fun `a session during onboarding shows the lock, and onboarding returns after it`() {
        val stack = onboarding.toMutableList()

        // AppNavHost applies the lock first and the gate only while unlocked.
        stack.applySessionLock(active = true)
        assertEquals(listOf<NavKey>(Route.SessionInProgress), stack)

        stack.applySessionLock(active = false)
        stack.applyOnboarding(required = true)
        assertEquals(onboarding, stack)
    }

    @Test
    fun `onboarding is not a tab, so the nav capsule hides`() {
        assertNull(Route.Onboarding.tab)
        AppTab.entries.forEach { assertTrue(it.route() != Route.Onboarding) }
    }
}
