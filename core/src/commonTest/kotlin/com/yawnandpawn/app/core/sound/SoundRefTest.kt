package com.yawnandpawn.app.core.sound

import com.yawnandpawn.app.core.alarm.Alarm
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SoundRefTest {
    @Test
    fun `a built-in sound is builtin colon id and the default is the alarm default`() {
        assertEquals("builtin:chimes", SoundRef.BuiltIn("chimes").encode())
        assertEquals(SoundRef.BuiltIn("default"), SoundRef.parse(Alarm.DEFAULT_SOUND_REF))
    }

    @Test
    fun `a system sound keeps its title and uri through encode and parse`() {
        val ref = SoundRef.System(uri = "content://media/internal/audio/media/42?title=Argon&canonical=1", title = "Argon")

        assertEquals("system:Argon|content://media/internal/audio/media/42?title=Argon&canonical=1", ref.encode())
        assertEquals(ref, SoundRef.parse(ref.encode()))
    }

    @Test
    fun `a title with a bar or a percent sign is escaped so the uri still parses`() {
        val ref = SoundRef.System(uri = "content://media/1|2", title = "50% | loud %7C")

        assertEquals(ref, SoundRef.parse(ref.encode()))
        assertTrue(ref.encode().startsWith("system:50%25 %7C loud %257C|"))
    }

    @Test
    fun `unknown or incomplete references parse to nothing`() {
        listOf("", "builtin:", "builtin:  ", "system:", "system:Argon", "system:Argon|", "file:x", "default").forEach {
            assertNull(SoundRef.parse(it), "'$it'")
        }
    }
}
