package com.yawnandpawn.app.android.wake

import android.view.KeyEvent

/**
 * Which volume keys the wake screen swallows (Story 2.8, FR-SES-6, UX-DR69). Only while the screen is [resumed]:
 * volume up, down and mute do nothing then, so the alarm cannot be turned down from it. Every other key, and every key
 * while another screen is in front, passes through, so the rest of the phone behaves normally.
 *
 * The accessibility shortcut (both volume keys held) is never captured: from the moment the second of up and down goes
 * down until both are released, no volume key is consumed. Pure: [WakeActivity] feeds it every key event.
 */
class VolumeKeyGate {
    /**
     * The wake screen is in front (set in `onResume`, cleared in `onPause`). Either change forgets the held keys: keys
     * pressed while another screen was in front never count towards the shortcut.
     */
    var resumed: Boolean = false
        set(value) {
            field = value
            held.clear()
            shortcut = false
        }

    private val held = mutableSetOf<Int>()

    /** Both volume keys went down together: the accessibility shortcut, passed through until both are up. */
    private var shortcut = false

    /** True when the key event with [action] (`KeyEvent.ACTION_*`) for [keyCode] is consumed and does nothing. */
    fun consumes(
        action: Int,
        keyCode: Int,
    ): Boolean {
        if (keyCode !in VOLUME_KEYS) return false
        if (keyCode != KeyEvent.KEYCODE_VOLUME_MUTE) track(action, keyCode)
        val consumed = resumed && !shortcut
        if (shortcut && held.isEmpty()) shortcut = false
        return consumed
    }

    private fun track(
        action: Int,
        keyCode: Int,
    ) {
        when (action) {
            KeyEvent.ACTION_DOWN -> {
                held += keyCode
                if (held.containsAll(SHORTCUT_KEYS)) shortcut = true
            }

            KeyEvent.ACTION_UP -> {
                held -= keyCode
            }
        }
    }

    private companion object {
        val SHORTCUT_KEYS = setOf(KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN)
        val VOLUME_KEYS = SHORTCUT_KEYS + KeyEvent.KEYCODE_VOLUME_MUTE
    }
}
