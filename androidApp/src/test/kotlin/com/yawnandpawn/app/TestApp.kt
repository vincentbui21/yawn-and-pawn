package com.yawnandpawn.app

import com.yawnandpawn.app.android.ApplicationScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement
import org.koin.core.context.GlobalContext
import org.koin.core.context.stopKoin
import kotlin.time.Duration.Companion.seconds

/** How long a test waits for the app's background work before it gives up and fails. */
private val APP_WORK_TIMEOUT = 5.seconds

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
        scope?.awaitChildren()
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
