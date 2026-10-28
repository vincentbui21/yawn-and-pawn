package com.yawnandpawn.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.yawnandpawn.app.android.backup.SkippedRestoreNotice
import com.yawnandpawn.app.data.db.AppDatabase
import com.yawnandpawn.app.data.db.RuntimeDatabase
import com.yawnandpawn.app.data.recording.RecordingFiles
import com.yawnandpawn.app.data.settings.ConsentDataStore
import com.yawnandpawn.app.data.settings.InstallIdDataStore
import com.yawnandpawn.app.data.settings.PriceCacheDataStore
import com.yawnandpawn.app.data.settings.ReliabilityDataStore
import com.yawnandpawn.app.data.settings.SettingsDataStore
import com.yawnandpawn.app.data.sound.SoundFiles
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
 * of the restore notice, the recordings (Story 7.1), the user's sounds (Story 7.4) and all credential-protected storage
 * (where media lives), on both API ranges.
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
    private val installId = "datastore/${InstallIdDataStore.FILE_NAME}"
    private val reliability = "datastore/${ReliabilityDataStore.FILE_NAME}"

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
            // The install id (Story 4.8): never restored onto another phone.
            Triple("exclude", "device_file", installId),
            // The cached Play prices (Story 4.3): in the phone's Play currency, never restored onto another phone.
            Triple("exclude", "device_file", "datastore/${PriceCacheDataStore.FILE_NAME}"),
            // The reliability checklist's records (Story 5.3 review fix 14): true of this phone only, never restored.
            Triple("exclude", "device_file", reliability),
            // The usage stats choice (Story 5.6 review fix 5): per install, so a restored phone starts with stats off.
            Triple("exclude", "device_file", "datastore/${ConsentDataStore.FILE_NAME}"),
            // The motivation messages' audio (Story 7.1): credential-protected media, never backed up; their rows are in app.db.
            Triple("exclude", "file", RecordingFiles.FOLDER),
            // The user's own alarm sounds (Story 7.4): credential-protected copies, never backed up; their rows are in app.db.
            Triple("exclude", "file", SoundFiles.FOLDER),
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
    fun `purchase history is backed up in app db by token hash, and the grant ledger with its tokens stays in runtime db`() {
        val schemas = File("../data/schemas")
        val app = File(schemas, "com.yawnandpawn.app.data.db.AppDatabase/${AppDatabase.SCHEMA_VERSION}.json").readText()
        val runtime = File(schemas, "com.yawnandpawn.app.data.db.RuntimeDatabase/${RuntimeDatabase.SCHEMA_VERSION}.json").readText()
        val all = backupRules(context, R.xml.data_extraction_rules) + backupRules(context, R.xml.backup_rules)
        val includes = all.filter { it.kind == "include" }

        assertTrue(app.contains("\"tableName\": \"purchase_record\""), "the records live in app.db")
        assertTrue(includes.any { it.path == appDb }, "app.db is backed up")
        assertFalse(app.substringAfter("\"tableName\": \"purchase_record\"").contains("`token` TEXT"), "only the token hash")
        assertTrue(runtime.contains("\"tableName\": \"grant_ledger\""), "the ledger lives in runtime.db")
        assertFalse(includes.any { it.path.startsWith(runtimeDb) }, "runtime.db is never backed up")
    }

    @Test
    fun `recordings and sounds are excluded in every section while the recording rows stay in the backed-up app db (Story 7-1)`() {
        val schema = File("../data/schemas/com.yawnandpawn.app.data.db.AppDatabase/${AppDatabase.SCHEMA_VERSION}.json").readText()
        val all = backupRules(context, R.xml.data_extraction_rules) + backupRules(context, R.xml.backup_rules)

        listOf(RecordingFiles.FOLDER, SoundFiles.FOLDER).forEach { media ->
            assertEquals(
                setOf("cloud-backup", "device-transfer", "full-backup-content"),
                all.filter { it.kind == "exclude" && it.domain == "file" && it.path == media }.map { it.section }.toSet(),
                media,
            )
            assertFalse(all.any { it.kind == "include" && it.path.contains(media) }, "$media is never included")
        }
        assertTrue(schema.contains("\"tableName\": \"recording\""), "the rows live in app.db")
        assertTrue(all.any { it.kind == "include" && it.path == appDb }, "app.db is backed up")
        // Story 7.4: the imported sounds' rows (name, file name, length; no audio) are backed up with app.db too.
        val sounds = schema.substringAfter("\"tableName\": \"custom_sound\"", missingDelimiterValue = "")
        assertTrue(sounds.isNotEmpty(), "the custom_sound rows live in app.db")
        assertTrue(sounds.substringBefore("\"tableName\"").contains("`display_name` TEXT NOT NULL"), "the name is backed up")
        assertFalse(sounds.substringBefore("\"tableName\"").contains("BLOB"), "no audio in app.db")
    }

    @Test
    fun `the install id is excluded from cloud backup, device transfer and full backup, and never included`() {
        val all = backupRules(context, R.xml.data_extraction_rules) + backupRules(context, R.xml.backup_rules)

        assertEquals(
            setOf("cloud-backup", "device-transfer", "full-backup-content"),
            all.filter { it.kind == "exclude" && it.domain == "device_file" && it.path == installId }.map { it.section }.toSet(),
        )
        assertFalse(all.any { it.kind == "include" && it.path.contains("install_id") })
    }

    @Test
    fun `the reliability records are excluded from cloud backup, device transfer and full backup, and never included`() {
        val all = backupRules(context, R.xml.data_extraction_rules) + backupRules(context, R.xml.backup_rules)

        assertEquals(
            setOf("cloud-backup", "device-transfer", "full-backup-content"),
            all.filter { it.kind == "exclude" && it.domain == "device_file" && it.path == reliability }.map { it.section }.toSet(),
        )
        assertFalse(all.any { it.kind == "include" && it.path.contains("reliability") })
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
