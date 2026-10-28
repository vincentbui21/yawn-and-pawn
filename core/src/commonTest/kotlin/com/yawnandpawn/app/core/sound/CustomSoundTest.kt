package com.yawnandpawn.app.core.sound

import kotlin.test.Test
import kotlin.test.assertEquals

/** Story 7.4: the copy's extension and the listed name come from the picked file's name. */
class CustomSoundTest {
    @Test
    fun `the extension is the picked name's own in lower case when it is 1 to 5 letters or digits`() {
        assertEquals("mp3", CustomSound.extensionOf("Morning song.MP3"))
        assertEquals("m4a", CustomSound.extensionOf("a.b.m4a"))
        assertEquals("ogg", CustomSound.extensionOf("x.ogg"))
        assertEquals("flac", CustomSound.extensionOf("x.flac"))
    }

    @Test
    fun `a name without a usable extension gets audio`() {
        listOf("song", "song.", ".hidden", "song.toolong", "song.mp-3", "song.m p3", "song.äö", "").forEach {
            assertEquals(CustomSound.FALLBACK_EXTENSION, CustomSound.extensionOf(it), "'$it'")
        }
    }

    @Test
    fun `the listed name is the picked name without its extension`() {
        assertEquals("Morning song", CustomSound.displayNameOf("Morning song.MP3"))
        assertEquals("a.b", CustomSound.displayNameOf("a.b.m4a"))
        assertEquals("song", CustomSound.displayNameOf("song"))
        assertEquals(".hidden", CustomSound.displayNameOf(".hidden"))
    }

    @Test
    fun `the copy is named by id and extension and the sound's ref is file colon id`() {
        assertEquals("id-1.mp3", CustomSound.fileNameFor("id-1", "mp3"))
        assertEquals("file:id-1", aSound("id-1").ref.encode())
    }

    private fun aSound(id: String) = CustomSound(id, "Song", "$id.mp3", 30_000, kotlin.time.Instant.fromEpochMilliseconds(0))
}
