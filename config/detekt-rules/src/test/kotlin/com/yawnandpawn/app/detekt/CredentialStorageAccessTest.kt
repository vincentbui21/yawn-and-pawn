package com.yawnandpawn.app.detekt

import dev.detekt.api.Config
import dev.detekt.test.lint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CredentialStorageAccessTest {
    private val rule = CredentialStorageAccess(Config.empty)

    private fun findings(
        code: String,
        packageName: String = "com.yawnandpawn.app.data.db",
    ) = rule.lint("package $packageName\n\nimport android.content.Context\nimport java.io.File\n\n$code\n")

    @Test
    fun `storage on the normal context is reported, call by call`() {
        listOf(
            "context.getDatabasePath(\"app.db\")",
            "context.getSharedPreferences(\"prefs\", Context.MODE_PRIVATE)",
            "context.dataStoreFile(\"settings.preferences_pb\")",
            "File(context.filesDir, \"x\")",
            "context.cacheDir",
            "context.dataDir",
            "context.getFilesDir()",
            "context?.filesDir",
        ).forEach { access ->
            val found = findings("fun open(context: Context) = $access")

            assertEquals(1, found.size, access)
            assertTrue(found.single().message.contains("createDeviceProtectedStorageContext"), access)
        }
    }

    @Test
    fun `an unqualified access inside a Context subclass and preferencesDataStore are reported`() {
        val found =
            findings(
                """
                val Context.settings by preferencesDataStore(name = "settings")

                class Screen : android.app.Activity() {
                    fun open() = getSharedPreferences("prefs", MODE_PRIVATE)

                    fun dir() = filesDir
                }
                """.trimIndent(),
            )

        assertEquals(3, found.size, found.joinToString { it.message })
    }

    @Test
    fun `the device-protected context is compliant, directly or through a device-named value`() {
        val found =
            findings(
                """
                fun open(context: Context): File {
                    val deviceContext = context.createDeviceProtectedStorageContext()
                    deviceContext.getDatabasePath("app.db")
                    context.createDeviceProtectedStorageContext().getSharedPreferences("wake_runtime", Context.MODE_PRIVATE)
                    return File(context.createDeviceProtectedStorageContext().filesDir, "datastore/settings.preferences_pb")
                }
                """.trimIndent(),
            )

        assertEquals(emptyList(), found.map { it.message })
    }

    @Test
    fun `credential-protected media in the android media package is exempt`() {
        val found = findings("fun media(context: Context) = File(context.filesDir, \"house_hunt\")", "com.yawnandpawn.app.android.media")

        assertEquals(emptyList(), found.map { it.message })
    }

    @Test
    fun `names that only look like storage are not reported`() {
        val found = findings("class Paths(val filesDirName: String)\n\nfun name(paths: Paths) = paths.filesDirName")

        assertEquals(emptyList(), found.map { it.message })
    }
}
