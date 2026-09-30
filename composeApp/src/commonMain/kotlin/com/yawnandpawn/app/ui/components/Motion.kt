package com.yawnandpawn.app.ui.components

import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.ui.unit.IntOffset

/**
 * Screens and sub-screens slide (owner decision 2026-09-27, EXPERIENCE.md Motion): [forward] (deeper) slides in from the
 * end while the outgoing screen moves a quarter of the width; back slides the other way. 250 ms emphasized easing; with
 * the animator duration scale at 0 it is an instant change.
 */
fun subScreenTransition(forward: Boolean): ContentTransform {
    val spec = tween<IntOffset>(durationMillis = TRANSITION_MILLIS, easing = EmphasizedEasing)
    val fade = tween<Float>(durationMillis = TRANSITION_MILLIS, easing = EmphasizedEasing)
    return if (forward) {
        (slideInHorizontally(spec) { it } + fadeIn(fade)) togetherWith (slideOutHorizontally(spec) { -it / PARALLAX } + fadeOut(fade))
    } else {
        (slideInHorizontally(spec) { -it / PARALLAX } + fadeIn(fade)) togetherWith (slideOutHorizontally(spec) { it } + fadeOut(fade))
    }
}

private const val TRANSITION_MILLIS = 250

/** The outgoing screen moves a quarter of the way (a gentle parallax), the incoming one the whole width. */
private const val PARALLAX = 4

/** Material 3 emphasized easing (EXPERIENCE.md standard transition). */
private val EmphasizedEasing = CubicBezierEasing(0.2f, 0f, 0f, 1f)
