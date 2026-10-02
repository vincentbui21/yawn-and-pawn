package com.yawnandpawn.app.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.test.core.app.ApplicationProvider
import com.yawnandpawn.app.core.error.DomainError
import com.yawnandpawn.app.core.error.Outcome
import com.yawnandpawn.app.core.log.LogEvent
import com.yawnandpawn.app.testing.FakeLogger
import com.yawnandpawn.app.testing.aSessionConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** Story 1.18: the one pending test ring in the device-protected settings DataStore. */
@RunWith(RobolectricTestRunner::class)
class DataStoreTestAlarmStoreTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val logger = FakeLogger()
    private var settings = SettingsDataStore(context)

    @After
    fun tearDown() = settings.close()

    @Test
    fun `nothing is pending at first, and take returns the config once`() =
        runTest {
            val store = DataStoreTestAlarmStore(settings.store, logger)
            assertEquals(Outcome.Success(null), store.take())
            val config = aSessionConfig(testMode = true)

            assertEquals(Outcome.Success(Unit), store.put(config))

            assertEquals(Outcome.Success(config), store.take())
            assertEquals(Outcome.Success(null), store.take(), "taken: a fire never rings the same test twice")
        }

    @Test
    fun `a second test replaces the first`() =
        runTest {
            val store = DataStoreTestAlarmStore(settings.store, logger)
            store.put(aSessionConfig(label = "first", testMode = true))
            val second = aSessionConfig(label = "second", testMode = true)
            store.put(second)

            assertEquals(Outcome.Success(second), store.take())
        }

    @Test
    fun `the pending test survives a new DataStore instance`() =
        runTest {
            val config = aSessionConfig(testMode = true)
            DataStoreTestAlarmStore(settings.store, logger).put(config)
            settings.close()

            settings = SettingsDataStore(context)

            assertEquals(Outcome.Success(config), DataStoreTestAlarmStore(settings.store, logger).take())
        }

    @Test
    fun `an undecodable value is logged, dropped and read as none`() =
        runTest {
            settings.store.edit { it[DataStoreTestAlarmStore.KEY] = "{not a config" }
            val store = DataStoreTestAlarmStore(settings.store, logger)

            assertEquals(Outcome.Success(null), store.take())

            assertEquals(listOf<LogEvent>(LogEvent.OperationFailed("read pending test alarm", "undecodable")), logger.events)
            assertEquals(Outcome.Success(null), store.take())
        }

    @Test
    fun `a file that cannot be written is a storage failure, not an exception`() =
        runTest {
            val store = DataStoreTestAlarmStore(UnwritableStore, logger)

            assertIs<DomainError.StorageFailure>(assertIs<Outcome.Failure<DomainError>>(store.put(aSessionConfig())).error)
            assertIs<DomainError.StorageFailure>(assertIs<Outcome.Failure<DomainError>>(store.take()).error)
        }

    /** A DataStore whose file cannot be read or written. */
    private object UnwritableStore : DataStore<Preferences> {
        override val data: Flow<Preferences> = flow { throw IOException("disk unreadable") }

        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences = throw IOException("disk full")
    }
}
