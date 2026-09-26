package com.yawnandpawn.app.data

import com.yawnandpawn.app.testing.anAppVersion
import org.koin.dsl.koinApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class DataModuleTest {
    @Test
    fun `the data module loads into a Koin application`() {
        val app = koinApplication { modules(dataModule) }

        assertNotNull(app.koin)
        app.close()
    }

    @Test
    fun `data tests can use builders from the testing module`() {
        assertEquals(100, anAppVersion().versionCode)
    }
}
