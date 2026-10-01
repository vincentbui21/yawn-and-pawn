package com.yawnandpawn.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.yawnandpawn.app.data.db.AppDatabase
import com.yawnandpawn.app.data.db.RuntimeDatabase
import com.yawnandpawn.app.stopApp
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.xmlpull.v1.XmlPullParser
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** NFR-14 and AD-6: Auto Backup includes only app.db in the device-protected domain and excludes runtime.db, on both API ranges. */
@RunWith(RobolectricTestRunner::class)
class BackupRulesTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @After
    fun tearDown() {
        // The Robolectric application starts Koin for every test.
        stopApp()
    }

    /** A rule element: the section it sits in (cloud-backup, device-transfer or the root), include/exclude, domain, path. */
    private data class Rule(
        val section: String,
        val kind: String,
        val domain: String?,
        val path: String?,
    )

    private fun rules(xmlRes: Int): List<Rule> {
        val parser = context.resources.getXml(xmlRes)
        val sections = ArrayDeque<String>()
        val rules = mutableListOf<Rule>()
        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> {
                    if (parser.name == "include" || parser.name == "exclude") {
                        rules +=
                            Rule(
                                section = sections.last(),
                                kind = parser.name,
                                domain = parser.getAttributeValue(null, "domain"),
                                path = parser.getAttributeValue(null, "path"),
                            )
                    } else {
                        sections.addLast(parser.name)
                    }
                }

                XmlPullParser.END_TAG -> {
                    if (parser.name != "include" && parser.name != "exclude") sections.removeLast()
                }
            }
        }
        return rules
    }

    private val appDb = AppDatabase.FILE_NAME
    private val runtimeDb = RuntimeDatabase.FILE_NAME

    @Test
    fun `data extraction rules (API 31+) back up and transfer only app db and exclude runtime db in every section`() {
        assertEquals(
            listOf(
                Rule("cloud-backup", "include", "device_database", appDb),
                Rule("cloud-backup", "exclude", "device_database", runtimeDb),
                Rule("device-transfer", "include", "device_database", appDb),
                Rule("device-transfer", "exclude", "device_database", runtimeDb),
            ),
            rules(R.xml.data_extraction_rules),
        )
    }

    @Test
    fun `full backup content (API 30 and lower) backs up only app db and excludes runtime db`() {
        assertEquals(
            listOf(
                Rule("full-backup-content", "include", "device_database", appDb),
                Rule("full-backup-content", "exclude", "device_database", runtimeDb),
            ),
            rules(R.xml.backup_rules),
        )
    }

    @Test
    fun `app db is the only include and runtime db is the only exclude, so nothing else is backed up`() {
        val all = rules(R.xml.data_extraction_rules) + rules(R.xml.backup_rules)

        assertEquals(setOf(appDb), all.filter { it.kind == "include" }.map { it.path }.toSet())
        assertEquals(setOf(runtimeDb), all.filter { it.kind == "exclude" }.map { it.path }.toSet())
        assertTrue(all.none { it.kind == "include" && it.path == runtimeDb }, "runtime.db is never included")
    }

    @Test
    fun `the manifest references both rule files`() {
        // Both ApplicationInfo fields are hidden; check the manifest source instead (tests run in :androidApp).
        val manifest = File("src/main/AndroidManifest.xml").readText()
        assertTrue(manifest.contains("android:fullBackupContent=\"@xml/backup_rules\""))
        assertTrue(manifest.contains("android:dataExtractionRules=\"@xml/data_extraction_rules\""))
        assertTrue(manifest.contains("android:allowBackup=\"true\""))
    }
}
