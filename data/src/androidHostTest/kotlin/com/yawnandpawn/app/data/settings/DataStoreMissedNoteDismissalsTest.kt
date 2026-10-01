package com.yawnandpawn.app.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.test.core.app.ApplicationProvider
import com.yawnandpawn.app.core.error.DomainError
import com.yawnandpawn.app.core.error.Outcome
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Story 1.16: the missed-note dismissals in the device-protected settings DataStore. */
@RunWith(RobolectricTestRunner::class)
class DataStoreMissedNoteDismissalsTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private var settings = SettingsDataStore(context)

    @After
    fun tearDown() = settings.close()

    @Test
    fun `nothing is dismissed at first, and a dismissal is kept per session id`() =
        runTest {
            val dismissals = DataStoreMissedNoteDismissals(settings.store)
            assertEquals(emptySet(), dismissals.dismissed().first())

            assertEquals(Outcome.Success(Unit), dismissals.dismiss("s1"))
            assertEquals(Outcome.Success(Unit), dismissals.dismiss("s2"))

            assertEquals(setOf("s1", "s2"), dismissals.dismissed().first())
        }

    @Test
    fun `a dismissal survives a new DataStore instance on the device-protected file`() =
        runTest {
            DataStoreMissedNoteDismissals(settings.store).dismiss("s1")
            settings.close()

            settings = SettingsDataStore(context)

            assertEquals(setOf("s1"), DataStoreMissedNoteDismissals(settings.store).dismissed().first())
            val file = SettingsDataStore.settingsFile(context)
            assertTrue(file.exists(), "written to ${file.path}")
            assertTrue(file.path.startsWith(context.createDeviceProtectedStorageContext().filesDir.path), "device-protected")
        }

    @Test
    fun `a read error emits nothing dismissed and a write error is a storage failure`() =
        runTest {
            val dismissals = DataStoreMissedNoteDismissals(BrokenStore)

            assertEquals(emptySet(), dismissals.dismissed().first())
            assertIs<DomainError.StorageFailure>(assertIs<Outcome.Failure<DomainError>>(dismissals.dismiss("s1")).error)
        }

    /** A DataStore whose file cannot be read or written. */
    private object BrokenStore : DataStore<Preferences> {
        override val data: Flow<Preferences> = flow { throw IOException("disk unreadable") }

        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences = throw IOException("disk full")
    }
}
