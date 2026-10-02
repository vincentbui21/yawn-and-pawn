package com.yawnandpawn.app.debug.fire

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri

/**
 * Debug builds only (Story 1.18): created by the system when the app process starts (debug manifest, not exported), it
 * registers [DebugFireReceiver] for the adb fire broadcast. It serves no data.
 */
class DebugFireProvider : ContentProvider() {
    override fun onCreate(): Boolean {
        context?.let { DebugFireReceiver.register(it) }
        return true
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null

    override fun insert(
        uri: Uri,
        values: ContentValues?,
    ): Uri? = null

    override fun delete(
        uri: Uri,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0
}
