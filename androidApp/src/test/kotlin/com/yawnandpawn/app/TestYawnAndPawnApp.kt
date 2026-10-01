package com.yawnandpawn.app

import com.yawnandpawn.app.core.reliability.ReliabilityProbe
import com.yawnandpawn.app.testing.FakeReliabilityProbe
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * The phone every Robolectric app test runs on grants every reliability setting (Story 1.19). Robolectric reports the
 * API 34 full-screen intent grant as off and has no shadow to change it, so the real probe would put the reliability
 * banner on every Home screen. `ReliabilityProbeTest` tests the real probe directly.
 */
val grantedPhoneModule: Module = module { single<ReliabilityProbe> { FakeReliabilityProbe() } }

/**
 * The application every Robolectric test in :androidApp boots (`robolectric.properties`). It tears down any app a
 * previous test left running (its Koin graph, background work and databases) before starting the real one, so a test
 * class that forgets its teardown cannot break the next. Tests still tear down with [StopAppRule] or [stopApp].
 */
class TestYawnAndPawnApp : YawnAndPawnApp() {
    override val overrideModules: List<Module> = listOf(grantedPhoneModule)

    override fun onCreate() {
        stopApp()
        super.onCreate()
    }
}
