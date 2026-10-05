package com.yawnandpawn.app.ui.wake

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.traversalIndex
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.TextUnit
import com.yawnandpawn.app.ui.format.formatClockTime
import com.yawnandpawn.app.ui.format.formatLongDate
import com.yawnandpawn.app.ui.format.formatMoney
import com.yawnandpawn.app.ui.resources.Res
import com.yawnandpawn.app.ui.resources.wake_im_up
import com.yawnandpawn.app.ui.resources.wake_session_line
import com.yawnandpawn.app.ui.theme.PpsTheme
import org.jetbrains.compose.resources.stringResource

/**
 * Ringing, always Sunrise: label, `clock-xl` (on one line: on a narrow screen it shrinks to fit, never below `display`)
 * and date in the top 40% (on the optional sunrise gradient), the flat thumb
 * zone with the session line, "I'm up" (72 dp, the largest element, always enabled) and `button-snooze` 16 dp below.
 * TalkBack reads the clock first, then "I'm up". The confirm sheet and payment snackbars sit over it.
 */
@Composable
fun RingingScreen(
    state: RingingUiState,
    is24Hour: Boolean,
    onIntent: (WakeIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    WakeSurface(
        modifier = modifier,
        overlay = { backdrop ->
            state.sheet?.let { sheet ->
                SnoozeConfirmSheet(
                    sheet = sheet,
                    onUpper = { onIntent(WakeIntent.SheetUpperClicked) },
                    onDismiss = { onIntent(WakeIntent.SheetDismissed) },
                    backdrop = backdrop,
                )
            }
        },
    ) {
        val colors = PpsTheme.colors
        val spacing = PpsTheme.spacing
        // The sunrise gradient (WakeSurface) covers the top 40% only, behind label, clock and date; no accent on it.
        Column(modifier = Modifier.fillMaxSize().wakeContentPadding()) {
            Column(
                modifier = Modifier.fillMaxWidth().weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                state.label?.let { label ->
                    Text(text = label, style = PpsTheme.typography.title, color = colors.text, textAlign = TextAlign.Center)
                }
                RingingClock(time = formatClockTime(state.time, is24Hour))
                Text(text = formatLongDate(state.date), style = PpsTheme.typography.body, color = colors.textSecondary)
                state.note?.let { WakeNoteView(note = it, modifier = Modifier.padding(top = spacing.space4)) }
            }
            Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(spacing.space4)) {
                state.message?.let { WakeSnackbar(message = it) }
                state.sessionLine?.let { line ->
                    Text(
                        text = stringResource(Res.string.wake_session_line, line.snoozeNumber, line.maxSnoozes, formatMoney(line.paid)),
                        modifier = Modifier.fillMaxWidth(),
                        style = PpsTheme.typography.body,
                        color = colors.textSecondary,
                        textAlign = TextAlign.Center,
                    )
                }
                WakePrimaryButton(
                    text = stringResource(Res.string.wake_im_up),
                    onClick = { onIntent(WakeIntent.ImUpClicked) },
                    // Semantics only: TalkBack goes from the clock straight to "I'm up" (EXPERIENCE.md Accessibility Floor).
                    modifier = Modifier.semantics { traversalIndex = IM_UP_TRAVERSAL_INDEX },
                    pulse = state.sheet == null,
                )
                SnoozeButton(offer = state.snooze, onClick = { onIntent(WakeIntent.SnoozeClicked) })
            }
        }
    }
}

/**
 * The `clock-xl` time on one line (device test round 1): where the full time ("6:15 AM") is wider than the screen (a
 * 360 dp phone at a large font scale) it is drawn smaller, just enough to fit, never below `display`; where it fits it
 * keeps `clock-xl` exactly. TalkBack reads it first, as the full time.
 */
@Composable
private fun RingingClock(time: String) {
    val clock = PpsTheme.typography.clockXl
    val smallest = PpsTheme.typography.display.fontSize
    val measurer = rememberTextMeasurer()
    BoxWithConstraints {
        val maxWidth = constraints.maxWidth
        val style =
            remember(time, clock, smallest, maxWidth) {
                fitOnOneLine(time, clock, smallest, maxWidth) { candidate ->
                    measurer.measure(time, candidate, maxLines = 1, softWrap = false).size.width
                }
            }
        Text(
            text = time,
            modifier =
                Modifier.semantics {
                    traversalIndex = -1f
                    contentDescription = time
                },
            style = style,
            color = PpsTheme.colors.text,
            maxLines = 1,
            softWrap = false,
        )
    }
}

/**
 * [style], or a smaller copy whose [text] is narrower than [maxWidth] px ([widthOf] measures one line), never below
 * [smallest]. The width grows with the font size, so the first guess scales it down in proportion; steps of
 * [FIT_STEP] then absorb rounding.
 */
private fun fitOnOneLine(
    text: String,
    style: TextStyle,
    smallest: TextUnit,
    maxWidth: Int,
    widthOf: (TextStyle) -> Int,
): TextStyle {
    val width = widthOf(style)
    if (width <= maxWidth || maxWidth <= 1 || text.isEmpty()) return style
    // One pixel of room: the measured width is rounded up, the laid-out one may be a fraction wider.
    val room = maxWidth - 1
    val floor = smallest.value.coerceAtMost(style.fontSize.value)
    var size = (style.fontSize.value * room / width).coerceAtLeast(floor)
    var fitted = style.copy(fontSize = style.fontSize * (size / style.fontSize.value))
    while (widthOf(fitted) > room && size > floor) {
        size = (size * FIT_STEP).coerceAtLeast(floor)
        fitted = style.copy(fontSize = style.fontSize * (size / style.fontSize.value))
    }
    return fitted
}

/** Each correction step of [fitOnOneLine] shrinks the size by 2%. */
private const val FIT_STEP = 0.98f

/** TalkBack order on Ringing: the clock (traversal index -1) first, then "I'm up", then the rest in reading order. */
private const val IM_UP_TRAVERSAL_INDEX: Float = -0.5f
