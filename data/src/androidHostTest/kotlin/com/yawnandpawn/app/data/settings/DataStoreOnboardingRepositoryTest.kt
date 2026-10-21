package com.yawnandpawn.app.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.test.core.app.ApplicationProvider
import com.yawnandpawn.app.core.error.DomainError
import com.yawnandpawn.app.core.error.Outcome
import com.yawnandpawn.app.core.log.LogEvent
import com.yawnandpawn.app.core.onboarding.OnboardingProgress
import com.yawnandpawn.app.testing.FakeLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * Story 5.10: the onboarding progress and the disclosure consent in the settings DataStore: empty reads as a first
 * launch, every write survives a new instance, "Delete all data"'s `clear()` resets it, and a read error is logged and
 * reads as not completed.
 */
@RunWith(RobolectricTestRunner::class)
class DataStoreOnboardingRepositoryTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val logger = FakeLogger()
    private var settings = SettingsDataStore(context)
    private val accepted = Instant.parse("2027-03-08T21:00:00.123Z")

    @After
    fun tearDown() = settings.close()

    private fun repository(store: DataStore<Preferences> = settings.store) = DataStoreOnboardingRepository(store, logger)

    @Test
    fun `an empty store is a first launch, not completed and without consent`() =
        runTest {
            assertEquals(OnboardingProgress(), repository().observe().first())
            assertEquals(Outcome.Success(OnboardingProgress()), repository().get())
        }

    @Test
    fun `the step, the acceptance time and completion are stored under their keys and survive a new instance`() =
        runTest {
            val onboarding = repository()

            assertEquals(Outcome.Success(Unit), onboarding.setStep("Disclosure"))
            assertEquals(Outcome.Success(Unit), onboarding.setDisclosureAccepted(accepted))
            assertEquals(Outcome.Success(Unit), onboarding.setStep("BaseFee"))
            assertEquals(Outcome.Success(Unit), onboarding.setCompleted())

            val expected = OnboardingProgress(completed = true, step = "BaseFee", disclosureAcceptedAt = accepted)
            assertEquals(Outcome.Success(expected), onboarding.get())
            val raw = settings.store.data.first()
            assertEquals(true, raw[DataStoreOnboardingRepository.COMPLETED])
            assertEquals("BaseFee", raw[DataStoreOnboardingRepository.STEP])
            assertEquals(accepted.toEpochMilliseconds(), raw[DataStoreOnboardingRepository.ACCEPTED_AT])
            assertEquals("onboarding_completed", DataStoreOnboardingRepository.COMPLETED.name)
            assertEquals("onboarding_step", DataStoreOnboardingRepository.STEP.name)
            assertEquals("disclosure_accepted_at", DataStoreOnboardingRepository.ACCEPTED_AT.name)

            settings.close()
            settings = SettingsDataStore(context)
            assertEquals(expected, repository().observe().first())
        }

    @Test
    fun `an open collection sees each change`() =
        runTest {
            val onboarding = repository()
            val seen = MutableStateFlow<OnboardingProgress?>(null)
            backgroundScope.launch(Dispatchers.Default) { onboarding.observe().collect { seen.value = it } }
            withContext(Dispatchers.Default) { withTimeout(WAIT) { seen.first { it == OnboardingProgress() } } }

            onboarding.setCompleted()

            withContext(Dispatchers.Default) { withTimeout(WAIT) { seen.first { it?.completed == true } } }
        }

    @Test
    fun `clearing the settings store (Delete all data) resets onboarding to a first launch`() =
        runTest {
            val onboarding = repository()
            onboarding.setDisclosureAccepted(accepted)
            onboarding.setCompleted()

            settings.store.edit { it.clear() }

            assertEquals(OnboardingProgress(), onboarding.observe().first())
        }

    @Test
    fun `a read error is logged and reads as not completed in the flow, and as a storage failure from get`() =
        runTest {
            repository().setCompleted()
            val flaky = FlakyStore(settings.store, failures = 1)

            assertEquals(OnboardingProgress(), repository(flaky).observe().first())
            assertEquals(listOf<LogEvent>(LogEvent.OperationFailed("read onboarding progress", "IOException")), logger.events)

            val broken = BrokenStore(IOException("disk unreadable"))
            assertIs<DomainError.StorageFailure>(assertIs<Outcome.Failure<DomainError>>(repository(broken).get()).error)
        }

    @Test
    fun `a read error after a good read keeps the last progress, so a finished user is not sent back`() =
        runTest {
            repository().setCompleted()
            val store = FailsAfterFirstRead(settings.store)
            val seen = mutableListOf<OnboardingProgress>()
            backgroundScope.launch(Dispatchers.Default) { repository(store).observe().collect { synchronized(seen) { seen += it } } }

            withContext(Dispatchers.Default) {
                withTimeout(WAIT) { while (logger.events.isEmpty() || !store.reread.get()) delay(POLL) }
            }

            assertEquals(listOf<LogEvent>(LogEvent.OperationFailed("read onboarding progress", "IOException")), logger.events)
            assertEquals(listOf(OnboardingProgress(completed = true)), synchronized(seen) { seen.toList() })
        }

    @Test
    fun `an error that is not a file read error is not hidden`() =
        runTest {
            assertFailsWith<IllegalStateException> { repository(BrokenStore(IllegalStateException("bug"))).observe().first() }
        }

    @Test
    fun `a write error is a storage failure`() =
        runTest {
            val onboarding = repository(BrokenStore(IOException("disk unreadable")))

            listOf(onboarding.setStep("Disclosure"), onboarding.setDisclosureAccepted(accepted), onboarding.setCompleted()).forEach {
                assertIs<DomainError.StorageFailure>(assertIs<Outcome.Failure<DomainError>>(it).error)
            }
        }

    /** A DataStore whose reads throw [error] and whose writes fail. */
    private class BrokenStore(
        error: Exception,
    ) : DataStore<Preferences> {
        override val data: Flow<Preferences> = flow { throw error }

        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences = throw IOException("disk full")
    }

    /** [inner], except that its first [failures] reads throw an IOException. */
    private class FlakyStore(
        private val inner: DataStore<Preferences>,
        failures: Int,
    ) : DataStore<Preferences> {
        private val failuresLeft = AtomicInteger(failures)

        override val data: Flow<Preferences> =
            flow {
                if (failuresLeft.getAndDecrement() > 0) throw IOException("disk unreadable")
                emitAll(inner.data)
            }

        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences = inner.updateData(transform)
    }

    /** [inner]'s first value, then an IOException once; read again, [inner] as it is ([reread] once that happened). */
    private class FailsAfterFirstRead(
        private val inner: DataStore<Preferences>,
    ) : DataStore<Preferences> {
        private val failed = AtomicBoolean(false)
        val reread = AtomicBoolean(false)

        override val data: Flow<Preferences> =
            flow {
                if (!failed.getAndSet(true)) {
                    emit(inner.data.first())
                    throw IOException("disk unreadable")
                }
                reread.set(true)
                emitAll(inner.data)
            }

        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences = inner.updateData(transform)
    }

    private companion object {
        val WAIT = 10.seconds
        val POLL = 10.milliseconds
    }
}
