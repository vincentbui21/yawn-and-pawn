package com.yawnandpawn.app.data.checks

import android.content.Context
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.sqlite.execSQL
import androidx.test.core.app.ApplicationProvider
import com.yawnandpawn.app.core.checks.qr.CodeFormat
import com.yawnandpawn.app.core.checks.qr.RegisteredCode
import com.yawnandpawn.app.data.db.APP_DATABASE_MIGRATIONS
import com.yawnandpawn.app.data.db.MIGRATION_7_8
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Story 3.10: the registered code's columns. The migration on a bare `check_config` table with an entry in it, the
 * column mapping, and the tripwire that keeps the code columns in the latest exported schema; the migration through
 * Room is in `AppDatabaseFactoryTest`.
 */
@RunWith(RobolectricTestRunner::class)
class CheckCodeMigrationTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    private fun <T> withConnection(block: (SQLiteConnection) -> T): T {
        val file = File(context.cacheDir, "check-code-migration.db").apply { delete() }
        val connection = AndroidSQLiteDriver().open(file.absolutePath)
        try {
            return block(connection)
        } finally {
            connection.close()
            file.delete()
        }
    }

    private fun SQLiteConnection.columns(table: String): List<String> =
        prepare("PRAGMA table_info(`$table`)").use { statement ->
            buildList { while (statement.step()) add(statement.getText(1)) }
        }

    @Test
    fun `the migration adds two nullable code columns and keeps every entry`() =
        withConnection { connection ->
            connection.execSQL(
                "CREATE TABLE `check_config` (`alarm_id` TEXT NOT NULL, `position` INTEGER NOT NULL, `type` TEXT NOT NULL, " +
                    "`difficulty` TEXT NOT NULL, `count` INTEGER NOT NULL, PRIMARY KEY(`alarm_id`, `position`))",
            )
            connection.execSQL("INSERT INTO `check_config` VALUES ('alarm-a', 0, 'Math', 'Medium', 3)")

            runTest { MIGRATION_7_8.migrate(connection) }

            assertEquals(
                listOf("alarm_id", "position", "type", "difficulty", "count", "code_format", "code_value", "code_registered_at"),
                connection.columns("check_config"),
            )
            connection.prepare("SELECT `type`, `code_format`, `code_value` FROM `check_config`").use { row ->
                row.step()
                assertEquals("Math", row.getText(0))
                assertEquals(true, row.isNull(1))
                assertEquals(true, row.isNull(2))
                assertFalse(row.step(), "one entry")
            }
            connection.execSQL(
                "INSERT INTO `check_config` VALUES ('alarm-a', 1, 'QrBarcode', 'Medium', 1, 'QR_CODE', '${"a".repeat(64)}', 3000)",
            )
        }

    /**
     * Tripwire (Story 3.10 review): the code columns are never silently skipped on rebase. While the latest exported
     * `app.db` schema has no `check_config` (this base), the migration is a placeholder and not registered. Once Story
     * 3.5's table is in the latest schema, it must have `code_format` and `code_value`, and this migration (renumbered to
     * the next free version) must be registered; otherwise QR entries would lose their code and ring as Math.
     */
    @Test
    fun `the code columns are in the latest schema and migrated as soon as check_config is`() {
        val schemas = File("schemas/com.yawnandpawn.app.data.db.AppDatabase")
        val latest = schemas.listFiles { file -> file.extension == "json" }.orEmpty().maxByOrNull { it.nameWithoutExtension.toInt() }
        val schema = assertNotNull(latest, "the exported schemas in ${schemas.absolutePath}").readText()
        val table = schema.substringAfter("\"tableName\": \"check_config\"", missingDelimiterValue = "")

        if (table.isEmpty()) {
            assertEquals(7 to 8, MIGRATION_7_8.startVersion to MIGRATION_7_8.endVersion)
            assertFalse(MIGRATION_7_8 in APP_DATABASE_MIGRATIONS, "registered only on rebase, with the next free number")
            return
        }
        val columns = table.substringBefore("\"tableName\"")
        listOf("code_format", "code_value").forEach { column ->
            assertTrue("\"columnName\": \"$column\"" in columns, "${latest.name}: check_config has no $column (Story 3.10 rebase notes)")
        }
        assertTrue(MIGRATION_7_8 in APP_DATABASE_MIGRATIONS, "register the Story 3.10 code-columns migration (rebase notes)")
    }

    @Test
    fun `a code maps to its columns and back, and a damaged row reads as no code`() {
        val code = RegisteredCode.of(CodeFormat.Ean13, "4006381333931")!!

        val (format, value) = CheckCodeColumns.columnsOf(code)
        assertEquals("EAN_13", format)
        assertEquals(code.fingerprint, value)
        assertEquals(code, CheckCodeColumns.registeredCodeOf(format, value))
        assertEquals(null to null, CheckCodeColumns.columnsOf(null))
        assertNull(CheckCodeColumns.registeredCodeOf(null, value))
        assertNull(CheckCodeColumns.registeredCodeOf(format, null))
        assertNull(CheckCodeColumns.registeredCodeOf("MAXICODE", value), "a format this build does not know")
        assertNull(CheckCodeColumns.registeredCodeOf(format, "4006381333931"), "a raw value is never read as a code")
    }
}
