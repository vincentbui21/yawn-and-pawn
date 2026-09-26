package com.yawnandpawn.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yawnandpawn.app.ui.resources.Res
import com.yawnandpawn.app.ui.resources.geist_light
import com.yawnandpawn.app.ui.resources.geist_medium
import com.yawnandpawn.app.ui.resources.geist_regular
import com.yawnandpawn.app.ui.resources.geist_semibold
import org.jetbrains.compose.resources.Font

/** The eight DESIGN.md type styles. Read through `PpsTheme.typography`. */
@Immutable
data class PpsTypography(
    /** Ringing clock. Font scale is capped at [CLOCK_XL_MAX_FONT_SCALE] by `PpsTheme`. */
    val clockXl: TextStyle,
    val display: TextStyle,
    val headline: TextStyle,
    val title: TextStyle,
    /** Wake-screen action labels only. */
    val buttonWake: TextStyle,
    val body: TextStyle,
    val label: TextStyle,
    val caption: TextStyle,
)

/** DESIGN.md: `clock-xl` scales to at most 1.3x (114 sp) so wake actions stay in the thumb zone. */
const val CLOCK_XL_MAX_FONT_SCALE: Float = 1.3f

/** Tabular figures (`tnum`) so clock, price and list-time digits do not jump. Geist has `tnum` (docs/decisions/geist-tnum.md). */
const val TABULAR_FIGURES: String = "tnum"

/**
 * Geist (SIL OFL 1.1, docs/licenses/geist-OFL.txt), bundled at the four weights the ramp uses.
 * Glyphs Geist lacks fall back to the platform's system sans-serif font.
 */
@Composable
fun geistFontFamily(): FontFamily =
    FontFamily(
        Font(Res.font.geist_light, FontWeight.Light),
        Font(Res.font.geist_regular, FontWeight.Normal),
        Font(Res.font.geist_medium, FontWeight.Medium),
        Font(Res.font.geist_semibold, FontWeight.SemiBold),
    )

/** Builds the ramp from the generated tokens. Pure; [fontFamily] defaults to the system sans for non-UI tests. */
fun ppsTypography(fontFamily: FontFamily = FontFamily.SansSerif): PpsTypography {
    fun style(
        token: PpsTypeToken,
        tabular: Boolean = false,
    ) = TextStyle(
        fontFamily = fontFamily,
        fontSize = token.fontSize,
        lineHeight = token.lineHeight,
        fontWeight = token.fontWeight,
        fontFeatureSettings = if (tabular) TABULAR_FIGURES else null,
    )
    return with(PpsTokens.Type) {
        PpsTypography(
            clockXl = style(clockXl, tabular = true),
            display = style(display, tabular = true),
            headline = style(headline),
            title = style(title, tabular = true),
            buttonWake = style(buttonWake, tabular = true),
            body = style(body),
            label = style(label),
            caption = style(caption),
        )
    }
}

/**
 * [size], but never rendered larger than [size] at [maxScale] times its unscaled size.
 * Goes through [density]'s own sp/dp conversion, so Android 14+ non-linear font scaling (where large
 * sizes grow less than the font scale) is respected: the cap only shrinks, never enlarges.
 * Example with linear scaling: 88 sp at 2.0x becomes 57.2 sp, which renders as 114.4 dp (1.3x).
 */
fun cappedFontSize(
    size: TextUnit,
    density: Density,
    maxScale: Float,
): TextUnit =
    with(density) {
        if (!size.isSp) return size
        val max = (size.value * maxScale).dp
        if (size.toDp() <= max) return size
        // The non-linear converter's inverse is not exact for large sizes, so search the sp value directly.
        var low = 0f
        var high = size.value
        repeat(CAP_SEARCH_STEPS) {
            val mid = (low + high) / 2
            if (mid.sp.toDp() <= max) low = mid else high = mid
        }
        low.sp
    }

private const val CAP_SEARCH_STEPS = 24

/** Applies the `clock-xl` font-scale cap (line height scales with it). */
fun PpsTypography.withClockCap(density: Density): PpsTypography =
    copy(
        clockXl =
            clockXl.copy(
                fontSize = cappedFontSize(clockXl.fontSize, density, CLOCK_XL_MAX_FONT_SCALE),
                lineHeight = cappedFontSize(clockXl.lineHeight, density, CLOCK_XL_MAX_FONT_SCALE),
            ),
    )

/**
 * Material 3 slots, each filled with one DESIGN.md style (no new sizes): display* = display,
 * headline* = headline, titleLarge = title, titleMedium / titleSmall / labelLarge / labelMedium = label,
 * bodyLarge / bodyMedium = body, bodySmall / labelSmall = caption. `clock-xl` and `button-wake`
 * have no Material slot; use `PpsTheme.typography`.
 */
fun PpsTypography.toMaterialTypography(): Typography =
    Typography(
        displayLarge = display,
        displayMedium = display,
        displaySmall = display,
        headlineLarge = headline,
        headlineMedium = headline,
        headlineSmall = headline,
        titleLarge = title,
        titleMedium = label,
        titleSmall = label,
        bodyLarge = body,
        bodyMedium = body,
        bodySmall = caption,
        labelLarge = label,
        labelMedium = label,
        labelSmall = caption,
    )
