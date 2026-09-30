package com.yawnandpawn.app.ui.daydetail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import com.yawnandpawn.app.ui.checks.CheckType
import com.yawnandpawn.app.ui.checks.displayName
import com.yawnandpawn.app.ui.components.GroupCard
import com.yawnandpawn.app.ui.components.GroupDivider
import com.yawnandpawn.app.ui.components.NoteInline
import com.yawnandpawn.app.ui.components.RowIcon
import com.yawnandpawn.app.ui.components.SubScreen
import com.yawnandpawn.app.ui.components.ValueEndRow
import com.yawnandpawn.app.ui.format.Money
import com.yawnandpawn.app.ui.format.formatClockTime
import com.yawnandpawn.app.ui.format.formatLongDate
import com.yawnandpawn.app.ui.format.formatMoney
import com.yawnandpawn.app.ui.progress.FallbackBadge
import com.yawnandpawn.app.ui.progress.Outcome
import com.yawnandpawn.app.ui.progress.OutcomeMarker
import com.yawnandpawn.app.ui.progress.label
import com.yawnandpawn.app.ui.resources.Res
import com.yawnandpawn.app.ui.resources.alarm_card_summary
import com.yawnandpawn.app.ui.resources.day_detail_before_unlock
import com.yawnandpawn.app.ui.resources.day_detail_deleted
import com.yawnandpawn.app.ui.resources.day_detail_fallback
import com.yawnandpawn.app.ui.resources.day_detail_merged
import com.yawnandpawn.app.ui.resources.day_detail_paid
import com.yawnandpawn.app.ui.resources.day_detail_rings
import com.yawnandpawn.app.ui.resources.day_detail_snoozes
import com.yawnandpawn.app.ui.resources.day_detail_time_to_up
import com.yawnandpawn.app.ui.resources.day_detail_turned_off
import com.yawnandpawn.app.ui.resources.editor_back
import com.yawnandpawn.app.ui.resources.editor_checks
import com.yawnandpawn.app.ui.resources.progress_minutes
import com.yawnandpawn.app.ui.resources.progress_under_minute
import com.yawnandpawn.app.ui.resources.symbol_lock
import com.yawnandpawn.app.ui.theme.PpsTheme
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import org.jetbrains.compose.resources.stringResource

/** One session of the day (Day detail). Test and skipped sessions show their outcome label only. */
data class SessionDetail(
    val alarmTime: LocalTime,
    val alarmLabel: String?,
    val outcome: Outcome,
    val rings: Int = 1,
    val snoozes: Int = 0,
    /** What this session charged; `null` when nothing was paid. */
    val paid: Money? = null,
    val checks: List<CheckType> = emptyList(),
    /** Minutes from first ring to "I'm up"; 0 is "Under 1 min". */
    val minutesToUp: Int? = null,
    val fallbackUsed: Boolean = false,
    val rangBeforeFirstUnlock: Boolean = false,
    /** Another alarm that rang during this session and was merged into it. */
    val mergedAlarmAt: LocalTime? = null,
)

/** A logged change to an alarm that day ("Your 7:30 alarm was turned off. Logged."). */
data class AlarmChange(
    val time: LocalTime,
    val deleted: Boolean,
)

/** What Day detail renders: one card per session of [date], then the logged alarm changes. */
data class DayDetailUiState(
    val date: LocalDate,
    val sessions: List<SessionDetail>,
    val changes: List<AlarmChange> = emptyList(),
)

/**
 * Day detail, stateless (pushed from a `calendar-day`): the long date as the title, then per session its
 * `outcome-marker` with the label, the flags ("Fallback check used", "Rang before your first unlock") and its rings,
 * snoozes, paid amount (`text`), checks and time to up. A test or skipped session shows the outcome label only (it is
 * excluded from rates and streaks). Logged alarm changes follow as notes.
 */
@Composable
fun DayDetailScreen(
    state: DayDetailUiState,
    is24Hour: Boolean,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SubScreen(
        title = formatLongDate(state.date),
        backContentDescription = stringResource(Res.string.editor_back),
        onBack = onBack,
        modifier = modifier,
    ) {
        state.sessions.forEach { session -> SessionCard(session = session, is24Hour = is24Hour) }
        state.changes.forEach { change ->
            val time = formatClockTime(change.time, is24Hour)
            NoteInline(
                text = stringResource(if (change.deleted) Res.string.day_detail_deleted else Res.string.day_detail_turned_off, time),
                modifier = Modifier.padding(horizontal = PpsTheme.spacing.cardPadding),
            )
        }
    }
}

@Composable
private fun SessionCard(
    session: SessionDetail,
    is24Hour: Boolean,
) {
    val time = formatClockTime(session.alarmTime, is24Hour)
    val counted = session.outcome != Outcome.Test && session.outcome != Outcome.Skipped
    GroupCard(title = session.alarmLabel?.let { stringResource(Res.string.alarm_card_summary, time, it) } ?: time) {
        OutcomeRow(session.outcome)
        if (session.fallbackUsed) {
            GroupDivider()
            FlagRow(text = stringResource(Res.string.day_detail_fallback)) { FallbackBadge(size = PpsTheme.spacing.space5) }
        }
        if (session.rangBeforeFirstUnlock) {
            GroupDivider()
            FlagRow(text = stringResource(Res.string.day_detail_before_unlock)) {
                RowIcon(icon = Res.drawable.symbol_lock, tint = PpsTheme.colors.textSecondary)
            }
        }
        if (counted) {
            GroupDivider()
            ValueEndRow(label = stringResource(Res.string.day_detail_rings), value = session.rings.toString())
            GroupDivider()
            ValueEndRow(label = stringResource(Res.string.day_detail_snoozes), value = session.snoozes.toString())
            session.paid?.let { paid ->
                GroupDivider()
                ValueEndRow(label = stringResource(Res.string.day_detail_paid), value = formatMoney(paid))
            }
            if (session.checks.isNotEmpty()) {
                GroupDivider()
                ValueEndRow(
                    label = stringResource(Res.string.editor_checks),
                    value = session.checks.map { it.displayName() }.joinToString(),
                )
            }
            session.minutesToUp?.let { minutes ->
                GroupDivider()
                ValueEndRow(
                    label = stringResource(Res.string.day_detail_time_to_up),
                    value =
                        if (minutes <
                            1
                        ) {
                            stringResource(Res.string.progress_under_minute)
                        } else {
                            stringResource(Res.string.progress_minutes, minutes)
                        },
                )
            }
        }
        session.mergedAlarmAt?.let { merged ->
            GroupDivider()
            NoteInline(
                text = stringResource(Res.string.day_detail_merged, formatClockTime(merged, is24Hour)),
                modifier = Modifier.padding(PpsTheme.spacing.cardPadding),
            )
        }
    }
}

/** The outcome: its marker and label in `title`. */
@Composable
private fun OutcomeRow(outcome: Outcome) {
    Row(
        modifier = Modifier.padding(PpsTheme.spacing.cardPadding).semantics(mergeDescendants = true) { },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(PpsTheme.spacing.space3),
    ) {
        OutcomeMarker(outcome, size = PpsTheme.spacing.space6)
        Text(text = outcome.label(), style = PpsTheme.typography.title, color = PpsTheme.colors.text)
    }
}

@Composable
private fun FlagRow(
    text: String,
    icon: @Composable () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .padding(horizontal = PpsTheme.spacing.cardPadding, vertical = PpsTheme.spacing.space3)
                .semantics(mergeDescendants = true) { },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(PpsTheme.spacing.space3),
    ) {
        icon()
        Column { Text(text = text, style = PpsTheme.typography.body, color = PpsTheme.colors.text) }
    }
}
