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
     * Purchase history (Story 4.16), pushed over the You tab from its Money card, or over [Payments] (Story 5.8); Back
     * returns to the screen under it. Not a tab, so the nav capsule hides.
     */
    @Serializable
    data object PurchaseHistory : Route

    /**
     * "How payments & refunds work" (Story 5.8), pushed over the You tab from its Money card; Back returns to You. Its
     * links push Purchase history and "Problem with a charge?" over it. Not a tab, so the nav capsule hides.
     */
    @Serializable
    data object Payments : Route

    /**
     * "Problem with a charge?" (Story 4.17), pushed over Purchase history or [Payments]; Back returns to it. Its "Email
     * support" picker is a pane of the same entry. Not a tab, so the nav capsule hides.
     */
    @Serializable
    data object ProblemWithCharge : Route

    /**
     * The reliability checklist (Story 5.4), pushed over Settings (its "Reliability checklist" row or banner) or Home
     * (the banner's "Fix"); Back returns there. Not a tab, so the nav capsule hides.
     */
    @Serializable
    data object Reliability : Route

    /**
     * The maker guidance (Story 5.5): the phone maker's steps, "Open settings" and "I've done this", which closes it.
     * Pushed over the reliability checklist from its Manufacturer row (Story 5.4); Back returns there. Not a tab, so the
     * nav capsule hides.
     */
    @Serializable
    data object ManufacturerGuidance : Route

    /**
     * The session lock (Story 2.6, FR-SES-3): while a wake session is active it is the whole back stack, showing only
     * "Alarm in progress" with "Back to alarm" ([applySessionLock]). It is not a tab, so the nav capsule hides.
     */
    @Serializable
    data object SessionInProgress : Route

    /**
     * Onboarding (Story 5.10, FR-ONB-1): until it is completed it is the whole back stack ([applyOnboarding]), behind the
     * session lock, which still wins. Not a tab, so the nav capsule hides.
     */
    @Serializable
    data object Onboarding : Route
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
