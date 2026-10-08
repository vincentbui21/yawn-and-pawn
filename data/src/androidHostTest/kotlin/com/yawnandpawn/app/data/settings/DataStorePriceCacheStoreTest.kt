package com.yawnandpawn.app.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.preferencesOf
import androidx.test.core.app.ApplicationProvider
import com.yawnandpawn.app.core.billing.CachedPriceCatalog
import com.yawnandpawn.app.core.billing.PriceCatalogJson
import com.yawnandpawn.app.core.billing.PriceCatalogSnapshot
import com.yawnandpawn.app.core.billing.SnoozeProducts
import com.yawnandpawn.app.core.error.DomainError
import com.yawnandpawn.app.core.error.Outcome
import com.yawnandpawn.app.core.log.LogEvent
import com.yawnandpawn.app.testing.DEFAULT_FAKE_INSTANT
import com.yawnandpawn.app.testing.FakeClock
import com.yawnandpawn.app.testing.FakeLogger
import com.yawnandpawn.app.testing.FakeProductDetailsSource
import com.yawnandpawn.app.testing.aPriceSnapshot
import com.yawnandpawn.app.testing.productPrices
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days

/** Story 4.3: the cached Play prices in their own device-protected DataStore. */
@RunWith(RobolectricTestRunner::class)
class DataStorePriceCacheStoreTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val logger = FakeLogger()
    private var cache = PriceCacheDataStore(context)

    @After
    fun tearDown() = cache.close()

    private fun store() = DataStorePriceCacheStore(cache.store, logger)

    @Test
    fun `the cache is empty at first and a snapshot survives a new DataStore instance`() =
        runTest {
            assertEquals(PriceCatalogSnapshot.EMPTY, store().observe().first())
            val snapshot = PriceCatalogSnapshot(aPriceSnapshot(1..3).entries + aPriceSnapshot(4..4, currency = "JPY").entries)

            assertEquals(Outcome.Success(snapshot), store().update { snapshot })
            cache.close()
            cache = PriceCacheDataStore(context)

            assertEquals(snapshot, store().observe().first())
        }

    @Test
    fun `the file is device-protected, apart from the settings`() {
        val file = PriceCacheDataStore.priceCacheFile(context)

        assertTrue(file.startsWith(context.createDeviceProtectedStorageContext().filesDir))
        assertEquals(
            "datastore/price_cache.preferences_pb",
            file.relativeTo(context.createDeviceProtectedStorageContext().filesDir).invariantSeparatorsPath,
        )
        assertTrue(file != SettingsDataStore.settingsFile(context))
    }

    @Test
    fun `a partial refresh through the catalog keeps the entries Play did not return`() =
        runTest {
            val store = store()
            store.update { aPriceSnapshot(1..50) }
            val later = DEFAULT_FAKE_INSTANT + 1.days
            val source = FakeProductDetailsSource()
            source.succeedWith(productPrices(1..10), unfetched = (11..50).map { SnoozeProducts.idOf(it) }.toSet())
            val catalog = CachedPriceCatalog(source, store, FakeClock(later), logger)

            assertEquals(Outcome.Success(Unit), catalog.refresh())

            val expected = PriceCatalogSnapshot(aPriceSnapshot(1..10, later).entries + aPriceSnapshot(11..50).entries)
            assertEquals(expected, catalog.observe().first())
        }

    @Test
    fun `a failed refresh keeps the stored snapshot`() =
        runTest {
            val store = store()
            store.update { aPriceSnapshot(1..50) }
            val source = FakeProductDetailsSource().apply { failWith() }

            assertIs<Outcome.Failure<*>>(CachedPriceCatalog(source, store, FakeClock(), logger).refresh())

            assertEquals(aPriceSnapshot(1..50), store.observe().first())
        }

    @Test
    fun `a corrupt file reads as empty without crashing, and the next write succeeds`() =
        runTest {
            cache.close()
            PriceCacheDataStore.priceCacheFile(context).apply {
                parentFile?.mkdirs()
                writeBytes(byteArrayOf(0x7f, 0x00, 0x13, 0x37, 0x42, 0x0a, 0xff.toByte()))
            }
            cache = PriceCacheDataStore(context)

            assertEquals(PriceCatalogSnapshot.EMPTY, store().observe().first())
            assertEquals(Outcome.Success(aPriceSnapshot(1..1)), store().update { aPriceSnapshot(1..1) })
            assertEquals(aPriceSnapshot(1..1), store().observe().first())
        }

    @Test
    fun `an undecodable value is logged, read as empty and overwritten by the next update`() =
        runTest {
            cache.store.edit { it[DataStorePriceCacheStore.KEY] = "{not a snapshot" }

            assertEquals(PriceCatalogSnapshot.EMPTY, store().observe().first())
            assertEquals(listOf<LogEvent>(LogEvent.OperationFailed("read price cache", "undecodable")), logger.events)

            assertEquals(
                Outcome.Success(aPriceSnapshot(2..2)),
                store().update { previous ->
                    PriceCatalogSnapshot(previous.entries + aPriceSnapshot(2..2).entries)
                },
            )
            assertEquals(aPriceSnapshot(2..2), store().observe().first())
        }

    @Test
    fun `an unreadable store emits an empty snapshot, and an unwritable one is a storage failure`() =
        runTest {
            val store = DataStorePriceCacheStore(UnusableStore, logger)

            assertEquals(PriceCatalogSnapshot.EMPTY, store.observe().first())
            assertEquals(listOf<LogEvent>(LogEvent.OperationFailed("read price cache", "IOException")), logger.events)
            assertIs<DomainError.StorageFailure>(assertIs<Outcome.Failure<DomainError>>(store.update { it }).error)
        }

    @Test
    fun `after a read error the flow reads again and emits the stored snapshot, so it never ends on a transient error`() =
        runTest {
            val stored = preferencesOf(DataStorePriceCacheStore.KEY to PriceCatalogJson.encode(aPriceSnapshot(1..3)))
            var reads = 0
            val flaky =
                object : DataStore<Preferences> {
                    override val data: Flow<Preferences> =
                        flow {
                            if (reads++ == 0) throw IOException("busy")
                            emit(stored)
                        }

                    override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences = transform(stored)
                }

            val emitted = DataStorePriceCacheStore(flaky, logger).observe().take(2).toList()

            assertEquals(listOf(PriceCatalogSnapshot.EMPTY, aPriceSnapshot(1..3)), emitted)
            assertEquals(2, reads)
            assertEquals(listOf<LogEvent>(LogEvent.OperationFailed("read price cache", "IOException")), logger.events)
        }

    @Test
    fun `a transform that throws is a storage failure and the stored snapshot stays, also after reopening`() =
        runTest {
            store().update { aPriceSnapshot(1..50) }

            val failed = store().update { error("boom") }

            assertIs<DomainError.StorageFailure>(assertIs<Outcome.Failure<DomainError>>(failed).error)
            cache.close()
            cache = PriceCacheDataStore(context)
            assertEquals(aPriceSnapshot(1..50), store().observe().first())
        }

    /** A DataStore whose file cannot be read or written. */
    private object UnusableStore : DataStore<Preferences> {
        override val data: Flow<Preferences> = flow { throw IOException("disk unreadable") }

        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences = throw IOException("disk full")
    }
}
