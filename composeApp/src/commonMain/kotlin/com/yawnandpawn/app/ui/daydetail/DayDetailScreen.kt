package com.yawnandpawn.app.ui.daydetail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.yawnandpawn.app.core.billing.Money
import com.yawnandpawn.app.core.billing.totalsByCurrency
import com.yawnandpawn.app.ui.checks.CheckType
import com.yawnandpawn.app.ui.checks.displayName
import com.yawnandpawn.app.ui.checks.icon
import com.yawnandpawn.app.ui.components.NoteInline
import com.yawnandpawn.app.ui.components.RowIcon
import com.yawnandpawn.app.ui.components.SubScreen
import com.yawnandpawn.app.ui.components.glass
import com.yawnandpawn.app.ui.format.DateStyle
import com.yawnandpawn.app.ui.format.formatClockTime
import com.yawnandpawn.app.ui.format.formatDate
import com.yawnandpawn.app.ui.format.formatMoney
import com.yawnandpawn.app.ui.progress.Outcome
import com.yawnandpawn.app.ui.progress.OutcomeMarker
import com.yawnandpawn.app.ui.progress.Tile
import com.yawnandpawn.app.ui.progress.TileRow
import com.yawnandpawn.app.ui.progress.entranceProgress
import com.yawnandpawn.app.ui.progress.label
import com.yawnandpawn.app.ui.progress.rememberEntered
import com.yawnandpawn.app.ui.resources.Res
import com.yawnandpawn.app.ui.resources.alarm_card_summary
import com.yawnandpawn.app.ui.resources.day_detail_before_unlock
import com.yawnandpawn.app.ui.resources.day_detail_deleted
import com.yawnandpawn.app.ui.resources.day_detail_merged
import com.yawnandpawn.app.ui.resources.day_detail_turned_off
import com.yawnandpawn.app.ui.resources.day_event_fallback
import com.yawnandpawn.app.ui.resources.day_event_quiet_ended
import com.yawnandpawn.app.ui.resources.day_event_rang
import com.yawnandpawn.app.ui.resources.day_event_rang_again
import com.yawnandpawn.app.ui.resources.day_event_snoozed
import com.yawnandpawn.app.ui.resources.day_event_solved_first
import com.yawnandpawn.app.ui.resources.day_event_solved_tries
import com.yawnandpawn.app.ui.resources.day_event_stopped
import com.yawnandpawn.app.ui.resources.day_event_switched
import com.yawnandpawn.app.ui.resources.day_tile_no_charge
import com.yawnandpawn.app.ui.resources.day_tile_paid
import com.yawnandpawn.app.ui.resources.day_tile_rings
import com.yawnandpawn.app.ui.resources.editor_back
import com.yawnandpawn.app.ui.resources.progress_minutes
import com.yawnandpawn.app.ui.resources.progress_tile_snoozes
import com.yawnandpawn.app.ui.resources.progress_tile_to_get_up
import com.yawnandpawn.app.ui.resources.progress_under_minute
import com.yawnandpawn.app.ui.resources.symbol_alarm
import com.yawnandpawn.app.ui.resources.symbol_alt_route
import com.yawnandpawn.app.ui.resources.symbol_lock
import com.yawnandpawn.app.ui.resources.symbol_payments
import com.yawnandpawn.app.ui.resources.symbol_snooze
import com.yawnandpawn.app.ui.resources.symbol_timer
import com.yawnandpawn.app.ui.resources.wake_im_up
import com.yawnandpawn.app.ui.theme.PpsTheme
import com.yawnandpawn.app.ui.theme.TABULAR_FIGURES
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import org.jetbrains.compose.resources.stringResource

/** One moment of a morning, in the order it happened (Day detail timeline, owner decision 2026-10-01). */
sealed interface MorningEvent {
    val time: LocalTime

    /** The alarm rang ([again] after a snooze), or rang before the first unlock after a restart. */
    data class Rang(
        override val time: LocalTime,
        val again: Boolean = false,
        val beforeFirstUnlock: Boolean = false,
    ) : MorningEvent

    data class Snoozed(
        override val time: LocalTime,
        val minutes: Int,
        val price: Money,
    ) : MorningEvent

    data class ImUp(
        override val time: LocalTime,
    ) : MorningEvent

    data class QuietTimeRanOut(
        override val time: LocalTime,
    ) : MorningEvent

    /** Before the first unlock a camera check is swapped for [to] for this ring. */
    data class CheckSwitched(
        override val time: LocalTime,
        val to: CheckType,
    ) : MorningEvent

    data class FallbackUsed(
        override val time: LocalTime,
        val check: CheckType,
    ) : MorningEvent

    data class Merged(
        override val time: LocalTime,
        val alarmTime: LocalTime,
    ) : MorningEvent

    data class CheckSolved(
        override val time: LocalTime,
        val check: CheckType,
        val tries: Int = 1,
    ) : MorningEvent

    /** No interaction for 30 minutes: the session stopped and was logged as missed. */
    data class Stopped(
        override val time: LocalTime,
    ) : MorningEvent
}

/**
 * One session of the day: its alarm, outcome and the [events] of the morning in order. Rings, snoozes, the amount paid
 * and the time to get up are all read from the events, so the hero, the tiles and the timeline always agree. Test and
 * skipped sessions have no events and show their outcome label only.
 */
data class SessionDetail(
    val alarmTime: LocalTime,
    val alarmLabel: String?,
    val outcome: Outcome,
    val events: List<MorningEvent> = emptyList(),
) {
    val rings: Int get() = events.count { it is MorningEvent.Rang }
    val snoozes: Int get() = events.count { it is MorningEvent.Snoozed }

    /** What this session charged, one total per currency (AD-8); empty when nothing was paid. */
    val paid: List<Money> get() = totalsByCurrency(events.filterIsInstance<MorningEvent.Snoozed>().map { it.price })

    /** Minutes from the first ring to the solved check (the session's end); `null` when it never ended that way. */
    val minutesToUp: Int?
        get() {
            val first = events.firstOrNull { it is MorningEvent.Rang }?.time ?: return null
            val done = events.lastOrNull { it is MorningEvent.CheckSolved }?.time ?: return null
            return done.toSecondOfDay() / SECONDS_PER_MINUTE - first.toSecondOfDay() / SECONDS_PER_MINUTE
        }

    val counted: Boolean get() = outcome != Outcome.Test && outcome != Outcome.Skipped
}

/** A logged change to an alarm that day ("Your 7:30 alarm was turned off. Logged."). */
data class AlarmChange(
    val time: LocalTime,
    val deleted: Boolean,
)

/** What Day detail renders: one section per session of [date], then the logged alarm changes. */
data class DayDetailUiState(
    val date: LocalDate,
    val sessions: List<SessionDetail>,
    val changes: List<AlarmChange> = emptyList(),
)

/**
 * Day detail, stateless (owner redesign 2026-10-01, feedback item 25; opened from the Progress ring or calendar chip):
 * a one-line title ("Wed, Sep 23" in the phone's locale), then per session a hero card (outcome glyph and label,
 * "{time} · {label}", and the key figure large: "22 min to get up", or "Stopped after 30 minutes"), three small tiles
 * (rings · snoozes · paid, money in `text`, "No charge" when nothing was paid) and a vertical timeline of the morning
 * that draws in top to bottom (instant with reduced motion). Test and skipped days show the hero only. Logged alarm
 * changes follow as notes.
 */
@Composable
fun DayDetailScreen(
    state: DayDetailUiState,
    is24Hour: Boolean,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val entered = rememberEntered()
    SubScreen(
        title = formatDate(state.date, DateStyle.WeekdayDayMonth),
        backContentDescription = stringResource(Res.string.editor_back),
        onBack = onBack,
        modifier = modifier,
    ) {
        state.sessions.forEach { session ->
            HeroCard(session = session, is24Hour = is24Hour)
            if (session.counted) {
                SessionTiles(session)
                if (session.events.isNotEmpty()) Timeline(events = session.events, is24Hour = is24Hour, entered = entered)
            }
        }
        state.changes.forEach { change ->
            val time = formatClockTime(change.time, is24Hour)
            NoteInline(
                text = stringResource(if (change.deleted) Res.string.day_detail_deleted else Res.string.day_detail_turned_off, time),
                modifier = Modifier.padding(horizontal = PpsTheme.spacing.cardPadding),
            )
        }
    }
}

/** The hero: outcome glyph and label, the alarm, and the key figure (time to get up, or "Stopped after 30 minutes"). */
@Composable
private fun HeroCard(
    session: SessionDetail,
    is24Hour: Boolean,
) {
    val colors = PpsTheme.colors
    val spacing = PpsTheme.spacing
    val time = formatClockTime(session.alarmTime, is24Hour)
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .glass(PpsTheme.shapes.md)
                .padding(spacing.cardPadding)
                .semantics(mergeDescendants = true) { },
        verticalArrangement = Arrangement.spacedBy(spacing.space2),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(spacing.space3)) {
            OutcomeMarker(session.outcome, size = spacing.space6)
            Text(text = session.outcome.label(), style = PpsTheme.typography.title, color = colors.text)
        }
        Text(
            text = session.alarmLabel?.let { stringResource(Res.string.alarm_card_summary, time, it) } ?: time,
            style = PpsTheme.typography.body,
            color = colors.textSecondary,
        )
        when {
            session.outcome == Outcome.Missed -> {
                Text(text = stringResource(Res.string.day_event_stopped), style = PpsTheme.typography.headline, color = colors.text)
            }

            session.counted -> {
                session.minutesToUp?.let { minutes ->
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(
                            text =
                                if (minutes < 1) {
                                    stringResource(Res.string.progress_under_minute)
                                } else {
                                    stringResource(Res.string.progress_minutes, minutes)
                                },
                            style = PpsTheme.typography.display,
                            color = colors.text,
                        )
                        Text(
                            text = stringResource(Res.string.progress_tile_to_get_up),
                            modifier = Modifier.padding(start = spacing.space2, bottom = spacing.space2),
                            style = PpsTheme.typography.body,
                            color = colors.textSecondary,
                        )
                    }
                }
            }

            else -> {
                Unit
            }
        }
    }
}

/** Rings · snoozes · paid, like the Progress tiles; money in `text`, "No charge" when nothing was paid. */
@Composable
private fun SessionTiles(session: SessionDetail) {
    TileRow(
        tiles =
            listOf(
                Tile(Res.drawable.symbol_alarm, session.rings.toString(), stringResource(Res.string.day_tile_rings)),
                Tile(Res.drawable.symbol_snooze, session.snoozes.toString(), stringResource(Res.string.progress_tile_snoozes)),
                Tile(
                    Res.drawable.symbol_payments,
                    session.paid.takeIf { it.isNotEmpty() }?.let { formatMoney(it) } ?: stringResource(Res.string.day_tile_no_charge),
                    stringResource(Res.string.day_tile_paid),
                ),
            ),
    )
}

/**
 * The morning as a vertical timeline: time on the left (`caption`, tabular), a glyph on a hairline line, the event in
 * `body`. The line and the events draw in one after another from the top on entry.
 */
@Composable
private fun Timeline(
    events: List<MorningEvent>,
    is24Hour: Boolean,
    entered: Boolean,
) {
    Column(modifier = Modifier.fillMaxWidth().glass(PpsTheme.shapes.md).padding(vertical = PpsTheme.spacing.space3)) {
        events.forEachIndexed { index, event ->
            val progress = entranceProgress(entered, delayMillis = EVENT_DELAY_MILLIS + index * EVENT_STAGGER_MILLIS)
            TimelineRow(
                event = event,
                is24Hour = is24Hour,
                first = index == 0,
                last = index == events.lastIndex,
                progress = progress,
            )
        }
    }
}

@Composable
private fun TimelineRow(
    event: MorningEvent,
    is24Hour: Boolean,
    first: Boolean,
    last: Boolean,
    progress: Float,
) {
    val colors = PpsTheme.colors
    val spacing = PpsTheme.spacing
    val line = colors.outlineSubtle
    val stroke = with(LocalDensity.current) { LINE_WIDTH.toPx() }
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min)
                .heightIn(min = ROW_HEIGHT)
                .padding(horizontal = spacing.cardPadding)
                .semantics(mergeDescendants = true) { },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = formatClockTime(event.time, is24Hour),
            modifier = Modifier.width(TIME_WIDTH).graphicsLayer { alpha = progress },
            style = PpsTheme.typography.caption.copy(fontFeatureSettings = TABULAR_FIGURES),
            color = colors.textSecondary,
        )
        Box(
            modifier =
                Modifier
                    .width(MARKER_COLUMN)
                    .fillMaxHeight()
                    .drawBehind {
                        // The line grows down with the row: from the top (or the centre on the first row) to the bottom
                        // (or the centre on the last row).
                        val x = size.width / 2
                        val top = if (first) size.height / 2 else 0f
                        val bottom = if (last) size.height / 2 else size.height
                        if (bottom > top) {
                            drawLine(line, Offset(x, top), Offset(x, top + (bottom - top) * progress), strokeWidth = stroke)
                        }
                    },
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier =
                    Modifier.graphicsLayer {
                        alpha = progress
                        scaleX = progress
                        scaleY = progress
                    },
            ) { EventGlyph(event) }
        }
        Text(
            text = eventText(event, is24Hour),
            modifier =
                Modifier
                    .weight(1f)
                    .padding(start = spacing.space3, top = spacing.space2, bottom = spacing.space2)
                    .graphicsLayer {
                        alpha = progress
                        translationX = (1f - progress) * SLIDE_PX
                    },
            style = PpsTheme.typography.body,
            color = colors.text,
        )
    }
}

/** The event's glyph: outcome shapes where they mean an outcome, otherwise an icon in `text-secondary`. */
@Composable
private fun EventGlyph(event: MorningEvent) {
    val tint = PpsTheme.colors.textSecondary
    when (event) {
        is MorningEvent.Rang -> {
            RowIcon(
                icon = if (event.beforeFirstUnlock) Res.drawable.symbol_lock else Res.drawable.symbol_alarm,
                tint = tint,
            )
        }

        is MorningEvent.Snoozed -> {
            OutcomeMarker(Outcome.Snoozed)
        }

        is MorningEvent.ImUp -> {
            OutcomeMarker(Outcome.OnTime)
        }

        is MorningEvent.QuietTimeRanOut -> {
            RowIcon(icon = Res.drawable.symbol_timer, tint = tint)
        }

        is MorningEvent.CheckSwitched -> {
            RowIcon(icon = event.to.icon, tint = tint)
        }

        is MorningEvent.FallbackUsed -> {
            RowIcon(icon = Res.drawable.symbol_alt_route, tint = tint)
        }

        is MorningEvent.Merged -> {
            RowIcon(icon = Res.drawable.symbol_alarm, tint = tint)
        }

        is MorningEvent.CheckSolved -> {
            RowIcon(icon = event.check.icon, tint = tint)
        }

        is MorningEvent.Stopped -> {
            OutcomeMarker(Outcome.Missed)
        }
    }
}

@Composable
private fun eventText(
    event: MorningEvent,
    is24Hour: Boolean,
): String =
    when (event) {
        is MorningEvent.Rang -> {
            when {
                event.beforeFirstUnlock -> stringResource(Res.string.day_detail_before_unlock)
                event.again -> stringResource(Res.string.day_event_rang_again)
                else -> stringResource(Res.string.day_event_rang)
            }
        }

        is MorningEvent.Snoozed -> {
            stringResource(Res.string.day_event_snoozed, event.minutes, formatMoney(event.price))
        }

        is MorningEvent.ImUp -> {
            stringResource(Res.string.wake_im_up)
        }

        is MorningEvent.QuietTimeRanOut -> {
            stringResource(Res.string.day_event_quiet_ended)
        }

        is MorningEvent.CheckSwitched -> {
            stringResource(Res.string.day_event_switched, event.to.displayName())
        }

        is MorningEvent.FallbackUsed -> {
            stringResource(Res.string.day_event_fallback, event.check.displayName())
        }

        is MorningEvent.Merged -> {
            stringResource(Res.string.day_detail_merged, formatClockTime(event.alarmTime, is24Hour))
        }

        is MorningEvent.CheckSolved -> {
            if (event.tries <= 1) {
                stringResource(Res.string.day_event_solved_first, event.check.displayName())
            } else {
                stringResource(Res.string.day_event_solved_tries, event.check.displayName(), event.tries)
            }
        }

        is MorningEvent.Stopped -> {
            stringResource(Res.string.day_event_stopped)
        }
    }

private const val SECONDS_PER_MINUTE = 60

/** The timeline starts once the hero and tiles are in. */
private const val EVENT_DELAY_MILLIS = 150

private const val EVENT_STAGGER_MILLIS = 90

/** Room for "07:30" and "12:30 PM" at 100%. */
private val TIME_WIDTH = 64.dp

private val MARKER_COLUMN = 24.dp

private val ROW_HEIGHT = 44.dp

private val LINE_WIDTH = 1.5.dp

/** Each event's text slides in from 12 px to the right. */
private const val SLIDE_PX = 12f
