package com.yawnandpawn.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.yawnandpawn.app.ui.theme.PpsTheme

/**
 * `pill-save` (owner decision 2026-09-27, Samsung style): a floating `glass-bar` pill with "Cancel" and "Save" halves
 * split by a hairline. Sits [PILL_BOTTOM_GAP] above the navigation bar and above the keyboard (`imePadding`), over the
 * screen content that [backdrop] records, which it blurs on Android 12+. Each half is a ≥ 48 dp button.
 */
@Composable
fun SaveCancelPill(
    cancelText: String,
    saveText: String,
    onCancel: () -> Unit,
    onSave: () -> Unit,
    modifier: Modifier = Modifier,
    saveEnabled: Boolean = true,
    backdrop: GlassBackdrop? = null,
) {
    val colors = PpsTheme.colors
    val spacing = PpsTheme.spacing
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .imePadding()
                .navigationBarsPadding()
                .padding(start = spacing.screenMargin, end = spacing.screenMargin, bottom = PILL_BOTTOM_GAP)
                .glass(PpsTheme.shapes.full, strong = true, backdrop = backdrop)
                .height(IntrinsicSize.Min),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PillHalf(text = cancelText, onClick = onCancel, modifier = Modifier.weight(1f))
        Box(
            modifier =
                Modifier
                    .width(spacing.hairline)
                    .fillMaxHeight()
                    .padding(vertical = spacing.space3)
                    .background(colors.glassEdge),
        )
        PillHalf(text = saveText, onClick = onSave, modifier = Modifier.weight(1f), emphasized = true, enabled = saveEnabled)
    }
}

@Composable
private fun PillHalf(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    emphasized: Boolean = false,
    enabled: Boolean = true,
) {
    val colors = PpsTheme.colors
    Box(
        modifier =
            modifier
                .heightIn(min = PILL_HEIGHT)
                .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
                .padding(horizontal = PpsTheme.spacing.space4, vertical = PpsTheme.spacing.space2),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = PpsTheme.typography.label,
            fontWeight = if (emphasized) FontWeight.Bold else null,
            color =
                when {
                    !enabled -> colors.textSecondary
                    emphasized -> colors.accentText
                    else -> colors.text
                },
            textAlign = TextAlign.Center,
        )
    }
}

/** Room the scrolling content leaves at its end so its last row can scroll clear of the floating pill. */
val PILL_CLEARANCE: Dp = 96.dp

/** DESIGN.md `pill-save.height`. */
private val PILL_HEIGHT = 56.dp

/** The pill floats 16 dp above the navigation bar (or the keyboard). */
private val PILL_BOTTOM_GAP = 16.dp

/**
 * A sub-screen (progressive disclosure, owner decision 2026-09-27): the background gradient, a `top-app-bar` with a back
 * arrow and [title], and [content] as a scrolling column of `card-group`s, 12 dp apart, inside the navigation bar.
 */
@Composable
fun SubScreen(
    title: String,
    backContentDescription: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    PpsBackground(modifier = modifier) {
        Column(modifier = Modifier.fillMaxSize()) {
            PpsTopAppBar(title = title, backContentDescription = backContentDescription, onBack = onBack)
            ScreenColumn(content = content)
        }
    }
}

/** The scrolling column of cards of an app screen: `screen-margin` sides, 12 dp between cards, clear of the nav bar. */
@Composable
fun ScreenColumn(
    modifier: Modifier = Modifier,
    bottomClearance: Dp = PpsTheme.spacing.space6,
    content: @Composable ColumnScope.() -> Unit,
) {
    val spacing = PpsTheme.spacing
    Column(
        modifier =
            modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(start = spacing.screenMargin, end = spacing.screenMargin, top = spacing.space2, bottom = bottomClearance),
        verticalArrangement = Arrangement.spacedBy(spacing.space3),
        content = content,
    )
}
