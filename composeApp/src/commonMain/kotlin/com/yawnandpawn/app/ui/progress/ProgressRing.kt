package com.yawnandpawn.app.ui.progress

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.yawnandpawn.app.ui.components.glass
import com.yawnandpawn.app.ui.components.rememberReducedMotion
import com.yawnandpawn.app.ui.format.DayNameStyle
import com.yawnandpawn.app.ui.format.dayName
import com.yawnandpawn.app.ui.resources.Res
import com.yawnandpawn.app.ui.resources.progress_day_fallback
import com.yawnandpawn.app.ui.resources.progress_day_no_alarm
import com.yawnandpawn.app.ui.resources.progress_day_outcome
import com.yawnandpawn.app.ui.resources.progress_day_sessions
import com.yawnandpawn.app.ui.resources.progress_day_today
import com.yawnandpawn.app.ui.resources.progress_empty
import com.yawnandpawn.app.ui.resources.progress_ring_of
import com.yawnandpawn.app.ui.resources.progress_ring_streak
import com.yawnandpawn.app.ui.theme.PpsTheme
import kotlinx.datetime.LocalDate
import org.jetbrains.compose.resources.stringResource
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The hero ring card (owner decisions 2026-09-30 and 2026-10-01, feedback items 21 and 23): the last 30 mornings as
 * `outcome-marker` shapes around a circle, oldest at the top and clockwise to today (in an outlined accent pill); a day
 * without an alarm is a faint dot. No legend: each shape carries its meaning. The centre shows the current streak in
 * `display` (`accent-text`, counting up on entry), "/ 30" and "day streak"; with nothing logged it shows "Your first
 * morning shows up here.". The dots sweep in around the circle on entry. Tapping a dot with a session shows its label
 * chip under the ring; a second tap or the chip opens Day detail. The dots are read by TalkBack ("Tuesday 22, Snoozed")
 * but are not separate 48 dp buttons (30 would not fit the ring); the chip and the calendar's days are buttons.
 */
@Composable
internal fun HeroRing(
    ring: List<RingDay>,
    today: LocalDate?,
    streak: Int?,
    selection: DaySelection?,
    entered: Boolean,
    onIntent: (ProgressIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = PpsTheme.spacing
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .glass(PpsTheme.shapes.md)
                .padding(top = spacing.cardPadding, start = spacing.cardPadding, end = spacing.cardPadding, bottom = spacing.space2),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            val diameter = if (maxWidth < RING_MAX) maxWidth else RING_MAX
            val density = LocalDensity.current
            val radiusPx = with(density) { (diameter / 2 - TODAY_PILL.width / 2).toPx() }
            val hitPx = with(density) { DOT_HIT.toPx() }
            val days = ring.ifEmpty { List(RING_DAYS) { null } }
            val positions = days.indices.map { index -> dotOffset(index, days.size, radiusPx) }
            Box(
                modifier =
                    Modifier.size(diameter).pointerInput(days, selection) {
                        detectTapGestures { tap ->
                            val centre = Offset(size.width / 2f, size.height / 2f)
                            val nearest = positions.indices.minBy { (tap - centre - positions[it]).getDistance() }
                            val day = days[nearest]
                            val outcome = day?.outcome
                            if (outcome != null && (tap - centre - positions[nearest]).getDistance() <= hitPx) {
                                onIntent(tapIntent(DaySelection(day.date, outcome, inCalendar = false), selection))
                            }
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                RingTrack(radiusPx)
                days.forEachIndexed { index, day ->
                    val appear = entranceProgress(entered, delayMillis = index * DOT_STAGGER_MILLIS, durationMillis = DOT_MILLIS)
                    RingDot(
                        day = day,
                        isToday = day != null && day.date == today,
                        selected = day != null && selection.isRingDay(day.date),
                        modifier =
                            Modifier
                                .offset { IntOffset(positions[index].x.roundToInt(), positions[index].y.roundToInt()) }
                                .graphicsLayer {
                                    alpha = appear
                                    scaleX = appear
                                    scaleY = appear
                                },
                    )
                }
                RingCentre(streak = streak, entered = entered, modifier = Modifier.widthIn(max = diameter * CENTRE_WIDTH))
            }
        }
        DayChip(
            selection = selection?.takeIf { !it.inCalendar },
            onOpen = { onIntent(ProgressIntent.DayTapped(it.date)) },
        )
    }
}

/** The hairline circle the dots sit on (`outline-subtle`, decorative). */
@Composable
private fun RingTrack(radiusPx: Float) {
    val track = PpsTheme.colors.outlineSubtle
    Canvas(modifier = Modifier.fillMaxSize().clearAndSetSemantics { }) {
        drawCircle(color = track, radius = radiusPx, style = Stroke(width = 1.dp.toPx()))
    }
}

/** The chip under the ring refers to this day. */
private fun DaySelection?.isRingDay(date: LocalDate): Boolean = this != null && !inCalendar && this.date == date

/** A first tap selects (shows the chip); a second tap on the selected day opens Day detail. */
internal fun tapIntent(
    tapped: DaySelection,
    selection: DaySelection?,
): ProgressIntent =
    if (selection?.date == tapped.date && selection.inCalendar == tapped.inCalendar) {
        ProgressIntent.DayTapped(tapped.date)
    } else {
        ProgressIntent.DaySelected(tapped)
    }

/** Where dot [index] of [count] sits relative to the ring's centre: from the top, clockwise. */
private fun dotOffset(
    index: Int,
    count: Int,
    radius: Float,
): Offset {
    val angle = -PI / 2 + 2 * PI * index / count
    return Offset((radius * cos(angle)).toFloat(), (radius * sin(angle)).toFloat())
}

@Composable
private fun RingDot(
    day: RingDay?,
    isToday: Boolean,
    selected: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = PpsTheme.colors
    val description = day?.let { ringDayDescription(it, isToday) }
    Box(
        modifier =
            modifier
                .size(if (isToday) TODAY_PILL else DpSize(DOT_BOX, DOT_BOX))
                .then(
                    if (description !=
                        null
                    ) {
                        Modifier.semantics { contentDescription = description }
                    } else {
                        Modifier.clearAndSetSemantics { }
                    },
                ).then(if (isToday) Modifier.border(TODAY_RING, colors.accent, PpsTheme.shapes.full) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        val outcome = day?.outcome
        if (outcome == null) {
            Box(modifier = Modifier.size(NO_ALARM_DOT).clip(PpsTheme.shapes.full).background(colors.outlineSubtle))
        } else {
            // The dot the chip refers to: a selection ring in `text`, inside today's accent pill when it is today.
            val ring = if (selected) Modifier.border(SELECTED_RING, colors.text, PpsTheme.shapes.full) else Modifier
            Box(modifier = Modifier.size(DOT_BOX).then(ring), contentAlignment = Alignment.Center) {
                OutcomeMarker(outcome, size = if (selected) SELECTED_DOT else DOT)
            }
        }
    }
}

@Composable
private fun ringDayDescription(
    day: RingDay,
    isToday: Boolean,
): String {
    val base = dayDescription(day.date, day.outcome?.let { CalendarDay(day.date, it, day.fallbackUsed) })
    return if (isToday) "$base, ${stringResource(Res.string.progress_day_today)}" else base
}

/** The ring's centre: the streak (counting up on entry), "/ 30" and "day streak"; empty, the prompt. */
@Composable
private fun RingCentre(
    streak: Int?,
    entered: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = PpsTheme.colors
    val reduced = rememberReducedMotion()
    val countSpec: AnimationSpec<Int> = if (reduced) snap() else tween(durationMillis = COUNT_MILLIS, delayMillis = COUNT_DELAY_MILLIS)
    val shown by animateIntAsState(targetValue = if (entered) streak ?: 0 else 0, animationSpec = countSpec, label = "streak")
    Column(modifier = modifier.semantics(mergeDescendants = true) { }, horizontalAlignment = Alignment.CenterHorizontally) {
        if (streak == null) {
            Text(
                text = stringResource(Res.string.progress_empty),
                style = PpsTheme.typography.body,
                color = colors.text,
                textAlign = TextAlign.Center,
            )
        } else {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = shown.toString(),
                    // TalkBack reads the final streak, never a number on its way up.
                    modifier = Modifier.semantics { contentDescription = streak.toString() },
                    style = PpsTheme.typography.display,
                    color = colors.accentText,
                )
                Text(
                    text = stringResource(Res.string.progress_ring_of, RING_DAYS),
                    modifier = Modifier.padding(start = PpsTheme.spacing.space1, bottom = PpsTheme.spacing.space1),
                    style = PpsTheme.typography.title,
                    color = colors.textSecondary,
                )
            }
            Text(
                text = stringResource(Res.string.progress_ring_streak),
                style = PpsTheme.typography.body,
                color = colors.text,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** "Tuesday 14, On time" + ", fallback check used" + ", 2 sessions"; a day without a session "Tuesday 14, no alarm". */
@Composable
internal fun dayDescription(
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

/** The ring never grows past this (a 360 dp phone's card is 288 dp inside). */
private val RING_MAX = 280.dp

private val DOT_BOX = 22.dp

private val DOT = 16.dp

/** Today: an outlined pill around its dot, wider than tall. */
private val TODAY_PILL = DpSize(32.dp, 22.dp)

private val TODAY_RING = 1.5.dp

private val NO_ALARM_DOT = 4.dp

/** A selected dot shrinks a little inside its ring. */
private val SELECTED_DOT = 14.dp

private val SELECTED_RING = 1.5.dp

/** A tap this close to a dot's centre selects its day. */
private val DOT_HIT = 20.dp

/** The centre text stays inside the ring. */
private const val CENTRE_WIDTH = 0.62f

/** The sweep: each dot pops in this long after the one before it, from the top clockwise. */
private const val DOT_STAGGER_MILLIS = 22

private const val DOT_MILLIS = 220

private const val COUNT_MILLIS = 700

private const val COUNT_DELAY_MILLIS = 150
