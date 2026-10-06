package com.yawnandpawn.app.android.call

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioManager
import android.os.Build

/**
 * Whether the phone is in a call (Story 2.7, FR-SES-8), read without any telephony permission or API (NFR-13): the
 * audio mode only. [watch] reports mode changes where the platform can (API 31+); elsewhere the detector polls.
 */
interface CallState {
    /** True during a call: the audio mode is in-call, in-communication (VoIP) or ringtone (an incoming call ringing). */
    fun inCall(): Boolean

    /** Calls [onChange] on every audio-mode change until the returned function is called; null when not supported. */
    fun watch(onChange: () -> Unit): (() -> Unit)? = null
}

/** [CallState] from `AudioManager.getMode()` and, on API 31+, `addOnModeChangedListener`. */
class AudioModeCallState(
    private val context: Context,
    private val sdkInt: Int = Build.VERSION.SDK_INT,
) : CallState {
    private val audio: AudioManager = context.getSystemService(AudioManager::class.java)

    override fun inCall(): Boolean = audio.mode in CALL_MODES

    // The guard is [sdkInt] (injectable for tests), which lint cannot follow: the API 31 calls run only on API 31+.
    @SuppressLint("NewApi")
    override fun watch(onChange: () -> Unit): (() -> Unit)? {
        if (sdkInt < Build.VERSION_CODES.S) return null
        val listener = AudioManager.OnModeChangedListener { onChange() }
        audio.addOnModeChangedListener(context.mainExecutor, listener)
        return { audio.removeOnModeChangedListener(listener) }
    }

    companion object {
        /** The audio modes that mean a call: in a call, a VoIP call, or an incoming call ringing. */
        val CALL_MODES = setOf(AudioManager.MODE_IN_CALL, AudioManager.MODE_IN_COMMUNICATION, AudioManager.MODE_RINGTONE)
    }
}
