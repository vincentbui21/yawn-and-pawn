package com.yawnandpawn.app.android.telemetry

import com.yawnandpawn.app.android.ApplicationScope
import com.yawnandpawn.app.android.crash.FirebaseConfiguration
import com.yawnandpawn.app.android.crash.FirebaseStartup
import com.yawnandpawn.app.core.telemetry.ConsentGatedTelemetry
import com.yawnandpawn.app.core.telemetry.SetAnalyticsConsent
import com.yawnandpawn.app.core.telemetry.Telemetry
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.Module
import org.koin.core.module.dsl.onClose
import org.koin.core.module.dsl.withOptions
import org.koin.dsl.module

/**
 * Koin bindings of anonymous usage stats (Story 5.6, AD-15), included by `appModule`. With a Firebase configuration the
 * engine's [Telemetry] is Firebase Analytics behind the stored consent, and [AnalyticsConsentGate] turns collection on
 * and off; without one (local builds, CI without the secret) the telemetry sends nothing and the gate never calls
 * Analytics. The `ConsentStore` comes from `dataModule`; [FirebaseConfiguration] and [FirebaseStartup] from
 * `reliabilityModule`. The engine reaches the telemetry through `TelemetryReporter.Launching` (`appModule`).
 */
fun telemetryModule(): Module =
    module {
        single<AnalyticsSink> { if (get<FirebaseConfiguration>().present) FirebaseAnalyticsSink(androidContext()) else NoOpAnalyticsSink }
        single<Telemetry> {
            if (get<FirebaseConfiguration>().present) ConsentGatedTelemetry(FirebaseTelemetry(get(), get()), get()) else Telemetry.None
        }
        factory { SetAnalyticsConsent(get()) }
        // Its own app-wide scope (as the purchase coordinator's): it follows the consent for the life of the process, so it
        // is not among the app start's finite jobs; it stops when the Koin graph closes.
        single {
            AnalyticsConsentGate(get(), get(), get<FirebaseStartup>(), get(), ApplicationScope(get()), get())
        } withOptions { onClose { it?.stop() } }
    }
