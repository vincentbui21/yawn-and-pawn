package com.yawnandpawn.app.core.reliability

import com.yawnandpawn.app.core.error.Outcome
import com.yawnandpawn.app.core.log.LogEvent
import com.yawnandpawn.app.core.log.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Instant

/**
 * Where `SessionEngine` reports that a test alarm rung with the phone locked was completed (Story 5.3). It never
 * suspends: the engine calls it inside its Mutex, and a storage write there would hold back every later step, a real
 * alarm's first ring included (review fix 11). The write itself runs elsewhere ([Launching]).
 */
fun interface LockedTestReporter {
    fun lockedTestPassed(at: Instant)

    /**
     * Launches [recorder] on [scope] (the app-wide scope) and returns at once. A failed write, a throwing recorder and
     * a cancelled write are logged, never thrown: the session is already committed, and the user can ring another test.
     */
    class Launching(
        private val recorder: LockedTestRecorder,
        private val scope: CoroutineScope,
        private val logger: Logger,
    ) : LockedTestReporter {
        override fun lockedTestPassed(at: Instant) {
            scope.launch { record(at) }
        }

        @Suppress("TooGenericExceptionCaught") // An adapter call: whatever it throws is logged by type name only.
        private suspend fun record(at: Instant) {
            try {
                val recorded = recorder.lockedTestPassed(at)
                if (recorded is Outcome.Failure) logger.log(LogEvent.OperationFailed.of(RECORD, recorded.error))
            } catch (e: CancellationException) {
                // The scope ended (the process is going away): logged, and the cancellation ends this job only.
                logger.log(LogEvent.OperationFailed(RECORD, "cancelled"))
                throw e
            } catch (e: Throwable) {
                logger.log(LogEvent.OperationFailed(RECORD, e::class.simpleName ?: "Throwable"))
            }
        }
    }

    companion object {
        /** Reports nothing (engines without the checklist, and tests). */
        val None: LockedTestReporter = LockedTestReporter {}

        /** The log operation of a failed record. */
        const val RECORD = "record locked test"
    }
}
