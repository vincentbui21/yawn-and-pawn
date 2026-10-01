package com.yawnandpawn.app.debug

import androidx.test.core.app.ApplicationProvider
import com.yawnandpawn.app.StopAppRule
import com.yawnandpawn.app.TestYawnAndPawnApp
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertIs

/** The debug-only host tests boot the same test application as src/test (robolectric.properties). */
@RunWith(RobolectricTestRunner::class)
class TestApplicationTest {
    @get:Rule(order = 0)
    val stopApp = StopAppRule()

    @Test
    fun `debug host tests run on the test application`() {
        assertIs<TestYawnAndPawnApp>(ApplicationProvider.getApplicationContext())
    }
}
