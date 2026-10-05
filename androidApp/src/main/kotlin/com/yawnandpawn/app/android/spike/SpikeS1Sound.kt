// SPIKE S1 (branch spike/s1-billing-lockscreen only, never merged to main). Throwaway prototype code.
package com.yawnandpawn.app.android.spike

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import com.yawnandpawn.app.R

/**
 * The spike's alarm sound: the bundled `alarm_default.ogg`, looping, on `USAGE_ALARM`, with `STREAM_ALARM` set to its
 * maximum (the user's volume is put back on [stop]). It lives in the process, not in a foreground service (the real
 * app plays from `WakeService`), so it keeps playing while the spike screen is paused under the Play sheet.
 *
 * While it plays, a monitor logs `playing` and the alarm-stream volume every 2 s while a purchase is in flight and
 * every 10 s otherwise: the evidence for question 3 (does it keep ringing at full volume under the Play sheet).
 */
object SpikeS1Sound {
    private const val MONITOR_FAST_MS = 2_000L
    private const val MONITOR_SLOW_MS = 10_000L

    private val main = Handler(Looper.getMainLooper())
    private var player: MediaPlayer? = null
    private var audio: AudioManager? = null
    private var savedUserVolume: Int? = null

    /** True while a Pay flow waits for its purchase result (the monitor then logs faster). */
    @Volatile
    var purchaseInFlight = false

    val isPlaying: Boolean
        get() = runCatching { player?.isPlaying == true }.getOrDefault(false)

    /** "7/7" for the alarm stream, or "?" before the first start. */
    fun alarmVolume(): String {
        val am = audio ?: return "?"
        return "${am.getStreamVolume(AudioManager.STREAM_ALARM)}/${am.getStreamMaxVolume(AudioManager.STREAM_ALARM)}"
    }

    @Suppress("TooGenericExceptionCaught") // Spike: any MediaPlayer failure is logged, never thrown.
    fun start(context: Context) {
        main.post {
            if (player != null) {
                SpikeS1Log.log("sound: start ignored, already playing=$isPlaying")
                return@post
            }
            val app = context.applicationContext
            val am = app.getSystemService(AudioManager::class.java)
            audio = am
            if (savedUserVolume == null) savedUserVolume = am.getStreamVolume(AudioManager.STREAM_ALARM)
            try {
                am.setStreamVolume(AudioManager.STREAM_ALARM, am.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0)
            } catch (e: SecurityException) {
                SpikeS1Log.log("sound: setStreamVolume refused: ${e.message}")
            }
            try {
                val mp = MediaPlayer()
                mp.setAudioAttributes(
                    AudioAttributes
                        .Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build(),
                )
                mp.setWakeMode(app, PowerManager.PARTIAL_WAKE_LOCK)
                app.resources.openRawResourceFd(R.raw.alarm_default).use { afd -> mp.setDataSource(afd) }
                mp.isLooping = true
                mp.setOnErrorListener { _, what, extra ->
                    SpikeS1Log.log("sound: MediaPlayer ERROR what=$what extra=$extra")
                    false
                }
                mp.prepare()
                mp.start()
                player = mp
                SpikeS1Log.log("sound: STARTED usage=ALARM looping alarmVolume=${alarmVolume()} (user volume was $savedUserVolume)")
                scheduleMonitor()
            } catch (e: Exception) {
                SpikeS1Log.log("sound: start FAILED ${e::class.simpleName}: ${e.message}")
            }
        }
    }

    fun stop() {
        main.post {
            main.removeCallbacks(monitor)
            player?.let {
                runCatching { it.stop() }
                it.release()
            }
            player = null
            val am = audio
            val user = savedUserVolume
            if (am != null && user != null) {
                runCatching { am.setStreamVolume(AudioManager.STREAM_ALARM, user, 0) }
                savedUserVolume = null
            }
            SpikeS1Log.log("sound: STOPPED, alarm volume restored to ${alarmVolume()}")
        }
    }

    private val monitor =
        object : Runnable {
            override fun run() {
                if (player == null) return
                SpikeS1Log.log("sound monitor: playing=$isPlaying alarmVolume=${alarmVolume()} purchaseInFlight=$purchaseInFlight")
                scheduleMonitor()
            }
        }

    private fun scheduleMonitor() {
        main.removeCallbacks(monitor)
        main.postDelayed(monitor, if (purchaseInFlight) MONITOR_FAST_MS else MONITOR_SLOW_MS)
    }

    /** Re-times the monitor right away (called when a purchase starts or ends). */
    fun kickMonitor() {
        main.post { if (player != null) scheduleMonitor() }
    }
}
