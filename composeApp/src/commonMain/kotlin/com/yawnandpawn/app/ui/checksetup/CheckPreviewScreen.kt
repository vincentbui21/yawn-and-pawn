package com.yawnandpawn.app.ui.checksetup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.yawnandpawn.app.ui.components.DismissButton
import com.yawnandpawn.app.ui.components.glass
import com.yawnandpawn.app.ui.resources.Res
import com.yawnandpawn.app.ui.resources.check_preview_done
import com.yawnandpawn.app.ui.resources.check_try_it
import com.yawnandpawn.app.ui.resources.editor_back
import com.yawnandpawn.app.ui.resources.success_done
import com.yawnandpawn.app.ui.resources.symbol_check_circle_fill1
import com.yawnandpawn.app.ui.theme.PpsTheme
import com.yawnandpawn.app.ui.wake.CheckContentView
import com.yawnandpawn.app.ui.wake.WakeIntent
import com.yawnandpawn.app.ui.wake.WakePrimaryButton
import com.yawnandpawn.app.ui.wake.WakeSurface
import com.yawnandpawn.app.ui.wake.wakeContentPadding
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

/**
 * "Try it" (FR-PWK-12): a no-stakes preview of a check, in Sunrise like the real morning (the check components are the
 * wake ones), with no quiet-time ring and no snooze: nothing rings and nothing is paid. "Try it" as the heading with a
 * 48 dp close ("Back"), then the check itself ([CheckContentView], shared with the Check screen). Solved, it shows
 * "Nice. That's how it works." on glass and "Done" (64 dp) in the thumb zone.
 */
@Composable
fun CheckPreviewScreen(
    state: CheckPreviewUiState,
    onIntent: (WakeIntent) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    WakeSurface(modifier = modifier) {
        val colors = PpsTheme.colors
        val spacing = PpsTheme.spacing
        Column(modifier = Modifier.fillMaxSize().wakeContentPadding()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(Res.string.check_try_it),
                    modifier = Modifier.weight(1f).semantics { heading() },
                    style = PpsTheme.typography.headline,
                    color = colors.text,
                )
                DismissButton(label = stringResource(Res.string.editor_back), onClick = onClose, tint = colors.text)
            }
            if (state.done) {
                Column(modifier = Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.Center) { DoneCard() }
                WakePrimaryButton(
                    text = stringResource(Res.string.success_done),
                    onClick = { onIntent(WakeIntent.DoneClicked) },
                    hero = false,
                )
            } else {
                Column(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .padding(top = spacing.space4)
                            .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(spacing.space4),
                ) {
                    CheckContentView(content = state.content, onIntent = onIntent)
                }
            }
        }
    }
}

/** "Nice. That's how it works." on glass, with a check. */
@Composable
private fun DoneCard() {
    val colors = PpsTheme.colors
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .glass(PpsTheme.shapes.md)
                .padding(PpsTheme.spacing.space6)
                .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(PpsTheme.spacing.space3),
    ) {
        Icon(
            painter = painterResource(Res.drawable.symbol_check_circle_fill1),
            contentDescription = null,
            modifier = Modifier.size(DONE_ICON),
            // `text`, not `success`: success means "on time" only (DESIGN.md Colors).
            tint = colors.text,
        )
        Text(
            text = stringResource(Res.string.check_preview_done),
            style = PpsTheme.typography.headline,
            color = colors.text,
            textAlign = TextAlign.Center,
        )
    }
}

private val DONE_ICON = 48.dp
