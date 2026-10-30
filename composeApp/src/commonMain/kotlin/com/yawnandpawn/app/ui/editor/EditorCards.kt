package com.yawnandpawn.app.ui.editor

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.yawnandpawn.app.ui.components.DayChipRow
import com.yawnandpawn.app.ui.components.GroupCard
import com.yawnandpawn.app.ui.components.NoteInline
import com.yawnandpawn.app.ui.components.PpsSegmentedControl
import com.yawnandpawn.app.ui.components.PpsWheelTimePicker
import com.yawnandpawn.app.ui.format.DayNameStyle
import com.yawnandpawn.app.ui.format.dayName
import com.yawnandpawn.app.ui.format.formatClockTime
import com.yawnandpawn.app.ui.resources.Res
import com.yawnandpawn.app.ui.resources.editor_repeat
import com.yawnandpawn.app.ui.resources.editor_rings_next_day
import com.yawnandpawn.app.ui.resources.editor_rings_on_day
import com.yawnandpawn.app.ui.resources.editor_rings_today
import com.yawnandpawn.app.ui.resources.editor_rings_tomorrow
import com.yawnandpawn.app.ui.resources.repeat_custom
import com.yawnandpawn.app.ui.resources.repeat_once
import com.yawnandpawn.app.ui.resources.repeat_weekdays
import com.yawnandpawn.app.ui.theme.PpsTheme
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalTime
import org.jetbrains.compose.resources.stringResource

/**
 * The time wheels in their own card and, always under them, when the alarm rings: "Rings today at {time}.", "Rings
 * tomorrow at {time}." or "Rings on {weekday} at {time}." (the editor and the onboarding first alarm). Until [ringsAt]
 * is known the line's space stays reserved (empty and silent), so the card never changes height.
 */
@Composable
internal fun TimeWheelCard(
    time: LocalTime,
    is24Hour: Boolean,
    onTimeChange: (LocalTime) -> Unit,
    ringsAt: RingsAt? = null,
) {
    val spacing = PpsTheme.spacing
    GroupCard {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = spacing.space2, vertical = spacing.space3),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            PpsWheelTimePicker(time = time, is24Hour = is24Hour, onTimeChange = onTimeChange)
            val noteModifier = Modifier.padding(top = spacing.space2)
            if (ringsAt != null) {
                NoteInline(text = ringsLine(ringsAt, is24Hour), modifier = noteModifier)
            } else {
                // Holds the line's height (one line of the note: its icon or its caption) while the next ring is unknown,
                // with nothing for TalkBack to read.
                val lineHeight =
                    with(LocalDensity.current) {
                        PpsTheme.typography.caption.lineHeight
                            .toDp()
                    }
                Spacer(noteModifier.height(maxOf(NOTE_ICON, lineHeight)))
            }
        }
    }
}

/**
 * "Rings today at 7:00 AM.", "Rings tomorrow at 7:00 AM.", "Rings on Monday at 7:00 AM." or, a week or more ahead (today's
 * weekday whose time has passed, or a skipped ring), "Rings next Wednesday at 7:00 AM.", so it never reads as today.
 */
@Composable
private fun ringsLine(
    ringsAt: RingsAt,
    is24Hour: Boolean,
): String {
    val time = formatClockTime(ringsAt.time, is24Hour)
    return when (ringsAt.daysAhead) {
        0 -> stringResource(Res.string.editor_rings_today, time)
        1 -> stringResource(Res.string.editor_rings_tomorrow, time)
        in 2 until DAYS_PER_WEEK -> stringResource(Res.string.editor_rings_on_day, dayName(ringsAt.dayOfWeek, DayNameStyle.Full), time)
        else -> stringResource(Res.string.editor_rings_next_day, dayName(ringsAt.dayOfWeek, DayNameStyle.Full), time)
    }
}

/** "Once" · "Weekdays" · "Custom"; Custom expands to the seven day chips (the editor and the onboarding first alarm). */
@Composable
internal fun RepeatChoiceCard(
    choice: RepeatChoice,
    days: Set<DayOfWeek>,
    onChoose: (RepeatChoice) -> Unit,
    onToggleDay: (DayOfWeek) -> Unit,
) {
    val spacing = PpsTheme.spacing
    GroupCard(title = stringResource(Res.string.editor_repeat)) {
        Column(modifier = Modifier.fillMaxWidth().padding(spacing.space3)) {
            PpsSegmentedControl(
                options = RepeatChoice.entries,
                selected = choice,
                label = { option ->
                    stringResource(
                        when (option) {
                            RepeatChoice.Once -> Res.string.repeat_once
                            RepeatChoice.Weekdays -> Res.string.repeat_weekdays
                            RepeatChoice.Custom -> Res.string.repeat_custom
                        },
                    )
                },
                onSelect = onChoose,
            )
            AnimatedVisibility(
                visible = choice == RepeatChoice.Custom,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut(),
            ) {
                DayChipRow(
                    selectedDays = days,
                    onToggle = onToggleDay,
                    modifier = Modifier.padding(top = spacing.space3),
                )
            }
        }
    }
}

private const val DAYS_PER_WEEK = 7

/** `note-inline`'s icon size, the least height of its line. */
private val NOTE_ICON = 20.dp
