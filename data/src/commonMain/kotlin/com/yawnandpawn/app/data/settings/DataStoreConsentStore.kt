package com.yawnandpawn.app.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import com.yawnandpawn.app.core.error.DomainError
import com.yawnandpawn.app.core.error.Outcome
import com.yawnandpawn.app.core.log.LogEvent
import com.yawnandpawn.app.core.log.Logger
import com.yawnandpawn.app.core.telemetry.AnalyticsConsent
import com.yawnandpawn.app.core.telemetry.ConsentStore
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.retryWhen
import okio.IOException
import kotlin.coroutines.cancellation.CancellationException

/**
 * [ConsentStore] in the settings DataStore (device-protected, Story 5.6): the usage stats choice under [CONSENT] and
 * whether the user was asked under [ASKED], both false until set. A file read error is logged and reads as no consent
 * (stats are never sent because a file could not be read); the flow then reads the file again after a growing pause,
 * like [DataStoreMissedNoteDismissals]. Other errors are not hidden. A write failure is a `StorageFailure`.
 *
 * The settings DataStore is backed up, so a restore carries the choice to a new phone.
 */
class DataStoreConsentStore(
    private val store: DataStore<Preferences>,
    private val logger: Logger,
) : ConsentStore {
    override fun observe(): Flow<AnalyticsConsent> =
        store.data
            .retryWhen { cause, attempt ->
                if (cause !is IOException) return@retryWhen false
                logger.log(LogEvent.OperationFailed("read usage stats consent", cause::class.simpleName.orEmpty()))
                emit(emptyPreferences())
                delay(DataStoreMissedNoteDismissals.readRetryDelay(attempt))
                true
            }.map { AnalyticsConsent(consent = it[CONSENT] ?: false, asked = it[ASKED] ?: false) }
            .distinctUntilChanged()

    // Same boundary as the Room repositories: every storage exception is a StorageFailure, cancellation propagates.
    @Suppress("TooGenericExceptionCaught")
    override suspend fun setConsent(on: Boolean): Outcome<Unit, DomainError> =
        try {
            store.edit {
                it[CONSENT] = on
                it[ASKED] = true
            }
            Outcome.Success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Outcome.Failure(DomainError.StorageFailure(e.message ?: e::class.simpleName ?: "storage error"))
        }

    companion object {
        /** The user shares anonymous usage stats. */
        val CONSENT: Preferences.Key<Boolean> = booleanPreferencesKey("analytics_consent")

        /** The user made a usage stats choice (onboarding asks once). */
        val ASKED: Preferences.Key<Boolean> = booleanPreferencesKey("analytics_asked")
    }
}
