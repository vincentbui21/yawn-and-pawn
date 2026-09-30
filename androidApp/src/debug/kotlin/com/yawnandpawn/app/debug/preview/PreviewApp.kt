package com.yawnandpawn.app.debug.preview

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Density
import com.yawnandpawn.app.ui.components.GroupCard
import com.yawnandpawn.app.ui.components.GroupDivider
import com.yawnandpawn.app.ui.components.PpsBackground
import com.yawnandpawn.app.ui.components.PpsSegmentedControl
import com.yawnandpawn.app.ui.components.SwitchRow
import com.yawnandpawn.app.ui.format.is24HourClock
import com.yawnandpawn.app.ui.shell.AppTab
import com.yawnandpawn.app.ui.theme.PpsTheme
import com.yawnandpawn.app.ui.theme.PpsThemeMode

/**
 * [content] as the preview shows it: [mode] (Light or Dark; wake screens switch to Sunrise themselves) and, with
 * [largeFont], a 200% font scale, whatever the phone's own settings.
 */
@Composable
fun PreviewFrame(
    mode: PpsThemeMode,
    largeFont: Boolean,
    content: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    val scaled = if (largeFont) Density(density.density, LARGE_FONT_SCALE) else density
    CompositionLocalProvider(LocalDensity provides scaled) {
        PpsTheme(mode = mode, content = content)
    }
}

/** The tap-through entries at the top of the menu (interactive, fake state). */
private enum class Flow(
    val round: Int,
    val title: String,
    val startTab: AppTab = AppTab.Alarms,
    val startInSession: Boolean = false,
) {
    App(1, "Tap through the app: Home, editor and its sub-screens, test alarm"),
    Session(1, "Tap through a morning: Back to alarm, Ringing, Check, Success", startInSession = true),
    Progress(2, "Tap through Progress: chart, calendar, day detail, purchase history", startTab = AppTab.Progress),
    Settings(2, "Tap through Settings: sub-screens, checklist, payments, delete dialog", startTab = AppTab.Settings),
}

/**
 * Debug-only design preview (spec-design-preview-whole-app): a menu grouped by round and screen listing every state,
 * with Light / Dark and 200% font toggles, plus tap-through flows. Menu labels are developer text, not app copy.
 */
@Composable
fun PreviewApp() {
    var dark by rememberSaveable { mutableBooleanSaveable(false) }
    var largeFont by rememberSaveable { mutableBooleanSaveable(false) }
    var openItem by rememberSaveable { mutableNullableString() }
    var openFlow by rememberSaveable { mutableNullableString() }
    val mode = if (dark) PpsThemeMode.Dark else PpsThemeMode.Light

    PreviewFrame(mode = mode, largeFont = largeFont) {
        val is24 = is24HourClock()
        val item = PreviewCatalog.items.firstOrNull { it.id == openItem }
        val flow = Flow.entries.firstOrNull { it.name == openFlow }
        when {
            flow != null -> {
                TapThrough(is24Hour = is24, onExit = { openFlow = null }, startInSession = flow.startInSession, startTab = flow.startTab)
            }

            item != null -> {
                item.render(is24)
                // Registered after the screen, so it wins over a confirm sheet's own Back: on wake screens Back returns
                // to this menu in the preview only (a real alarm ignores Back; design preview feedback item 6).
                BackHandler { openItem = null }
            }

            else -> {
                PreviewMenu(
                    dark = dark,
                    largeFont = largeFont,
                    onDark = { dark = it },
                    onLargeFont = { largeFont = it },
                    onOpenItem = { openItem = it.id },
                    onOpenFlow = { openFlow = it.name },
                )
            }
        }
    }
}

@Composable
private fun PreviewMenu(
    dark: Boolean,
    largeFont: Boolean,
    onDark: (Boolean) -> Unit,
    onLargeFont: (Boolean) -> Unit,
    onOpenItem: (PreviewItem) -> Unit,
    onOpenFlow: (Flow) -> Unit,
) {
    val colors = PpsTheme.colors
    val spacing = PpsTheme.spacing
    val grouped = PreviewCatalog.items.groupBy { it.round to it.group }
    PpsBackground {
        LazyColumn(
            modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars),
            contentPadding = PaddingValues(horizontal = spacing.screenMargin, vertical = spacing.space4),
            verticalArrangement = Arrangement.spacedBy(spacing.space3),
        ) {
            item {
                Column {
                    Text(
                        text = "Yawn & Pawn Preview",
                        modifier = Modifier.semantics { heading() },
                        style = PpsTheme.typography.headline,
                        color = colors.text,
                    )
                    Text(
                        text = "Fake data only. Nothing is saved, scheduled, played or charged. Back returns here.",
                        style = PpsTheme.typography.caption,
                        color = colors.textSecondary,
                    )
                }
            }
            item {
                GroupCard {
                    PpsSegmentedControl(
                        options = listOf(false, true),
                        selected = dark,
                        label = { if (it) "Dark" else "Light" },
                        onSelect = onDark,
                        modifier = Modifier.padding(spacing.cardPadding),
                    )
                    GroupDivider()
                    SwitchRow(label = "200% font size", checked = largeFont, onCheckedChange = onLargeFont)
                }
            }
            ROUNDS.forEach { (round, heading) ->
                item(key = "round$round") { MenuHeader(heading) }
                val flows = Flow.entries.filter { it.round == round }
                if (flows.isNotEmpty()) {
                    item(key = "flows$round") { MenuCard(title = "Tap through", rows = flows.map { it.title to { onOpenFlow(it) } }) }
                }
                grouped.filterKeys { it.first == round }.forEach { (key, items) ->
                    item(key = "$round/${key.second}") {
                        MenuCard(
                            title = key.second,
                            rows = items.map { item -> (if (item.wake) "${item.title} (Sunrise)" else item.title) to { onOpenItem(item) } },
                        )
                    }
                }
            }
            item { MenuHeader("Round 3 · Setup flows (later)") }
            item { Column(modifier = Modifier.windowInsetsPadding(WindowInsets.navigationBars)) {} }
        }
    }
}

/** One screen's states as a card of rows (the grouped-card pattern the app uses). */
@Composable
private fun MenuCard(
    title: String,
    rows: List<Pair<String, () -> Unit>>,
) {
    GroupCard(title = title) {
        rows.forEachIndexed { index, (text, onClick) ->
            if (index > 0) GroupDivider()
            MenuRow(title = text, onClick = onClick)
        }
    }
}

@Composable
private fun MenuHeader(text: String) {
    Text(
        text = text,
        modifier = Modifier.padding(top = PpsTheme.spacing.space4).semantics { heading() },
        style = PpsTheme.typography.title,
        color = PpsTheme.colors.text,
    )
}

@Composable
private fun MenuRow(
    title: String,
    onClick: () -> Unit,
) {
    Text(
        text = title,
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = PpsTheme.spacing.targetMin)
                .clickable(role = Role.Button, onClick = onClick)
                .padding(horizontal = PpsTheme.spacing.cardPadding, vertical = PpsTheme.spacing.space3),
        style = PpsTheme.typography.body,
        color = PpsTheme.colors.text,
    )
}

private fun mutableBooleanSaveable(value: Boolean) = androidx.compose.runtime.mutableStateOf(value)

private fun mutableNullableString() = androidx.compose.runtime.mutableStateOf<String?>(null)

private const val LARGE_FONT_SCALE = 2f

/** The rounds the menu shows, with their headings. */
private val ROUNDS = listOf(1 to "Round 1 · The daily loop", 2 to "Round 2 · Progress and settings")
