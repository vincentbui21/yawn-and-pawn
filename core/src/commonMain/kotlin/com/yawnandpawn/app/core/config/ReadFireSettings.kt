package com.yawnandpawn.app.core.config

import com.yawnandpawn.app.core.error.DomainError
import com.yawnandpawn.app.core.error.Outcome
import com.yawnandpawn.app.core.log.LogEvent
import com.yawnandpawn.app.core.log.Logger
import com.yawnandpawn.app.core.log.diagnostic
import com.yawnandpawn.app.core.session.GlobalSettings
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/** What a fire resolves its session with: the global [settings] and every [pendingChanges] that may apply to it. */
data class FireSettings(
    val settings: GlobalSettings,
    val pendingChanges: List<PendingChange>,
)

/**
 * The settings read of a fire (Story 4.4, review fix 11), before `AlarmFired`: one snapshot of the settings store (the
 * live global settings and the global pending changes, so they always agree) and, at the same time, the alarm's own
 * pending changes, both within one [budget] (NFR-1 leaves 2 s from the fire to the ring).
 *
 * A setting never fails or delays the ring:
 * - the store not read in time or failing: the last-known snapshot ([GlobalSettingsRepository.lastKnown]), or the
 *   defaults only when there never was one, logged;
 * - the alarm's pending changes not read: none (the alarm's live settings, the stronger ones), logged.
 */
class ReadFireSettings(
    private val settingsRepository: GlobalSettingsRepository,
    private val pendingRepository: PendingChangeRepository,
    private val logger: Logger,
    private val budget: Duration = BUDGET,
) {
    suspend fun read(alarmId: String): FireSettings =
        coroutineScope {
            val global = async { bounded(OPERATION_SETTINGS) { settingsRepository.snapshot() } }
            val own = async { bounded(OPERATION_PENDING) { pendingRepository.forAlarm(alarmId) } }
            val snapshot = global.await() ?: settingsRepository.lastKnown() ?: SettingsSnapshot(GlobalSettings(), emptyList())
            FireSettings(snapshot.settings, snapshot.pending + own.await().orEmpty())
        }

    /** [read]'s value within [budget], or null (logged) when it failed, threw or took too long. */
    @Suppress("TooGenericExceptionCaught")
    private suspend fun <T> bounded(
        operation: String,
        read: suspend () -> Outcome<T, DomainError>,
    ): T? {
        val cause =
            try {
                when (val result = withTimeoutOrNull(budget) { read() }) {
                    null -> "timed out"
                    is Outcome.Success -> return result.value
                    is Outcome.Failure -> result.error.diagnostic()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                e::class.simpleName ?: "exception"
            }
        logger.log(LogEvent.OperationFailed(operation, cause))
        return null
    }

    companion object {
        /** The longest the settings may hold up a fire. */
        val BUDGET: Duration = 500.milliseconds

        const val OPERATION_SETTINGS = "read global settings"
        const val OPERATION_PENDING = "read pending changes"
    }
}
