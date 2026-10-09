package com.yawnandpawn.app.debug.preview

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Density
import com.yawnandpawn.app.android.AndroidMonotonicClock
import com.yawnandpawn.app.ui.components.DismissButton
import com.yawnandpawn.app.ui.components.GroupCard
import com.yawnandpawn.app.ui.components.GroupDivider
import com.yawnandpawn.app.ui.components.PpsBackground
import com.yawnandpawn.app.ui.components.PpsSegmentedControl
import com.yawnandpawn.app.ui.components.SwitchRow
import com.yawnandpawn.app.ui.format.is24HourClock
import com.yawnandpawn.app.ui.shell.AppTab
import com.yawnandpawn.app.ui.theme.PpsTheme
import com.yawnandpawn.app.ui.theme.PpsThemeMode
import com.yawnandpawn.app.ui.wake.LocalWakeClock

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
    // The confirm sheet's 500 ms guard reads the app's monotonic clock, as on the real wake screen (Story 4.13).
    CompositionLocalProvider(LocalDensity provides scaled, LocalWakeClock provides previewClock::elapsedMillis) {
        PpsTheme(mode = mode, content = content)
    }
}

private val previewClock = AndroidMonotonicClock()

/** Where a tap-through starts: the tabs, onboarding, or the editor on its Wake-up check or Motivation sub-screen. */
enum class FlowStart { Tabs, Onboarding, CheckPicker, Recordings }

/** The tap-through entries at the top of the menu (interactive, fake state). */
internal enum class Flow(
    val round: Int,
    val title: String,
    /** The deep-link id (`--es state <id>`). */
    val stateId: String,
    val startTab: AppTab = AppTab.Alarms,
    val startInSession: Boolean = false,
    val start: FlowStart = FlowStart.Tabs,
) {
    App(1, "Tap through the app: tabs, the + for a new alarm, editor, test alarm", "tap-app"),
    Session(1, "Tap through a morning: Back to alarm, Ringing, Check, Success", "tap-morning", startInSession = true),
    Progress(2, "Tap through Progress: ring, chart, calendar, day detail, purchase history", "tap-progress", startTab = AppTab.Progress),
    Settings(2, "Tap through Settings: sub-screens, reliability checklist", "tap-settings", startTab = AppTab.Settings),
    You(2, "Tap through You: purchase history, payments, delete dialog", "tap-you", startTab = AppTab.You),
    Onboarding(
        SETUP_ROUND,
        "Tap through onboarding: 8 steps, check setup, try it, test alarm, then Home",
        "tap-onboarding",
        start = FlowStart.Onboarding,
    ),
    Checks(
        SETUP_ROUND,
        "Tap through checks: picker, setup, try it, QR and House Hunt registration",
        "tap-checks",
        start = FlowStart.CheckPicker,
    ),
    Recordings(SETUP_ROUND, "Tap through recordings: record, save, play, delete", "tap-recordings", start = FlowStart.Recordings),
}

/**
 * Debug-only design preview (spec-design-preview-whole-app): a menu grouped by round and screen listing every state,
 * with Light / Dark and 200% font toggles, plus tap-through flows. Menu labels are developer text, not app copy.
 */
@Composable
fun PreviewApp(launch: PreviewLaunch = PreviewLaunch()) {
    var dark by rememberSaveable { mutableBooleanSaveable(launch.dark) }
    var largeFont by rememberSaveable { mutableBooleanSaveable(launch.largeFont) }
    // A deep link (`--es state <id>`) opens its item or tap-through directly; Back from it returns to the menu.
    var openItem by rememberSaveable { mutableNullableString(PreviewCatalog.items.firstOrNull { it.stateId == launch.state }?.id) }
    var openFlow by rememberSaveable { mutableNullableString(Flow.entries.firstOrNull { it.stateId == launch.state }?.name) }
    var query by rememberSaveable { mutableStateOf("") }
    val mode = if (dark) PpsThemeMode.Dark else PpsThemeMode.Light

    PreviewFrame(mode = mode, largeFont = largeFont) {
        val is24 = is24HourClock()
        val item = PreviewCatalog.items.firstOrNull { it.id == openItem }
        val flow = Flow.entries.firstOrNull { it.name == openFlow }
        when {
            flow != null -> {
                TapThrough(
                    is24Hour = is24,
                    onExit = { openFlow = null },
                    startInSession = flow.startInSession,
                    startTab = flow.startTab,
                    start = flow.start,
                )
            }

            item != null -> {
                item.render(is24)
                // Registered after the screen, so it wins over a confirm sheet's own Back: on wake screens Back returns
                // to this menu in the preview only (a real alarm ignores Back; design preview feedback item 6).
                BackHandler { openItem = null }
            }

            else -> {
                PreviewMenu(
                    query = query,
                    onQuery = { query = it },
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
    query: String,
    onQuery: (String) -> Unit,
    dark: Boolean,
    largeFont: Boolean,
    onDark: (Boolean) -> Unit,
    onLargeFont: (Boolean) -> Unit,
    onOpenItem: (PreviewItem) -> Unit,
    onOpenFlow: (Flow) -> Unit,
) {
    val colors = PpsTheme.colors
    val spacing = PpsTheme.spacing
    val grouped = PreviewCatalog.items.filter { it.matches(query) }.groupBy { it.round to it.group }
    val shownFlows = Flow.entries.filter { it.matches(query) }
    PpsBackground {
        LazyColumn(
            modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars),
            contentPadding = PaddingValues(horizontal = spacing.screenMargin, vertical = spacing.space4),
            verticalArrangement = Arrangement.spacedBy(spacing.space3),
        ) {
            item { MenuTop(dark = dark, largeFont = largeFont, onDark = onDark, onLargeFont = onLargeFont) }
            item(key = "search") { SearchField(query = query, onQuery = onQuery) }
            if (grouped.isEmpty() && shownFlows.isEmpty()) {
                item(key = "none") {
                    Text(
                        text = "No matches",
                        modifier = Modifier.padding(horizontal = spacing.cardPadding, vertical = spacing.space4),
                        style = PpsTheme.typography.body,
                        color = colors.textSecondary,
                    )
                }
            }
            ROUNDS.forEach { (round, heading) ->
                val flows = shownFlows.filter { it.round == round }
                if (flows.isEmpty() && grouped.keys.none { it.first == round }) return@forEach
                item(key = "round$round") { MenuHeader(heading) }
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
            item { Column(modifier = Modifier.windowInsetsPadding(WindowInsets.navigationBars)) {} }
        }
    }
}

/** The menu's title, its note, and the Light / Dark and 200% toggles (above the search and the list). */
@Composable
private fun MenuTop(
    dark: Boolean,
    largeFont: Boolean,
    onDark: (Boolean) -> Unit,
    onLargeFont: (Boolean) -> Unit,
) {
    val colors = PpsTheme.colors
    val spacing = PpsTheme.spacing
    Column(verticalArrangement = Arrangement.spacedBy(spacing.space3)) {
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

private fun mutableBooleanSaveable(value: Boolean) = mutableStateOf(value)

private fun mutableNullableString(value: String? = null) = mutableStateOf(value)

/**
 * The menu's search field (debug text, not app copy): filters as you type on screen, state, round and id, with a
 * clear button while there is a query.
 */
@Composable
private fun SearchField(
    query: String,
    onQuery: (String) -> Unit,
) {
    val colors = PpsTheme.colors
    val spacing = PpsTheme.spacing
    GroupCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BasicTextField(
                value = query,
                onValueChange = onQuery,
                modifier =
                    Modifier
                        .weight(1f)
                        .heightIn(min = spacing.targetMin)
                        .semantics { contentDescription = "Search screens and states" },
                textStyle = PpsTheme.typography.body.copy(color = colors.text),
                singleLine = true,
                cursorBrush = SolidColor(colors.text),
                decorationBox = { field ->
                    Box(
                        modifier = Modifier.padding(horizontal = spacing.cardPadding, vertical = spacing.space3),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        if (query.isEmpty()) {
                            Text(text = "Search screens and states", style = PpsTheme.typography.body, color = colors.textSecondary)
                        }
                        field()
                    }
                },
            )
            if (query.isNotEmpty()) DismissButton(label = "Clear search", onClick = { onQuery("") })
        }
    }
}

private const val LARGE_FONT_SCALE = 2f

/** The rounds the menu shows, with their headings. */
private val ROUNDS = ROUND_HEADINGS.toList()
