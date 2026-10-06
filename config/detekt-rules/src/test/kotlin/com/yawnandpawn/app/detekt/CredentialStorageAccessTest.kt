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
            "context.openFileOutput(\"x\", Context.MODE_PRIVATE)",
            "context.openFileInput(\"x\")",
            "context.getDir(\"x\", Context.MODE_PRIVATE)",
            "context.getNoBackupFilesDir()",
            "context.noBackupFilesDir",
            "context.codeCacheDir",
            "context.getCodeCacheDir()",
            "context.deleteDatabase(\"app.db\")",
            "context.databaseList()",
            "Room.databaseBuilder(context, AppDatabase::class.java, \"app.db\")",
            "Room.databaseBuilder<AppDatabase>(context = context, name = \"app.db\")",
            "(context).filesDir",
            "context!!.filesDir",
            "deviceContext.applicationContext.filesDir",
            "(ContextCompat.createDeviceProtectedStorageContext(context) ?: context).filesDir",
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
    fun `DataStore file helpers are reported even on the device context, they resolve applicationContext`() {
        listOf(
            "deviceContext.dataStoreFile(\"settings.preferences_pb\")",
            "context.createDeviceProtectedStorageContext().preferencesDataStoreFile(\"settings\")",
            "preferencesDataStoreFile(\"settings\")",
        ).forEach { access ->
            assertEquals(1, findings("fun open(context: Context, deviceContext: Context) = $access").size, access)
        }
    }

    @Test
    fun `the device context is recognised through parentheses, not-null assertions, this and ContextCompat`() {
        val found =
            findings(
                """
                class Store(private val deviceContext: Context) {
                    fun open(context: Context) {
                        (deviceContext).filesDir
                        deviceContext!!.cacheDir
                        this.deviceContext.getDatabasePath("app.db")
                        (this.deviceContext).openFileOutput("x", Context.MODE_PRIVATE)
                        ContextCompat.createDeviceProtectedStorageContext(context)!!.filesDir
                        ContextCompat.createDeviceProtectedStorageContext(context)?.noBackupFilesDir
                        (context.createDeviceProtectedStorageContext()).getDir("x", Context.MODE_PRIVATE)
                        Room.databaseBuilder(context.createDeviceProtectedStorageContext(), AppDatabase::class.java, "app.db")
                        Room.databaseBuilder<AppDatabase>(context = deviceContext, name = deviceContext.getDatabasePath("a").path)
                        Room.databaseBuilder<AppDatabase>(name = "/data/app.db")
                        Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
                    }
                }
                """.trimIndent(),
            )

        assertEquals(emptyList(), found.map { it.message })
    }

    @Test
    fun `named arguments, parameters, locals and own properties named like a storage directory are not reported`() {
        val found =
            findings(
                """
                class Paths(val filesDir: File, val cacheDir: File) {
                    fun cache() = File(cacheDir, "x")
                }

                fun build(deviceContext: Context) = Paths(filesDir = deviceContext.filesDir, cacheDir = deviceContext.cacheDir)

                fun child(filesDir: File) = File(filesDir, "x")

                fun local(): File {
                    val dataDir = File("/tmp")
                    listOf(File("a")).forEach { codeCacheDir: File -> codeCacheDir.delete() }
                    for (noBackupFilesDir in listOf(File("b"))) noBackupFilesDir.delete()
                    return dataDir
                }
                """.trimIndent(),
            )

        assertEquals(emptyList(), found.map { it.message })
    }

    @Test
    fun `credential-protected media in the android media package is exempt`() {
        val access = "fun media(context: Context) = File(context.filesDir, \"house_hunt\")"

        assertEquals(emptyList(), findings(access, "com.yawnandpawn.app.android.media").map { it.message })
        assertEquals(emptyList(), findings(access, "com.yawnandpawn.app.android.media.househunt").map { it.message })
    }

    @Test
    fun `a package that only starts like the media package is not exempt`() {
        val access = "fun media(context: Context) = File(context.filesDir, \"house_hunt\")"

        assertEquals(1, findings(access, "com.yawnandpawn.app.android.mediaplayer").size)
        assertEquals(1, findings(access, "com.yawnandpawn.app.android.media2").size)
    }

    @Test
    fun `names that only look like storage are not reported`() {
        val found = findings("class Paths(val filesDirName: String)\n\nfun name(paths: Paths) = paths.filesDirName")

        assertEquals(emptyList(), found.map { it.message })
    }
}
