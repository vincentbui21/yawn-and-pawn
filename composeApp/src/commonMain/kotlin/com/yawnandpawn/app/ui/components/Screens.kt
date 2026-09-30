package com.yawnandpawn.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.yawnandpawn.app.ui.resources.Res
import com.yawnandpawn.app.ui.resources.session_back_to_alarm
import com.yawnandpawn.app.ui.resources.session_in_progress_title
import com.yawnandpawn.app.ui.theme.PpsTheme
import org.jetbrains.compose.resources.stringResource

/**
 * How far a tab screen's scrolling content must end above the bottom of the screen so its last row sits above the
 * floating `nav-bar` capsule (0 when the bar is hidden, or outside the app shell). Includes the navigation bar inset.
 */
val LocalNavBarClearance = staticCompositionLocalOf { 0.dp }

/**
 * A bottom-navigation tab screen (Progress, Settings): the background gradient, [title] pinned top-left in `headline`
 * under the status bar (none on Progress, owner decision 2026-10-01: the tab names it), and [content] as a scrolling
 * column of `card-group`s below it (clipped under the title or the status bar, so no card text shows behind the status
 * bar). [overlay] draws over the screen (a `snackbar` at the bottom).
 */
@Composable
fun TabScreen(
    title: String?,
    modifier: Modifier = Modifier,
    overlay: @Composable () -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    PpsBackground(modifier = modifier) {
        Column(modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars)) {
            if (title != null) ScreenTitle(title = title)
            ScreenColumn(modifier = Modifier.weight(1f), content = content)
        }
        Box(modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = LocalNavBarClearance.current)) { overlay() }
    }
}

/** A tab screen's title in `headline`, a heading for TalkBack; it wraps at large font scales instead of clipping. */
@Composable
fun ScreenTitle(
    title: String,
    modifier: Modifier = Modifier,
) {
    val spacing = PpsTheme.spacing
    Text(
        text = title,
        modifier =
            modifier
                .fillMaxWidth()
                .padding(start = spacing.screenMargin, end = spacing.screenMargin, top = spacing.space4, bottom = spacing.space2)
                .semantics { heading() },
        style = PpsTheme.typography.headline,
        color = PpsTheme.colors.text,
    )
}

/**
 * `panel-session-in-progress`: during a session it replaces the whole app's content (Home, Settings): a glass card with
 * "Alarm in progress" in `headline` and one `button-filled` "Back to alarm".
 */
@Composable
fun SessionInProgressPanel(
    onBackToAlarm: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = PpsTheme.spacing
    Column(
        modifier =
            modifier
                .padding(horizontal = spacing.screenMargin)
                .fillMaxWidth()
                .glass(PpsTheme.shapes.md)
                .padding(spacing.cardPadding),
        verticalArrangement = Arrangement.spacedBy(spacing.space4),
    ) {
        Text(
            text = stringResource(Res.string.session_in_progress_title),
            modifier = Modifier.semantics { heading() },
            style = PpsTheme.typography.headline,
            color = PpsTheme.colors.text,
        )
        PpsFilledButton(
            text = stringResource(Res.string.session_back_to_alarm),
            onClick = onBackToAlarm,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * An app-screen `snackbar` shown from state (the screen's state clears it after 4 s): `inverse-surface`, `rounded.sm`,
 * [text] in `inverse-text`, above the navigation bar, announced politely by TalkBack.
 */
@Composable
fun AppSnackbar(
    text: String,
    modifier: Modifier = Modifier,
) {
    val colors = PpsTheme.colors
    val spacing = PpsTheme.spacing
    Text(
        text = text,
        modifier =
            modifier
                .navigationBarsPadding()
                .padding(horizontal = spacing.screenMargin, vertical = spacing.space4)
                .fillMaxWidth()
                .clip(PpsTheme.shapes.sm)
                .background(colors.inverseSurface)
                .semantics { liveRegion = LiveRegionMode.Polite }
                .padding(horizontal = spacing.space4, vertical = spacing.space3),
        style = PpsTheme.typography.body,
        color = colors.inverseText,
    )
}

/**
 * Body copy in a glass card (Payments & refunds, Problem with a charge?): each of [paragraphs] in `body` / `text`, 12 dp
 * apart, inside `card-padding`.
 */
@Composable
fun TextCard(
    paragraphs: List<String>,
    modifier: Modifier = Modifier,
    title: String? = null,
) {
    val spacing = PpsTheme.spacing
    GroupCard(modifier = modifier, title = title) {
        Column(modifier = Modifier.padding(spacing.cardPadding), verticalArrangement = Arrangement.spacedBy(spacing.space3)) {
            paragraphs.forEach { paragraph ->
                Text(text = paragraph, style = PpsTheme.typography.body, color = PpsTheme.colors.text)
            }
        }
    }
}
