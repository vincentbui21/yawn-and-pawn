package com.yawnandpawn.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.yawnandpawn.app.android.backup.SkippedRestoreNotice
import com.yawnandpawn.app.data.db.AppDatabase
import com.yawnandpawn.app.data.db.RuntimeDatabase
import com.yawnandpawn.app.data.settings.SettingsDataStore
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * NFR-14, AD-6 and Story 2.12: Auto Backup includes only `app.db` and the settings DataStore, both in device-protected
 * storage. It explicitly excludes the session (`runtime.db` and its journals), the preferences of the wake runtime and
 * of the restore notice, and all credential-protected storage (where media will live), on both API ranges.
 */
@RunWith(RobolectricTestRunner::class)
class BackupRulesTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @After
    fun tearDown() {
        // The Robolectric application starts Koin for every test.
        stopApp()
    }

    private val appDb = AppDatabase.FILE_NAME
    private val runtimeDb = RuntimeDatabase.FILE_NAME
    private val settings = "datastore/${SettingsDataStore.FILE_NAME}"

    /** The entries every section must hold, in this order: kind, domain, path. */
    private val sectionEntries =
        listOf(
            Triple("include", "device_database", appDb),
            Triple("include", "device_file", settings),
            Triple("exclude", "device_database", "$appDb-journal"),
            Triple("exclude", "device_database", "$appDb.lck"),
            Triple("exclude", "device_database", runtimeDb),
            Triple("exclude", "device_database", "$runtimeDb-journal"),
            Triple("exclude", "device_database", "$runtimeDb-wal"),
            Triple("exclude", "device_database", "$runtimeDb-shm"),
            Triple("exclude", "device_database", "$runtimeDb.lck"),
            Triple("exclude", "device_sharedpref", "wake_runtime.xml"),
            Triple("exclude", "device_sharedpref", "${SkippedRestoreNotice.PREFS}.xml"),
            // AndroidNotificationPermission's "asked once" flag (device-protected since Story 2.3), kept per device.
            Triple("exclude", "device_sharedpref", "reliability.xml"),
            // The fire's last-known global settings (Story 4.4 review fix 11), a per-device fallback.
            Triple("exclude", "device_sharedpref", "settings_fallback.xml"),
            Triple("exclude", "root", "."),
            Triple("exclude", "file", "."),
            Triple("exclude", "database", "."),
            Triple("exclude", "sharedpref", "."),
        )

    private fun expected(section: String): List<BackupRule> =
        sectionEntries.map { (kind, domain, path) -> BackupRule(section, kind, domain, path) }

    @Test
    fun `data extraction rules (API 31+) list every include and exclude in both the cloud backup and the device transfer`() {
        assertEquals(
            expected("cloud-backup") + expected("device-transfer"),
            backupRules(context, R.xml.data_extraction_rules),
        )
    }

    @Test
    fun `full backup content (API 30 and lower) lists the same includes and excludes`() {
        assertEquals(expected("full-backup-content"), backupRules(context, R.xml.backup_rules))
    }

    @Test
    fun `only device-protected storage is included, and the session is never included`() {
        val all = backupRules(context, R.xml.data_extraction_rules) + backupRules(context, R.xml.backup_rules)
        val includes = all.filter { it.kind == "include" }

        assertTrue(includes.all { it.domain.startsWith("device_") }, "$includes")
        assertEquals(setOf(appDb, settings), includes.map { it.path }.toSet())
        assertFalse(includes.any { it.path.startsWith(runtimeDb) }, "runtime.db is never included")
    }

    @Test
    fun `the manifest uses the backup agent with full backup only, references both rule files and never restores any version`() {
        // The ApplicationInfo fields are hidden or not set by Robolectric; check the manifest source instead (tests run in :androidApp).
        val manifest = File("src/main/AndroidManifest.xml").readText()
        assertTrue(manifest.contains("android:fullBackupContent=\"@xml/backup_rules\""))
        assertTrue(manifest.contains("android:dataExtractionRules=\"@xml/data_extraction_rules\""))
        assertTrue(manifest.contains("android:allowBackup=\"true\""))
        assertTrue(manifest.contains("android:fullBackupOnly=\"true\""))
        assertTrue(manifest.contains("android:backupAgent=\".android.backup.PpsBackupAgent\""))
        val restrictedMode = Regex("android:name=\"android.app.backup.PROPERTY_USE_RESTRICTED_BACKUP_MODE\"\\s+android:value=\"true\"")
        assertTrue(restrictedMode.containsMatchIn(manifest), "the restore runs in restricted mode")
        assertFalse(manifest.contains("android:restoreAnyVersion"), "a newer backup is never forced onto an older install")
    }
}
