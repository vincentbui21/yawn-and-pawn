package com.yawnandpawn.app.ui.nav

import androidx.navigation3.runtime.NavKey
import com.yawnandpawn.app.ui.shell.AppTab
import kotlinx.datetime.LocalDate
import kotlinx.serialization.PolymorphicSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
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

        backStack.openPurchaseHistory(from = Route.You)
        backStack.openPurchaseHistory(from = Route.You)
        backStack.selectTab(AppTab.Settings)
        backStack.openEditor(alarmId = null)
        assertEquals(listOf<NavKey>(Route.Alarms, Route.You, Route.PurchaseHistory), backStack)

        backStack.close(Route.PurchaseHistory)
        assertEquals(listOf<NavKey>(Route.Alarms, Route.You), backStack)

        backStack.openPurchaseHistory(from = Route.You)
        backStack.pop()
        assertEquals(listOf<NavKey>(Route.Alarms, Route.You), backStack)
    }

    @Test
    fun `Purchase history is pushed over Progress once, tabs and plus wait under it, and Back returns to Progress`() {
        val backStack = stack(Route.Alarms)
        backStack.selectTab(AppTab.Progress)

        backStack.openPurchaseHistory(from = Route.Progress)
        backStack.openPurchaseHistory(from = Route.Progress)
        backStack.selectTab(AppTab.Settings)
        backStack.openEditor(alarmId = null)
        assertEquals(listOf<NavKey>(Route.Alarms, Route.Progress, Route.PurchaseHistory), backStack)
        assertNull(backStack.topTab())

        backStack.close(Route.PurchaseHistory)
        assertEquals(listOf<NavKey>(Route.Alarms, Route.Progress), backStack)

        backStack.openPurchaseHistory(from = Route.Progress)
        backStack.pop()
        assertEquals(listOf<NavKey>(Route.Alarms, Route.Progress), backStack)
    }

    @Test
    fun `Day detail is pushed over Progress once, tabs and plus wait under it, and Back returns to Progress (Story 6_7)`() {
        val day = LocalDate(2027, 3, 2)
        val backStack = stack(Route.Alarms, Route.Progress)

        backStack.openDayDetail(day)
        backStack.openDayDetail(day)
        backStack.openDayDetail(LocalDate(2027, 3, 1))
        backStack.selectTab(AppTab.Settings)
        backStack.openEditor(alarmId = null)
        backStack.openPurchaseHistory(from = Route.Progress)
        assertEquals(listOf<NavKey>(Route.Alarms, Route.Progress, Route.DayDetail(day)), backStack)
        assertNull(backStack.topTab())
        assertNull(Route.DayDetail(day).tab)

        backStack.close(Route.DayDetail(day))
        assertEquals(listOf<NavKey>(Route.Alarms, Route.Progress), backStack)
        backStack.close(Route.DayDetail(day))
        assertEquals(listOf<NavKey>(Route.Alarms, Route.Progress), backStack)

        backStack.openDayDetail(day)
        backStack.pop()
        assertEquals(listOf<NavKey>(Route.Alarms, Route.Progress), backStack)
    }

    @Test
    fun `Day detail opens only from Progress on top`() {
        val day = LocalDate(2027, 3, 2)
        listOf(
            stack(Route.Alarms),
            stack(Route.Alarms, Route.Settings),
            stack(Route.Alarms, Route.You),
            stack(Route.Alarms, Route.Progress, Route.PurchaseHistory),
            stack(Route.Alarms, Route.You, Route.PurchaseHistory),
            stack(Route.Alarms, Route.AlarmEditor(alarmId = null)),
            stack(Route.SessionInProgress),
            stack(Route.Onboarding),
        ).forEach { backStack ->
            val before = backStack.toList()
            backStack.openDayDetail(day)
            assertEquals(before, backStack)
        }
    }

    @Test
    fun `Day detail survives a saved back stack, its date kept (Story 6_7)`() {
        val json = Json { serializersModule = RouteSavedStateConfiguration.serializersModule }
        val saved: List<NavKey> = listOf(Route.Alarms, Route.Progress, Route.DayDetail(LocalDate(2027, 3, 28)))

        val serializer = ListSerializer(PolymorphicSerializer(NavKey::class))

        assertEquals(saved, json.decodeFromString(serializer, json.encodeToString(serializer, saved)))
    }

    @Test
    fun `Problem with a charge is pushed over Purchase history once, only from there, and Back returns to it`() {
        val backStack = stack(Route.Alarms, Route.You)
        backStack.openProblemWithCharge(from = Route.You)
        assertEquals(listOf<NavKey>(Route.Alarms, Route.You), backStack)
        assertNull(Route.ProblemWithCharge.tab)

        backStack.openPurchaseHistory(from = Route.You)
        backStack.openProblemWithCharge(from = Route.PurchaseHistory)
        backStack.openProblemWithCharge(from = Route.PurchaseHistory)
        backStack.openPurchaseHistory(from = Route.You)
        assertEquals(listOf<NavKey>(Route.Alarms, Route.You, Route.PurchaseHistory, Route.ProblemWithCharge), backStack)

        backStack.close(Route.ProblemWithCharge)
        assertEquals(listOf<NavKey>(Route.Alarms, Route.You, Route.PurchaseHistory), backStack)

        backStack.openProblemWithCharge(from = Route.PurchaseHistory)
        assertEquals(true, backStack.applySessionLock(active = true))
        backStack.openProblemWithCharge(from = Route.PurchaseHistory)
        backStack.openProblemWithCharge(from = Route.SessionInProgress)
        assertEquals(listOf<NavKey>(Route.SessionInProgress), backStack)
    }

    @Test
    fun `the checklist is pushed once over Settings or Home, only while that screen is on top, and Back returns there`() {
        val settings = stack(Route.Alarms, Route.Settings)
        assertNull(Route.Reliability.tab)

        settings.openReliability(from = Route.Settings)
        settings.openReliability(from = Route.Settings)
        settings.selectTab(AppTab.You)
        settings.openEditor(alarmId = null)
        assertEquals(listOf<NavKey>(Route.Alarms, Route.Settings, Route.Reliability), settings)

        settings.close(Route.Reliability)
        settings.close(Route.Reliability)
        assertEquals(listOf<NavKey>(Route.Alarms, Route.Settings), settings)

        val home = stack(Route.Alarms)
        home.openReliability(from = Route.Alarms)
        assertEquals(listOf<NavKey>(Route.Alarms, Route.Reliability), home)
        home.pop()
        assertEquals(listOf<NavKey>(Route.Alarms), home)

        // A late tap from a screen no longer in front, or from a screen that has no checklist entry, does nothing.
        val you = stack(Route.Alarms, Route.You)
        you.openReliability(from = Route.Alarms)
        you.openReliability(from = Route.You)
        assertEquals(listOf<NavKey>(Route.Alarms, Route.You), you)
        val editor = stack(Route.Alarms, Route.AlarmEditor(alarmId = null))
        editor.openReliability(from = Route.Alarms)
        assertEquals(listOf<NavKey>(Route.Alarms, Route.AlarmEditor(alarmId = null)), editor)
    }

    @Test
    fun `About is pushed once over Settings, only while Settings is on top, and closes to it (0_1_7 polish)`() {
        val settings = stack(Route.Alarms, Route.Settings)
        assertNull(Route.About.tab)

        settings.openAbout()
        settings.openAbout()
        assertEquals(listOf<NavKey>(Route.Alarms, Route.Settings, Route.About), settings)
        settings.close(Route.About)
        settings.close(Route.About)
        assertEquals(listOf<NavKey>(Route.Alarms, Route.Settings), settings)

        // Never over another tab, the checklist or the session lock.
        listOf(stack(Route.Alarms), stack(Route.Alarms, Route.You), stack(Route.Alarms, Route.Settings, Route.Reliability)).forEach {
            val before = it.toList()
            it.openAbout()
            assertEquals(before, it)
        }
        val locked = stack(Route.SessionInProgress)
        locked.openAbout()
        assertEquals(listOf<NavKey>(Route.SessionInProgress), locked)
    }

    @Test
    fun `the maker guidance is pushed over the checklist once, only from there, and closes to it`() {
        val backStack = stack(Route.Alarms, Route.Settings)
        assertNull(Route.ManufacturerGuidance.tab)
        backStack.openManufacturerGuidance()
        assertEquals(listOf<NavKey>(Route.Alarms, Route.Settings), backStack, "never over a tab")

        backStack.openReliability(from = Route.Settings)
        backStack.openManufacturerGuidance()
        backStack.openManufacturerGuidance()
        backStack.openReliability(from = Route.Settings)
        assertEquals(listOf<NavKey>(Route.Alarms, Route.Settings, Route.Reliability, Route.ManufacturerGuidance), backStack)

        backStack.close(Route.ManufacturerGuidance)
        assertEquals(listOf<NavKey>(Route.Alarms, Route.Settings, Route.Reliability), backStack)
        backStack.openManufacturerGuidance()
        backStack.pop()
        assertEquals(listOf<NavKey>(Route.Alarms, Route.Settings, Route.Reliability), backStack)
    }

    @Test
    fun `a session replaces the checklist and the maker guidance with the lock, and nothing pushes them over it`() {
        val backStack = stack(Route.Alarms, Route.Settings, Route.Reliability, Route.ManufacturerGuidance)

        assertEquals(true, backStack.applySessionLock(active = true))
        backStack.openReliability(from = Route.Settings)
        backStack.openReliability(from = Route.Alarms)
        backStack.openManufacturerGuidance()
        assertEquals(listOf<NavKey>(Route.SessionInProgress), backStack)

        assertEquals(true, backStack.applySessionLock(active = false))
        assertEquals(listOf<NavKey>(Route.Alarms), backStack)
    }

    @Test
    fun `Payments is pushed over You once, and Back returns to You`() {
        val backStack = stack(Route.Alarms)
        backStack.selectTab(AppTab.You)

        backStack.openPayments(from = Route.You)
        backStack.openPayments(from = Route.You)
        backStack.selectTab(AppTab.Settings)
        backStack.openEditor(alarmId = null)
        assertEquals(listOf<NavKey>(Route.Alarms, Route.You, Route.Payments), backStack)
        assertNull(Route.Payments.tab)

        backStack.close(Route.Payments)
        assertEquals(listOf<NavKey>(Route.Alarms, Route.You), backStack)

        backStack.openPayments(from = Route.You)
        backStack.pop()
        assertEquals(listOf<NavKey>(Route.Alarms, Route.You), backStack)
    }

    @Test
    fun `Payments pushes Purchase history or Problem with a charge once each, and Back walks back to Payments`() {
        val backStack = stack(Route.Alarms, Route.You, Route.Payments)

        backStack.openPurchaseHistory(from = Route.Payments)
        backStack.openPurchaseHistory(from = Route.Payments)
        backStack.openPayments(from = Route.You)
        assertEquals(listOf<NavKey>(Route.Alarms, Route.You, Route.Payments, Route.PurchaseHistory), backStack)
        // Purchase history pushed from Payments still pushes "Problem with a charge?".
        backStack.openProblemWithCharge(from = Route.PurchaseHistory)
        backStack.openProblemWithCharge(from = Route.PurchaseHistory)
        assertEquals(
            listOf<NavKey>(Route.Alarms, Route.You, Route.Payments, Route.PurchaseHistory, Route.ProblemWithCharge),
            backStack,
        )
        backStack.close(Route.ProblemWithCharge)
        backStack.close(Route.PurchaseHistory)
        assertEquals(listOf<NavKey>(Route.Alarms, Route.You, Route.Payments), backStack)

        backStack.openProblemWithCharge(from = Route.Payments)
        backStack.openProblemWithCharge(from = Route.Payments)
        backStack.openPurchaseHistory(from = Route.Payments)
        assertEquals(listOf<NavKey>(Route.Alarms, Route.You, Route.Payments, Route.ProblemWithCharge), backStack)
        backStack.pop()
        assertEquals(listOf<NavKey>(Route.Alarms, Route.You, Route.Payments), backStack)
    }

    @Test
    fun `a second, different row tapped on the screen below during the slide pushes nothing`() {
        // You: "How payments & refunds work", then "Purchase history" before Payments covers You.
        val fromYou = stack(Route.Alarms, Route.You)
        fromYou.openPayments(from = Route.You)
        fromYou.openPurchaseHistory(from = Route.You)
        assertEquals(listOf<NavKey>(Route.Alarms, Route.You, Route.Payments), fromYou)

        // You: "Purchase history", then "How payments & refunds work".
        val historyFirst = stack(Route.Alarms, Route.You)
        historyFirst.openPurchaseHistory(from = Route.You)
        historyFirst.openPayments(from = Route.You)
        assertEquals(listOf<NavKey>(Route.Alarms, Route.You, Route.PurchaseHistory), historyFirst)

        // Payments: "Purchase history", then "Problem with a charge?" (Purchase history's own link still works).
        val fromPayments = stack(Route.Alarms, Route.You, Route.Payments)
        fromPayments.openPurchaseHistory(from = Route.Payments)
        fromPayments.openProblemWithCharge(from = Route.Payments)
        assertEquals(listOf<NavKey>(Route.Alarms, Route.You, Route.Payments, Route.PurchaseHistory), fromPayments)
    }

    @Test
    fun `a session replaces Payments and anything over it with the lock, and nothing pushes over the lock`() {
        val backStack = stack(Route.Alarms, Route.You, Route.Payments, Route.PurchaseHistory)

        assertEquals(true, backStack.applySessionLock(active = true))
        backStack.openPayments(from = Route.You)
        backStack.openPurchaseHistory(from = Route.Payments)
        backStack.openProblemWithCharge(from = Route.PurchaseHistory)
        backStack.openPayments(from = Route.SessionInProgress)
        backStack.openPurchaseHistory(from = Route.SessionInProgress)
        assertEquals(listOf<NavKey>(Route.SessionInProgress), backStack)

        backStack.applySessionLock(active = false)
        assertEquals(listOf<NavKey>(Route.Alarms), backStack)
    }

    @Test
    fun `a session replaces Purchase history with the lock, and its end lands on Home`() {
        val backStack = stack(Route.Alarms, Route.You, Route.PurchaseHistory)

        assertEquals(true, backStack.applySessionLock(active = true))
        assertEquals(listOf<NavKey>(Route.SessionInProgress), backStack)
        backStack.openPurchaseHistory(from = Route.You)
        assertEquals(listOf<NavKey>(Route.SessionInProgress), backStack)

        backStack.applySessionLock(active = false)
        assertEquals(listOf<NavKey>(Route.Alarms), backStack)
    }

    @Test
    fun `after Delete all data only the empty Home is left, and a locked stack stays locked (Story 5_9)`() {
        val fromYou = stack(Route.Alarms, Route.You)
        assertEquals(true, fromYou.resetToHome())
        assertEquals(listOf<NavKey>(Route.Alarms), fromYou)

        val deeper = stack(Route.Alarms, Route.You, Route.PurchaseHistory, Route.ProblemWithCharge)
        assertEquals(true, deeper.resetToHome())
        assertEquals(listOf<NavKey>(Route.Alarms), deeper)

        val home = stack(Route.Alarms)
        assertEquals(false, home.resetToHome())
        assertEquals(listOf<NavKey>(Route.Alarms), home)

        val locked = stack(Route.SessionInProgress)
        assertEquals(false, locked.resetToHome())
        assertEquals(listOf<NavKey>(Route.SessionInProgress), locked)
    }
}
