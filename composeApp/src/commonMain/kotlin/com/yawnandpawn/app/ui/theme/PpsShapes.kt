package com.yawnandpawn.app.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.runtime.Immutable

/** The locked radius system (DESIGN.md `rounded`): 8 / 16 / 28 dp and full. Read through `PpsTheme.shapes`. */
@Immutable
data class PpsShapes(
    /** Chips, inputs, letter tiles, snackbars, small thumbnails. */
    val sm: RoundedCornerShape = RoundedCornerShape(PpsTokens.Rounded.sm),
    /** Cards, list items, check tiles, number-pad keys, viewfinder frame. */
    val md: RoundedCornerShape = RoundedCornerShape(PpsTokens.Rounded.md),
    /** Bottom sheets and dialogs. */
    val lg: RoundedCornerShape = RoundedCornerShape(PpsTokens.Rounded.lg),
    /** Primary and wake buttons, FAB, shutter, segmented control, badges. */
    val full: RoundedCornerShape = RoundedCornerShape(PpsTokens.Rounded.full),
)

/** Material 3 slots: extraSmall / small = sm, medium = md, large / extraLarge = lg. */
fun PpsShapes.toMaterialShapes(): Shapes =
    Shapes(
        extraSmall = sm,
        small = sm,
        medium = md,
        large = lg,
        extraLarge = lg,
    )
