package com.yawnandpawn.app.ui

import com.yawnandpawn.app.testing.anAppVersion
import org.koin.dsl.koinApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class UiModuleTest {
    @Test
    fun `the ui module loads into a Koin application`() {
        val app = koinApplication { modules(uiModule) }

        assertNotNull(app.koin)
        app.close()
    }

    @Test
    fun `ui tests can use builders from the testing module`() {
        assertEquals("0.1.0", anAppVersion().versionName)
    }
}
