package com.yawnandpawn.app.android.backup

import android.content.Context
import com.yawnandpawn.app.BackupRule
import com.yawnandpawn.app.R
import com.yawnandpawn.app.StopAppRule
import com.yawnandpawn.app.android.reliability.AndroidNotificationPermission
import com.yawnandpawn.app.android.wake.WakeApp
import com.yawnandpawn.app.backupRules
import com.yawnandpawn.app.core.alarm.Alarm
import com.yawnandpawn.app.core.alarm.AlarmDraft
import com.yawnandpawn.app.core.alarm.AlarmFired
import com.yawnandpawn.app.core.alarm.SaveAlarm
import com.yawnandpawn.app.core.config.CommitmentAction
import com.yawnandpawn.app.core.config.CommitmentEvent
import com.yawnandpawn.app.core.config.CommitmentEventRepository
import com.yawnandpawn.app.core.config.Occurrence
import com.yawnandpawn.app.core.config.PendingChange
import com.yawnandpawn.app.core.config.PendingChangeRepository
import com.yawnandpawn.app.core.config.SetBaseFee
import com.yawnandpawn.app.core.config.SettingValue
import com.yawnandpawn.app.core.error.Outcome
import com.yawnandpawn.app.core.history.MissedNoteDismissals
import com.yawnandpawn.app.core.history.SessionHistoryRepository
import com.yawnandpawn.app.core.session.ScheduleTestAlarm
import com.yawnandpawn.app.core.session.SessionEvent
import com.yawnandpawn.app.core.session.SessionState
import com.yawnandpawn.app.core.stats.CheckKey
import com.yawnandpawn.app.core.stats.ReRegisterDismissals
import com.yawnandpawn.app.core.stats.ReRegisterSuggestions
import com.yawnandpawn.app.data.db.appDatabaseFile
import com.yawnandpawn.app.data.db.runtimeDatabaseFile
import com.yawnandpawn.app.data.settings.SettingsDataStore
import com.yawnandpawn.app.stopApp
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalTime
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * Story 2.12: every file the app creates is either backed up or explicitly kept out, in every rule section. A full
 * morning runs on the real Room databases, DataStore and wake runtime: an alarm saved, its session rung, the check
 * done, history recorded, a missed note dismissed, a test alarm armed and the notification permission asked. Every
 * file left under the app's storage must then be named by an include or an exclude of the cloud backup, the device
 * transfer and the API 30 rules. A later
 * story that adds a file must add it to the rules in the same change; this test names the file it missed. It only
 * scans the files its own flow creates, so a later story that adds a new storage file must also extend the flow here to
 * create that file (and add its rule).
 */
@RunWith(RobolectricTestRunner::class)
class BackupRulesCoverageTest {
    @get:Rule(order = 0)
    val stopAppRule = StopAppRule()

    @Test
    fun `after a full morning every app file is included or explicitly excluded in every rule section`() {
        val app = WakeApp()
        val koin = app.koin
        // The alarms are editable once the stored session is restored (the session lock, Story 2.6).
        runBlocking { app.engine.restore() }
        val saved = assertIs<Outcome.Success<*>>(runBlocking { koin.get<SaveAlarm>()(AlarmDraft(time = LocalTime(7, 0))) })
        val alarmId = (saved.value as Alarm).id
        app.ring(AlarmFired(alarmId, Instant.parse("2027-03-08T06:00:00Z")))
        app.awaitRinging()
        val sessionId = (app.engine.state.value as SessionState.Ringing).session.sessionId
        app.dispatch(SessionEvent.ImUpTapped)
        app.solveCheck()
        app.awaitUntil("the session is recorded and Idle") { app.engine.state.value == SessionState.Idle }
        assertNotNull(assertIs<Outcome.Success<*>>(runBlocking { koin.get<SessionHistoryRepository>().find(sessionId) }).value)
        assertIs<Outcome.Success<*>>(runBlocking { koin.get<MissedNoteDismissals>().dismiss("missed-session") })
        // The re-register banner dismissal (Story 3.13) lives in the same device-protected DataStore.
        val reRegisterKey = CheckKey(alarmId, "QrBarcode")
        val dismissedAt = Instant.parse("2027-03-08T06:10:00Z")
        assertIs<Outcome.Success<*>>(runBlocking { koin.get<ReRegisterDismissals>().dismiss(reRegisterKey, dismissedAt) })
        // The production banner source resolves from the real graph: the dismissal reads back, and no check is registered
        // before Story 3.10.
        val reRegisterInputs = runBlocking { koin.get<ReRegisterSuggestions>().inputs().first() }
        assertEquals(emptyList(), reRegisterInputs.checkConfigs)
        assertEquals(mapOf(reRegisterKey to dismissedAt), reRegisterInputs.dismissedAt)
        assertIs<Outcome.Success<*>>(runBlocking { koin.get<ScheduleTestAlarm>()(AlarmDraft(time = LocalTime(8, 0))) })
        // The commitment lock (Story 4.4): the global settings and a global pending change live in the settings DataStore,
        // an alarm's pending change and a commitment event in app.db; no new file, all backed up.
        assertIs<Outcome.Success<*>>(runBlocking { koin.get<SetBaseFee>()(3) })
        val waitsFor = Occurrence(alarmId, Instant.parse("2027-03-09T06:00:00Z"))
        val pending = koin.get<PendingChangeRepository>()
        assertIs<Outcome.Success<*>>(runBlocking { pending.put(PendingChange(null, SettingValue.MaxSnoozes(5), waitsFor)) })
        assertIs<Outcome.Success<*>>(runBlocking { pending.put(PendingChange(alarmId, SettingValue.GraceSeconds(30), waitsFor)) })
        val event = CommitmentEvent("event-1", alarmId, waitsFor.scheduledAt, CommitmentAction.Disabled, dismissedAt)
        assertIs<Outcome.Success<*>>(runBlocking { koin.get<CommitmentEventRepository>().insert(event) })
        // The notification permission is asked once (Story 2.3 keeps that flag in device-protected preferences).
        val reliability = File(app.app.createDeviceProtectedStorageContext().dataDir, "shared_prefs/reliability.xml")
        koin.get<AndroidNotificationPermission>().apply { attach {} }.request()
        app.awaitUntil("the asked-once flag is written") { reliability.isFile }
        // Close the databases and the DataStore, as when the process ends, so their files are final.
        stopApp()

        val storage = AppStorage(app.app)
        val files = storage.files()
        val names = files.map { it.second }
        listOf(appDatabaseFile(app.app), runtimeDatabaseFile(app.app), SettingsDataStore.settingsFile(app.app)).forEach { expected ->
            assertTrue(files.any { it.first == expected.canonicalFile }, "the morning created $expected; found $names")
        }

        assertEquals(emptyList(), storage.uncovered(), "files no backup rule names (found $names)")
    }

    @Test
    fun `device-protected and credential-protected storage are separate directories, so each file has one domain`() {
        val app = WakeApp()
        stopApp()
        val deviceRoot =
            app.app
                .createDeviceProtectedStorageContext()
                .dataDir.canonicalFile
        val credentialRoot = app.app.dataDir.canonicalFile

        assertNotEquals(deviceRoot, credentialRoot)
        assertFalse(deviceRoot.startsWith(credentialRoot) || credentialRoot.startsWith(deviceRoot), "$deviceRoot vs $credentialRoot")
    }

    @Test
    fun `a file no rule names is reported with its domain, path and section`() {
        val app = WakeApp()
        stopApp()
        val storage = AppStorage(app.app)
        File(app.app.createDeviceProtectedStorageContext().filesDir, "photos/new.jpg").apply {
            parentFile?.mkdirs()
            writeText("not covered")
        }

        assertEquals(
            listOf("cloud-backup", "device-transfer", "full-backup-content").map { "$it: device_file/photos/new.jpg" },
            storage.uncovered(),
        )
    }

    /**
     * The app's storage as Auto Backup sees it: each file's domain and its path inside the domain, and the files the
     * rules leave out by themselves (cache, code cache and no-backup directories, which Auto Backup never copies).
     */
    private class AppStorage(
        private val context: Context,
    ) {
        private val rules = backupRules(context, R.xml.data_extraction_rules) + backupRules(context, R.xml.backup_rules)
        private val sections = rules.map { it.section }.distinct()

        private val deviceProtected = context.createDeviceProtectedStorageContext()

        /** Domain directories, most specific first within each storage. */
        private val domains: List<Pair<String, File>> =
            listOf(
                "device_file" to deviceProtected.filesDir,
                "device_database" to deviceProtected.getDatabasePath("x").parentFile!!,
                "device_sharedpref" to File(deviceProtected.dataDir, "shared_prefs"),
                "device_root" to deviceProtected.dataDir,
                "file" to context.filesDir,
                "database" to context.getDatabasePath("x").parentFile!!,
                "sharedpref" to File(context.dataDir, "shared_prefs"),
                "root" to context.dataDir,
            ).map { (domain, dir) -> domain to dir.canonicalFile }

        private val neverBackedUp: List<File> =
            listOf(deviceProtected, context)
                .flatMap { listOf(it.cacheDir, it.codeCacheDir, it.noBackupFilesDir) }
                .map { it.canonicalFile }

        /** Every file under both storage roots, with its domain-relative name ("domain/path"). */
        fun files(): List<Pair<File, String>> =
            listOf(deviceProtected.dataDir, context.dataDir)
                .flatMap { root ->
                    root
                        .walkTopDown()
                        .filter { it.isFile }
                        .map { it.canonicalFile }
                        .toList()
                }.distinct()
                .filter { file -> neverBackedUp.none { file.startsWith(it) } }
                .map { file -> file to locate(file).let { (domain, path) -> "$domain/$path" } }

        /** "section: domain/path" for every file a section neither includes nor excludes. */
        fun uncovered(): List<String> =
            sections.flatMap { section ->
                files()
                    .map { it.second }
                    .filter { name -> rules.none { it.section == section && it.matches(name) } }
                    .map { "$section: $it" }
            }

        private fun locate(file: File): Pair<String, String> {
            val (domain, dir) = domains.filter { file.startsWith(it.second) }.maxBy { it.second.path.length }
            return domain to file.relativeTo(dir).invariantSeparatorsPath
        }

        private fun BackupRule.matches(name: String): Boolean {
            val (fileDomain, path) = name.split("/", limit = 2).let { it[0] to it[1] }
            return fileDomain == domain && (this.path == "." || path == this.path || path.startsWith(this.path + "/"))
        }
    }
}
