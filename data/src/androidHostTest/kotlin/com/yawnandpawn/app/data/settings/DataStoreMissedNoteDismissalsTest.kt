package com.yawnandpawn.app.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.test.core.app.ApplicationProvider
import com.yawnandpawn.app.core.error.DomainError
import com.yawnandpawn.app.core.error.Outcome
import com.yawnandpawn.app.core.log.LogEvent
import com.yawnandpawn.app.testing.FakeLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
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
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/** Story 1.16: the missed-note dismissals in the device-protected settings DataStore. */
@RunWith(RobolectricTestRunner::class)
class DataStoreMissedNoteDismissalsTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val logger = FakeLogger()
    private var settings = SettingsDataStore(context)

    @After
    fun tearDown() = settings.close()

    /** Collects [dismissals] in the background (on a real dispatcher, as DataStore works on IO threads). */
    private fun TestScope.collect(dismissals: DataStoreMissedNoteDismissals): MutableStateFlow<Set<String>?> {
        val seen = MutableStateFlow<Set<String>?>(null)
        backgroundScope.launch(Dispatchers.Default) { dismissals.dismissed().collect { seen.value = it } }
        return seen
    }

    /** Waits (real time, bounded) until the collection [seen] has [expected]. */
    private suspend fun awaitSeen(
        seen: MutableStateFlow<Set<String>?>,
        expected: Set<String>,
    ) = withContext(Dispatchers.Default) { withTimeout(WAIT) { seen.first { it == expected } } }

    @Test
    fun `nothing is dismissed at first, and a dismissal is kept per session id`() =
        runTest {
            val dismissals = DataStoreMissedNoteDismissals(settings.store, logger)
            assertEquals(emptySet(), dismissals.dismissed().first())

            assertEquals(Outcome.Success(Unit), dismissals.dismiss("s1"))
            assertEquals(Outcome.Success(Unit), dismissals.dismiss("s2"))

            assertEquals(setOf("s1", "s2"), dismissals.dismissed().first())
        }

    @Test
    fun `an open collection receives a new dismissal`() =
        runTest {
            val dismissals = DataStoreMissedNoteDismissals(settings.store, logger)
            val seen = collect(dismissals)
            awaitSeen(seen, emptySet())

            dismissals.dismiss("s1")

            awaitSeen(seen, setOf("s1"))
        }

    @Test
    fun `a dismissal survives a new DataStore instance on the device-protected file`() =
        runTest {
            DataStoreMissedNoteDismissals(settings.store, logger).dismiss("s1")
            settings.close()

            settings = SettingsDataStore(context)

            assertEquals(setOf("s1"), DataStoreMissedNoteDismissals(settings.store, logger).dismissed().first())
            val file = SettingsDataStore.settingsFile(context)
            assertTrue(file.exists(), "written to ${file.path}")
            assertTrue(file.path.startsWith(context.createDeviceProtectedStorageContext().filesDir.path), "device-protected")
        }

    @Test
    fun `a read error is logged and reads as nothing dismissed, and the flow stays alive for later dismissals`() =
        runTest {
            val flaky = FlakyStore(settings.store, failures = 1)
            val dismissals = DataStoreMissedNoteDismissals(flaky, logger)
            val seen = collect(dismissals)
            awaitSeen(seen, emptySet())

            // After the retry pause the file is read again, and the same collection sees the dismissal.
            withContext(Dispatchers.Default) { withTimeout(WAIT) { while (flaky.reads.get() < 2) delay(POLL) } }
            dismissals.dismiss("s1")

            awaitSeen(seen, setOf("s1"))
            assertEquals(listOf<LogEvent>(LogEvent.OperationFailed("read missed note dismissals", "IOException")), logger.events)
        }

    @Test
    fun `an error that is not a file read error is not hidden`() =
        runTest {
            val dismissals = DataStoreMissedNoteDismissals(BrokenStore(IllegalStateException("bug")), logger)

            assertFailsWith<IllegalStateException> { dismissals.dismissed().first() }
        }

    @Test
    fun `a write error is a storage failure`() =
        runTest {
            val dismissals = DataStoreMissedNoteDismissals(BrokenStore(IOException("disk unreadable")), logger)

            assertIs<DomainError.StorageFailure>(assertIs<Outcome.Failure<DomainError>>(dismissals.dismiss("s1")).error)
        }

    @Test
    fun `the read retry waits 1 s, then doubles up to a minute`() {
        val waits = (0L..8L).map { DataStoreMissedNoteDismissals.readRetryDelay(it) }

        assertEquals(listOf(1, 2, 4, 8, 16, 32, 60, 60, 60).map { it.seconds }, waits)
    }

    /** A DataStore whose reads throw [error] and whose writes fail. */
    private class BrokenStore(
        error: Exception,
    ) : DataStore<Preferences> {
        override val data: Flow<Preferences> = flow { throw error }

        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences = throw IOException("disk full")
    }

    /** [inner], except that its first [failures] reads throw an IOException. [reads] counts every read. */
    private class FlakyStore(
        private val inner: DataStore<Preferences>,
        failures: Int,
    ) : DataStore<Preferences> {
        private val failuresLeft = AtomicInteger(failures)
        val reads = AtomicInteger()

        override val data: Flow<Preferences> =
            flow {
                reads.incrementAndGet()
                if (failuresLeft.getAndDecrement() > 0) throw IOException("disk unreadable")
                emitAll(inner.data)
            }

        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences = inner.updateData(transform)
    }

    private companion object {
        val WAIT = 10.seconds
        const val POLL = 20L
    }
}
