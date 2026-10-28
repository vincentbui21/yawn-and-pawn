package com.yawnandpawn.app.testing

import com.yawnandpawn.app.core.error.Outcome
import com.yawnandpawn.app.core.sound.ImportResult
import com.yawnandpawn.app.core.sound.SoundRef
import kotlinx.coroutines.flow.first
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
            assertFalse(library.isAvailable(SoundRef.File("sound-1")))
            library.files += "sound-1"
            assertTrue(library.isAvailable(SoundRef.File("sound-1")))
        }

    @Test
    fun `the fake custom sounds follow their files and the fake importer stores what it imports (Story 7_4)`() =
        runTest {
            val sounds = FakeCustomSoundRepository()
            val importer = FakeSoundImporter(into = sounds)

            val result = importer.import("content://picked/1")

            assertEquals(Outcome.Success(ImportResult.Imported(aCustomSound())), result)
            assertEquals(listOf("content://picked/1"), importer.imported)
            assertEquals(listOf(aCustomSound()), sounds.observeAll().first())
            sounds.markMissing("sound-1")
            assertEquals(Outcome.Success(aCustomSound(missing = true)), sounds.get("sound-1"))
            importer.result = Outcome.Success(ImportResult.TooBig)
            assertEquals(Outcome.Success(ImportResult.TooBig), importer.import("content://picked/2"))
            assertEquals(1, sounds.stored.size)
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
