package com.yawnandpawn.app.ui.progress

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.ZeroCornerSize
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.yawnandpawn.app.ui.components.GroupCard
import com.yawnandpawn.app.ui.format.DateStyle
import com.yawnandpawn.app.ui.format.DayNameStyle
import com.yawnandpawn.app.ui.format.WeekOrder
import com.yawnandpawn.app.ui.format.dayName
import com.yawnandpawn.app.ui.format.formatDate
import com.yawnandpawn.app.ui.resources.Res
import com.yawnandpawn.app.ui.resources.day_detail_fallback
import com.yawnandpawn.app.ui.resources.progress_bar_snooze_one
import com.yawnandpawn.app.ui.resources.progress_bar_snoozes
import com.yawnandpawn.app.ui.resources.progress_bar_week
import com.yawnandpawn.app.ui.resources.progress_bar_week_one
import com.yawnandpawn.app.ui.resources.progress_chart_caption
import com.yawnandpawn.app.ui.resources.progress_chart_title
import com.yawnandpawn.app.ui.resources.progress_day_fallback
import com.yawnandpawn.app.ui.resources.progress_day_no_alarm
import com.yawnandpawn.app.ui.resources.progress_day_outcome
import com.yawnandpawn.app.ui.resources.progress_day_sessions
import com.yawnandpawn.app.ui.resources.progress_next_month
import com.yawnandpawn.app.ui.resources.progress_previous_month
import com.yawnandpawn.app.ui.resources.symbol_chevron_left
import com.yawnandpawn.app.ui.resources.symbol_chevron_right
import com.yawnandpawn.app.ui.theme.PpsTheme
import kotlinx.datetime.LocalDate
import kotlinx.datetime.isoDayNumber
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

/**
 * The snoozes `bar-chart`: one bar per week (the last 8), in `snoozed` with `rounded.sm` top corners on an `outline`
 * baseline, week labels in `text-secondary`, caption "Lower is better.". Tapping anywhere over a bar shows its number
 * ("3 snoozes") above the chart. Bars are values, not buttons: TalkBack reads each as "Week of 9/22, 3 snoozes".
 */
@Composable
internal fun SnoozesChart(
    weeks: List<WeekSnoozes>,
    selected: Int?,
    onIntent: (ProgressIntent) -> Unit,
) {
    val colors = PpsTheme.colors
    val spacing = PpsTheme.spacing
    val shown = (selected ?: weeks.lastIndex).coerceIn(weeks.indices)
    // At large font scales every other week label is left out so none clips (the bars and TalkBack keep all weeks).
    val labelStep = if (LocalDensity.current.fontScale > LABEL_FONT_SCALE) 2 else 1
    GroupCard(title = stringResource(Res.string.progress_chart_title)) {
        Column(modifier = Modifier.padding(spacing.cardPadding), verticalArrangement = Arrangement.spacedBy(spacing.space2)) {
            Text(text = snoozeCount(weeks[shown].snoozes), style = PpsTheme.typography.title, color = colors.text)
            Bars(weeks = weeks, onIntent = onIntent)
            Box(modifier = Modifier.fillMaxWidth().height(spacing.hairline).background(colors.outline))
            Row(modifier = Modifier.fillMaxWidth().clearAndSetSemantics { }) {
                weeks.forEachIndexed { index, week ->
                    val showLabel = (weeks.lastIndex - index) % labelStep == 0
                    // Centred under its bar at its full width: a label wider than its bar spills over the empty
                    // neighbouring slots (at large font scales only every other week has a label), never clipped.
                    Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                        if (showLabel) {
                            Text(
                                text = formatDate(week.weekStart, DateStyle.Numeric),
                                modifier = Modifier.wrapContentWidth(unbounded = true),
                                style = PpsTheme.typography.caption,
                                fontWeight = if (index == shown) FontWeight.SemiBold else null,
                                color = if (index == shown) colors.text else colors.textSecondary,
                                maxLines = 1,
                                softWrap = false,
                            )
                        }
                    }
                }
            }
            Text(
                text = stringResource(Res.string.progress_chart_caption),
                style = PpsTheme.typography.caption,
                color = colors.textSecondary,
            )
        }
    }
}

/** The bars, bottom-aligned; a tap anywhere over a bar selects its week. */
@Composable
private fun Bars(
    weeks: List<WeekSnoozes>,
    onIntent: (ProgressIntent) -> Unit,
) {
    val colors = PpsTheme.colors
    val spacing = PpsTheme.spacing
    val max = weeks.maxOf { it.snoozes }.coerceAtLeast(1)
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(CHART_HEIGHT)
                .pointerInput(weeks.size) {
                    detectTapGestures { offset ->
                        val index = (offset.x / (size.width.toFloat() / weeks.size)).toInt().coerceIn(weeks.indices)
                        onIntent(ProgressIntent.WeekTapped(index))
                    }
                },
        verticalAlignment = Alignment.Bottom,
    ) {
        weeks.forEach { week ->
            val description = weekDescription(week)
            Box(
                modifier = Modifier.weight(1f).fillMaxHeight().semantics { contentDescription = description },
                contentAlignment = Alignment.BottomCenter,
            ) {
                if (week.snoozes > 0) {
                    Box(
                        modifier =
                            Modifier
                                .padding(horizontal = spacing.space2)
                                .fillMaxWidth()
                                .fillMaxHeight(week.snoozes.toFloat() / max)
                                .clip(PpsTheme.shapes.sm.copy(bottomStart = ZeroCornerSize, bottomEnd = ZeroCornerSize))
                                .background(colors.snoozed),
                    )
                }
            }
        }
    }
}

@Composable
private fun snoozeCount(n: Int): String =
    if (n == 1) stringResource(Res.string.progress_bar_snooze_one) else stringResource(Res.string.progress_bar_snoozes, n)

@Composable
private fun weekDescription(week: WeekSnoozes): String {
    val date = formatDate(week.weekStart, DateStyle.DayMonth)
    return if (week.snoozes == 1) {
        stringResource(Res.string.progress_bar_week_one, date)
    } else {
        stringResource(Res.string.progress_bar_week, date, week.snoozes)
    }
}

/**
 * The calendar: month heading with "Previous month" / "Next month", weekday initials, then one 48 dp `calendar-day` per
 * date with its `outcome-marker` (and the `alt_route` badge when a fallback check was used); today has a 1 dp accent
 * ring. A day with a session opens Day detail. Below it, the legend pairs every glyph with its label. The card sits
 * 12 dp from the screen edges instead of 20 so seven 48 dp days fit a 360 dp phone.
 */
@Composable
internal fun OutcomeCalendar(
    month: CalendarMonth,
    onIntent: (ProgressIntent) -> Unit,
) {
    val spacing = PpsTheme.spacing
    GroupCard(modifier = Modifier.bleed(spacing.screenMargin - spacing.space3)) {
        Column(modifier = Modifier.padding(vertical = spacing.space2)) {
            MonthHeader(month = month, onIntent = onIntent)
            Row(modifier = Modifier.fillMaxWidth().clearAndSetSemantics { }) {
                WeekOrder.forEach { day ->
                    Text(
                        text = dayName(day, DayNameStyle.Narrow),
                        modifier = Modifier.weight(1f),
                        style = PpsTheme.typography.caption,
                        color = PpsTheme.colors.textSecondary,
                        textAlign = TextAlign.Center,
                    )
                }
            }
            val leading = month.firstDay.dayOfWeek.isoDayNumber - 1
            val cells = List(leading) { null } + (1..month.length).toList()
            cells.chunked(DAYS_PER_WEEK).forEach { week ->
                Row(modifier = Modifier.fillMaxWidth()) {
                    week.forEach { dayOfMonth ->
                        if (dayOfMonth == null) {
                            Spacer(modifier = Modifier.weight(1f))
                        } else {
                            DayCell(month = month, dayOfMonth = dayOfMonth, onIntent = onIntent, modifier = Modifier.weight(1f))
                        }
                    }
                    repeat(DAYS_PER_WEEK - week.size) { Spacer(modifier = Modifier.weight(1f)) }
                }
            }
            Legend(modifier = Modifier.padding(start = spacing.cardPadding, end = spacing.cardPadding, top = spacing.space3))
        }
    }
}

@Composable
private fun MonthHeader(
    month: CalendarMonth,
    onIntent: (ProgressIntent) -> Unit,
) {
    Row(modifier = Modifier.fillMaxWidth().padding(horizontal = PpsTheme.spacing.space1), verticalAlignment = Alignment.CenterVertically) {
        MonthButton(
            icon = Res.drawable.symbol_chevron_left,
            label = stringResource(Res.string.progress_previous_month),
            enabled = month.hasPrevious,
            onClick = { onIntent(ProgressIntent.PreviousMonth) },
        )
        Text(
            text = formatDate(month.firstDay, DateStyle.MonthYear),
            modifier = Modifier.weight(1f).semantics { heading() },
            style = PpsTheme.typography.title,
            color = PpsTheme.colors.text,
            textAlign = TextAlign.Center,
        )
        MonthButton(
            icon = Res.drawable.symbol_chevron_right,
            label = stringResource(Res.string.progress_next_month),
            enabled = month.hasNext,
            onClick = { onIntent(ProgressIntent.NextMonth) },
        )
    }
}

@Composable
private fun MonthButton(
    icon: DrawableResource,
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val colors = PpsTheme.colors
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.size(PpsTheme.spacing.targetMin),
        colors = IconButtonDefaults.iconButtonColors(contentColor = colors.text, disabledContentColor = colors.disabledContent),
    ) {
        Icon(painter = painterResource(icon), contentDescription = label)
    }
}

/** One `calendar-day`: the date in `caption`, its marker below; today ringed in accent. */
@Composable
private fun DayCell(
    month: CalendarMonth,
    dayOfMonth: Int,
    onIntent: (ProgressIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = PpsTheme.colors
    val date = LocalDate(month.firstDay.year, month.firstDay.month, dayOfMonth)
    val day = month.day(dayOfMonth)
    val description = dayDescription(date, day)
    val isToday = date == month.today
    Box(
        modifier =
            modifier
                .heightIn(min = PpsTheme.spacing.targetMin)
                .then(
                    if (day != null) {
                        Modifier.clickable(role = Role.Button, onClick = { onIntent(ProgressIntent.DayTapped(date)) })
                    } else {
                        Modifier
                    },
                ).semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier =
                Modifier
                    .clearAndSetSemantics { }
                    .then(if (isToday) Modifier.border(1.dp, colors.accent, PpsTheme.shapes.md) else Modifier)
                    .padding(horizontal = PpsTheme.spacing.space1, vertical = PpsTheme.spacing.space1),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = dayOfMonth.toString(),
                style = PpsTheme.typography.caption,
                color = colors.text,
                textAlign = TextAlign.Center,
            )
            Box(modifier = Modifier.size(MARKER_BOX)) {
                if (day != null) {
                    OutcomeMarker(day.outcome, modifier = Modifier.align(Alignment.Center))
                    if (day.fallbackUsed) FallbackBadge(modifier = Modifier.align(Alignment.BottomEnd).offset(x = BADGE_OFFSET))
                }
            }
        }
    }
}

/** "Tuesday 14, On time" + ", fallback check used" + ", 2 sessions"; a day without a session "Tuesday 14, no alarm". */
@Composable
private fun dayDescription(
    date: LocalDate,
    day: CalendarDay?,
): String {
    val weekday = dayName(date.dayOfWeek, DayNameStyle.Full)
    if (day == null) return stringResource(Res.string.progress_day_no_alarm, weekday, date.day)
    val parts =
        listOfNotNull(
            stringResource(Res.string.progress_day_outcome, weekday, date.day, day.outcome.label()),
            if (day.fallbackUsed) stringResource(Res.string.progress_day_fallback) else null,
            if (day.sessions > 1) stringResource(Res.string.progress_day_sessions, day.sessions) else null,
        )
    return parts.joinToString(", ")
}

/** The legend: every glyph with its label (outcomes never rely on colour), and the fallback badge. */
@Composable
private fun Legend(modifier: Modifier = Modifier) {
    val spacing = PpsTheme.spacing
    FlowRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(spacing.space4),
        verticalArrangement = Arrangement.spacedBy(spacing.space2),
    ) {
        Outcome.entries.forEach { OutcomeLabel(it) }
        Row(
            modifier = Modifier.semantics(mergeDescendants = true) { },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(spacing.space1),
        ) {
            FallbackBadge(size = PpsTheme.spacing.space5)
            Text(text = stringResource(Res.string.day_detail_fallback), style = PpsTheme.typography.caption, color = PpsTheme.colors.text)
        }
    }
}

/**
 * Lets a card reach [horizontal] further towards each screen edge than the screen margin (the calendar: 12 dp from the
 * edges instead of 20), without changing the column around it.
 */
private fun Modifier.bleed(horizontal: Dp): Modifier =
    layout { measurable, constraints ->
        val extra = horizontal.roundToPx()
        val placeable =
            measurable.measure(
                constraints.copy(minWidth = constraints.minWidth + 2 * extra, maxWidth = constraints.maxWidth + 2 * extra),
            )
        layout(placeable.width - 2 * extra, placeable.height) { placeable.place(-extra, 0) }
    }

private const val DAYS_PER_WEEK = 7

/** Above this font scale the chart shows every other week label. */
private const val LABEL_FONT_SCALE = 1.3f

private val CHART_HEIGHT = 120.dp

/** Room for the 20 dp marker under the date. */
private val MARKER_BOX = 20.dp

/** The fallback badge overlaps the marker's lower right corner. */
private val BADGE_OFFSET = 6.dp
