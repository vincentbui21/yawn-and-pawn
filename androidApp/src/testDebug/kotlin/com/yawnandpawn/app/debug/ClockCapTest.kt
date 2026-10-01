package com.yawnandpawn.app.debug

import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.test.core.app.ActivityScenario
import com.yawnandpawn.app.StopAppRule
import com.yawnandpawn.app.ui.theme.PpsTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The `clock-xl` cap in a composed `PpsTheme` on a real 2.0x font-scale configuration (SDK 33).
 *
 * Finding: Compose converts sp to dp with its own non-linear font scaling on every API level, including
 * SDK 33 (body 16 sp renders at 28 dp, clock 88 sp at about 89 dp at 2.0x). So on the system density the
 * clock stays below the 1.3x cap (114.4 dp) by itself. The cap only binds under linear scaling, which the
 * second test provides through `LocalDensity` to prove PpsTheme applies it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], fontScale = 2f)
class ClockCapTest {
    @get:Rule(order = 0)
    val appTeardown = StopAppRule()

    @get:Rule(order = 1)
    val composeRule = createEmptyComposeRule()

    /** Linear font scaling: sp x fontScale = dp. */
    private class LinearDensity(
        override val density: Float,
        override val fontScale: Float,
    ) : Density {
        override fun TextUnit.toDp(): Dp = (value * fontScale).dp

        override fun Dp.toSp(): TextUnit = (value / fontScale).sp
    }

    /** Composes [content] around PpsTheme and returns the rendered dp of clock-xl and body. */
    private fun renderedSizes(content: @Composable (@Composable () -> Unit) -> Unit): Pair<Float, Float> {
        var sizes = 0f to 0f
        ActivityScenario.launch(ThemeShowcaseActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    content {
                        PpsTheme {
                            with(LocalDensity.current) {
                                sizes = PpsTheme.typography.clockXl.fontSize
                                    .toDp()
                                    .value to
                                    PpsTheme.typography.body.fontSize
                                        .toDp()
                                        .value
                            }
                        }
                    }
                }
            }
            composeRule.waitForIdle()
        }
        return sizes
    }

    @Test
    fun `on the system density at 2x the clock renders at or below 1_3x`() {
        val (clock, body) = renderedSizes { it() }

        assertTrue(clock <= 88f * 1.3f + 0.5f, "clock-xl renders at $clock dp")
        assertTrue(body > 16f, "body still scales up ($body dp)")
    }

    @Test
    fun `under linear 2x scaling PpsTheme caps the clock at 1_3x`() {
        val (clock, body) =
            renderedSizes { themed ->
                val system = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides LinearDensity(system.density, fontScale = 2f)) { themed() }
            }

        assertEquals(114.4f, clock, 0.5f) // 88 sp x 1.3, not 176 dp
        assertEquals(32f, body, 0.5f) // other styles scale fully (16 sp x 2)
    }
}
