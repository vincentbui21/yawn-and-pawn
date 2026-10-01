package com.yawnandpawn.app.android.reliability

import com.yawnandpawn.app.android.crash.FirebaseStartup
import com.yawnandpawn.app.core.reliability.NotificationPermission
import com.yawnandpawn.app.core.reliability.ReliabilityProbe
import com.yawnandpawn.app.core.reliability.ReliabilitySettings
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * Koin bindings of Story 1.19, included by `appModule`: the reliability probe and its settings deep links, the
 * notification permission (one instance, which `MainActivity` attaches its launcher to) and the Firebase start.
 */
fun reliabilityModule(): Module =
    module {
        single<ReliabilityProbe> { AndroidReliabilityProbe(androidContext()) }
        single<ReliabilitySettings> { AndroidReliabilitySettings(androidContext(), get()) }
        single { AndroidNotificationPermission(androidContext(), get()) }
        single<NotificationPermission> { get<AndroidNotificationPermission>() }
        single { FirebaseStartup(androidContext(), get()) }
    }
