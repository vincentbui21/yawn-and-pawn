package com.yawnandpawn.app.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.test.core.app.ApplicationProvider
import com.yawnandpawn.app.core.error.DomainError
import com.yawnandpawn.app.core.error.Outcome
import com.yawnandpawn.app.core.log.LogEvent
import com.yawnandpawn.app.core.stats.IsoWeek
import com.yawnandpawn.app.core.summary.WeeklySummaryPrefs
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
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.seconds

/** Story 6.8: the weekly summary switch and the last handled week, in the device-protected settings DataStore. */
@RunWith(RobolectricTestRunner::class)
class DataStoreWeeklySummaryStoreTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val logger = FakeLogger()
    private var settings = SettingsDataStore(context)
    private val week = IsoWeek(2027, 9)

    @After
    fun tearDown() = settings.close()

    @Test
    fun `the switch is on and no week is handled until something is stored`() =
        runTest {
            val store = DataStoreWeeklySummaryStore(settings.store, logger)

            assertEquals(WeeklySummaryPrefs(enabled = true, lastHandledWeek = null), store.observe().first())
            assertEquals(Outcome.Success(WeeklySummaryPrefs()), store.read())
        }

    @Test
    fun `the switch and the week are stored, read back, and survive a new DataStore instance`() =
        runTest {
            val store = DataStoreWeeklySummaryStore(settings.store, logger)

            assertEquals(Outcome.Success(Unit), store.setEnabled(false))
            assertEquals(Outcome.Success(Unit), store.setLastHandledWeek(week))
            assertEquals(Outcome.Success(WeeklySummaryPrefs(enabled = false, lastHandledWeek = week)), store.read())
            assertEquals("2027-W09", settings.store.data.first()[DataStoreWeeklySummaryStore.LAST_WEEK])

            settings.close()
            settings = SettingsDataStore(context)
            assertEquals(WeeklySummaryPrefs(false, week), DataStoreWeeklySummaryStore(settings.store, logger).observe().first())
        }

    @Test
    fun `an open collection sees each change`() =
        runTest {
            val store = DataStoreWeeklySummaryStore(settings.store, logger)
            val seen = MutableStateFlow<WeeklySummaryPrefs?>(null)
            backgroundScope.launch(Dispatchers.Default) { store.observe().collect { seen.value = it } }
            withContext(Dispatchers.Default) { withTimeout(WAIT) { seen.first { it == WeeklySummaryPrefs() } } }

            store.setEnabled(false)

            withContext(Dispatchers.Default) { withTimeout(WAIT) { seen.first { it?.enabled == false } } }
        }

    @Test
    fun `a week that cannot be parsed reads as none`() =
        runTest {
            settings.store.edit { it[DataStoreWeeklySummaryStore.LAST_WEEK] = "last Sunday" }

            assertEquals(WeeklySummaryPrefs(), DataStoreWeeklySummaryStore(settings.store, logger).observe().first())
        }

    @Test
    fun `an IO read error is logged and reads as the defaults, while a one-shot read fails`() =
        runTest {
            DataStoreWeeklySummaryStore(settings.store, logger).setEnabled(false)

            assertEquals(
                WeeklySummaryPrefs(),
                DataStoreWeeklySummaryStore(FlakyStore(settings.store, failures = 1), logger).observe().first(),
            )
            assertEquals(listOf<LogEvent>(LogEvent.OperationFailed("read weekly summary", "IOException")), logger.events)

            val read = DataStoreWeeklySummaryStore(FlakyStore(settings.store, failures = 1), logger).read()
            assertIs<DomainError.StorageFailure>(assertIs<Outcome.Failure<DomainError>>(read).error)
        }

    @Test
    fun `a write error is a storage failure`() =
        runTest {
            val store = DataStoreWeeklySummaryStore(FlakyStore(settings.store, failures = 0, writable = false), logger)

            assertIs<DomainError.StorageFailure>(assertIs<Outcome.Failure<DomainError>>(store.setEnabled(false)).error)
            assertIs<DomainError.StorageFailure>(assertIs<Outcome.Failure<DomainError>>(store.setLastHandledWeek(week)).error)
        }

    @Test
    fun `Delete all data clears both, so the switch reads on again and the week is forgotten`() =
        runTest {
            val store = DataStoreWeeklySummaryStore(settings.store, logger)
            store.setEnabled(false)
            store.setLastHandledWeek(week)

            settings.clear()

            assertEquals(Outcome.Success(WeeklySummaryPrefs()), store.read())
        }

    /** [inner], except that its first [failures] reads throw an IOException, and its writes fail unless [writable]. */
    private class FlakyStore(
        private val inner: DataStore<Preferences>,
        failures: Int,
        private val writable: Boolean = true,
    ) : DataStore<Preferences> {
        private val failuresLeft = AtomicInteger(failures)

        override val data: Flow<Preferences> =
            flow {
                if (failuresLeft.getAndDecrement() > 0) throw IOException("disk unreadable")
                emitAll(inner.data)
            }

        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
            if (writable) inner.updateData(transform) else throw IOException("disk full")
    }

    private companion object {
        val WAIT = 10.seconds
    }
}
