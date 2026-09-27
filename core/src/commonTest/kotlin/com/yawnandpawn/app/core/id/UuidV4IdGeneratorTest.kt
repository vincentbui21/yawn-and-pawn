package com.yawnandpawn.app.core.id

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class UuidV4IdGeneratorTest {
    private val uuidV4 = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")

    @Test
    fun `ids are distinct lowercase UUID v4 strings`() {
        val generator = UuidV4IdGenerator()
        val ids = List(100) { generator.newId() }

        ids.forEach { assertTrue(uuidV4.matches(it), "not a UUID v4: $it") }
        assertEquals(ids.size, ids.toSet().size)
    }
}
