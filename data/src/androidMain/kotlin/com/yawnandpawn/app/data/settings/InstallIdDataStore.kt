package com.yawnandpawn.app.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import okio.Path.Companion.toPath
import java.io.File

/**
 * The install id's own Preferences DataStore (Story 4.8), in device-protected storage so billing can read it right after
 * the first unlock. A file of its own, not the settings DataStore: that one is backed up and moved to a new phone, and
 * an install id must never be (NFR-14, both backup rule files exclude this file). One instance per process (a Koin
 * single); [close] releases the file.
 */
class InstallIdDataStore(
    context: Context,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val store: DataStore<Preferences> =
        PreferenceDataStoreFactory.createWithPath(scope = scope) { installIdFile(context).absolutePath.toPath() }

    /** Releases the file (Koin closing, a test restarting the app). */
    fun close() = runBlocking { scope.coroutineContext.job.cancelAndJoin() }

    companion object {
        const val FILE_NAME = "install_id.preferences_pb"

        /** `datastore/install_id.preferences_pb` in the device-protected files directory, never credential-protected. */
        fun installIdFile(context: Context): File = File(context.createDeviceProtectedStorageContext().filesDir, "datastore/$FILE_NAME")
    }
}
