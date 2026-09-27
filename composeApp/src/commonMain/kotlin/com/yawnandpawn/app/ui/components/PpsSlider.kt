package com.yawnandpawn.app.ui.components

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import com.yawnandpawn.app.ui.theme.PpsTheme
import kotlin.math.roundToInt

/**
 * `slider` for a percentage in steps of [stepPercent]: title and current value ([valueText], "80%") above an
 * accent / `outline` Material 3 slider. TalkBack reads [title] and announces [valueText] on every change.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PercentSlider(
    title: String,
    valueText: String,
    percent: Int,
    onPercentChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    stepPercent: Int = PERCENT_STEP,
) {
    val colors = PpsTheme.colors
    val sliderColors =
        SliderDefaults.colors(
            thumbColor = colors.accent,
            activeTrackColor = colors.accent,
            // No tick marks: 21 stops would add visual noise and extra colour pairs.
            activeTickColor = Color.Transparent,
            inactiveTrackColor = colors.outline,
            inactiveTickColor = Color.Transparent,
        )
    val interactionSource = remember { MutableInteractionSource() }
    Column(modifier = modifier.fillMaxWidth()) {
        // The slider carries the label and value for TalkBack; this row is visual only.
        Row(modifier = Modifier.fillMaxWidth().clearAndSetSemantics { }, verticalAlignment = Alignment.CenterVertically) {
            Text(text = title, modifier = Modifier.weight(1f), style = PpsTheme.typography.body, color = colors.text)
            Text(text = valueText, style = PpsTheme.typography.label, color = colors.textSecondary)
        }
        Slider(
            value = percent.toFloat(),
            onValueChange = { onPercentChange(snap(it, stepPercent)) },
            modifier =
                Modifier.fillMaxWidth().semantics {
                    contentDescription = title
                    stateDescription = valueText
                },
            valueRange = 0f..MAX_PERCENT.toFloat(),
            steps = MAX_PERCENT / stepPercent - 1,
            colors = sliderColors,
            interactionSource = interactionSource,
            // A 48 dp tall thumb makes the whole slider a 48 dp touch target (the Material thumb is 44 dp).
            thumb = {
                SliderDefaults.Thumb(
                    interactionSource = interactionSource,
                    colors = sliderColors,
                    thumbSize = DpSize(THUMB_WIDTH, PpsTheme.spacing.targetMin),
                )
            },
        )
    }
}

private fun snap(
    value: Float,
    step: Int,
): Int = ((value / step).roundToInt() * step).coerceIn(0, MAX_PERCENT)

private const val MAX_PERCENT = 100

/** Material 3 thumb width. */
private val THUMB_WIDTH = 4.dp

/** EXPERIENCE.md `slider`: volume levels move in 5% steps. */
const val PERCENT_STEP = 5
