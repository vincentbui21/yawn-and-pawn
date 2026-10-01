package com.yawnandpawn.app.android.sound

import android.content.Context
import android.media.RingtoneManager
import android.net.Uri
import com.yawnandpawn.app.core.log.LogEvent
import com.yawnandpawn.app.core.log.Logger
import com.yawnandpawn.app.core.sound.SoundCatalog
import com.yawnandpawn.app.core.sound.SoundLibrary
import com.yawnandpawn.app.core.sound.SoundRef
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Reads the phone's alarm ringtones and opens sound URIs: the platform half of [AndroidSoundLibrary], a seam for tests. */
interface RingtoneSource {
    /** The alarm ringtones (`RingtoneManager.TYPE_ALARM`), in the system's order. May throw. */
    fun alarmRingtones(): List<SoundRef.System>

    /** Whether the sound behind [uri] can be opened for reading now. May throw. */
    fun opens(uri: String): Boolean
}

/** [RingtoneSource] over `RingtoneManager` and the content resolver. */
class PlatformRingtoneSource(
    private val context: Context,
) : RingtoneSource {
    override fun alarmRingtones(): List<SoundRef.System> {
        val manager = RingtoneManager(context).apply { setType(RingtoneManager.TYPE_ALARM) }
        val cursor = manager.cursor ?: return emptyList()
        return cursor.use {
            buildList {
                while (it.moveToNext()) {
                    val title = it.getString(RingtoneManager.TITLE_COLUMN_INDEX)?.trim().orEmpty()
                    val uri = manager.getRingtoneUri(it.position) ?: continue
                    add(SoundRef.System(uri = uri.toString(), title = title))
                }
            }
        }
    }

    override fun opens(uri: String): Boolean = context.contentResolver.openAssetFileDescriptor(Uri.parse(uri), "r")?.use { true } ?: false
}

/**
 * The Android [SoundLibrary] (Story 1.17): the phone's alarm ringtones through `RingtoneManager`, read on [io]. A
 * ringtone list that cannot be read is empty and logged; a ringtone that cannot be opened is not available. Nothing
 * logged names a sound or its URI.
 */
class AndroidSoundLibrary(
    private val ringtones: RingtoneSource,
    private val logger: Logger,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : SoundLibrary {
    // RingtoneManager and the content resolver report a missing provider, a revoked grant or a broken file with several
    // runtime exception types; any of them means "no ringtones" or "not available".
    @Suppress("TooGenericExceptionCaught")
    override suspend fun systemSounds(): List<SoundRef.System> =
        withContext(io) {
            try {
                // A ringtone without a name would be a blank row: it is left out.
                ringtones.alarmRingtones().filter { it.title.isNotBlank() }
            } catch (e: Exception) {
                logger.log(LogEvent.OperationFailed("list alarm ringtones", e::class.simpleName.orEmpty()))
                emptyList()
            }
        }

    @Suppress("TooGenericExceptionCaught")
    override suspend fun isAvailable(ref: SoundRef): Boolean =
        when (ref) {
            is SoundRef.BuiltIn -> {
                SoundCatalog.find(ref) != null
            }

            is SoundRef.System -> {
                withContext(io) {
                    try {
                        ringtones.opens(ref.uri)
                    } catch (e: Exception) {
                        logger.log(LogEvent.OperationFailed("open alarm ringtone", e::class.simpleName.orEmpty()))
                        false
                    }
                }
            }
        }
}
