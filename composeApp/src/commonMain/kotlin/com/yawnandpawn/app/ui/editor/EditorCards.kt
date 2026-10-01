package com.yawnandpawn.app.ui.editor

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.yawnandpawn.app.ui.components.DayChipRow
import com.yawnandpawn.app.ui.components.GroupCard
import com.yawnandpawn.app.ui.components.NoteInline
import com.yawnandpawn.app.ui.components.PpsSegmentedControl
import com.yawnandpawn.app.ui.components.PpsWheelTimePicker
import com.yawnandpawn.app.ui.format.formatClockTime
import com.yawnandpawn.app.ui.resources.Res
import com.yawnandpawn.app.ui.resources.editor_repeat
import com.yawnandpawn.app.ui.resources.editor_rings_tomorrow
import com.yawnandpawn.app.ui.resources.repeat_custom
import com.yawnandpawn.app.ui.resources.repeat_once
import com.yawnandpawn.app.ui.resources.repeat_weekdays
import com.yawnandpawn.app.ui.theme.PpsTheme
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalTime
import org.jetbrains.compose.resources.stringResource

/**
 * The time wheels in their own card and, for a one-time alarm whose time has passed today, "Rings tomorrow at {time}."
 * (the editor and the onboarding first alarm).
 */
@Composable
internal fun TimeWheelCard(
    time: LocalTime,
    is24Hour: Boolean,
    onTimeChange: (LocalTime) -> Unit,
    ringsTomorrowAt: LocalTime? = null,
) {
    val spacing = PpsTheme.spacing
    GroupCard {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = spacing.space2, vertical = spacing.space3),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            PpsWheelTimePicker(time = time, is24Hour = is24Hour, onTimeChange = onTimeChange)
            ringsTomorrowAt?.let { ringTime ->
                NoteInline(
                    text = stringResource(Res.string.editor_rings_tomorrow, formatClockTime(ringTime, is24Hour)),
                    modifier = Modifier.padding(top = spacing.space2),
                )
            }
        }
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
