package com.yawnandpawn.app.testing

import com.yawnandpawn.app.core.sound.SoundRef
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SoundFakesTest {
    @Test
    fun `the fake library knows catalog sounds and its system list, minus the missing ones`() =
        runTest {
            val argon = SoundRef.System("content://ringtones/argon", "Argon")
            val library = FakeSoundLibrary(system = listOf(argon))

            assertEquals(listOf(argon), library.systemSounds())
            assertTrue(library.isAvailable(SoundRef.BuiltIn("chimes")))
            assertTrue(library.isAvailable(argon))
            assertFalse(library.isAvailable(SoundRef.BuiltIn("gone")))
            assertFalse(library.isAvailable(SoundRef.System("content://gone", "Gone")))
            library.missing += argon
            assertFalse(library.isAvailable(argon))
        }

    @Test
    fun `the fake preview records plays, volume changes while playing, stops and a natural end`() {
        val preview = FakeSoundPreview()

        preview.setVolume(10)
        preview.play(SoundRef.BuiltIn("bell"), 60)
        preview.setVolume(70)

        assertEquals("builtin:bell", preview.previewing.value)
        assertEquals(listOf("builtin:bell" to 60), preview.played)
        assertEquals(listOf(70), preview.volumes, "only while playing")
        preview.finish()
        assertNull(preview.previewing.value)
        preview.play(SoundRef.BuiltIn("bell"), 60)
        preview.stop()
        assertNull(preview.previewing.value)
        assertEquals(1, preview.stops)
    }
}
