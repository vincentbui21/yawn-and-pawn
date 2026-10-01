package com.yawnandpawn.app.ui.wake

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import com.yawnandpawn.app.ui.components.rememberReducedMotion
import com.yawnandpawn.app.ui.resources.Res
import com.yawnandpawn.app.ui.resources.success_day_in_a_row
import com.yawnandpawn.app.ui.resources.success_days_in_a_row
import com.yawnandpawn.app.ui.theme.PpsTheme
import org.jetbrains.compose.resources.stringResource
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

/**
 * The on-time celebration (owner decision 2026-09-28), one [progress] from 0 to 1 over 1.5 s, started once when the
 * Success screen opens, with one success haptic. With reduced motion it starts at 1 (the final state, no confetti).
 */
@Stable
class Celebration internal constructor(
    internal val progress: Animatable<Float, *>,
) {
    val value: Float get() = progress.value
    val running: Boolean get() = value < 1f
}

@Composable
internal fun rememberCelebration(): Celebration {
    val reduced = rememberReducedMotion()
    val haptics = LocalHapticFeedback.current
    val celebration = remember { Celebration(Animatable(if (reduced) 1f else 0f)) }
    LaunchedEffect(celebration) {
        haptics.performHapticFeedback(HapticFeedbackType.Confirm)
        if (!reduced) celebration.progress.animateTo(1f, tween(CELEBRATION_MILLIS, easing = LinearEasing))
    }
    return celebration
}

/**
 * The streak number and "days in a row" under it (owner decision 2026-09-28: the number is not repeated in a sentence).
 * The number counts up from n−1 to n with a small bounce at 20% of the celebration. TalkBack reads the final number.
 */
@Composable
internal fun StreakCount(
    streakDays: Int,
    celebration: Celebration,
) {
    val colors = PpsTheme.colors
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        val shown = if (celebration.value < COUNT_AT) streakDays - 1 else streakDays
        Text(
            text = shown.toString(),
            modifier =
                Modifier
                    .graphicsLayer {
                        val bounce = ((celebration.value - COUNT_AT) / BOUNCE_LENGTH).coerceIn(0f, 1f)
                        val scale = 1f + BOUNCE_HEIGHT * sin(PI * bounce).toFloat()
                        scaleX = scale
                        scaleY = scale
                    }.clearAndSetSemantics { contentDescription = streakDays.toString() },
            style = PpsTheme.typography.display,
            color = colors.accentText,
        )
        Text(
            text = stringResource(if (streakDays == 1) Res.string.success_day_in_a_row else Res.string.success_days_in_a_row),
            style = PpsTheme.typography.title,
            color = colors.text,
        )
    }
}

/**
 * A 1.5 s confetti burst from behind the streak number in brand and Sunrise colours (accent, accent-text, success,
 * sunrise-gradient-top): decorative only, drawn while the celebration runs, then gone. Hidden from TalkBack.
 */
@Composable
internal fun Confetti(
    celebration: Celebration,
    modifier: Modifier = Modifier,
) {
    if (!celebration.running) return
    val colors = PpsTheme.colors
    val palette = listOf(colors.accent, colors.accentText, colors.success, colors.gradientTop)
    val pieces = remember { confettiPieces(palette) }
    Canvas(modifier = modifier.fillMaxSize().clearAndSetSemantics { }) {
        val t = celebration.value
        val pieceSize = Size(PIECE_WIDTH.toPx(), PIECE_HEIGHT.toPx())
        pieces.forEach { piece ->
            val x = size.width * (piece.startX + piece.velocityX * t)
            val y = size.height * (ORIGIN_Y + piece.velocityY * t + GRAVITY * t * t)
            val alpha = if (t < FADE_FROM) 1f else (1f - (t - FADE_FROM) / (1f - FADE_FROM)).coerceIn(0f, 1f)
            rotate(piece.spin * t * FULL_TURN, pivot = Offset(x, y)) {
                drawRect(color = piece.color, topLeft = Offset(x, y), size = pieceSize, alpha = alpha)
            }
        }
    }
}

private class ConfettiPiece(
    val startX: Float,
    val velocityX: Float,
    val velocityY: Float,
    val spin: Float,
    val color: Color,
)

/** A fixed pattern (seeded), so every screenshot of a moment of the burst is the same. */
private fun confettiPieces(palette: List<Color>): List<ConfettiPiece> {
    val random = Random(CONFETTI_SEED)
    return List(PIECES) { index ->
        ConfettiPiece(
            startX = CENTRE + (random.nextFloat() - CENTRE) * SPREAD_START,
            velocityX = (random.nextFloat() - CENTRE) * SPREAD_X,
            velocityY = -(LIFT_MIN + random.nextFloat() * LIFT_RANGE),
            spin = random.nextFloat() * 2f - 1f,
            color = palette[index % palette.size],
        )
    }
}

private const val CELEBRATION_MILLIS = 1_500
private const val COUNT_AT = 0.2f
private const val BOUNCE_LENGTH = 0.25f
private const val BOUNCE_HEIGHT = 0.2f
private const val PIECES = 48
private const val CONFETTI_SEED = 28
private const val CENTRE = 0.5f
private const val SPREAD_START = 0.3f
private const val SPREAD_X = 1.1f
private const val LIFT_MIN = 0.35f
private const val LIFT_RANGE = 0.35f
private const val GRAVITY = 0.9f
private const val ORIGIN_Y = 0.35f
private const val FADE_FROM = 0.7f
private const val FULL_TURN = 720f
private val PIECE_WIDTH = 6.dp
private val PIECE_HEIGHT = 10.dp
