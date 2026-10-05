package com.yawnandpawn.app.android.wake

import android.content.Context
import android.content.SharedPreferences
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.RealObject
import org.robolectric.shadows.ShadowContextImpl
import org.robolectric.util.reflector.Direct
import org.robolectric.util.reflector.ForType
import org.robolectric.util.reflector.Reflector.reflector
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/**
 * Credential-protected storage before the first unlock (Story 2.3), for every context: the shadow of `ContextImpl`, the
 * base of the Application, of each service and of the context a receiver gets. While [locked], each storage call on a
 * context that is not `createDeviceProtectedStorageContext()` is recorded in [touched] and throws, as on a phone; on the
 * device-protected context it runs as usual. That covers `applicationContext.*` too, as it is the Application's base.
 * `getDataDir` closes every path built on it; the other calls are listed because `ContextImpl` caches their result.
 */
@Implements(className = "android.app.ContextImpl", isInAndroidSdk = false)
class LockedStorageContextImpl : ShadowContextImpl() {
    @field:RealObject
    private lateinit var real: Context

    private val direct: ContextImplReflector get() = reflector(ContextImplReflector::class.java, real)

    private fun <T> open(
        what: String,
        call: ContextImplReflector.() -> T,
    ): T {
        guard(what)
        return direct.call()
    }

    private fun guard(what: String) {
        if (locked && !real.isDeviceProtectedStorage) {
            touched += what
            throw IllegalStateException("credential-protected storage is locked: $what")
        }
    }

    @Implementation
    fun getDataDir(): File = open("dataDir") { getDataDir() }

    @Implementation
    fun getFilesDir(): File = open("filesDir") { getFilesDir() }

    @Implementation
    fun getCacheDir(): File = open("cacheDir") { getCacheDir() }

    @Implementation
    fun getCodeCacheDir(): File = open("codeCacheDir") { getCodeCacheDir() }

    @Implementation
    fun getNoBackupFilesDir(): File = open("noBackupFilesDir") { getNoBackupFilesDir() }

    // Robolectric shadows these two already; they keep its behaviour.
    @Implementation
    override fun getDatabasePath(name: String): File {
        guard("getDatabasePath($name)")
        return super.getDatabasePath(name)
    }

    @Implementation
    override fun getSharedPreferences(
        name: String,
        mode: Int,
    ): SharedPreferences {
        guard("getSharedPreferences($name)")
        return super.getSharedPreferences(name, mode)
    }

    @Implementation
    fun getDir(
        name: String,
        mode: Int,
    ): File = open("getDir($name)") { getDir(name, mode) }

    @Implementation
    fun openFileInput(name: String): FileInputStream = open("openFileInput($name)") { openFileInput(name) }

    @Implementation
    fun openFileOutput(
        name: String,
        mode: Int,
    ): FileOutputStream = open("openFileOutput($name)") { openFileOutput(name, mode) }

    @Implementation
    fun deleteDatabase(name: String): Boolean = open("deleteDatabase($name)") { deleteDatabase(name) }

    @Implementation
    fun databaseList(): Array<String> = open("databaseList") { databaseList() }

    /** The real `ContextImpl` methods, past this shadow. */
    @ForType(className = "android.app.ContextImpl")
    interface ContextImplReflector {
        @Direct
        fun getDataDir(): File

        @Direct
        fun getFilesDir(): File

        @Direct
        fun getCacheDir(): File

        @Direct
        fun getCodeCacheDir(): File

        @Direct
        fun getNoBackupFilesDir(): File

        @Direct
        fun getDir(
            name: String,
            mode: Int,
        ): File

        @Direct
        fun openFileInput(name: String): FileInputStream

        @Direct
        fun openFileOutput(
            name: String,
            mode: Int,
        ): FileOutputStream

        @Direct
        fun deleteDatabase(name: String): Boolean

        @Direct
        fun databaseList(): Array<String>
    }

    companion object {
        /** The user has not unlocked since boot: credential-protected storage is closed. */
        @Volatile
        var locked = false

        /** Every closed access while [locked], in order. */
        val touched: MutableList<String> = java.util.Collections.synchronizedList(mutableListOf())

        fun reset() {
            locked = false
            touched.clear()
        }
    }
}
