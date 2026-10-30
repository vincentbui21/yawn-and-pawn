package com.yawnandpawn.app.ui.components

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp

/**
 * The entry animation's switch (owner notes 2026-10-01, feedback item 23): `false` on the first frame of a screen, then
 * `true`, so every entrance animates from its start to its final state once. With reduced motion it is `true` at once.
 */
@Composable
internal fun rememberEntered(): Boolean {
    val reduced = rememberReducedMotion()
    var entered by remember { mutableStateOf(reduced) }
    LaunchedEffect(Unit) { entered = true }
    return entered
}

/**
 * [rememberEntered] that stays `true` once the screen has entered, across a screen pushed over it (saved state): back on
 * it, or for content composed later (a card added or scrolled into view), the final state shows at once.
 */
@Composable
internal fun rememberEnteredOnce(): Boolean {
    var enteredBefore by rememberSaveable { mutableStateOf(false) }
    val entered = rememberEntered() || enteredBefore
    LaunchedEffect(entered) { if (entered) enteredBefore = true }
    return entered
}

/** A 0 to 1 entrance progress, starting [delayMillis] after entry and lasting [durationMillis]; instant with reduced motion. */
@Composable
internal fun entranceProgress(
    entered: Boolean,
    delayMillis: Int,
    durationMillis: Int = ENTRANCE_MILLIS,
): Float {
    val reduced = rememberReducedMotion()
    val spec: AnimationSpec<Float> = if (reduced) snap() else tween(durationMillis = durationMillis, delayMillis = delayMillis)
    val progress by animateFloatAsState(targetValue = if (entered) 1f else 0f, animationSpec = spec, label = "entrance")
    return progress
}

/** Cards and tiles fade in and rise 16 dp, the [order]-th one [CARD_STAGGER_MILLIS] after the one before. */
@Composable
internal fun Modifier.entrance(
    entered: Boolean,
    order: Int,
): Modifier {
    val progress = entranceProgress(entered, delayMillis = order * CARD_STAGGER_MILLIS)
    val rise = with(LocalDensity.current) { RISE.toPx() }
    return graphicsLayer {
        alpha = progress
        translationY = (1f - progress) * rise
    }
}

/** Standard entrance length. */
internal const val ENTRANCE_MILLIS = 300

/** Cards follow each other by this much. */
private const val CARD_STAGGER_MILLIS = 70

private val RISE = 16.dp
