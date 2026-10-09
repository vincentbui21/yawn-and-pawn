package com.yawnandpawn.app.ui.nav

import androidx.navigation3.runtime.NavKey
import androidx.savedstate.serialization.SavedStateConfiguration
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.modules.subclassesOfSealed

/**
 * Every app-screen destination (AD-11). The wake flow is a separate activity, outside this graph. The four tabs of the
 * nav bar are routes too: [Alarms] is the root, and another tab sits on top of it ([Progress], [Settings] or [You]), so
 * Back on a tab returns to Alarms and Back on Alarms leaves the app. The editor and Purchase history are pushed over
 * them.
 */
@Serializable
sealed interface Route : NavKey {
    /** Home, the Alarms tab: the alarm list, or the empty state when there are no alarms. */
    @Serializable
    data object Alarms : Route

    /** The Progress tab. */
    @Serializable
    data object Progress : Route

    /** The Settings tab. */
    @Serializable
    data object Settings : Route

    /** The You tab. */
    @Serializable
    data object You : Route

    /**
     * The alarm editor; [alarmId] is `null` for a new alarm, which [copyOf] prefills from that stored alarm (Duplicate).
     * [scanCode] opens it on QR registration of the alarm's code ("Re-register", Story 3.10).
     */
    @Serializable
    data class AlarmEditor(
        val alarmId: String?,
        val copyOf: String? = null,
        val scanCode: Boolean = false,
    ) : Route

    /**
     * Purchase history (Story 4.16), pushed over the You tab from its Money card; Back returns to You. Not a tab, so
     * the nav capsule hides.
     */
    @Serializable
    data object PurchaseHistory : Route

    /**
     * The session lock (Story 2.6, FR-SES-3): while a wake session is active it is the whole back stack, showing only
     * "Alarm in progress" with "Back to alarm" ([applySessionLock]). It is not a tab, so the nav capsule hides.
     */
    @Serializable
    data object SessionInProgress : Route
}

/** Saves and restores the back stack: every [Route] is registered as a polymorphic [NavKey]. */
@OptIn(ExperimentalSerializationApi::class)
val RouteSavedStateConfiguration: SavedStateConfiguration =
    SavedStateConfiguration {
        serializersModule =
            SerializersModule {
                polymorphic(NavKey::class) {
                    subclassesOfSealed<Route>()
                }
            }
    }
