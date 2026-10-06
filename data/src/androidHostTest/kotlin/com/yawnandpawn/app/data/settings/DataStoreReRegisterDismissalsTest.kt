package com.yawnandpawn.app.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.core.app.ApplicationProvider
import com.yawnandpawn.app.core.error.DomainError
import com.yawnandpawn.app.core.error.Outcome
import com.yawnandpawn.app.core.log.LogEvent
import com.yawnandpawn.app.core.stats.CheckKey
import com.yawnandpawn.app.testing.FakeLogger
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.time.Instant

/** Story 3.13: when the re-register banner of each check was dismissed, in the device-protected settings DataStore. */
@RunWith(RobolectricTestRunner::class)
class DataStoreReRegisterDismissalsTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val logger = FakeLogger()
    private var settings = SettingsDataStore(context)
    private val qr = CheckKey("alarm-1", "QrBarcode")
    private val first = Instant.parse("2027-03-10T07:00:00Z")
    private val later = Instant.parse("2027-03-14T07:00:00Z")

    @After
    fun tearDown() = settings.close()

    @Test
    fun `nothing at first, one time per check, and a later dismissal replaces it`() =
        runTest {
            val dismissals = DataStoreReRegisterDismissals(settings.store, logger)
            assertEquals(emptyMap(), dismissals.dismissed().first())

            assertEquals(Outcome.Success(Unit), dismissals.dismiss(qr, first))
            assertEquals(Outcome.Success(Unit), dismissals.dismiss(CheckKey("alarm-2", "QrBarcode"), first))
            assertEquals(Outcome.Success(Unit), dismissals.dismiss(qr, later))

            assertEquals(mapOf(qr to later, CheckKey("alarm-2", "QrBarcode") to first), dismissals.dismissed().first())
        }

    @Test
    fun `other settings and malformed entries are not dismissals, and a dismissal survives a new DataStore`() =
        runTest {
            settings.store.edit {
                it[DataStoreMissedNoteDismissals.KEY] = setOf("s1")
                it[longPreferencesKey("${DataStoreReRegisterDismissals.PREFIX}no-type")] = 1L
                it[longPreferencesKey("${DataStoreReRegisterDismissals.PREFIX}/QrBarcode")] = 1L
                it[stringPreferencesKey("${DataStoreReRegisterDismissals.PREFIX}alarm-3/QrBarcode")] = "not a time"
            }
            DataStoreReRegisterDismissals(settings.store, logger).dismiss(qr, first)
            settings.close()

            settings = SettingsDataStore(context)

            assertEquals(mapOf(qr to first), DataStoreReRegisterDismissals(settings.store, logger).dismissed().first())
        }

    @Test
    fun `a read error is logged and reads as nothing dismissed, then the file is read again`() =
        runTest {
            DataStoreReRegisterDismissals(settings.store, logger).dismiss(qr, first)
            val flaky = FlakyStore(settings.store, failures = 1)
            val dismissals = DataStoreReRegisterDismissals(flaky, logger)

            val seen = dismissals.dismissed().take2()

            assertEquals(listOf(emptyMap(), mapOf(qr to first)), seen)
            assertEquals(listOf<LogEvent>(LogEvent.OperationFailed("read re-register dismissals", "IOException")), logger.events)
        }

    @Test
    fun `an error that is not a file read error is not hidden, and a write error is a storage failure`() =
        runTest {
            assertFailsWith<IllegalStateException> {
                DataStoreReRegisterDismissals(BrokenStore(IllegalStateException("bug")), logger).dismissed().first()
            }
            val failed = DataStoreReRegisterDismissals(BrokenStore(IOException("disk unreadable")), logger).dismiss(qr, first)

            assertIs<DomainError.StorageFailure>(assertIs<Outcome.Failure<DomainError>>(failed).error)
        }

    /** The first two values (the retry pause runs on the test scheduler's virtual time). */
    private suspend fun Flow<Map<CheckKey, Instant>>.take2(): List<Map<CheckKey, Instant>> {
        val values = mutableListOf<Map<CheckKey, Instant>>()
        first {
            values += it
            values.size == 2
        }
        return values
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
}
