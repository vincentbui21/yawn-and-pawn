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
