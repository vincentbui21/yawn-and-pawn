package com.yawnandpawn.app.debug

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import com.yawnandpawn.app.ui.resources.Res
import com.yawnandpawn.app.ui.resources.symbol_cancel_fill1
import com.yawnandpawn.app.ui.resources.symbol_check_circle_fill1
import com.yawnandpawn.app.ui.resources.symbol_radio_button_unchecked
import com.yawnandpawn.app.ui.resources.symbol_schedule_fill1
import com.yawnandpawn.app.ui.theme.PpsColors
import com.yawnandpawn.app.ui.theme.PpsTheme
import com.yawnandpawn.app.ui.theme.PpsThemeMode
import org.jetbrains.compose.resources.vectorResource

/**
 * Debug-only theme showcase (Story 1.3): every colour role, type style, shape and outcome glyph of
 * the active token set. Lives in `src/debug`, so release builds never contain it. Labels are
 * DESIGN.md token names (developer data, not user-facing copy).
 */
@Composable
fun ThemeShowcase(
    mode: PpsThemeMode = PpsThemeMode.System,
    wake: Boolean = false,
    modifier: Modifier = Modifier,
) {
    PpsTheme(mode = mode, wake = wake) {
        Surface(modifier = modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.background) {
            Column(
                modifier = Modifier.padding(PpsTheme.spacing.screenMargin),
                verticalArrangement = Arrangement.spacedBy(PpsTheme.spacing.sectionGap),
            ) {
                Text(text = "PpsTheme · ${PpsTheme.colorSet}", style = PpsTheme.typography.headline)
                ColorRoles(PpsTheme.colors)
                TypeRamp()
                ShapeSet()
                OutcomeGlyphs(PpsTheme.colors)
            }
        }
    }
}

private fun PpsColors.roles(): List<Pair<String, Color>> =
    listOfNotNull(
        "bg" to bg,
        "surface" to surface,
        "surface-variant" to surfaceVariant,
        "outline" to outline,
        "outline-subtle" to outlineSubtle,
        "text" to text,
        "text-secondary" to textSecondary,
        "accent" to accent,
        "on-accent" to onAccent,
        "accent-text" to accentText,
        "success" to success,
        "snoozed" to snoozed,
        "missed" to missed,
        "error" to error,
        "disabled-container" to disabledContainer,
        "disabled-content" to disabledContent,
        "inverse-surface" to inverseSurface,
        "inverse-text" to inverseText,
        "inverse-accent" to inverseAccent,
        sunriseGradientTop?.let { "sunrise-gradient-top" to it },
    )

@Composable
private fun ColorRoles(colors: PpsColors) {
    Section("colors") {
        colors.roles().chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(PpsTheme.spacing.space4)) {
                row.forEach { (name, color) -> Swatch(name, color, Modifier.weight(1f)) }
                if (row.size == 1) Box(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun Swatch(
    name: String,
    color: Color,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(PpsTheme.spacing.space2),
    ) {
        Box(
            Modifier
                .size(PpsTheme.spacing.targetMin)
                .border(1.dp, PpsTheme.colors.outline, PpsTheme.shapes.sm)
                .background(color, PpsTheme.shapes.sm),
        )
        Column {
            Text(text = name, style = PpsTheme.typography.label)
            Text(text = color.hex(), style = PpsTheme.typography.caption, color = PpsTheme.colors.textSecondary)
        }
    }
}

private const val RGB_MASK = 0xFFFFFF
private const val HEX_RADIX = 16
private const val HEX_DIGITS = 6

private fun Color.hex(): String = "#" + (toArgb() and RGB_MASK).toString(HEX_RADIX).uppercase().padStart(HEX_DIGITS, '0')

@Composable
private fun TypeRamp() {
    val type = PpsTheme.typography
    Section("typography") {
        TypeSample("clock-xl", type.clockXl, "07:30")
        TypeSample("display", type.display, "12 · 07:30")
        TypeSample("headline", type.headline, "headline 0123456789")
        TypeSample("title", type.title, "title 07:30")
        TypeSample("button-wake", type.buttonWake, "button-wake 0123")
        TypeSample("body", type.body, "body 0123456789")
        TypeSample("label", type.label, "label 0123456789")
        TypeSample("caption", type.caption, "caption 0123456789")
    }
}

@Composable
private fun TypeSample(
    name: String,
    style: TextStyle,
    sample: String,
) {
    Column {
        Text(text = name, style = PpsTheme.typography.caption, color = PpsTheme.colors.textSecondary)
        Text(text = sample, style = style)
    }
}

@Composable
private fun ShapeSet() {
    val shapes = PpsTheme.shapes
    Section("rounded") {
        Row(horizontalArrangement = Arrangement.spacedBy(PpsTheme.spacing.space4)) {
            listOf("sm" to shapes.sm, "md" to shapes.md, "lg" to shapes.lg, "full" to shapes.full).forEach { (name, shape) ->
                Labeled(name) {
                    Box(
                        Modifier
                            .size(PpsTheme.spacing.targetWake)
                            .background(PpsTheme.colors.accent, shape),
                    )
                }
            }
        }
    }
}

@Composable
private fun OutcomeGlyphs(colors: PpsColors) {
    val glyphs =
        listOf(
            Triple("on time", Res.drawable.symbol_check_circle_fill1, colors.success),
            Triple("snoozed", Res.drawable.symbol_schedule_fill1, colors.snoozed),
            Triple("missed", Res.drawable.symbol_cancel_fill1, colors.missed),
            Triple("skipped", Res.drawable.symbol_radio_button_unchecked, colors.outline),
        )
    Section("outcome-marker") {
        Row(horizontalArrangement = Arrangement.spacedBy(PpsTheme.spacing.space6)) {
            glyphs.forEach { (name, icon, tint) ->
                Labeled(name) { Icon(imageVector = vectorResource(icon), contentDescription = name, tint = tint) }
            }
        }
    }
}

/** A sample with its token name underneath. */
@Composable
private fun Labeled(
    name: String,
    sample: @Composable () -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        sample()
        Text(text = name, style = PpsTheme.typography.caption)
    }
}

@Composable
private fun Section(
    title: String,
    content: @Composable () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(PpsTheme.spacing.space2)) {
        Text(text = title, style = PpsTheme.typography.title)
        content()
    }
}
