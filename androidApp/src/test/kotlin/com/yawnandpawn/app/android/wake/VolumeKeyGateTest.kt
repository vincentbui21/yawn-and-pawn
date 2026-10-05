package com.yawnandpawn.app.android.wake

import android.view.KeyEvent
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals

/** Story 2.8 (FR-SES-6, UX-DR69): which volume keys the wake screen swallows. */
@RunWith(RobolectricTestRunner::class)
class VolumeKeyGateTest {
    private val gate = VolumeKeyGate().apply { resumed = true }

    private fun down(key: Int) = gate.consumes(KeyEvent.ACTION_DOWN, key)

    private fun up(key: Int) = gate.consumes(KeyEvent.ACTION_UP, key)

    private val volumeUp = KeyEvent.KEYCODE_VOLUME_UP
    private val volumeDown = KeyEvent.KEYCODE_VOLUME_DOWN

    @Test
    fun `while the wake screen is in front volume up, down and mute do nothing`() {
        listOf(volumeUp, volumeDown, KeyEvent.KEYCODE_VOLUME_MUTE).forEach { key ->
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
