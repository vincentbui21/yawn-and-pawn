package com.yawnandpawn.app.buildlogic

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class VersionCodeTest {
    @Test
    fun `0_1_0 maps to versionCode 100`() {
        assertEquals(100, versionCodeOf("0.1.0"))
    }

    @Test
    fun `1_2_3 maps to versionCode 10203`() {
        assertEquals(10203, versionCodeOf("1.2.3"))
    }

    @Test
    fun `a versionName that is not major_minor_patch is rejected`() {
        assertFailsWith<IllegalArgumentException> { versionCodeOf("1.2") }
        assertFailsWith<IllegalArgumentException> { versionCodeOf("1.x.3") }
        assertFailsWith<IllegalArgumentException> { versionCodeOf("1.100.0") }
    }
}
