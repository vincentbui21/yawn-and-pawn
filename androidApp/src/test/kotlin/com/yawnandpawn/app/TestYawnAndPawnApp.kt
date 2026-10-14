package com.yawnandpawn.app

import com.yawnandpawn.app.android.billing.BillingClientFacade
import com.yawnandpawn.app.android.billing.FakeBillingClientFacade
import com.yawnandpawn.app.android.crash.FirebaseStartup
import com.yawnandpawn.app.core.net.Connectivity
import com.yawnandpawn.app.core.reliability.ReliabilityProbe
import com.yawnandpawn.app.core.work.BackgroundWork
import com.yawnandpawn.app.testing.FakeBackgroundWork
import com.yawnandpawn.app.testing.FakeConnectivity
import com.yawnandpawn.app.testing.FakeReliabilityProbe
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * What every Robolectric app test runs on (Story 1.19):
 * - A phone that grants every reliability setting. Robolectric reports the API 34 full-screen intent grant as off and
 *   has no shadow to change it, so the real probe would put the reliability banner on every Home screen.
 *   `ReliabilityProbeTest` tests the real probe directly.
 * - No real Firebase start. With a CI `google-services.json` the app is configured, but a test must never initialise
 *   Firebase (it would try to reach the network); the startup records that it started and does nothing else.
 * - Background jobs are only recorded ([FakeBackgroundWork]): app start enqueues the price refresh jobs (Story 4.3) and
 *   a failed consume its retry jobs (Story 4.10), which would start WorkManager's database and threads in every test.
 *   `BackgroundWorkWiringTest` tests the real WorkManager adapter with WorkManager's test driver.
 * - No Play (Story 4.12): the billing adapter talks to a fake `BillingClientFacade` that answers like a phone without the
 *   Play Store, so no test ever binds to Play; `AndroidBillingTest` drives the adapter through a programmable one.
 */
val testAppModule: Module =
    module {
        single<ReliabilityProbe> { FakeReliabilityProbe() }
        single { FirebaseStartup(androidContext(), get(), initialize = {}) }
        single<BackgroundWork> { FakeBackgroundWork() }
        // An online phone (Story 4.7), so snooze reasons and the session-start refresh never depend on the host's
        // network. AndroidConnectivityTest tests the real adapter.
        single<Connectivity> { FakeConnectivity(online = true) }
        single<BillingClientFacade> { FakeBillingClientFacade() }
    }

/**
 * The application every Robolectric test in :androidApp boots (`robolectric.properties`). It tears down any app a
 * previous test left running (its Koin graph, background work and databases) before starting the real one, so a test
 * class that forgets its teardown cannot break the next. Tests still tear down with [StopAppRule] or [stopApp].
 */
class TestYawnAndPawnApp : YawnAndPawnApp() {
    override val overrideModules: List<Module> = listOf(testAppModule)

    override fun onCreate() {
        stopApp()
        super.onCreate()
    }
}
