package com.yawnandpawn.app.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.test.core.app.ApplicationProvider
import com.yawnandpawn.app.core.error.DomainError
import com.yawnandpawn.app.core.error.Outcome
import com.yawnandpawn.app.core.log.LogEvent
import com.yawnandpawn.app.core.telemetry.AnalyticsConsent
import com.yawnandpawn.app.testing.FakeLogger
import kotlinx.coroutines.Dispatchers
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
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/** Story 5.6: the usage stats choice in its own device-protected DataStore, apart from the backed-up settings. */
@RunWith(RobolectricTestRunner::class)
class DataStoreConsentStoreTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val logger = FakeLogger()
    private var settings = ConsentDataStore(context)

    @After
    fun tearDown() = settings.close()

    @Test
    fun `consent is off and not asked until the user chooses`() =
        runTest {
            assertEquals(AnalyticsConsent(consent = false, asked = false), DataStoreConsentStore(settings.store, logger).observe().first())
        }

    @Test
    fun `a yes is stored at once and marks the user as asked, and a later no keeps asked`() =
        runTest {
            val consents = DataStoreConsentStore(settings.store, logger)

            assertEquals(Outcome.Success(Unit), consents.setConsent(true))
            assertEquals(AnalyticsConsent(consent = true, asked = true), consents.observe().first())

            assertEquals(Outcome.Success(Unit), consents.setConsent(false))
            assertEquals(AnalyticsConsent(consent = false, asked = true), consents.observe().first())
        }

    @Test
    fun `a no marks the user as asked too`() =
        runTest {
            val consents = DataStoreConsentStore(settings.store, logger)

            consents.setConsent(false)

            assertEquals(AnalyticsConsent(consent = false, asked = true), consents.observe().first())
        }

    @Test
    fun `an open collection sees the change, and the choice survives a new DataStore instance`() =
        runTest {
            val consents = DataStoreConsentStore(settings.store, logger)
            val seen = MutableStateFlow<AnalyticsConsent?>(null)
            backgroundScope.launch(Dispatchers.Default) { consents.observe().collect { seen.value = it } }
            withContext(Dispatchers.Default) { withTimeout(WAIT) { seen.first { it == AnalyticsConsent() } } }

            consents.setConsent(true)

            withContext(Dispatchers.Default) { withTimeout(WAIT) { seen.first { it?.consent == true } } }
            settings.close()
            settings = ConsentDataStore(context)
            assertEquals(AnalyticsConsent(consent = true, asked = true), DataStoreConsentStore(settings.store, logger).observe().first())
        }

    @Test
    fun `the file is device-protected, apart from the backed-up settings (review fix 5)`() {
        val file = ConsentDataStore.consentFile(context)

        assertTrue(file.startsWith(context.createDeviceProtectedStorageContext().filesDir))
        assertEquals(
            "datastore/usage_stats_consent.preferences_pb",
            file.relativeTo(context.createDeviceProtectedStorageContext().filesDir).invariantSeparatorsPath,
        )
        assertNotEquals(SettingsDataStore.settingsFile(context), file)
    }

    @Test
    fun `a corrupt file reads as no consent, and the next choice is stored`() =
        runTest {
            settings.close()
            ConsentDataStore.consentFile(context).apply {
                parentFile?.mkdirs()
                writeBytes(byteArrayOf(0x7f, 0x01, 0x02, 0x03))
            }
            settings = ConsentDataStore(context)
            val consents = DataStoreConsentStore(settings.store, logger)

            assertEquals(AnalyticsConsent(), consents.observe().first())
            assertEquals(Outcome.Success(Unit), consents.setConsent(true))
            assertEquals(AnalyticsConsent(consent = true, asked = true), consents.observe().first())
        }

    @Test
    fun `a read error is logged and reads as no consent`() =
        runTest {
            DataStoreConsentStore(settings.store, logger).setConsent(true)
            val flaky = FlakyStore(settings.store, failures = 1)

            assertEquals(AnalyticsConsent(), DataStoreConsentStore(flaky, logger).observe().first())

            assertEquals(listOf<LogEvent>(LogEvent.OperationFailed("read usage stats consent", "IOException")), logger.events)
        }

    @Test
    fun `an error that is not a file read error is not hidden`() =
        runTest {
            val consents = DataStoreConsentStore(BrokenStore(IllegalStateException("bug")), logger)

            assertFailsWith<IllegalStateException> { consents.observe().first() }
        }

    @Test
    fun `a write error is a storage failure`() =
        runTest {
            val consents = DataStoreConsentStore(BrokenStore(IOException("disk unreadable")), logger)

            assertIs<DomainError.StorageFailure>(assertIs<Outcome.Failure<DomainError>>(consents.setConsent(true)).error)
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

    private companion object {
        val WAIT = 10.seconds
    }
}
