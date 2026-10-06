package com.yawnandpawn.app.android.wake

import android.view.KeyEvent
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals

/** Story 2.8 (FR-SES-6, UX-DR69): which volume keys the wake screen swallows. */
@RunWith(RobolectricTestRunner::class)
class VolumeKeyGateTest {
    private var ringing = true

    private val gate =
        VolumeKeyGate(ringing = { ringing }).apply {
            resumed = true
            focused = true
        }

    private fun down(
        key: Int,
        device: Int = 0,
    ) = gate.consumes(KeyEvent.ACTION_DOWN, key, device)

    private fun up(
        key: Int,
        device: Int = 0,
    ) = gate.consumes(KeyEvent.ACTION_UP, key, device)

    private val volumeUp = KeyEvent.KEYCODE_VOLUME_UP
    private val volumeDown = KeyEvent.KEYCODE_VOLUME_DOWN
    private val volumeKeys = listOf(volumeUp, volumeDown, KeyEvent.KEYCODE_VOLUME_MUTE)

    @Test
    fun `while the wake screen is in front volume up, down and mute do nothing`() {
        volumeKeys.forEach { key ->
            assertEquals(listOf(true, true), listOf(down(key), up(key)), "key $key")
        }
    }

    @Test
    fun `other keys always pass`() {
        val others = listOf(KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_HEADSETHOOK, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_ENTER)
        others.forEach { key ->
            assertEquals(listOf(false, false), listOf(down(key), up(key)), "key $key")
        }
    }

    @Test
    fun `when nothing rings (snoozed, waiting for the session, ended) the volume keys work normally`() {
        ringing = false

        volumeKeys.forEach { key ->
            assertEquals(listOf(false, false), listOf(down(key), up(key)), "key $key")
        }
        ringing = true
        assertEquals(listOf(true, true), listOf(down(volumeDown), up(volumeDown)), "rings again")
    }

    @Test
    fun `a key held when the ring stops still has its up swallowed, so the window never sees a lone up`() {
        assertEquals(true, down(volumeDown))
        ringing = false
        assertEquals(false, down(volumeDown), "a repeat once nothing rings")
        assertEquals(true, up(volumeDown))
    }

    @Test
    fun `resumed without window focus (shade down, split screen) nothing is swallowed`() {
        gate.focused = false

        volumeKeys.forEach { key ->
            assertEquals(listOf(false, false), listOf(down(key), up(key)), "key $key")
        }
        gate.focused = true
        assertEquals(listOf(true, true), listOf(down(volumeUp), up(volumeUp)), "focus back")
    }

    @Test
    fun `losing focus forgets a held key whose up never arrives, so a single key is not the shortcut later`() {
        down(volumeDown)
        // Its up goes to the shade's window.
        gate.focused = false
        gate.focused = true

        assertEquals(true, down(volumeUp), "a single key: swallowed")
        assertEquals(true, up(volumeUp))
    }

    @Test
    fun `an up whose down was passed through passes through too`() {
        gate.resumed = false
        assertEquals(false, down(volumeDown), "pressed before the screen came to the front")
        gate.resumed = true

        assertEquals(false, up(volumeDown))
        assertEquals(false, up(volumeUp), "an up with no down at all")
    }

    @Test
    fun `the accessibility shortcut passes from the second key down until both are released`() {
        val sequence =
            listOf(
                down(volumeDown),
                down(volumeUp),
                // Held keys repeat their down events.
                down(volumeDown),
                down(volumeUp),
                up(volumeUp),
                up(volumeDown),
            )

        assertEquals(listOf(true, false, false, false, false, false), sequence)
        assertEquals(listOf(true, true), listOf(down(volumeUp), up(volumeUp)), "afterwards a single key is swallowed again")
    }

    @Test
    fun `the shortcut works in either order and with a key released first`() {
        assertEquals(true, down(volumeUp))
        assertEquals(false, down(volumeDown))
        assertEquals(false, up(volumeDown))
        // Still held: the shortcut lasts until both keys are up.
        assertEquals(false, down(volumeDown))
        assertEquals(false, up(volumeUp))
        assertEquals(false, up(volumeDown))
        assertEquals(true, down(volumeDown))
    }

    @Test
    fun `the shortcut needs both keys on the same device`() {
        assertEquals(true, down(volumeDown, device = 1))
        assertEquals(true, down(volumeUp, device = 2), "the other key on another device: not the shortcut")
        assertEquals(true, up(volumeUp, device = 2))
        assertEquals(false, down(volumeUp, device = 1), "both on device 1: the shortcut")
        assertEquals(false, up(volumeUp, device = 1))
        assertEquals(false, up(volumeDown, device = 1))
    }

    @Test
    fun `once another screen is in front nothing is swallowed, and a shortcut in progress is forgotten`() {
        down(volumeDown)
        down(volumeUp)

        gate.resumed = false

        assertEquals(listOf(false, false, false), listOf(down(volumeDown), up(volumeDown), down(KeyEvent.KEYCODE_VOLUME_MUTE)))
        up(volumeUp)
        gate.resumed = true
        assertEquals(true, down(volumeUp), "back in front, a single key is swallowed")
    }
}
