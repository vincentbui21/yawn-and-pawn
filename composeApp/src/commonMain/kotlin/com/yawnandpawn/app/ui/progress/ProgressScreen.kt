package com.yawnandpawn.app.ui.progress

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.rememberTextMeasurer
import com.yawnandpawn.app.ui.components.GroupCard
import com.yawnandpawn.app.ui.components.GroupDivider
import com.yawnandpawn.app.ui.components.NavRow
import com.yawnandpawn.app.ui.components.TabScreen
import com.yawnandpawn.app.ui.components.ValueEndRow
import com.yawnandpawn.app.ui.components.glass
import com.yawnandpawn.app.ui.format.formatMoney
import com.yawnandpawn.app.ui.resources.Res
import com.yawnandpawn.app.ui.resources.nav_progress
import com.yawnandpawn.app.ui.resources.progress_average_time
import com.yawnandpawn.app.ui.resources.progress_best_streak
import com.yawnandpawn.app.ui.resources.progress_current_streak
import com.yawnandpawn.app.ui.resources.progress_empty
import com.yawnandpawn.app.ui.resources.progress_export
import com.yawnandpawn.app.ui.resources.progress_export_empty
import com.yawnandpawn.app.ui.resources.progress_minutes
import com.yawnandpawn.app.ui.resources.progress_money_all
import com.yawnandpawn.app.ui.resources.progress_money_month
import com.yawnandpawn.app.ui.resources.progress_money_title
import com.yawnandpawn.app.ui.resources.progress_money_week
import com.yawnandpawn.app.ui.resources.progress_no_mornings
import com.yawnandpawn.app.ui.resources.progress_on_time_30
import com.yawnandpawn.app.ui.resources.progress_on_time_7
import com.yawnandpawn.app.ui.resources.progress_percent
import com.yawnandpawn.app.ui.resources.progress_under_minute
import com.yawnandpawn.app.ui.resources.purchase_history_title
import com.yawnandpawn.app.ui.theme.PpsTheme
import org.jetbrains.compose.resources.stringResource

/**
 * Progress (the Progress tab), stateless: the `stat-tile`s (streaks, on-time rates, average time to up), the weekly
 * snoozes `bar-chart`, the calendar of `calendar-day`s with `outcome-marker`s, "Money paid", and the links to Purchase
 * history and "Export CSV". With no mornings logged: "Your first morning shows up here." and the links, export disabled.
 */
@Composable
fun ProgressScreen(
    state: ProgressUiState,
    onIntent: (ProgressIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    TabScreen(title = stringResource(Res.string.nav_progress), modifier = modifier) {
        val stats = state.stats
        if (stats == null) {
            GroupCard {
                Text(
                    text = stringResource(Res.string.progress_empty),
                    modifier = Modifier.padding(PpsTheme.spacing.cardPadding),
                    style = PpsTheme.typography.body,
                    color = PpsTheme.colors.text,
                )
            }
        } else {
            StatTiles(stats)
            if (state.weeks.isNotEmpty()) SnoozesChart(weeks = state.weeks, selected = state.selectedWeek, onIntent = onIntent)
            state.calendar?.let { OutcomeCalendar(month = it, onIntent = onIntent) }
            state.money?.let { MoneyCard(it) }
        }
        LinksCard(canExport = state.canExport, onIntent = onIntent)
    }
}

/**
 * Two `stat-tile`s per row (a pair whose numbers do not fit half the width splits into one per row): glass, number in
 * `display` (`text`), label in `caption`. A tile without data says "No mornings yet". Not tappable.
 */
@Composable
private fun StatTiles(stats: ProgressStats) {
    val average =
        stats.averageMinutesToUp?.let {
            if (it < 1) stringResource(Res.string.progress_under_minute) else stringResource(Res.string.progress_minutes, it)
        }
    val tiles =
        listOf(
            stringResource(Res.string.progress_current_streak) to stats.currentStreak.toString(),
            stringResource(Res.string.progress_best_streak) to stats.bestStreak.toString(),
            stringResource(Res.string.progress_on_time_7) to percentText(stats.onTime7Days),
            stringResource(Res.string.progress_on_time_30) to percentText(stats.onTime30Days),
            stringResource(Res.string.progress_average_time) to average,
        )
    val spacing = PpsTheme.spacing
    val display = PpsTheme.typography.display
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    BoxWithConstraints {
        // Tiles pair up two per row while both numbers fit half the width in `display`; a pair that does not ("100%" on a
        // 360 dp phone) splits into one per row, so no number wraps or clips.
        val half = (maxWidth - spacing.space3) / 2 - spacing.cardPadding * 2
        val fits = { value: String? ->
            value == null || with(density) {
                measurer
                    .measure(value, display)
                    .size.width
                    .toDp()
            } <= half
        }
        val rows = tiles.chunked(2).flatMap { pair -> if (pair.all { fits(it.second) }) listOf(pair) else pair.map { listOf(it) } }
        Column(verticalArrangement = Arrangement.spacedBy(spacing.space3)) {
            rows.forEach { row ->
                Row(
                    modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min),
                    horizontalArrangement = Arrangement.spacedBy(spacing.space3),
                ) {
                    row.forEach { (label, value) -> StatTile(label = label, value = value, modifier = Modifier.weight(1f).fillMaxHeight()) }
                }
            }
        }
    }
}

@Composable
private fun percentText(value: Int?): String? = value?.let { stringResource(Res.string.progress_percent, it) }

@Composable
private fun StatTile(
    label: String,
    value: String?,
    modifier: Modifier = Modifier,
) {
    val colors = PpsTheme.colors
    Column(
        modifier =
            modifier
                .glass(PpsTheme.shapes.md)
                .padding(PpsTheme.spacing.cardPadding)
                .semantics(mergeDescendants = true) { },
        verticalArrangement = Arrangement.spacedBy(PpsTheme.spacing.space1),
    ) {
        if (value != null) {
            Text(text = value, style = PpsTheme.typography.display, color = colors.text)
        } else {
            Text(text = stringResource(Res.string.progress_no_mornings), style = PpsTheme.typography.body, color = colors.textSecondary)
        }
        Text(text = label, style = PpsTheme.typography.caption, color = colors.textSecondary)
    }
}

/** "Money paid": this week, this month, all time, in `text` (money is never green or red). */
@Composable
private fun MoneyCard(money: MoneyPaid) {
    GroupCard(title = stringResource(Res.string.progress_money_title)) {
        ValueEndRow(label = stringResource(Res.string.progress_money_week), value = formatMoney(money.thisWeek))
        GroupDivider()
        ValueEndRow(label = stringResource(Res.string.progress_money_month), value = formatMoney(money.thisMonth))
        GroupDivider()
        ValueEndRow(label = stringResource(Res.string.progress_money_all), value = formatMoney(money.allTime))
    }
}

/** Purchase history and "Export CSV" (disabled with "Nothing to export yet." while nothing is logged). */
@Composable
private fun LinksCard(
    canExport: Boolean,
    onIntent: (ProgressIntent) -> Unit,
) {
    GroupCard {
        NavRow(label = stringResource(Res.string.purchase_history_title), onClick = { onIntent(ProgressIntent.PurchaseHistoryClicked) })
        GroupDivider()
        NavRow(
            label = stringResource(Res.string.progress_export),
            onClick = { onIntent(ProgressIntent.ExportClicked) },
            value = if (canExport) null else stringResource(Res.string.progress_export_empty),
            enabled = canExport,
        )
    }
}
