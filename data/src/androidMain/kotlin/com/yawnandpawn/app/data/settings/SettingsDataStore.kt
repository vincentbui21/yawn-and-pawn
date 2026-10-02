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
 * The app's settings Preferences DataStore (Story 1.16: the missed-note dismissals; Epic 5 adds the settings), in
 * device-protected storage so it reads before the first unlock. One instance per process (a Koin single): DataStore
 * refuses two active instances on one file, so [close] cancels its scope and waits until the file is released.
 */
class SettingsDataStore(
    context: Context,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val store: DataStore<Preferences> =
        PreferenceDataStoreFactory.createWithPath(scope = scope) { settingsFile(context).absolutePath.toPath() }

    /** Releases the file (Koin closing, a test restarting the app). */
    fun close() = runBlocking { scope.coroutineContext.job.cancelAndJoin() }

    companion object {
        const val FILE_NAME = "settings.preferences_pb"

        /** `datastore/settings.preferences_pb` in the device-protected files directory, never credential-protected. */
        fun settingsFile(context: Context): File = File(context.createDeviceProtectedStorageContext().filesDir, "datastore/$FILE_NAME")
    }
}
