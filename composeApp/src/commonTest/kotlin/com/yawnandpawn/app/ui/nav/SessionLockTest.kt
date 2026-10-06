package com.yawnandpawn.app.ui.nav

import androidx.navigation3.runtime.NavKey
import com.yawnandpawn.app.ui.shell.AppTab
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.descriptors.PolymorphicKind
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.descriptors.elementDescriptors
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Story 2.6 (FR-SES-3): while a session is active the back stack is only [Route.SessionInProgress], whatever the user
 * (or a later screen) tries to open. Every [Route] subclass is taken from the sealed serializer, so a route added later
 * is covered here automatically.
 */
class SessionLockTest {
    private val locked = listOf<NavKey>(Route.SessionInProgress)

    @Test
    fun `the routes under test are every Route subclass`() {
        val known = listOf(Route.Alarms, Route.Progress, Route.Settings, Route.You, Route.SessionInProgress)
        assertTrue(everyRoute.containsAll(known), "$everyRoute")
        assertTrue(everyRoute.any { it is Route.AlarmEditor }, "$everyRoute")
        assertEquals(everyRoute.map { it::class }.distinct().size, everyRoute.size, "one sample per subclass")
    }

    @Test
    fun `a session starting on any screen replaces the whole stack with the session lock`() {
        everyRoute.forEach { route ->
            val stack = mutableListOf<NavKey>(Route.Alarms, route)

            assertTrue(stack.applySessionLock(active = true) || route == Route.SessionInProgress, "$route")

            assertEquals(locked, stack, "from $route")
        }
    }

    @Test
    fun `navigating to any route while locked leaves the session lock on screen`() {
        everyRoute.forEach { route ->
            val stack = locked.toMutableList()
            // A push, a tab tap, the "+" and Back: the tab and editor calls already change nothing without a tab on top.
            route.tab?.let { stack.selectTab(it) }
            stack.openEditor(alarmId = null)
            stack.pop()
            assertEquals(locked, stack, "the shell rules alone keep the lock ($route)")
            stack.add(route)

            stack.applySessionLock(active = true)

            assertEquals(locked, stack, "after pushing $route")
        }
    }

    @Test
    fun `the end of the session returns to Home, and an unlocked stack is left alone`() {
        val stack = locked.toMutableList()

        assertTrue(stack.applySessionLock(active = false))
        assertEquals(listOf<NavKey>(Route.Alarms), stack)

        val settings = mutableListOf<NavKey>(Route.Alarms, Route.Settings)
        assertFalse(settings.applySessionLock(active = false))
        assertEquals(listOf<NavKey>(Route.Alarms, Route.Settings), settings)
        assertFalse(locked.toMutableList().applySessionLock(active = true), "already locked")
    }

    @Test
    fun `the session lock is not a tab, so the nav capsule hides`() {
        assertEquals(null, Route.SessionInProgress.tab)
        AppTab.entries.forEach { assertTrue(it.route() != Route.SessionInProgress) }
    }

    private companion object {
        val json = Json { classDiscriminator = "type" }

        /**
         * The descriptors of every [Route] subclass: a sealed serializer's descriptor holds "type" and "value", and
         * "value" lists one element per subclass.
         */
        @OptIn(ExperimentalSerializationApi::class)
        fun subclassDescriptors(): List<SerialDescriptor> {
            val sealed = Route.serializer().descriptor
            check(sealed.kind == PolymorphicKind.SEALED) { "Route must stay a sealed, serializable interface" }
            return sealed.getElementDescriptor(sealed.getElementIndex("value")).elementDescriptors.toList()
        }

        /** One instance of every [Route] subclass: each property gets a sample value by its kind (null when nullable). */
        @OptIn(ExperimentalSerializationApi::class)
        val everyRoute: List<Route> =
            subclassDescriptors().map { descriptor ->
                val fields =
                    (0 until descriptor.elementsCount).associate { i ->
                        descriptor.getElementName(i) to sample(descriptor.getElementDescriptor(i))
                    }
                json.decodeFromJsonElement(Route.serializer(), JsonObject(mapOf("type" to JsonPrimitive(descriptor.serialName)) + fields))
            }

        @OptIn(ExperimentalSerializationApi::class)
        fun sample(descriptor: SerialDescriptor): JsonElement =
            when {
                descriptor.isNullable -> JsonNull
                descriptor.kind == PrimitiveKind.STRING -> JsonPrimitive("sample")
                descriptor.kind == PrimitiveKind.BOOLEAN -> JsonPrimitive(false)
                descriptor.kind is PrimitiveKind -> JsonPrimitive(0)
                descriptor.kind == StructureKind.LIST -> JsonArray(emptyList())
                else -> error("Add a sample for ${descriptor.serialName} (${descriptor.kind}) to SessionLockTest")
            }
    }
}
