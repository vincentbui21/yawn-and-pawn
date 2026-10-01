package com.yawnandpawn.app.ui.components

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.TextUnit
import com.yawnandpawn.app.ui.theme.CLOCK_XL_MAX_FONT_SCALE
import com.yawnandpawn.app.ui.theme.PpsTheme
import com.yawnandpawn.app.ui.theme.cappedFontSize

/**
 * The "h" / "min" label after a wheel (owner decision 2026-09-27), in `label` / `text-secondary` capped at 1.3x like the
 * digits so the wheels still fit 360 dp at 200%. TalkBack already reads "Hour" / "Minute", so the label is not read.
 */
@Composable
internal fun UnitLabel(text: String) {
    val label = PpsTheme.typography.label
    val density = LocalDensity.current
    val style =
        remember(label, density) {
            label.copy(fontSize = cappedFontSize(label.fontSize, density, CLOCK_XL_MAX_FONT_SCALE), lineHeight = TextUnit.Unspecified)
        }
    Text(
        text = text,
        modifier = Modifier.clearAndSetSemantics { }.padding(start = PpsTheme.spacing.space1, end = PpsTheme.spacing.space2),
        style = style,
        color = PpsTheme.colors.textSecondary,
    )
}

/** `display` with its font scale capped at 1.3x (DESIGN.md `clock-xl` rule applied to the wheel digits). */
@Composable
internal fun wheelDigitStyle(): TextStyle {
    val display = PpsTheme.typography.display
    val density = LocalDensity.current
    return remember(display, density) {
        display.copy(
            fontSize = cappedFontSize(display.fontSize, density, CLOCK_XL_MAX_FONT_SCALE),
            lineHeight = cappedFontSize(display.lineHeight, density, CLOCK_XL_MAX_FONT_SCALE),
        )
    }
}
