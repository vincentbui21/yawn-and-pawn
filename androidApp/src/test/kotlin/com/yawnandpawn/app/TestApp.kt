package com.yawnandpawn.app

import android.app.Application
import com.yawnandpawn.app.android.ApplicationScope
import com.yawnandpawn.app.data.dataModule
import com.yawnandpawn.app.ui.uiModule
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.GlobalContext
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.core.module.Module
import kotlin.time.Duration.Companion.seconds

/**
 * How long a test waits for the app's background work. Generous because a loaded CI or gate run (many Gradle workers)
 * can take seconds to open Room and reschedule; a test that asserts on that work fails after this, teardown never does.
 */
private val APP_WORK_TIMEOUT = 30.seconds

/** Waits (bounded) until every job launched on this scope so far has finished. */
internal fun ApplicationScope.awaitChildren() {
    val job = coroutineContext[Job] ?: return
    runBlocking { withTimeout(APP_WORK_TIMEOUT) { job.children.toList().joinAll() } }
}

/**
 * Tears down the real app a Robolectric test booted (`YawnAndPawnApp.onCreate` starts Koin and launches
 * `rescheduleAll()` on [ApplicationScope]): waits for that background work, cancels the scope so nothing from this
 * sandbox runs on while the next test class sets up, then stops Koin. Safe to call twice, or when Koin never started.
 */
fun stopApp() {
    val scope = GlobalContext.getOrNull()?.getOrNull<ApplicationScope>()
    try {
        // Teardown must not fail a test that already passed: wait (bounded), then cancel whatever is left.
        scope?.coroutineContext?.get(Job)?.let { job ->
            val finished = runBlocking { withTimeoutOrNull(APP_WORK_TIMEOUT) { job.children.toList().joinAll() } }
            if (finished == null) {
                val leftover = job.children.count { it.isActive }
                println("stopApp: app work still running after $APP_WORK_TIMEOUT, cancelling $leftover leftover job(s)")
            }
        }
    } finally {
        scope?.cancel()
        stopKoin()
    }
}

/**
 * [stopApp] after each test, even when another rule (an activity launch in a compose rule) fails before the test and
 * its `@After` methods run. Register it as the outermost rule: `@get:Rule(order = 0)`.
 */
class StopAppRule : TestRule {
    override fun apply(
        base: Statement,
        description: Description,
    ): Statement =
        object : Statement() {
            override fun evaluate() {
                try {
                    base.evaluate()
                } finally {
                    stopApp()
                }
            }
        }
}

/**
 * Replaces the running app's Koin graph with the app's own modules plus [overrides], declared before anything is
 * created. Overriding a single after the app created it (`loadKoinModules`) would leave that instance cached in the
 * shared module object, and the next test's app would get it back (a closed database, a stale runtime). The app-start
 * work (`rescheduleAll`, the session restore) does not run again; a test that needs it calls it.
 */
fun restartKoin(
    app: Application,
    vararg overrides: Module,
) {
    stopApp()
    startKoin {
        androidContext(app)
        modules(listOf(appModule, dataModule, uiModule, grantedPhoneModule) + overrides)
    }
}
