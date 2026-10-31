package com.yawnandpawn.app.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.test.core.app.ApplicationProvider
import com.yawnandpawn.app.core.error.DomainError
import com.yawnandpawn.app.core.error.Outcome
import com.yawnandpawn.app.core.log.LogEvent
import com.yawnandpawn.app.core.reminder.SkipReminderPrefs
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
import kotlinx.datetime.LocalDate
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.seconds

/** 0.1.8: the Skip reminder switch and the last reminded date, in the device-protected settings DataStore. */
@RunWith(RobolectricTestRunner::class)
class DataStoreSkipReminderStoreTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val logger = FakeLogger()
    private var settings = SettingsDataStore(context)
    private val date = LocalDate(2027, 3, 4)

    @After
    fun tearDown() = settings.close()

    @Test
    fun `the switch is on and no date is reminded until something is stored`() =
        runTest {
            val store = DataStoreSkipReminderStore(settings.store, logger)

            assertEquals(SkipReminderPrefs(enabled = true, lastRemindedDate = null), store.observe().first())
            assertEquals(Outcome.Success(SkipReminderPrefs()), store.read())
        }

    @Test
    fun `the switch and the date are stored, read back, and survive a new DataStore instance`() =
        runTest {
            val store = DataStoreSkipReminderStore(settings.store, logger)

            assertEquals(Outcome.Success(Unit), store.setEnabled(false))
            assertEquals(Outcome.Success(Unit), store.setLastRemindedDate(date))
            assertEquals(Outcome.Success(SkipReminderPrefs(enabled = false, lastRemindedDate = date)), store.read())
            assertEquals("2027-03-04", settings.store.data.first()[DataStoreSkipReminderStore.LAST_DATE])

            settings.close()
            settings = SettingsDataStore(context)
            assertEquals(SkipReminderPrefs(false, date), DataStoreSkipReminderStore(settings.store, logger).observe().first())
        }

    @Test
    fun `an open collection sees each change`() =
        runTest {
            val store = DataStoreSkipReminderStore(settings.store, logger)
            val seen = MutableStateFlow<SkipReminderPrefs?>(null)
            backgroundScope.launch(Dispatchers.Default) { store.observe().collect { seen.value = it } }
            withContext(Dispatchers.Default) { withTimeout(WAIT) { seen.first { it == SkipReminderPrefs() } } }

            store.setEnabled(false)

            withContext(Dispatchers.Default) { withTimeout(WAIT) { seen.first { it?.enabled == false } } }
        }

    @Test
    fun `a date that cannot be parsed reads as none`() =
        runTest {
            settings.store.edit { it[DataStoreSkipReminderStore.LAST_DATE] = "last Sunday" }

            assertEquals(SkipReminderPrefs(), DataStoreSkipReminderStore(settings.store, logger).observe().first())
        }

    @Test
    fun `an IO read error is logged and reads as the defaults, while a one-shot read fails`() =
        runTest {
            DataStoreSkipReminderStore(settings.store, logger).setEnabled(false)

            assertEquals(
                SkipReminderPrefs(),
                DataStoreSkipReminderStore(FlakyStore(settings.store, failures = 1), logger).observe().first(),
            )
            assertEquals(listOf<LogEvent>(LogEvent.OperationFailed("read skip reminder", "IOException")), logger.events)

            val read = DataStoreSkipReminderStore(FlakyStore(settings.store, failures = 1), logger).read()
            assertIs<DomainError.StorageFailure>(assertIs<Outcome.Failure<DomainError>>(read).error)
        }

    @Test
    fun `a write error is a storage failure`() =
        runTest {
            val store = DataStoreSkipReminderStore(FlakyStore(settings.store, failures = 0, writable = false), logger)

            assertIs<DomainError.StorageFailure>(assertIs<Outcome.Failure<DomainError>>(store.setEnabled(false)).error)
            assertIs<DomainError.StorageFailure>(assertIs<Outcome.Failure<DomainError>>(store.setLastRemindedDate(date)).error)
        }

    @Test
    fun `Delete all data clears both, so the switch reads on again and the date is forgotten`() =
        runTest {
            val store = DataStoreSkipReminderStore(settings.store, logger)
            store.setEnabled(false)
            store.setLastRemindedDate(date)

            settings.clear()

            assertEquals(Outcome.Success(SkipReminderPrefs()), store.read())
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
