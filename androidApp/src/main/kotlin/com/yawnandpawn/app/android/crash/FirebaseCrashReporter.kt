package com.yawnandpawn.app.android.crash

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.crashlytics.FirebaseCrashlytics
import com.yawnandpawn.app.core.crash.CrashReporter
import com.yawnandpawn.app.core.crash.UnsentCrashReports
import com.yawnandpawn.app.core.log.LogEvent
import com.yawnandpawn.app.core.log.Logger

/** Where a report goes: Crashlytics in production, a recording fake in tests. Only exceptions; no keys, no logs. */
interface CrashSink {
    /** Crashlytics has started (after the first unlock, with a Firebase configuration). */
    val isReady: Boolean

    fun recordException(throwable: Throwable)

    /** Drops the reports not sent yet ("Delete all data", Story 5.9). */
    fun deleteUnsentReports()

    /** Turns Crashlytics' collection off or on (around [deleteUnsentReports]); nothing by default. */
    fun setCollectionEnabled(enabled: Boolean) = Unit
}

/** [CrashSink] on the default Firebase app's Crashlytics. */
class CrashlyticsSink(
    private val context: Context,
) : CrashSink {
    override val isReady: Boolean
        get() = FirebaseApp.getApps(context).isNotEmpty()

    override fun recordException(throwable: Throwable) = FirebaseCrashlytics.getInstance().recordException(throwable)

    override fun deleteUnsentReports() = FirebaseCrashlytics.getInstance().deleteUnsentReports()

    override fun setCollectionEnabled(enabled: Boolean) = FirebaseCrashlytics.getInstance().setCrashlyticsCollectionEnabled(enabled)
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
    /**
     * Never throws: the wake runtime reports before it falls back to the default sound, so a failing Crashlytics (not
     * on the classpath, half started) must not stop the ring. A failure is logged with its type only.
     */
    @Suppress("TooGenericExceptionCaught")
    override fun report(throwable: Throwable) {
        try {
            if (sink.isReady) {
                sink.recordException(sanitized(throwable))
            } else {
                logger.log(LogEvent.OperationFailed("report crash", "Crashlytics not started yet: ${throwable::class.simpleName}"))
            }
        } catch (e: Exception) {
            logger.log(LogEvent.OperationFailed("report crash", e::class.simpleName.orEmpty()))
        } catch (e: LinkageError) {
            logger.log(LogEvent.OperationFailed("report crash", e::class.simpleName.orEmpty()))
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

/**
 * The Crashlytics [UnsentCrashReports] ("Delete all data", Story 5.9): once Crashlytics has started, the reports it has
 * not sent yet are dropped; before that there is nothing to drop. Never throws: a failure is logged with its type only,
 * and the deletion it follows has already finished.
 */
class CrashlyticsUnsentReports(
    private val sink: CrashSink,
    private val logger: Logger,
) : UnsentCrashReports {
    @Suppress("TooGenericExceptionCaught")
    override fun deleteUnsent() {
        try {
            if (sink.isReady) deleteWithCollectionOff()
        } catch (e: Exception) {
            logger.log(LogEvent.OperationFailed(DELETE_UNSENT, e::class.simpleName.orEmpty()))
        } catch (e: LinkageError) {
            logger.log(LogEvent.OperationFailed(DELETE_UNSENT, e::class.simpleName.orEmpty()))
        }
    }

    /**
     * `deleteUnsentReports` is documented for opt-in reporting (collection off); with automatic collection on it may do
     * nothing (Story 5.9 review, item 6, not verified on a device). So collection is turned off around the delete and
     * back on after it, whatever happens: FirebaseStartup turns it on at every start anyway.
     */
    private fun deleteWithCollectionOff() {
        sink.setCollectionEnabled(false)
        try {
            sink.deleteUnsentReports()
        } finally {
            sink.setCollectionEnabled(true)
        }
    }

    private companion object {
        const val DELETE_UNSENT = "delete unsent crash reports"
    }
}

/** A reported exception: [className] of the original, no message. */
class ReportedException(
    val className: String,
    cause: Throwable?,
) : Exception(className, cause)
