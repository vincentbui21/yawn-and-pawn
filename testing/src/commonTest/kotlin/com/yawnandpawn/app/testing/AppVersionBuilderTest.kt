package com.yawnandpawn.app.testing

import kotlin.test.Test
import kotlin.test.assertEquals

class AppVersionBuilderTest {
    @Test
    fun `the default app version is the first release 0_1_0`() {
        assertEquals("0.1.0", anAppVersion().versionName)
    }
}
