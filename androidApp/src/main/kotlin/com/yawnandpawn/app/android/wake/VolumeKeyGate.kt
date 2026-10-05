package com.yawnandpawn.app.android.wake

import android.view.KeyEvent

/**
 * Which volume keys the wake screen swallows (Story 2.8, FR-SES-6, UX-DR69). Only while the screen is [resumed], has
 * window focus ([focused]) and something is [ringing] (a session in Ringing, Grace or Loud, or the emergency ring):
 * volume up, down and mute do nothing then, so the alarm cannot be turned down from it. Every other key, every key
 * while another window has focus (the notification shade, split screen, another screen in front), and every key while
 * nothing rings (snoozed, waiting for the session, after it ended) passes through, so the rest of the phone behaves
 * normally.
 *
 * A key up is swallowed only when its key down was: a key pressed before the screen took the keys passes through whole.
 *
 * The accessibility shortcut (both volume keys held on the same device) is never captured: from the moment the second
 * of up and down goes down until both are released, no volume key is consumed. Pure: [WakeActivity] feeds it every
 * key event.
 */
class VolumeKeyGate(
    private val ringing: () -> Boolean,
) {
    /**
     * The wake screen is in front (set in `onResume`, cleared in `onPause`). Either change forgets the held keys: keys
     * pressed while another screen was in front never count towards the shortcut.
     */
    var resumed: Boolean = false
        set(value) {
            field = value
            forget()
        }

    /**
     * The wake screen's window has focus (`onWindowFocusChanged`). Either change forgets the held keys too, so a key
     * whose up went to another window never makes a single key count as the shortcut later.
     */
    var focused: Boolean = false
        set(value) {
            field = value
            forget()
        }

    /** Volume up and down keys currently held, per input device. */
    private val held = mutableSetOf<Key>()

    /** Keys whose down was consumed: only their up is consumed too. */
    private val consumedDowns = mutableSetOf<Key>()

    /** Both volume keys went down together: the accessibility shortcut, passed through until both are up. */
    private var shortcut = false

    /**
     * True when the key event with [action] (`KeyEvent.ACTION_*`) for [keyCode] from the input device [deviceId] is
     * consumed and does nothing.
     */
    fun consumes(
        action: Int,
        keyCode: Int,
        deviceId: Int = 0,
    ): Boolean {
        if (keyCode !in VOLUME_KEYS) return false
        val key = Key(deviceId, keyCode)
        if (keyCode != KeyEvent.KEYCODE_VOLUME_MUTE) track(action, key)
        val consumed =
            when (action) {
                KeyEvent.ACTION_DOWN -> {
                    (resumed && focused && !shortcut && ringing()).also { if (it) consumedDowns += key }
                }

                KeyEvent.ACTION_UP -> {
                    consumedDowns.remove(key) && !shortcut
                }

                else -> {
                    false
                }
            }
        if (shortcut && held.isEmpty()) shortcut = false
        return consumed
    }

    private fun track(
        action: Int,
        key: Key,
    ) {
        when (action) {
            KeyEvent.ACTION_DOWN -> {
                held += key
                if (SHORTCUT_KEYS.all { Key(key.deviceId, it) in held }) {
                    shortcut = true
                    consumedDowns.clear()
                }
            }

            KeyEvent.ACTION_UP -> {
                held -= key
            }
        }
    }

    private fun forget() {
        held.clear()
        consumedDowns.clear()
        shortcut = false
    }

    private data class Key(
        val deviceId: Int,
        val keyCode: Int,
    )

    private companion object {
        val SHORTCUT_KEYS = setOf(KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN)
        val VOLUME_KEYS = SHORTCUT_KEYS + KeyEvent.KEYCODE_VOLUME_MUTE
    }
}
