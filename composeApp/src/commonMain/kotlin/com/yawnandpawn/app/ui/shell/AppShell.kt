package com.yawnandpawn.app.ui.shell

import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.yawnandpawn.app.ui.components.GlassBackdrop
import com.yawnandpawn.app.ui.components.LocalNavBarClearance
import com.yawnandpawn.app.ui.components.glass
import com.yawnandpawn.app.ui.components.glassSource
import com.yawnandpawn.app.ui.components.rememberGlassBackdrop
import com.yawnandpawn.app.ui.resources.Res
import com.yawnandpawn.app.ui.resources.alarms_add_alarm
import com.yawnandpawn.app.ui.resources.symbol_add
import com.yawnandpawn.app.ui.theme.PpsTheme
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

/**
 * The app root (owner decision 2026-10-01, feedback item 26): [content] for the [selected] tab, full height, with the
 * floating `nav-bar` capsule over its bottom: Alarms · Progress · (+) · Settings · You. The centre "+" ([onAdd]) starts
 * a new alarm from any tab (it replaces Home's FAB). The capsule is `glass-strong` and blurs the content under it on
 * Android 12+. Screens read [LocalNavBarClearance] so nothing hides behind it. Hidden while [showNavBar] is false (the
 * session lock shows only `panel-session-in-progress`).
 */
@Composable
fun AppShell(
    selected: AppTab,
    onSelect: (AppTab) -> Unit,
    modifier: Modifier = Modifier,
    onAdd: () -> Unit = {},
    showNavBar: Boolean = true,
    content: @Composable () -> Unit,
) {
    val backdrop = rememberGlassBackdrop()
    val density = LocalDensity.current
    val navInset = with(density) { WindowInsets.navigationBars.getBottom(density).toDp() }
    val clearance = if (showNavBar) navInset + CAPSULE_HEIGHT + CAPSULE_GAP + CONTENT_GAP else 0.dp
    Box(modifier = modifier.fillMaxSize().background(PpsTheme.colors.bg)) {
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .then(if (showNavBar) Modifier.consumeWindowInsets(WindowInsets.navigationBars) else Modifier)
                    .glassSource(backdrop),
        ) {
            CompositionLocalProvider(LocalNavBarClearance provides clearance) { content() }
        }
        if (showNavBar) {
            FloatingNavBar(
                selected = selected,
                onSelect = onSelect,
                onAdd = onAdd,
                modifier = Modifier.align(Alignment.BottomCenter),
                backdrop = backdrop,
            )
        }
    }
}

/**
 * `nav-bar` (owner decision 2026-10-01): a floating `glass-strong` capsule (`rounded.full`, hairline edge, blurred
 * backdrop on Android 12+), `screen-margin` from the sides and 12 dp above the navigation bar. Four tabs with an icon
 * and a short label (the selected one in `accent-text` with a filled icon, the others `text-secondary`; the change
 * animates, instant with reduced motion) around the raised 56 dp accent "+" ("Add alarm"). At large font scales only
 * the selected tab shows its label, in a wider slot, so no label is cut.
 */
@Composable
internal fun FloatingNavBar(
    selected: AppTab,
    onSelect: (AppTab) -> Unit,
    onAdd: () -> Unit,
    modifier: Modifier = Modifier,
    backdrop: GlassBackdrop? = null,
) {
    val spacing = PpsTheme.spacing
    val largeFont = LocalDensity.current.fontScale >= LARGE_FONT_SCALE
    val tabs = AppTab.entries
    Box(
        modifier =
            modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(start = spacing.screenMargin, end = spacing.screenMargin, bottom = CAPSULE_GAP),
        contentAlignment = Alignment.BottomCenter,
    ) {
        // The glass is a layer behind the row (not a clip on it), so the raised "+" can rise above the capsule.
        Box(modifier = Modifier.matchParentSize().glass(PpsTheme.shapes.full, strong = true, backdrop = backdrop))
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = CAPSULE_HEIGHT)
                    .padding(horizontal = spacing.space2),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            tabs.take(2).forEach { tab -> TabSlot(tab, tab == selected, largeFont, onSelect) }
            // The raised "+" keeps its own slot in the middle, whatever width the tabs take.
            AddButton(onAdd = onAdd, modifier = Modifier.offset(y = -ADD_RAISE))
            tabs.drop(2).forEach { tab -> TabSlot(tab, tab == selected, largeFont, onSelect) }
        }
    }
}

@Composable
private fun RowScope.TabSlot(
    tab: AppTab,
    isSelected: Boolean,
    largeFont: Boolean,
    onSelect: (AppTab) -> Unit,
) {
    val colors = PpsTheme.colors
    val tint by animateColorAsState(if (isSelected) colors.accentText else colors.textSecondary, label = "tab tint")
    val showLabel = !largeFont || isSelected
    val label = stringResource(tab.label)
    Column(
        modifier =
            Modifier
                .weight(if (largeFont && isSelected) SELECTED_WEIGHT else 1f)
                .heightIn(min = PpsTheme.spacing.targetMin)
                .clip(PpsTheme.shapes.full)
                .selectable(selected = isSelected, role = Role.Tab, onClick = { onSelect(tab) })
                .semantics { contentDescription = label }
                .padding(vertical = PpsTheme.spacing.space1),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Crossfade(targetState = isSelected, label = "tab icon") { filled ->
            Icon(
                painter = painterResource(if (filled) tab.selectedIcon else tab.icon),
                contentDescription = null,
                modifier = Modifier.size(ICON_SIZE),
                tint = tint,
            )
        }
        if (showLabel) {
            Text(
                text = label,
                // The slot carries the label for TalkBack (also when the text is hidden at large font scales).
                modifier = Modifier.clearAndSetSemantics { },
                style = PpsTheme.typography.caption,
                color = tint,
                textAlign = TextAlign.Center,
                maxLines = 1,
                softWrap = false,
            )
        }
    }
}

/** The raised accent "+": 56 dp, `accent` with the `on-accent` plus, TalkBack "Add alarm". */
@Composable
private fun AddButton(
    onAdd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = PpsTheme.colors
    val label = stringResource(Res.string.alarms_add_alarm)
    Box(
        modifier =
            modifier
                .size(ADD_SIZE)
                .clip(PpsTheme.shapes.full)
                .background(colors.accent)
                .clickable(role = Role.Button, onClick = onAdd)
                .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Icon(painter = painterResource(Res.drawable.symbol_add), contentDescription = null, tint = colors.onAccent)
    }
}

/** The capsule's height at 100% (it grows with the font). */
private val CAPSULE_HEIGHT: Dp = 64.dp

/** The capsule floats this far above the navigation bar. */
private val CAPSULE_GAP: Dp = 12.dp

/** Room between the last row of content and the capsule. */
private val CONTENT_GAP: Dp = 16.dp

private val ADD_SIZE: Dp = 56.dp

/** The "+" rises this far above the capsule's centre. */
private val ADD_RAISE: Dp = 14.dp

private val ICON_SIZE: Dp = 24.dp

/** From this font scale only the selected tab shows its label. */
private const val LARGE_FONT_SCALE = 1.5f

/** The selected tab's slot at large font scales, so its label fits. */
private const val SELECTED_WEIGHT = 2.2f
