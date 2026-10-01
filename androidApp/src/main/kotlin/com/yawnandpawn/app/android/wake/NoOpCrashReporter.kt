package com.yawnandpawn.app.android.wake

import com.yawnandpawn.app.core.crash.CrashReporter
import com.yawnandpawn.app.core.log.LogEvent
import com.yawnandpawn.app.core.log.Logger

/**
 * The [CrashReporter] of a build without a Firebase configuration (no `google-services.json`, Story 1.19): it sends
 * nothing anywhere and only logs the exception type, so a crash in the wake flow stays visible in Logcat.
 */
class NoOpCrashReporter(
    private val logger: Logger,
) : CrashReporter {
    override fun report(throwable: Throwable) {
        logger.log(
            LogEvent.OperationFailed("wake flow", "uncaught ${throwable::class.simpleName}, not reported (no Firebase configuration)"),
        )
    }
}
