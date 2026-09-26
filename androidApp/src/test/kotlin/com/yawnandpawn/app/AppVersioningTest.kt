package com.yawnandpawn.app

import androidx.test.core.app.ApplicationProvider
import com.yawnandpawn.app.core.AppVersion
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.stopKoin
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

@RunWith(RobolectricTestRunner::class)
class AppVersioningTest {
    @After
    fun tearDown() {
        stopKoin()
    }

    @Test
    fun `the installed versionCode follows the versionName formula and there is no debug suffix`() {
        val context = ApplicationProvider.getApplicationContext<YawnAndPawnApp>()
        val info = context.packageManager.getPackageInfo(context.packageName, 0)

        assertEquals("com.yawnandpawn.app", context.packageName)
        val version = assertNotNull(AppVersion.parseOrNull(info.versionName.orEmpty()))
        assertEquals(version.versionCode.toLong(), info.longVersionCode)
    }
}
