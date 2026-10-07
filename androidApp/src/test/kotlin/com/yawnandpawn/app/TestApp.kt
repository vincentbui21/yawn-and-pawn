package com.yawnandpawn.app

import android.app.Activity
import android.app.Application
import android.app.Service
import android.content.Intent
import androidx.test.core.app.ActivityScenario
import com.yawnandpawn.app.android.ApplicationScope
import com.yawnandpawn.app.android.wake.WakeScope
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
import org.robolectric.Robolectric
import org.robolectric.android.controller.ActivityController
import org.robolectric.android.controller.ServiceController
import kotlin.time.Duration.Companion.milliseconds

/**
 * How long a test waits for the app's background work (Room, ApplicationScope threads), in real time: that work runs
 * on real threads, so no test clock can stand in for it. Generous because a loaded CI or gate run (many Gradle workers)
 * can take seconds to open Room and reschedule; a test that asserts on that work fails after this, teardown never does.
 * Every wall-clock wait of these tests (`composeRule.waitUntil`, `awaitUntil`) uses this bound, never a shorter one.
 */
const val APP_WORK_TIMEOUT_MILLIS = 30_000L

private val APP_WORK_TIMEOUT = APP_WORK_TIMEOUT_MILLIS.milliseconds

/** Waits (bounded) until every job launched on this scope so far has finished. */
internal fun ApplicationScope.awaitChildren() {
    val job = coroutineContext[Job] ?: return
    runBlocking { withTimeout(APP_WORK_TIMEOUT) { job.children.toList().joinAll() } }
}

/**
 * The activities and services the current test built, which [stopApp] destroys. Robolectric never destroys a component
 * at the end of a test, and the next test of the same sandbox reuses its main looper: a component left running there
 * (`WakeService`'s scope, an activity's lifecycle and ViewModel scopes and its composition) resumes after Koin stopped
 * and throws `KoinApplication has not been started`, which kotlinx-coroutines-test then reports in the next test that
 * calls `runTest` (every compose rule) as `UncaughtExceptionsBeforeTest`. Build them with [buildActivity],
 * [launchActivity] and [buildService], never with `Robolectric` or `ActivityScenario` directly.
 */
private val liveComponents = mutableListOf<AutoCloseable>()

/** `Robolectric.buildActivity`, destroyed by [stopApp] unless the test destroyed it (`close` does nothing then). */
fun <T : Activity> buildActivity(
    type: Class<T>,
    intent: Intent? = null,
): ActivityController<T> = Robolectric.buildActivity(type, intent).also { liveComponents += it }

/** `ActivityScenario.launch`, closed by [stopApp] unless the test closed it (closing twice does nothing). */
fun <T : Activity> launchActivity(intent: Intent): ActivityScenario<T> = ActivityScenario.launch<T>(intent).also { liveComponents += it }

/**
 * `Robolectric.buildService`, destroyed by [stopApp] as the system destroys a stopped service. A service the test
 * destroyed itself gets `onDestroy` again; the app's services allow that (they only cancel and release).
 */
fun <T : Service> buildService(
    type: Class<T>,
    intent: Intent? = null,
): ServiceController<T> =
    Robolectric.buildService(type, intent).also { controller -> liveComponents += AutoCloseable { controller.destroy() } }

/** Destroys every component the test left (newest first) while its Koin graph still runs; never fails a teardown. */
@Suppress("TooGenericExceptionCaught")
private fun destroyLiveComponents() {
    val left = liveComponents.asReversed().toList()
    liveComponents.clear()
    left.forEach { component ->
        try {
            component.close()
        } catch (e: Exception) {
            println("stopApp: could not destroy a component the test left running: $e")
        }
    }
}

/**
 * Tears down the real app a Robolectric test booted (`YawnAndPawnApp.onCreate` starts Koin and launches
 * `rescheduleAll()` on [ApplicationScope]): destroys the activities and services the test left running (see
 * [liveComponents]), cancels the wake runtime's main-thread timers ([WakeScope]), waits for the background work,
 * cancels the scope so nothing from this sandbox runs on while the next test sets up, then stops Koin. Safe to call
 * twice, or when Koin never started.
 */
fun stopApp() {
    destroyLiveComponents()
    GlobalContext.getOrNull()?.getOrNull<WakeScope>()?.cancel()
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
        modules(listOf(appModule, dataModule, uiModule, testAppModule) + overrides)
    }
}
