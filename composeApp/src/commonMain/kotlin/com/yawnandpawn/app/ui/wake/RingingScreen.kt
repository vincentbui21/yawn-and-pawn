package com.yawnandpawn.app.ui.wake

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.traversalIndex
import androidx.compose.ui.text.style.TextAlign
import com.yawnandpawn.app.ui.format.formatClockTime
import com.yawnandpawn.app.ui.format.formatLongDate
import com.yawnandpawn.app.ui.format.formatMoney
import com.yawnandpawn.app.ui.resources.Res
import com.yawnandpawn.app.ui.resources.wake_im_up
import com.yawnandpawn.app.ui.resources.wake_session_line
import com.yawnandpawn.app.ui.theme.PpsTheme
import org.jetbrains.compose.resources.stringResource

/**
 * Ringing, always Sunrise: label, `clock-xl` and date in the top 40% (on the optional sunrise gradient), the flat thumb
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
    WakeSurface(modifier = modifier) {
        val colors = PpsTheme.colors
        val spacing = PpsTheme.spacing
        // The sunrise gradient: top 40% only, behind label, clock and date; nothing accent-coloured sits on it.
        colors.sunriseGradientTop?.let { top ->
            Box(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .fillMaxHeight(TOP_ZONE)
                        .background(Brush.verticalGradient(listOf(top, colors.bg))),
            )
        }
        Column(modifier = Modifier.fillMaxSize().wakeContentPadding()) {
            Column(
                modifier = Modifier.fillMaxWidth().weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                state.label?.let { label ->
                    Text(text = label, style = PpsTheme.typography.title, color = colors.text, textAlign = TextAlign.Center)
                }
                val time = formatClockTime(state.time, is24Hour)
                Text(
                    text = time,
                    modifier = Modifier.semantics { traversalIndex = -1f }.clearAndSetSemantics { contentDescription = time },
                    style = PpsTheme.typography.clockXl,
                    color = colors.text,
                    maxLines = 1,
                )
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
                WakePrimaryButton(text = stringResource(Res.string.wake_im_up), onClick = { onIntent(WakeIntent.ImUpClicked) })
                SnoozeButton(offer = state.snooze, onClick = { onIntent(WakeIntent.SnoozeClicked) })
            }
        }
        state.sheet?.let { sheet ->
            SnoozeConfirmSheet(
                sheet = sheet,
                onUpper = { onIntent(WakeIntent.SheetUpperClicked) },
                onDismiss = { onIntent(WakeIntent.SheetDismissed) },
            )
        }
    }
}

/** DESIGN.md: the gradient (and label, clock, date) live in the top 40%; the thumb zone is the bottom 40%. */
private const val TOP_ZONE = 0.4f
