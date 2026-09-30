package com.yawnandpawn.app.ui.progress

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.yawnandpawn.app.ui.components.glass
import com.yawnandpawn.app.ui.components.subScreenTransition
import com.yawnandpawn.app.ui.format.DateStyle
import com.yawnandpawn.app.ui.format.DayNameStyle
import com.yawnandpawn.app.ui.format.WeekOrder
import com.yawnandpawn.app.ui.format.dayName
import com.yawnandpawn.app.ui.format.formatDate
import com.yawnandpawn.app.ui.format.formatOneDecimal
import com.yawnandpawn.app.ui.resources.Res
import com.yawnandpawn.app.ui.resources.progress_bar_day
import com.yawnandpawn.app.ui.resources.progress_bar_day_one
import com.yawnandpawn.app.ui.resources.progress_bar_selected
import com.yawnandpawn.app.ui.resources.progress_bar_snooze_one
import com.yawnandpawn.app.ui.resources.progress_bar_snoozes
import com.yawnandpawn.app.ui.resources.progress_chart_average
import com.yawnandpawn.app.ui.resources.progress_chart_caption
import com.yawnandpawn.app.ui.resources.progress_chart_title
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
 * "Snoozes this week" (owner decision 2026-09-30): the last 7 days as rounded bars in `snoozed` (a day without snoozes is
 * a short `outline` stub), the weekday initials under them with today in an accent pill, the tapped day's number at the
 * top ("2 snoozes") and a one-line average summary with "Lower is better.". Tapping anywhere over a bar selects it; bars
 * are values, not buttons: TalkBack reads each as "Wednesday, 2 snoozes".
 */
@Composable
internal fun WeekChart(
    week: List<DaySnoozes>,
    today: LocalDate?,
    selected: Int?,
    average: Double?,
    entered: Boolean,
    onIntent: (ProgressIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = PpsTheme.colors
    val spacing = PpsTheme.spacing
    val shown = (selected ?: week.lastIndex).coerceIn(week.indices)
    Column(
        modifier = modifier.fillMaxWidth().glass(PpsTheme.shapes.md).padding(spacing.cardPadding),
        verticalArrangement = Arrangement.spacedBy(spacing.space3),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(Res.string.progress_chart_title),
                modifier = Modifier.weight(1f).padding(end = spacing.space2).semantics { heading() },
                style = PpsTheme.typography.title,
                color = colors.text,
            )
            // No bar tapped: the week's total; a tapped bar: that day ("Wed · 2 snoozes").
            val headerValue =
                if (selected == null) {
                    snoozeCount(week.sumOf { it.snoozes })
                } else {
                    stringResource(
                        Res.string.progress_bar_selected,
                        dayName(week[shown].date.dayOfWeek, DayNameStyle.Short),
                        snoozeCount(week[shown].snoozes),
                    )
                }
            Text(text = headerValue, style = PpsTheme.typography.label, color = colors.text)
        }
        Bars(week = week, entered = entered, onIntent = onIntent)
        Row(modifier = Modifier.fillMaxWidth().clearAndSetSemantics { }) {
            week.forEachIndexed { index, day ->
                Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    DayInitial(
                        text = dayName(day.date.dayOfWeek, DayNameStyle.Narrow),
                        today = day.date == today,
                        selected = index == shown,
                    )
                }
            }
        }
        val summary =
            listOfNotNull(
                average?.let { stringResource(Res.string.progress_chart_average, formatOneDecimal(it)) },
                stringResource(Res.string.progress_chart_caption),
            ).joinToString(" ")
        Text(text = summary, style = PpsTheme.typography.caption, color = colors.textSecondary)
    }
}

/** A weekday initial or date number: in an outlined accent pill for today (owner notes 2026-10-01); bold when selected. */
@Composable
private fun DayInitial(
    text: String,
    today: Boolean,
    selected: Boolean,
) {
    val colors = PpsTheme.colors
    Text(
        text = text,
        modifier =
            Modifier
                .widthIn(min = PILL_WIDTH)
                .then(if (today) Modifier.border(TODAY_RING, colors.accent, PpsTheme.shapes.full) else Modifier)
                .padding(horizontal = PpsTheme.spacing.space1, vertical = PILL_VERTICAL),
        style = PpsTheme.typography.caption,
        fontWeight = if (today || selected) FontWeight.SemiBold else null,
        color =
            if (today || selected) colors.text else colors.textSecondary,
        textAlign = TextAlign.Center,
        maxLines = 1,
    )
}

/** The bars, bottom-aligned; a tap anywhere over a bar selects its day. */
@Composable
private fun Bars(
    week: List<DaySnoozes>,
    entered: Boolean,
    onIntent: (ProgressIntent) -> Unit,
) {
    val colors = PpsTheme.colors
    val max = week.maxOf { it.snoozes }.coerceAtLeast(1)
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(CHART_HEIGHT)
                .pointerInput(week.size) {
                    detectTapGestures { offset ->
                        val index = (offset.x / (size.width.toFloat() / week.size)).toInt().coerceIn(week.indices)
                        onIntent(ProgressIntent.DayBarTapped(index))
                    }
                },
        verticalAlignment = Alignment.Bottom,
    ) {
        week.forEachIndexed { index, day ->
            val description = barDescription(day)
            // The bars grow from the bottom on entry, one after the other.
            val grow = entranceProgress(entered, delayMillis = BAR_DELAY_MILLIS + index * BAR_STAGGER_MILLIS)
            Box(
                modifier = Modifier.weight(1f).fillMaxHeight().semantics { contentDescription = description },
                contentAlignment = Alignment.BottomCenter,
            ) {
                val bar = Modifier.width(BAR_WIDTH).clip(PpsTheme.shapes.full)
                if (day.snoozes > 0) {
                    Box(
                        modifier =
                            bar
                                .fillMaxHeight(
                                    (day.snoozes.toFloat() / max * grow).coerceAtLeast(MIN_FRACTION),
                                ).background(colors.snoozed),
                    )
                } else {
                    Box(modifier = bar.height(STUB_HEIGHT).graphicsLayer { alpha = grow }.background(colors.outline))
                }
            }
        }
    }
}

@Composable
private fun snoozeCount(n: Int): String =
    if (n == 1) stringResource(Res.string.progress_bar_snooze_one) else stringResource(Res.string.progress_bar_snoozes, n)

@Composable
private fun barDescription(day: DaySnoozes): String {
    val weekday = dayName(day.date.dayOfWeek, DayNameStyle.Full)
    return if (day.snoozes == 1) {
        stringResource(Res.string.progress_bar_day_one, weekday)
    } else {
        stringResource(Res.string.progress_bar_day, weekday, day.snoozes)
    }
}

/**
 * The compact month calendar (owner decisions 2026-09-30 and 2026-10-01): month heading between 48 dp "Previous month" /
 * "Next month", weekday initials, then one 48 dp `calendar-day` per date with its small `outcome-marker` shape under it;
 * today's date sits in an outlined accent pill. The month slides left or right when it changes. A day with a session is
 * a button: the first tap shows its label chip under the grid, a second tap or the chip opens Day detail. The card sits
 * 12 dp from the screen edges so seven 48 dp days fit 360 dp.
 */
@Composable
internal fun OutcomeCalendar(
    month: CalendarMonth,
    selection: DaySelection?,
    onIntent: (ProgressIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = PpsTheme.spacing
    Column(
        modifier =
            modifier
                .bleed(spacing.screenMargin - spacing.space3)
                .fillMaxWidth()
                .glass(PpsTheme.shapes.md)
                .padding(vertical = spacing.space2),
    ) {
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
        AnimatedContent(
            targetState = month,
            transitionSpec = { subScreenTransition(forward = targetState.firstDay > initialState.firstDay) },
            contentKey = { it.firstDay },
            label = "calendar month",
        ) { shown -> MonthGrid(month = shown, selection = selection, onIntent = onIntent) }
        DayChip(
            selection = selection?.takeIf { it.inCalendar },
            onOpen = { onIntent(ProgressIntent.DayTapped(it.date)) },
        )
    }
}

@Composable
private fun MonthGrid(
    month: CalendarMonth,
    selection: DaySelection?,
    onIntent: (ProgressIntent) -> Unit,
) {
    Column {
        val leading = month.firstDay.dayOfWeek.isoDayNumber - 1
        val cells = List(leading) { null } + (1..month.length).toList()
        cells.chunked(DAYS_PER_WEEK).forEach { week ->
            Row(modifier = Modifier.fillMaxWidth()) {
                week.forEach { dayOfMonth ->
                    if (dayOfMonth == null) {
                        Spacer(modifier = Modifier.weight(1f))
                    } else {
                        DayCell(
                            month = month,
                            dayOfMonth = dayOfMonth,
                            selection = selection,
                            onIntent = onIntent,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                repeat(DAYS_PER_WEEK - week.size) { Spacer(modifier = Modifier.weight(1f)) }
            }
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

/** One `calendar-day`: the date in `caption` (today in an outlined accent pill), a small marker shape below. */
@Composable
private fun DayCell(
    month: CalendarMonth,
    dayOfMonth: Int,
    selection: DaySelection?,
    onIntent: (ProgressIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val date = LocalDate(month.firstDay.year, month.firstDay.month, dayOfMonth)
    val day = month.day(dayOfMonth)
    val description = dayDescription(date, day)
    Box(
        modifier =
            modifier
                .heightIn(min = PpsTheme.spacing.targetMin)
                .then(
                    if (day != null) {
                        Modifier.clickable(
                            role = Role.Button,
                            onClick = { onIntent(tapIntent(DaySelection(date, day.outcome, inCalendar = true), selection)) },
                        )
                    } else {
                        Modifier
                    },
                ).semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        // The day the chip refers to has a selection ring in `text` (not the accent of today's pill).
        val isSelected = selection?.inCalendar == true && selection.date == date
        Column(
            modifier =
                Modifier
                    .clearAndSetSemantics { }
                    .then(if (isSelected) Modifier.border(SELECTED_RING, PpsTheme.colors.text, PpsTheme.shapes.md) else Modifier)
                    .padding(horizontal = PILL_VERTICAL, vertical = PILL_VERTICAL),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            DayInitial(text = dayOfMonth.toString(), today = date == month.today, selected = isSelected)
            Box(modifier = Modifier.padding(top = PILL_VERTICAL).size(MARKER)) {
                if (day != null) OutcomeMarker(day.outcome, modifier = Modifier.align(Alignment.Center), size = MARKER)
            }
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

private val CHART_HEIGHT = 112.dp

private val BAR_WIDTH = 20.dp

/** A day without snoozes still shows where its bar would be. */
private val STUB_HEIGHT = 6.dp

/** The today pill around a weekday initial or date. */
private val PILL_WIDTH = 28.dp

private val PILL_VERTICAL = 2.dp

/** The compact calendar's outcome glyph. */
private val MARKER = 14.dp

private val TODAY_RING = 1.5.dp

/** The selected day's ring (`text`). */
private val SELECTED_RING = 1.5.dp

/** The bars start growing once the cards above have come in. */
private const val BAR_DELAY_MILLIS = 250

private const val BAR_STAGGER_MILLIS = 60

/** A growing bar never draws thinner than its rounded ends. */
private const val MIN_FRACTION = 0.01f
