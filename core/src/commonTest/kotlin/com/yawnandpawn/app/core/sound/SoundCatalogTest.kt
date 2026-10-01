package com.yawnandpawn.app.core.sound

import com.yawnandpawn.app.core.alarm.Alarm
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SoundCatalogTest {
    @Test
    fun `the catalog has at least ten sounds, unique ids and exactly one default`() {
        val sounds = SoundCatalog.sounds

        assertTrue(sounds.size >= 10, "${sounds.size} sounds")
        assertEquals(sounds.size, sounds.map { it.id }.toSet().size)
        assertEquals(SoundCatalog.default, sounds.first(), "the default is listed first")
        assertEquals(1, sounds.count { it.ref.encode() == Alarm.DEFAULT_SOUND_REF })
        assertEquals("alarm_default", SoundCatalog.default.resourceName)
    }

    @Test
    fun `every resource is alarm underscore id, which the loudness gate measures`() {
        SoundCatalog.sounds.forEach { assertEquals("alarm_${it.id}", it.resourceName) }
    }

    @Test
    fun `find returns known built-ins only`() {
        assertEquals("chimes", SoundCatalog.find(SoundRef.BuiltIn("chimes"))?.id)
        assertNull(SoundCatalog.find(SoundRef.BuiltIn("gone")))
        assertNull(SoundCatalog.find(SoundRef.System("content://x", "X")))
        assertNull(SoundCatalog.find(null as SoundRef?))
    }
}
