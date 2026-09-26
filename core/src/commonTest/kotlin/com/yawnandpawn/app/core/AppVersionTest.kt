package com.yawnandpawn.app.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class AppVersionTest {
    @Test
    fun `0_1_0 has versionCode 100`() {
        assertEquals(100, AppVersion(0, 1, 0).versionCode)
    }

    @Test
    fun `1_2_3 has versionCode 10203`() {
        assertEquals(10203, AppVersion(1, 2, 3).versionCode)
    }

    @Test
    fun `versionName is major dot minor dot patch`() {
        assertEquals("1.2.3", AppVersion(1, 2, 3).versionName)
    }

    @Test
    fun `a valid versionName parses`() {
        assertEquals(AppVersion(0, 1, 0), AppVersion.parseOrNull("0.1.0"))
    }

    @Test
    fun `an invalid versionName does not parse`() {
        assertNull(AppVersion.parseOrNull("0.1"))
        assertNull(AppVersion.parseOrNull("0.x.1"))
        assertNull(AppVersion.parseOrNull("-1.0.0"))
        assertNull(AppVersion.parseOrNull("1.100.0"))
        assertNull(AppVersion.parseOrNull("1.0.100"))
    }

    @Test
    fun `out of range parts are rejected`() {
        assertFailsWith<IllegalArgumentException> { AppVersion(-1, 0, 0) }
        assertFailsWith<IllegalArgumentException> { AppVersion(0, 100, 0) }
        assertFailsWith<IllegalArgumentException> { AppVersion(0, 0, 100) }
    }
}
