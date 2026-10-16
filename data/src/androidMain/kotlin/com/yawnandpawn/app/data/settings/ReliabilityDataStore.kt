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
 * The reliability checklist's own Preferences DataStore (Story 5.3 review fix 14), in device-protected storage like
 * the settings. A separate file from the settings because it is never backed up or transferred: what it holds is true
 * of this phone only (a locked test that passed here, the rows seen OK here), so a restore onto a new phone would give
 * a false locked-test pass and false "turned off" rows. A corrupt file is replaced with an empty one: the user rings
 * another test, and the rows are seen again. One instance per process (a Koin single); [close] releases the file.
 */
class ReliabilityDataStore(
    context: Context,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val store: DataStore<Preferences> =
        PreferenceDataStoreFactory.createWithPath(
            corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
            scope = scope,
        ) { reliabilityFile(context).absolutePath.toPath() }

    /** Releases the file (Koin closing, a test restarting the app). */
    fun close() = runBlocking { scope.coroutineContext.job.cancelAndJoin() }

    companion object {
        const val FILE_NAME = "reliability.preferences_pb"

        /** `datastore/reliability.preferences_pb` in the device-protected files directory, never credential-protected. */
        fun reliabilityFile(context: Context): File = File(context.createDeviceProtectedStorageContext().filesDir, "datastore/$FILE_NAME")
    }
}
