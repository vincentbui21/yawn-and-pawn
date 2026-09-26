package com.yawnandpawn.app.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.unit.Dp

/** Spacing (4 dp grid) and touch-target tokens from DESIGN.md `spacing`. Read through `PpsTheme.spacing`. */
@Immutable
data class PpsSpacing(
    val space1: Dp = PpsTokens.Spacing.space1,
    val space2: Dp = PpsTokens.Spacing.space2,
    val space3: Dp = PpsTokens.Spacing.space3,
    val space4: Dp = PpsTokens.Spacing.space4,
    val space5: Dp = PpsTokens.Spacing.space5,
    val space6: Dp = PpsTokens.Spacing.space6,
    val space8: Dp = PpsTokens.Spacing.space8,
    val screenMargin: Dp = PpsTokens.Spacing.screenMargin,
    val sectionGap: Dp = PpsTokens.Spacing.sectionGap,
    val cardPadding: Dp = PpsTokens.Spacing.cardPadding,
    /** Minimum touch target everywhere. */
    val targetMin: Dp = PpsTokens.Spacing.targetMin,
    /** Every wake-screen action. */
    val targetWake: Dp = PpsTokens.Spacing.targetWake,
    /** "I'm up" and the House Hunt shutter. */
    val targetWakeHero: Dp = PpsTokens.Spacing.targetWakeHero,
    val ringStroke: Dp = PpsTokens.Spacing.ringStroke,
)
