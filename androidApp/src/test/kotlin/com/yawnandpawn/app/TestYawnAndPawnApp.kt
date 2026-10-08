package com.yawnandpawn.app

import com.yawnandpawn.app.android.crash.FirebaseStartup
import com.yawnandpawn.app.core.reliability.ReliabilityProbe
import com.yawnandpawn.app.core.work.BackgroundWork
import com.yawnandpawn.app.testing.FakeReliabilityProbe
import com.yawnandpawn.app.testing.RecordingBackgroundWork
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
 * - Background jobs are only recorded (Story 4.10): the consume retry would otherwise wait out its real 30 s backoff on
 *   the app scope. `InProcessBackgroundWorkTest` tests the real one.
 */
val testAppModule: Module =
    module {
        single<ReliabilityProbe> { FakeReliabilityProbe() }
        single { FirebaseStartup(androidContext(), get(), initialize = {}) }
        single<BackgroundWork> { RecordingBackgroundWork() }
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
