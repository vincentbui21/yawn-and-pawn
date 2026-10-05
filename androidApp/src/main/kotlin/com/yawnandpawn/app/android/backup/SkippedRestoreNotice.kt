package com.yawnandpawn.app.android.backup

import android.annotation.SuppressLint
import android.content.Context

/**
 * Remembers that a restore skipped `app.db` because it came from a newer app version (`docs/decisions/db-downgrade.md`),
 * so the app can tell the user the next time it opens. Showing it waits for owner-approved copy (deferred-work.md,
 * Story 2.12). Kept in device-protected `SharedPreferences` (`backup_restore.xml`), which the backup rules exclude, so
 * the flag never travels to another phone.
 */
class SkippedRestoreNotice(
    context: Context,
) {
    private val prefs = context.createDeviceProtectedStorageContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** The schema version of the skipped `app.db`; null when no restore was skipped. */
    val skippedSchema: Int?
        get() = if (prefs.contains(KEY_SKIPPED_SCHEMA)) prefs.getInt(KEY_SKIPPED_SCHEMA, 0) else null

    /** Records that an `app.db` of schema [version] was not restored. False when the flag could not be written. */
    @SuppressLint("ApplySharedPref") // The restore process is killed soon after; the flag must be on disk first.
    fun record(version: Int): Boolean = prefs.edit().putInt(KEY_SKIPPED_SCHEMA, version).commit()

    /** Forgets an earlier skip, once a later restore replaced `app.db`. False when the flag could not be removed. */
    @SuppressLint("ApplySharedPref") // As in record: on disk before the restore process ends.
    fun clear(): Boolean = prefs.edit().remove(KEY_SKIPPED_SCHEMA).commit()

    companion object {
        /** The preferences file name; `backup_restore.xml` is excluded in both backup rule files. */
        const val PREFS = "backup_restore"
        private const val KEY_SKIPPED_SCHEMA = "skipped_app_db_schema"
    }
}
