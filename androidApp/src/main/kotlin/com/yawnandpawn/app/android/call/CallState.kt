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
    /** True during a call: the audio mode is one of [AudioModeCallState.callModes]. */
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
    private val modes = callModes(sdkInt)

    override fun inCall(): Boolean = audio.mode in modes

    // The guard is [sdkInt] (injectable for tests), which lint cannot follow: the API 31 calls run only on API 31+.
    @SuppressLint("NewApi")
    override fun watch(onChange: () -> Unit): (() -> Unit)? {
        if (sdkInt < Build.VERSION_CODES.S) return null
        val listener = AudioManager.OnModeChangedListener { onChange() }
        audio.addOnModeChangedListener(context.mainExecutor, listener)
        return { audio.removeOnModeChangedListener(listener) }
    }

    companion object {
        /**
         * The audio modes that mean a call on [sdkInt]: in a call, a VoIP call or an incoming call ringing; from API 30
         * also a call being screened, and from API 33 a call or VoIP call redirected to another device (the phone is
         * still busy with the call). The constants are compile-time values; the platform reports them only from there.
         */
        @SuppressLint("InlinedApi")
        fun callModes(sdkInt: Int): Set<Int> =
            buildSet {
                add(AudioManager.MODE_IN_CALL)
                add(AudioManager.MODE_IN_COMMUNICATION)
                add(AudioManager.MODE_RINGTONE)
                if (sdkInt >= Build.VERSION_CODES.R) add(AudioManager.MODE_CALL_SCREENING)
                if (sdkInt >= Build.VERSION_CODES.TIRAMISU) {
                    add(AudioManager.MODE_CALL_REDIRECT)
                    add(AudioManager.MODE_COMMUNICATION_REDIRECT)
                }
            }
    }
}

/**
 * The [phone]'s call state as the alarm sees it (Story 2.7 review): a call mode that outlasted the call-pause cap (an
 * app left the mode set) is ignored after [ignoreCurrentCall], until the phone leaves the call modes once. The call
 * adapter and the wake runtime share one instance, so a capped ring is neither paused again nor reopened silent.
 */
class StuckCallGuard(
    private val phone: CallState,
) : CallState {
    @Volatile
    private var ignoring = false

    override fun inCall(): Boolean {
        val inCall = phone.inCall()
        if (!inCall) ignoring = false
        return inCall && !ignoring
    }

    override fun watch(onChange: () -> Unit): (() -> Unit)? = phone.watch(onChange)

    /** The current call mode is stuck: it no longer counts as a call until the phone leaves the call modes. */
    fun ignoreCurrentCall() {
        ignoring = true
    }
}
