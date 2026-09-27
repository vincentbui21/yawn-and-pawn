package com.yawnandpawn.app.ui.nav

import androidx.navigation3.runtime.NavKey
import androidx.savedstate.serialization.SavedStateConfiguration
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.modules.subclassesOfSealed

/** Every app-screen destination (AD-11). The wake flow is a separate activity, outside this graph. */
@Serializable
sealed interface Route : NavKey {
    /** Home: the alarm list, or the empty state when there are no alarms. */
    @Serializable
    data object Alarms : Route

    /** The alarm editor; [alarmId] is `null` for a new alarm. */
    @Serializable
    data class AlarmEditor(
        val alarmId: String?,
    ) : Route
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
