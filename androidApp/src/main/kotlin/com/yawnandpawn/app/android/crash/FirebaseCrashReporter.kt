package com.yawnandpawn.app.android.crash

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.crashlytics.FirebaseCrashlytics
import com.yawnandpawn.app.core.crash.CrashReporter
import com.yawnandpawn.app.core.log.LogEvent
import com.yawnandpawn.app.core.log.Logger

/** Where a report goes: Crashlytics in production, a recording fake in tests. Only exceptions; no keys, no logs. */
interface CrashSink {
    /** Crashlytics has started (after the first unlock, with a Firebase configuration). */
    val isReady: Boolean

    fun recordException(throwable: Throwable)
}

/** [CrashSink] on the default Firebase app's Crashlytics. */
class CrashlyticsSink(
    private val context: Context,
) : CrashSink {
    override val isReady: Boolean
        get() = FirebaseApp.getApps(context).isNotEmpty()

    override fun recordException(throwable: Throwable) = FirebaseCrashlytics.getInstance().recordException(throwable)
}

/**
 * The Crashlytics [CrashReporter] (Story 1.19, NFR-15). A report carries no personal data: it sends a [sanitized]
 * copy of the exception, which keeps every class name and stack trace and drops every message (a message can name an
 * alarm label, a sound file path or a purchase token). It never sets custom keys or writes Crashlytics logs. Before
 * Crashlytics has started (the user has not unlocked yet) the exception type is only logged.
 */
class FirebaseCrashReporter(
    private val sink: CrashSink,
    private val logger: Logger,
) : CrashReporter {
    override fun report(throwable: Throwable) {
        if (sink.isReady) {
            sink.recordException(sanitized(throwable))
        } else {
            logger.log(LogEvent.OperationFailed("report crash", "Crashlytics not started yet: ${throwable::class.simpleName}"))
        }
    }

    companion object {
        /** The deepest cause chain kept; a longer (or cyclic) chain is cut there. */
        private const val MAX_CAUSES = 8

        /** [throwable] and its causes with every message replaced by the class name; stack traces are kept. */
        fun sanitized(throwable: Throwable): Throwable = sanitized(throwable, depth = 0)

        private fun sanitized(
            throwable: Throwable,
            depth: Int,
        ): Throwable {
            val cause = throwable.cause?.takeIf { it !== throwable && depth < MAX_CAUSES }?.let { sanitized(it, depth + 1) }
            return ReportedException(throwable::class.qualifiedName ?: "Throwable", cause).apply {
                stackTrace = throwable.stackTrace
            }
        }
    }
}

/** A reported exception: [className] of the original, no message. */
class ReportedException(
    val className: String,
    cause: Throwable?,
) : Exception(className, cause)
