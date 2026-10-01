package com.yawnandpawn.app.debug

import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.yawnandpawn.app.StopAppRule
import com.yawnandpawn.app.ui.theme.PpsColorSet
import com.yawnandpawn.app.ui.theme.PpsThemeMode
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * PpsTheme in a real composition on a real configuration: System follows the device light/dark
 * setting and wake is always Sunrise. The showcase headline prints the active token set.
 */
@RunWith(RobolectricTestRunner::class)
class PpsThemeSelectionTest {
    @get:Rule(order = 0)
    val appTeardown = StopAppRule()

    @get:Rule(order = 1)
    val composeRule = createEmptyComposeRule()

    private fun assertColorSet(
        expected: PpsColorSet,
        mode: PpsThemeMode = PpsThemeMode.System,
        wake: Boolean = false,
    ) = withShowcase(mode, wake) {
        composeRule.onNodeWithText("PpsTheme · $expected").assertExists()
    }

    @Test
    fun `System mode in a light system uses the Light set`() {
        assertColorSet(PpsColorSet.Light)
    }

    @Test
    @Config(qualifiers = "night")
    fun `System mode in a dark system uses the Dark set`() {
        assertColorSet(PpsColorSet.Dark)
    }

    @Test
    @Config(qualifiers = "night")
    fun `wake in a dark system uses Sunrise`() {
        assertColorSet(PpsColorSet.Sunrise, wake = true)
    }

    @Test
    fun `wake overrides an explicit Dark mode`() {
        assertColorSet(PpsColorSet.Sunrise, mode = PpsThemeMode.Dark, wake = true)
    }

    @Test
    @Config(qualifiers = "night")
    fun `explicit Light mode ignores a dark system`() {
        assertColorSet(PpsColorSet.Light, mode = PpsThemeMode.Light)
    }
}
