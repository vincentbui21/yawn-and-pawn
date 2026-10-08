package com.yawnandpawn.app.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.test.core.app.ApplicationProvider
import com.yawnandpawn.app.core.error.DomainError
import com.yawnandpawn.app.core.error.Outcome
import com.yawnandpawn.app.core.id.IdGenerator
import com.yawnandpawn.app.core.id.UuidV4IdGenerator
import com.yawnandpawn.app.core.log.LogEvent
import com.yawnandpawn.app.testing.FakeLogger
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** Story 4.8: the install id is a random UUID v4 in its own device-protected DataStore, stable and never logged. */
@RunWith(RobolectricTestRunner::class)
class DataStoreInstallIdProviderTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val logger = FakeLogger()

    private fun open(): InstallIdDataStore = InstallIdDataStore(context, UuidV4IdGenerator(), logger)

    private var file = open()

    @After
    fun tearDown() = file.close()

    private fun provider(ids: IdGenerator = UuidV4IdGenerator()) = DataStoreInstallIdProvider(file.store, ids, logger)

    private fun Outcome<String, DomainError>.id(): String = assertIs<Outcome.Success<String>>(this).value

    @Test
    fun `the first call makes a random UUID v4 and every later call returns the same one`() =
        runTest {
            val counting = CountingIds()
            val first = provider(counting).installId().id()

            assertTrue(UUID_V4.matches(first), first)
            assertEquals(first, provider(counting).installId().id())
            assertEquals(first, provider(counting).installId().id())
            assertEquals(1, counting.made, "made once")
        }

    @Test
    fun `the id survives a new process (a new DataStore over the same file)`() =
        runTest {
            val first = provider().installId().id()
            file.close()
            file = open()

            assertEquals(first, provider().installId().id())
        }

    @Test
    fun `concurrent first calls agree on one id`() =
        runTest {
            val counting = CountingIds()
            val ids = List(10) { async { provider(counting).installId().id() } }.awaitAll()

            assertEquals(1, ids.toSet().size)
            assertEquals(1, counting.made)
        }

    @Test
    fun `two installs get different ids, from the id generator and nothing about the device`() =
        runTest {
            val first = provider().installId().id()
            file.close()
            InstallIdDataStore.installIdFile(context).delete()
            file = open()

            val second = provider().installId().id()
            assertNotEquals(first, second, "a fresh install (or a cleared file) gets a new random id")
            val fixed = DataStoreInstallIdProvider(freshStore(), { "id-from-the-generator" }, logger)
            assertEquals("id-from-the-generator", fixed.installId().id(), "the value is the generator's, nothing else")
        }

    @Test
    fun `a corrupt file is replaced by a new id that then stays, and the log never holds it`() =
        runTest {
            file.close()
            InstallIdDataStore.installIdFile(context).apply {
                parentFile?.mkdirs()
                writeBytes(byteArrayOf(0x7f, 0x01, 0x02, 0x03, 0x04, 0x05))
            }
            file = open()

            val id = provider().installId().id()

            assertTrue(UUID_V4.matches(id), id)
            assertEquals(id, provider().installId().id())
            file.close()
            file = open()
            assertEquals(id, provider().installId().id(), "the replacement was written to the file")
            assertEquals(
                listOf<LogEvent>(LogEvent.OperationFailed("read install id", "corrupt file, install id regenerated")),
                logger.events,
            )
            assertFalse(logger.events.any { id in it.toString() })
        }

    @Test
    fun `a blank stored value is replaced by a new id`() =
        runTest {
            file.store.edit { it[DataStoreInstallIdProvider.KEY] = " " }

            assertTrue(UUID_V4.matches(provider().installId().id()))
        }

    @Test
    fun `the file lives in device-protected storage under the excluded name`() {
        val path = InstallIdDataStore.installIdFile(context)
        assertEquals("install_id.preferences_pb", path.name)
        assertTrue(path.absolutePath.startsWith(context.createDeviceProtectedStorageContext().filesDir.absolutePath))
    }

    @Test
    fun `the id is never logged, and a read failure is logged by type only`() =
        runTest {
            val id = provider().installId().id()
            val failing = DataStoreInstallIdProvider(BrokenStore(), { "never-used-id" }, logger)

            assertEquals(Outcome.Failure(DomainError.StorageFailure("IOException")), failing.installId())

            assertEquals(listOf<LogEvent>(LogEvent.OperationFailed("read install id", "IOException")), logger.events)
            assertFalse(logger.events.any { id in it.toString() || "never-used-id" in it.toString() })
        }

    /** The install id file emptied (closed, deleted and opened again), for a provider with a fixed generator. */
    private fun freshStore(): DataStore<Preferences> {
        file.close()
        InstallIdDataStore.installIdFile(context).delete()
        file = open()
        return file.store
    }

    private class CountingIds : IdGenerator {
        private val real = UuidV4IdGenerator()
        var made = 0

        override fun newId(): String {
            made++
            return real.newId()
        }
    }

    /** A DataStore whose every read and write fails, with a message that holds a secret-looking value. */
    private class BrokenStore : DataStore<Preferences> {
        override val data: Flow<Preferences> = flow { throw IOException("disk gone: never-used-id") }

        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
            throw IOException("disk gone: never-used-id")
    }

    private companion object {
        val UUID_V4 = Regex("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")
    }
}
