package com.yawnandpawn.app.ui.progress

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.semantics
import com.yawnandpawn.app.core.billing.Money
import com.yawnandpawn.app.ui.components.PpsTextButton
import com.yawnandpawn.app.ui.components.RowIcon
import com.yawnandpawn.app.ui.components.TabScreen
import com.yawnandpawn.app.ui.components.glass
import com.yawnandpawn.app.ui.format.formatMoney
import com.yawnandpawn.app.ui.resources.Res
import com.yawnandpawn.app.ui.resources.home_streak_day
import com.yawnandpawn.app.ui.resources.home_streak_days
import com.yawnandpawn.app.ui.resources.progress_best_streak
import com.yawnandpawn.app.ui.resources.progress_current_streak
import com.yawnandpawn.app.ui.resources.progress_insight_title
import com.yawnandpawn.app.ui.resources.progress_insight_weekdays
import com.yawnandpawn.app.ui.resources.progress_insight_weekends
import com.yawnandpawn.app.ui.resources.progress_minutes
import com.yawnandpawn.app.ui.resources.progress_money_month
import com.yawnandpawn.app.ui.resources.progress_money_title
import com.yawnandpawn.app.ui.resources.progress_percent
import com.yawnandpawn.app.ui.resources.progress_streak_keep_going
import com.yawnandpawn.app.ui.resources.progress_tile_on_time
import com.yawnandpawn.app.ui.resources.progress_tile_snoozes
import com.yawnandpawn.app.ui.resources.progress_tile_to_get_up
import com.yawnandpawn.app.ui.resources.progress_under_minute
import com.yawnandpawn.app.ui.resources.purchase_history_title
import com.yawnandpawn.app.ui.resources.symbol_lightbulb
import com.yawnandpawn.app.ui.resources.symbol_snooze
import com.yawnandpawn.app.ui.resources.symbol_timer
import com.yawnandpawn.app.ui.resources.symbol_wb_sunny
import com.yawnandpawn.app.ui.resources.symbol_wb_twilight
import com.yawnandpawn.app.ui.theme.PpsTheme
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.stringResource

/**
 * Progress (the Progress tab), stateless, owner redesign 2026-09-30 and notes 2026-10-01 (feedback items 21 and 23), top
 * to bottom with no heading (the tab names it): the hero ring of the last 30 mornings with the streak in the centre,
 * three small `stat-tile`s, the accent streak card, the compact month calendar, and "Money paid"
 * beside the Insight card. No period tabs, no export and no snoozes chart (owner decisions against FR-PRG-6 and
 * FR-PRG-2's chart, 2026-10-01: the ring shows each snoozed day and the "snoozes" tile the count). On entry the cards fade and rise
 * in sequence, the ring sweeps in and the streak counts up; reduced motion shows the final state at once.
 * With nothing logged: the empty ring with its prompt, the calendar and Purchase history. [showPurchaseHistory] false
 * leaves out the Purchase history links (production until that screen is built).
 */
@Composable
fun ProgressScreen(
    state: ProgressUiState,
    onIntent: (ProgressIntent) -> Unit,
    modifier: Modifier = Modifier,
    showPurchaseHistory: Boolean = true,
) {
    val entered = rememberEntered()
    TabScreen(title = null, modifier = modifier) {
        val stats = state.stats
        var order = 0
        HeroRing(
            ring = state.ring,
            today = state.today,
            streak = stats?.currentStreak,
            selection = state.selection,
            entered = entered,
            onIntent = onIntent,
            modifier = Modifier.entrance(entered, order++),
        )
        if (stats != null) {
            StatTiles(stats, modifier = Modifier.entrance(entered, order++))
            StreakCard(current = stats.currentStreak, best = stats.bestStreak, modifier = Modifier.entrance(entered, order++))
        }
        state.calendar?.let {
            OutcomeCalendar(month = it, selection = state.selection, onIntent = onIntent, modifier = Modifier.entrance(entered, order++))
        }
        if (stats != null) {
            MoneyAndInsight(
                paid = state.paidThisMonth,
                insight = state.insight,
                onIntent = if (showPurchaseHistory) onIntent else null,
                modifier = Modifier.entrance(entered, order),
            )
        } else if (showPurchaseHistory) {
            PpsTextButton(
                text = stringResource(Res.string.purchase_history_title),
                onClick = { onIntent(ProgressIntent.PurchaseHistoryClicked) },
                modifier = Modifier.entrance(entered, order),
            )
        }
    }
}

/** Values longer than this use the smaller style. */
private const val LONG_TILE_VALUE = 5

/** A tile with no data yet shows a dash (never an invented number). */
private const val DASH = "\u2013"

/** Font scale from which the three tiles wrap to 2 + 1 and the side-by-side cards stack. */
private const val LARGE_FONT_SCALE = 1.5f

@Composable
private fun isLargeFont(): Boolean = LocalDensity.current.fontScale >= LARGE_FONT_SCALE

/**
 * Three small `stat-tile`s in one row (2 + 1 at large font scales): an icon (`text-secondary`), the number in `title`
 * (`text`) and a short label in `caption` ("on time", "to get up", "snoozes"). Not tappable.
 */
@Composable
private fun StatTiles(
    stats: ProgressStats,
    modifier: Modifier = Modifier,
) {
    val average =
        stats.averageMinutesToUp?.let {
            if (it < 1) stringResource(Res.string.progress_under_minute) else stringResource(Res.string.progress_minutes, it)
        }
    val tiles =
        listOf(
            Tile(
                Res.drawable.symbol_wb_sunny,
                stats.onTime30Days?.let {
                    stringResource(Res.string.progress_percent, it)
                },
                stringResource(Res.string.progress_tile_on_time),
            ),
            Tile(Res.drawable.symbol_timer, average, stringResource(Res.string.progress_tile_to_get_up)),
            Tile(Res.drawable.symbol_snooze, stats.snoozes30Days.toString(), stringResource(Res.string.progress_tile_snoozes)),
        )
    TileRow(tiles = tiles, modifier = modifier)
}

/** Three small tiles in one row, 2 + 1 at large font scales (Progress, Day detail). */
@Composable
internal fun TileRow(
    tiles: List<Tile>,
    modifier: Modifier = Modifier,
) {
    val rows = if (isLargeFont()) listOf(tiles.take(2), tiles.drop(2)) else listOf(tiles)
    val spacing = PpsTheme.spacing
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(spacing.space2)) {
        rows.forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min),
                horizontalArrangement = Arrangement.spacedBy(spacing.space2),
            ) {
                row.forEach { tile -> StatTile(tile, modifier = Modifier.weight(1f).fillMaxHeight()) }
            }
        }
    }
}

internal class Tile(
    val icon: DrawableResource,
    val value: String?,
    val label: String,
)

@Composable
internal fun StatTile(
    tile: Tile,
    modifier: Modifier = Modifier,
) {
    val colors = PpsTheme.colors
    Column(
        modifier =
            modifier
                .glass(PpsTheme.shapes.md)
                .padding(PpsTheme.spacing.space3)
                .semantics(mergeDescendants = true) { },
        verticalArrangement = Arrangement.spacedBy(PpsTheme.spacing.space1),
    ) {
        RowIcon(icon = tile.icon, tint = colors.textSecondary)
        val value = tile.value ?: DASH
        Text(
            text = value,
            // A long value (a localized price, "25.000 ₫") steps down to `body` so it stays on one line in a third of
            // a 360 dp row at 100% (at large font scales it may wrap).
            style = if (value.length > LONG_TILE_VALUE) PpsTheme.typography.body else PpsTheme.typography.title,
            color = colors.text,
        )
        // Short labels on one line at 100% on 360 dp (owner notes 2026-10-01); only large font scales wrap.
        Text(text = tile.label, style = PpsTheme.typography.caption, color = colors.textSecondary)
    }
}

/**
 * The accent streak card: glass with the `glass-accent` tint. "Current streak" with the number in `display`
 * (`accent-text`, never plain accent on the tint) and "days on time", "Best streak" with its number, and "Keep it going."
 * while a streak runs. Stacks at large font scales.
 */
@Composable
private fun StreakCard(
    current: Int,
    best: Int,
    modifier: Modifier = Modifier,
) {
    val colors = PpsTheme.colors
    val spacing = PpsTheme.spacing
    val currentPart = @Composable {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(spacing.space2)) {
                RowIcon(icon = Res.drawable.symbol_wb_twilight, tint = colors.accentText)
                Text(
                    text = stringResource(Res.string.progress_current_streak),
                    style = PpsTheme.typography.caption,
                    color = colors.textSecondary,
                )
            }
            Row(verticalAlignment = Alignment.Bottom) {
                Text(text = current.toString(), style = PpsTheme.typography.display, color = colors.accentText)
                Text(
                    text = stringResource(if (current == 1) Res.string.home_streak_day else Res.string.home_streak_days),
                    modifier = Modifier.padding(start = spacing.space2, bottom = spacing.space2),
                    style = PpsTheme.typography.body,
                    color = colors.text,
                )
            }
        }
    }
    val bestPart = @Composable {
        Column {
            Text(text = stringResource(Res.string.progress_best_streak), style = PpsTheme.typography.caption, color = colors.textSecondary)
            Text(text = best.toString(), style = PpsTheme.typography.headline, color = colors.text)
        }
    }
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .glass(PpsTheme.shapes.md, accentTint = true)
                .padding(spacing.cardPadding)
                .semantics(mergeDescendants = true) { },
        verticalArrangement = Arrangement.spacedBy(spacing.space2),
    ) {
        if (isLargeFont()) {
            currentPart()
            bestPart()
        } else {
            Row(verticalAlignment = Alignment.Bottom) {
                Column(modifier = Modifier.weight(1f)) { currentPart() }
                bestPart()
            }
        }
        if (current > 0) {
            Text(
                text = stringResource(Res.string.progress_streak_keep_going),
                style = PpsTheme.typography.body,
                color = colors.textSecondary,
            )
        }
    }
}

/** "Money paid" (this month's total in `text`, a link to Purchase history) beside the Insight card; stacked at large font. */
@Composable
private fun MoneyAndInsight(
    paid: Money?,
    insight: Insight?,
    onIntent: ((ProgressIntent) -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val spacing = PpsTheme.spacing
    val money = @Composable { modifier: Modifier -> MoneyCard(paid = paid, onIntent = onIntent, modifier = modifier) }
    val insightCard = @Composable { modifier: Modifier -> insight?.let { InsightCard(it, modifier) } }
    if (isLargeFont() || insight == null) {
        Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(spacing.space3)) {
            money(Modifier.fillMaxWidth())
            insightCard(Modifier.fillMaxWidth())
        }
    } else {
        Row(modifier = modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(spacing.space3)) {
            money(Modifier.weight(1f).fillMaxHeight())
            insightCard(Modifier.weight(1f).fillMaxHeight())
        }
    }
}

/** "Money paid" this month; [onIntent] `null` leaves out its Purchase history link. */
@Composable
private fun MoneyCard(
    paid: Money?,
    onIntent: ((ProgressIntent) -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val colors = PpsTheme.colors
    Column(
        modifier = modifier.glass(PpsTheme.shapes.md).padding(start = PpsTheme.spacing.cardPadding, top = PpsTheme.spacing.cardPadding),
    ) {
        val texts = Modifier.padding(end = PpsTheme.spacing.cardPadding)
        Column(
            modifier =
                (if (onIntent == null) texts.padding(bottom = PpsTheme.spacing.cardPadding) else texts)
                    .semantics(mergeDescendants = true) { },
        ) {
            Text(text = stringResource(Res.string.progress_money_title), style = PpsTheme.typography.caption, color = colors.textSecondary)
            paid?.let { Text(text = formatMoney(it), style = PpsTheme.typography.title, color = colors.text) }
            Text(text = stringResource(Res.string.progress_money_month), style = PpsTheme.typography.caption, color = colors.textSecondary)
        }
        if (onIntent != null) {
            PpsTextButton(
                text = stringResource(Res.string.purchase_history_title),
                onClick = { onIntent(ProgressIntent.PurchaseHistoryClicked) },
                modifier = Modifier.padding(end = PpsTheme.spacing.space1),
            )
        }
    }
}

/** The Insight card: a lightbulb, "Insight" and one short line from the user's own data. */
@Composable
private fun InsightCard(
    insight: Insight,
    modifier: Modifier = Modifier,
) {
    val colors = PpsTheme.colors
    Column(
        modifier = modifier.glass(PpsTheme.shapes.md).padding(PpsTheme.spacing.cardPadding).semantics(mergeDescendants = true) { },
        verticalArrangement = Arrangement.spacedBy(PpsTheme.spacing.space1),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(PpsTheme.spacing.space2)) {
            RowIcon(icon = Res.drawable.symbol_lightbulb, tint = colors.textSecondary)
            Text(
                text = stringResource(Res.string.progress_insight_title),
                style = PpsTheme.typography.caption,
                color = colors.textSecondary,
            )
        }
        Text(
            text =
                stringResource(
                    when (insight) {
                        Insight.FastestOnWeekdays -> Res.string.progress_insight_weekdays
                        Insight.FastestOnWeekends -> Res.string.progress_insight_weekends
                    },
                ),
            style = PpsTheme.typography.body,
            color = colors.text,
        )
    }
}
