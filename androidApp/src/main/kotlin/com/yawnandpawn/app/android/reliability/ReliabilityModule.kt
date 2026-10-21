package com.yawnandpawn.app.android.reliability

import com.yawnandpawn.app.android.crash.FirebaseConfiguration
import com.yawnandpawn.app.android.crash.FirebaseStartup
import com.yawnandpawn.app.android.crash.isFirebaseConfigured
import com.yawnandpawn.app.core.reliability.ConfirmMakerGuidance
import com.yawnandpawn.app.core.reliability.DeviceInfoSource
import com.yawnandpawn.app.core.reliability.EvaluateReliability
import com.yawnandpawn.app.core.reliability.ManufacturerSettings
import com.yawnandpawn.app.core.reliability.NotificationPermission
import com.yawnandpawn.app.core.reliability.ReliabilityProbe
import com.yawnandpawn.app.core.reliability.ReliabilitySettings
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * Koin bindings of Story 1.19, included by `appModule`: the reliability probe and its settings deep links, the
 * notification permission (one instance, which `MainActivity` attaches its launcher to) and the Firebase start (with
 * whether this build has a Firebase configuration, Story 5.6). Story 5.3 adds the phone's details and the checklist
 * evaluation (over the alarms and the records from `dataModule`); 5.5 the maker's settings screens and "I've done this"
 * on the maker guidance. Since Story 5.4 the settings deep links open
 * each checklist row's screen (resolver-checked, with fallbacks), and the evaluation also feeds the Home and Settings
 * banner and the checklist screen (a factory: each screen gets its own).
 */
fun reliabilityModule(): Module =
    module {
        single<ReliabilityProbe> { AndroidReliabilityProbe(androidContext(), logger = get()) }
        single<ReliabilitySettings> { AndroidReliabilitySettings(androidContext(), get()) }
        single { AndroidNotificationPermission(androidContext(), get()) }
        single<NotificationPermission> { get<AndroidNotificationPermission>() }
        single { FirebaseConfiguration(isFirebaseConfigured(androidContext())) }
        // Story 5.6: it turns Analytics collection off right after starting Firebase (fail closed).
        single {
            val firebase = get<FirebaseConfiguration>()
            FirebaseStartup(androidContext(), get(), analytics = get(), configured = { firebase.present })
        }
        single<DeviceInfoSource> { AndroidDeviceInfoSource() }
        factory { EvaluateReliability(get(), get(), get(), get(), get()) }
        single<ManufacturerSettings> { AndroidManufacturerSettings(androidContext(), get()) }
        factory { ConfirmMakerGuidance(get(), get(), get()) }
    }
