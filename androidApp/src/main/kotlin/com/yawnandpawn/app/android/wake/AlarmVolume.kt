package com.yawnandpawn.app.android.wake

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioManager
import android.os.Build
import com.yawnandpawn.app.core.alarm.Alarm
import com.yawnandpawn.app.core.log.LogEvent
import com.yawnandpawn.app.core.log.Logger
import kotlin.math.roundToInt

/**
 * The alarm-stream volume during a session (AD-5): [setForRing] sets `STREAM_ALARM` to the alarm's volume at the
 * start of a ring, and [restore] puts the user's own volume back when the session ends.
 *
 * The user's volume is saved in device-protected `SharedPreferences` (readable before the first unlock, AD-6) the first
 * time a session changes it and written synchronously, so it survives a crash: the next [restore] (at the end of the
 * restored session, or at app start with no session) still finds it. Saving again while a value is saved keeps the
 * first one, so a re-ring never saves the alarm's own volume as the user's.
 */
class AlarmVolume(
    context: Context,
    private val logger: Logger,
) {
    private val audio: AudioManager = context.getSystemService(AudioManager::class.java)
    private val prefs = context.createDeviceProtectedStorageContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** The user's alarm volume saved before the session changed it; null when nothing is saved. */
    val saved: Int?
        get() = if (prefs.contains(KEY_SAVED)) prefs.getInt(KEY_SAVED, 0) else null

    /**
     * Sets the alarm stream to [percent] of its maximum, never below `Alarm.MIN_VOLUME_PERCENT` (Epic 3 review: a session
     * stored with 0% before that minimum re-rings at 10%) and at least the stream's lowest audible step,
     * saving the user's volume first unless one is saved already.
     */
    @SuppressLint("ApplySharedPref") // A crash right after must not lose the user's volume.
    fun setForRing(percent: Int) {
        if (saved == null) prefs.edit().putInt(KEY_SAVED, audio.getStreamVolume(AudioManager.STREAM_ALARM)).commit()
        setStream(indexFor(percent), "set alarm volume")
    }

    /** Puts the saved user volume back and forgets it; nothing when nothing is saved. */
    @SuppressLint("ApplySharedPref")
    fun restore() {
        val user = saved ?: return
        setStream(user, "restore alarm volume")
        prefs.edit().remove(KEY_SAVED).commit()
    }

    private fun indexFor(percent: Int): Int {
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_ALARM)
        val min = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) audio.getStreamMinVolume(AudioManager.STREAM_ALARM) else 0
        // Never below the alarm minimum (10%), also for a session stored with 0% before that minimum existed.
        return (max * Alarm.ringableVolume(percent) / FULL.toDouble()).roundToInt().coerceIn(maxOf(min, 1), maxOf(max, 1))
    }

    private fun setStream(
        index: Int,
        operation: String,
    ) {
        try {
            audio.setStreamVolume(AudioManager.STREAM_ALARM, index, 0)
        } catch (e: SecurityException) {
            // For example a fixed-volume device or a Do Not Disturb restriction; the alarm still rings at the stream's volume.
            logger.log(LogEvent.OperationFailed(operation, "SecurityException: ${e.message}"))
        }
    }

    private companion object {
        const val PREFS = "wake_runtime"
        const val KEY_SAVED = "user_alarm_volume"
        const val FULL = 100
    }
}
