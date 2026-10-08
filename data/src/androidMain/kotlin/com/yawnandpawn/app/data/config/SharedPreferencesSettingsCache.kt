package com.yawnandpawn.app.data.config

import android.content.Context
import com.yawnandpawn.app.core.config.PendingChangeJson
import com.yawnandpawn.app.core.config.SettingsSnapshot
import com.yawnandpawn.app.core.config.SettingsSnapshotCache

/**
 * [SettingsSnapshotCache] in device-protected shared preferences ([PREFS], so a ring before the first unlock reads it),
 * apart from the settings DataStore whose failure it stands in for (review fix 11). A per-device fallback: excluded from
 * backup. Reads come from the preferences' in-memory copy; a value that does not decode, or any failure, reads as none.
 */
class SharedPreferencesSettingsCache(
    context: Context,
) : SettingsSnapshotCache {
    private val preferences by lazy { context.createDeviceProtectedStorageContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE) }

    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    override fun load(): SettingsSnapshot? =
        try {
            preferences.getString(KEY, null)?.let(PendingChangeJson::decodeSnapshot)
        } catch (e: RuntimeException) {
            null
        }

    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    override fun save(snapshot: SettingsSnapshot) {
        try {
            preferences.edit().putString(KEY, PendingChangeJson.encodeSnapshot(snapshot)).apply()
        } catch (e: RuntimeException) {
            // A fallback that cannot be kept: the fire then uses an older one, or the defaults.
        }
    }

    companion object {
        /** `shared_prefs/settings_fallback.xml` in device-protected storage; named in the backup rules (excluded). */
        const val PREFS = "settings_fallback"
        private const val KEY = "snapshot"
    }
}
