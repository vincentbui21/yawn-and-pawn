package com.yawnandpawn.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalDensity

/** App-screen theme choice (Settings: System / Light / Dark). */
enum class PpsThemeMode { System, Light, Dark }

/** The three DESIGN.md token sets. */
enum class PpsColorSet { Light, Dark, Sunrise }

/** Wake screens always use Sunrise; otherwise [mode] decides, with System following the device. Pure. */
fun resolveColorSet(
    mode: PpsThemeMode,
    wake: Boolean,
    systemInDarkTheme: Boolean,
): PpsColorSet =
    when {
        wake -> PpsColorSet.Sunrise
        mode == PpsThemeMode.Light -> PpsColorSet.Light
        mode == PpsThemeMode.Dark -> PpsColorSet.Dark
        systemInDarkTheme -> PpsColorSet.Dark
        else -> PpsColorSet.Light
    }

fun PpsColorSet.colors(): PpsColors =
    when (this) {
        PpsColorSet.Light -> LightPpsColors
        PpsColorSet.Dark -> DarkPpsColors
        PpsColorSet.Sunrise -> SunrisePpsColors
    }

// No defaults: reading PpsTheme.* outside PpsTheme is a bug (it would silently give uncapped Light values).
private val LocalPpsColors = staticCompositionLocalOf<PpsColors> { notProvided() }
private val LocalPpsColorSet = staticCompositionLocalOf<PpsColorSet> { notProvided() }
private val LocalPpsTypography = staticCompositionLocalOf<PpsTypography> { notProvided() }
private val LocalPpsShapes = staticCompositionLocalOf<PpsShapes> { notProvided() }
private val LocalPpsSpacing = staticCompositionLocalOf<PpsSpacing> { notProvided() }

private fun notProvided(): Nothing = error("PpsTheme not provided: wrap the composable in PpsTheme { }")

/**
 * The only theme of the app (AD-10): one Material 3 `MaterialTheme` fed by the Light, Dark or
 * Sunrise token set. Dynamic colour is never used. `wake = true` (ringing, snooze confirm, checks,
 * success, snoozed) always gives Sunrise, whatever [mode] and the system setting say.
 */
@Composable
fun PpsTheme(
    mode: PpsThemeMode = PpsThemeMode.System,
    wake: Boolean = false,
    content: @Composable () -> Unit,
) {
    val colorSet = resolveColorSet(mode, wake, isSystemInDarkTheme())
    val colors = colorSet.colors()
    val fontFamily = geistFontFamily()
    val density = LocalDensity.current
    val typography = remember(fontFamily, density) { ppsTypography(fontFamily).withClockCap(density) }
    val shapes = remember { PpsShapes() }
    CompositionLocalProvider(
        LocalPpsColors provides colors,
        LocalPpsColorSet provides colorSet,
        LocalPpsTypography provides typography,
        LocalPpsShapes provides shapes,
        LocalPpsSpacing provides PpsSpacing(),
    ) {
        MaterialTheme(
            colorScheme = remember(colors) { colors.toColorScheme() },
            typography = remember(typography) { typography.toMaterialTypography() },
            shapes = remember(shapes) { shapes.toMaterialShapes() },
            content = content,
        )
    }
}

/** Token access for roles Material 3 has no slot for (`accentText`, `snoozed`, spacing, targets, ...). */
object PpsTheme {
    val colors: PpsColors
        @Composable @ReadOnlyComposable
        get() = LocalPpsColors.current

    val colorSet: PpsColorSet
        @Composable @ReadOnlyComposable
        get() = LocalPpsColorSet.current

    val typography: PpsTypography
        @Composable @ReadOnlyComposable
        get() = LocalPpsTypography.current

    val shapes: PpsShapes
        @Composable @ReadOnlyComposable
        get() = LocalPpsShapes.current

    val spacing: PpsSpacing
        @Composable @ReadOnlyComposable
        get() = LocalPpsSpacing.current
}
