package com.yawnandpawn.app.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.yawnandpawn.app.core.error.DomainError
import com.yawnandpawn.app.core.error.Outcome
import com.yawnandpawn.app.core.log.LogEvent
import com.yawnandpawn.app.core.log.Logger
import com.yawnandpawn.app.core.onboarding.OnboardingProgress
import com.yawnandpawn.app.core.onboarding.OnboardingRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.retryWhen
import okio.IOException
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Instant

/**
 * [OnboardingRepository] in the settings DataStore (device-protected, backed up, so a restored phone opens on Home; Story
 * 5.10): [COMPLETED], the current [STEP], the disclosure's [ACCEPTED_AT] (epoch milliseconds) and the first alarm's
 * [ALARM_ID] (Story 5.11). "Delete all data"
 * clears that store (Story 5.9), so the app goes back to onboarding by itself.
 *
 * A file read error in [observe] is logged; before the first good read it reads as a fresh [OnboardingProgress] (not
 * completed, no consent), after it the last progress read stays (review fix). The file is read again after a growing
 * pause, like [DataStoreMissedNoteDismissals]; other errors are not hidden. [get] and the writes return a
 * `StorageFailure`.
 */
class DataStoreOnboardingRepository(
    private val store: DataStore<Preferences>,
    private val logger: Logger,
) : OnboardingRepository {
    override fun observe(): Flow<OnboardingProgress> =
        flow {
            var read = false
            emitAll(
                store.data
                    .onEach { read = true }
                    .retryWhen { cause, attempt ->
                        if (cause !is IOException) return@retryWhen false
                        logger.log(LogEvent.OperationFailed(READ_PROGRESS, cause::class.simpleName.orEmpty()))
                        // Before the first read: a fresh progress. After it, the last one read stays, so a passing read
                        // error never sends a user who finished onboarding back to step 1 (nor clears their screens).
                        if (!read) emit(emptyPreferences())
                        delay(DataStoreMissedNoteDismissals.readRetryDelay(attempt))
                        true
                    },
            )
        }.map(::progressOf)
            .distinctUntilChanged()

    override suspend fun get(): Outcome<OnboardingProgress, DomainError> = storage { progressOf(store.data.first()) }

    override suspend fun setStep(step: String): Outcome<Unit, DomainError> = edit { it[STEP] = step }

    override suspend fun setDisclosureAccepted(at: Instant): Outcome<Unit, DomainError> =
        edit { it[ACCEPTED_AT] = at.toEpochMilliseconds() }

    override suspend fun setCompleted(): Outcome<Unit, DomainError> = edit { it[COMPLETED] = true }

    override suspend fun setAlarmId(id: String?): Outcome<Unit, DomainError> =
        edit { if (id == null) it.remove(ALARM_ID) else it[ALARM_ID] = id }

    private fun progressOf(preferences: Preferences): OnboardingProgress =
        OnboardingProgress(
            completed = preferences[COMPLETED] ?: false,
            step = preferences[STEP],
            disclosureAcceptedAt = preferences[ACCEPTED_AT]?.let(Instant::fromEpochMilliseconds),
            alarmId = preferences[ALARM_ID],
        )

    private suspend fun edit(change: (MutablePreferences) -> Unit): Outcome<Unit, DomainError> =
        storage {
            store.edit { change(it) }
            Unit
        }

    // Same boundary as the Room repositories: every storage exception is a StorageFailure, cancellation propagates.
    @Suppress("TooGenericExceptionCaught")
    private suspend fun <T> storage(block: suspend () -> T): Outcome<T, DomainError> =
        try {
            Outcome.Success(block())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Outcome.Failure(DomainError.StorageFailure(e.message ?: e::class.simpleName ?: "storage error"))
        }

    companion object {
        /** Onboarding is done: the app opens on Home. */
        val COMPLETED: Preferences.Key<Boolean> = booleanPreferencesKey("onboarding_completed")

        /** The step the user is on, by its UI name. */
        val STEP: Preferences.Key<String> = stringPreferencesKey("onboarding_step")

        /** When the user tapped "I understand" under the alarm behaviour disclosure, in epoch milliseconds. */
        val ACCEPTED_AT: Preferences.Key<Long> = longPreferencesKey("disclosure_accepted_at")

        /** The first alarm onboarding saved (Story 5.11), by its id. */
        val ALARM_ID: Preferences.Key<String> = stringPreferencesKey("onboarding_alarm_id")

        /** The operation logged when the progress cannot be read. */
        const val READ_PROGRESS = "read onboarding progress"
    }
}
