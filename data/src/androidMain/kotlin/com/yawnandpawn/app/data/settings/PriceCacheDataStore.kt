package com.yawnandpawn.app.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import okio.Path.Companion.toPath
import java.io.File

/**
 * The cached Play prices' own Preferences DataStore (Story 4.3), in device-protected storage so the wake screen reads
 * them before the first unlock. A separate file from the settings, because it is never backed up or transferred (a
 * phone restored in another country must not show the old currency). A corrupt file is replaced with an empty one:
 * the cache is only a copy of what Play says, and the next refresh fills it again. One instance per process (a Koin
 * single); [close] releases the file.
 */
class PriceCacheDataStore(
    context: Context,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val store: DataStore<Preferences> =
        PreferenceDataStoreFactory.createWithPath(
            corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
            scope = scope,
        ) { priceCacheFile(context).absolutePath.toPath() }

    /** Releases the file (Koin closing, a test restarting the app). */
    fun close() = runBlocking { scope.coroutineContext.job.cancelAndJoin() }

    companion object {
        const val FILE_NAME = "price_cache.preferences_pb"

        /** `datastore/price_cache.preferences_pb` in the device-protected files directory, never credential-protected. */
        fun priceCacheFile(context: Context): File = File(context.createDeviceProtectedStorageContext().filesDir, "datastore/$FILE_NAME")
    }
}
