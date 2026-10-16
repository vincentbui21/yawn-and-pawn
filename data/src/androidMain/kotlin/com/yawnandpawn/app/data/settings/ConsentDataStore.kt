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
 * The usage stats choice's own Preferences DataStore (Story 5.6 review fix 5), in device-protected storage like the
 * settings. A file of its own, not the settings DataStore, because it is never backed up or moved to a new phone:
 * consent is per install (DEFAULT TAKEN, owner can change), so a restored or transferred phone starts with usage stats
 * off and onboarding asks again (both backup rule files exclude this file). A corrupt file is replaced with an empty one,
 * which reads as no consent. One instance per process (a Koin single); [close] releases the file.
 */
class ConsentDataStore(
    context: Context,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val store: DataStore<Preferences> =
        PreferenceDataStoreFactory.createWithPath(
            corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
            scope = scope,
        ) { consentFile(context).absolutePath.toPath() }

    /** Releases the file (Koin closing, a test restarting the app). */
    fun close() = runBlocking { scope.coroutineContext.job.cancelAndJoin() }

    companion object {
        const val FILE_NAME = "usage_stats_consent.preferences_pb"

        /** `datastore/usage_stats_consent.preferences_pb` in the device-protected files directory, never credential-protected. */
        fun consentFile(context: Context): File = File(context.createDeviceProtectedStorageContext().filesDir, "datastore/$FILE_NAME")
    }
}
