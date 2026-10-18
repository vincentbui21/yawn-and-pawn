package com.yawnandpawn.app.ui.nav

import androidx.navigation3.runtime.NavKey
import com.yawnandpawn.app.ui.shell.AppTab
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class TabNavigationTest {
    private fun stack(vararg routes: Route): MutableList<NavKey> = mutableListOf<NavKey>(*routes)

    @Test
    fun `a tab other than Alarms sits on the Alarms root and replaces another tab`() {
        val backStack = stack(Route.Alarms)

        backStack.selectTab(AppTab.Progress)
        assertEquals(listOf<NavKey>(Route.Alarms, Route.Progress), backStack)
        backStack.selectTab(AppTab.Settings)
        assertEquals(listOf<NavKey>(Route.Alarms, Route.Settings), backStack)
        backStack.selectTab(AppTab.You)
        backStack.selectTab(AppTab.You)
        assertEquals(listOf<NavKey>(Route.Alarms, Route.You), backStack)
        backStack.selectTab(AppTab.Alarms)
        assertEquals(listOf<NavKey>(Route.Alarms), backStack)
    }

    @Test
    fun `Back on Progress, Settings or You returns to Alarms, and on Alarms does nothing so the app exits`() {
        listOf(AppTab.Progress, AppTab.Settings, AppTab.You).forEach { tab ->
            val backStack = stack(Route.Alarms)
            backStack.selectTab(tab)

            backStack.pop()

            assertEquals(listOf<NavKey>(Route.Alarms), backStack, "Back on $tab")
        }
        val root = stack(Route.Alarms)
        root.pop()
        assertEquals(listOf<NavKey>(Route.Alarms), root)
    }

    @Test
    fun `plus pushes a new alarm's editor from any tab, and closing it lands on Home`() {
        AppTab.entries.forEach { tab ->
            val backStack = stack(Route.Alarms)
            backStack.selectTab(tab)

            backStack.openEditor(alarmId = null)
            assertEquals(listOf<NavKey>(Route.Alarms, Route.AlarmEditor(null)), backStack, "+ on $tab")

            backStack.close(Route.AlarmEditor(null))
            assertEquals(listOf<NavKey>(Route.Alarms), backStack, "after Save or Cancel from $tab")
        }
    }

    @Test
    fun `a second open, a tab tap or a late close while the editor is on top change nothing`() {
        val backStack = stack(Route.Alarms)
        backStack.openEditor("a")

        backStack.openEditor(null)
        backStack.selectTab(AppTab.Progress)
        assertEquals(listOf<NavKey>(Route.Alarms, Route.AlarmEditor("a")), backStack)

        backStack.close(Route.AlarmEditor("a"))
        backStack.close(Route.AlarmEditor("a"))
        assertEquals(listOf<NavKey>(Route.Alarms), backStack)
    }

    @Test
    fun `Duplicate replaces the open editor with a new alarm prefilled from it`() {
        val backStack = stack(Route.Alarms, Route.AlarmEditor("a"))

        backStack.replaceEditor(Route.AlarmEditor("a"), "a")
        backStack.replaceEditor(Route.AlarmEditor("a"), "a")

        assertEquals(listOf<NavKey>(Route.Alarms, Route.AlarmEditor(alarmId = null, copyOf = "a")), backStack)
    }

    @Test
    fun `Duplicate on Home opens the editor on a new alarm prefilled from the card`() {
        val backStack = stack(Route.Alarms)

        backStack.openEditor(alarmId = null, copyOf = "a")

        assertEquals(listOf<NavKey>(Route.Alarms, Route.AlarmEditor(alarmId = null, copyOf = "a")), backStack)
    }

    @Test
    fun `every tab has its route and back`() {
        AppTab.entries.forEach { tab -> assertEquals(tab, tab.route().tab) }
        assertNull(Route.AlarmEditor(null).tab)
        assertNull(Route.PurchaseHistory.tab)
        assertNull(Route.Payments.tab)
    }

    @Test
    fun `Purchase history is pushed over You once, tabs and plus wait under it, and Back returns to You`() {
        val backStack = stack(Route.Alarms)
        backStack.selectTab(AppTab.You)

        backStack.openPurchaseHistory()
        backStack.openPurchaseHistory()
        backStack.selectTab(AppTab.Settings)
        backStack.openEditor(alarmId = null)
        assertEquals(listOf<NavKey>(Route.Alarms, Route.You, Route.PurchaseHistory), backStack)

        backStack.close(Route.PurchaseHistory)
        assertEquals(listOf<NavKey>(Route.Alarms, Route.You), backStack)

        backStack.openPurchaseHistory()
        backStack.pop()
        assertEquals(listOf<NavKey>(Route.Alarms, Route.You), backStack)
    }

    @Test
    fun `Problem with a charge is pushed over Purchase history once, only from there, and Back returns to it`() {
        val backStack = stack(Route.Alarms, Route.You)
        backStack.openProblemWithCharge()
        assertEquals(listOf<NavKey>(Route.Alarms, Route.You), backStack)
        assertNull(Route.ProblemWithCharge.tab)

        backStack.openPurchaseHistory()
        backStack.openProblemWithCharge()
        backStack.openProblemWithCharge()
        backStack.openPurchaseHistory()
        assertEquals(listOf<NavKey>(Route.Alarms, Route.You, Route.PurchaseHistory, Route.ProblemWithCharge), backStack)

        backStack.close(Route.ProblemWithCharge)
        assertEquals(listOf<NavKey>(Route.Alarms, Route.You, Route.PurchaseHistory), backStack)

        backStack.openProblemWithCharge()
        assertEquals(true, backStack.applySessionLock(active = true))
        backStack.openProblemWithCharge()
        assertEquals(listOf<NavKey>(Route.SessionInProgress), backStack)
    }

    @Test
    fun `the maker guidance is pushed over the tab on top once, never over a pushed screen or the lock, and closes to the tab`() {
        val backStack = stack(Route.Alarms, Route.Settings)
        assertNull(Route.ManufacturerGuidance.tab)

        backStack.openManufacturerGuidance()
        backStack.openManufacturerGuidance()
        backStack.selectTab(AppTab.You)
        backStack.openEditor(alarmId = null)
        assertEquals(listOf<NavKey>(Route.Alarms, Route.Settings, Route.ManufacturerGuidance), backStack)

        backStack.close(Route.ManufacturerGuidance)
        backStack.close(Route.ManufacturerGuidance)
        assertEquals(listOf<NavKey>(Route.Alarms, Route.Settings), backStack)

        backStack.openManufacturerGuidance()
        backStack.pop()
        assertEquals(listOf<NavKey>(Route.Alarms, Route.Settings), backStack)

        val overHistory = stack(Route.Alarms, Route.You, Route.PurchaseHistory)
        overHistory.openManufacturerGuidance()
        assertEquals(listOf<NavKey>(Route.Alarms, Route.You, Route.PurchaseHistory), overHistory)

        backStack.openManufacturerGuidance()
        assertEquals(true, backStack.applySessionLock(active = true))
        backStack.openManufacturerGuidance()
        assertEquals(listOf<NavKey>(Route.SessionInProgress), backStack)
    }

    @Test
    fun `Payments is pushed over You once, and Back returns to You`() {
        val backStack = stack(Route.Alarms)
        backStack.selectTab(AppTab.You)

        backStack.openPayments()
        backStack.openPayments()
        backStack.selectTab(AppTab.Settings)
        backStack.openEditor(alarmId = null)
        assertEquals(listOf<NavKey>(Route.Alarms, Route.You, Route.Payments), backStack)
        assertNull(Route.Payments.tab)

        backStack.close(Route.Payments)
        assertEquals(listOf<NavKey>(Route.Alarms, Route.You), backStack)

        backStack.openPayments()
        backStack.pop()
        assertEquals(listOf<NavKey>(Route.Alarms, Route.You), backStack)
    }

    @Test
    fun `Payments pushes Purchase history or Problem with a charge once each, and Back walks back to Payments`() {
        val backStack = stack(Route.Alarms, Route.You, Route.Payments)

        backStack.openPurchaseHistory()
        backStack.openPurchaseHistory()
        backStack.openPayments()
        assertEquals(listOf<NavKey>(Route.Alarms, Route.You, Route.Payments, Route.PurchaseHistory), backStack)
        // Purchase history pushed from Payments still pushes "Problem with a charge?".
        backStack.openProblemWithCharge()
        backStack.openProblemWithCharge()
        assertEquals(
            listOf<NavKey>(Route.Alarms, Route.You, Route.Payments, Route.PurchaseHistory, Route.ProblemWithCharge),
            backStack,
        )
        backStack.close(Route.ProblemWithCharge)
        backStack.close(Route.PurchaseHistory)
        assertEquals(listOf<NavKey>(Route.Alarms, Route.You, Route.Payments), backStack)

        backStack.openProblemWithCharge()
        backStack.openProblemWithCharge()
        backStack.openPurchaseHistory()
        assertEquals(listOf<NavKey>(Route.Alarms, Route.You, Route.Payments, Route.ProblemWithCharge), backStack)
        backStack.pop()
        assertEquals(listOf<NavKey>(Route.Alarms, Route.You, Route.Payments), backStack)
    }

    @Test
    fun `a session replaces Payments and anything over it with the lock, and nothing pushes over the lock`() {
        val backStack = stack(Route.Alarms, Route.You, Route.Payments, Route.PurchaseHistory)

        assertEquals(true, backStack.applySessionLock(active = true))
        backStack.openPayments()
        backStack.openPurchaseHistory()
        backStack.openProblemWithCharge()
        assertEquals(listOf<NavKey>(Route.SessionInProgress), backStack)

        backStack.applySessionLock(active = false)
        assertEquals(listOf<NavKey>(Route.Alarms), backStack)
    }

    @Test
    fun `a session replaces Purchase history with the lock, and its end lands on Home`() {
        val backStack = stack(Route.Alarms, Route.You, Route.PurchaseHistory)

        assertEquals(true, backStack.applySessionLock(active = true))
        assertEquals(listOf<NavKey>(Route.SessionInProgress), backStack)
        backStack.openPurchaseHistory()
        assertEquals(listOf<NavKey>(Route.SessionInProgress), backStack)

        backStack.applySessionLock(active = false)
        assertEquals(listOf<NavKey>(Route.Alarms), backStack)
    }

    @Test
    fun `production Progress is this month's empty calendar with no other months`() {
        val state = emptyProgress(LocalDate(2027, 3, 17))

        val month = assertNotNull(state.calendar)
        assertEquals(LocalDate(2027, 3, 1), month.firstDay)
        assertEquals(emptyList(), month.days)
        assertEquals(false, month.hasPrevious || month.hasNext)
        assertNull(state.stats)
    }
}
