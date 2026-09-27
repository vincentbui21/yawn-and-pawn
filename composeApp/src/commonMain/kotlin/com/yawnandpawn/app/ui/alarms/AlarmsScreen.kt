package com.yawnandpawn.app.ui.alarms

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yawnandpawn.app.ui.components.PpsFab
import com.yawnandpawn.app.ui.components.PpsFilledButton
import com.yawnandpawn.app.ui.format.formatClockTime
import com.yawnandpawn.app.ui.format.is24HourClock
import com.yawnandpawn.app.ui.format.repeatSummary
import com.yawnandpawn.app.ui.resources.Res
import com.yawnandpawn.app.ui.resources.alarms_add_alarm
import com.yawnandpawn.app.ui.resources.alarms_add_first
import com.yawnandpawn.app.ui.resources.alarms_empty_title
import com.yawnandpawn.app.ui.resources.app_name
import com.yawnandpawn.app.ui.theme.PpsTheme
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel

/** The Alarms route: its ViewModel (scoped to the nav entry) feeding [AlarmsScreen]. */
@Composable
fun AlarmsRoute(
    onAddAlarm: () -> Unit,
    onEditAlarm: (String) -> Unit,
    viewModel: AlarmsViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    AlarmsScreen(state = state, is24Hour = is24HourClock(), onAddAlarm = onAddAlarm, onEditAlarm = onEditAlarm)
}

/**
 * Home, Story 1.8 scope: the empty state ("No alarms yet." with "Add your first alarm"), or an interim list of the
 * alarms (time, label or repeat summary, tap to edit). The `fab` adds an alarm. Story 1.9 builds `card-alarm`,
 * the next-alarm countdown and the enable switches.
 */
@Composable
fun AlarmsScreen(
    state: AlarmsUiState,
    is24Hour: Boolean,
    onAddAlarm: () -> Unit,
    onEditAlarm: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = PpsTheme.colors
    val spacing = PpsTheme.spacing
    Box(modifier = modifier.fillMaxSize().background(colors.bg)) {
        Column(modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars)) {
            Text(
                text = stringResource(Res.string.app_name),
                modifier =
                    Modifier
                        .padding(horizontal = spacing.screenMargin, vertical = spacing.space4)
                        .semantics { heading() },
                style = PpsTheme.typography.headline,
                color = colors.text,
            )
            when {
                state.isLoading || state.loadFailed -> Unit
                state.alarms.isEmpty() -> EmptyState(onAddAlarm = onAddAlarm, modifier = Modifier.weight(1f))
                else -> AlarmList(alarms = state.alarms, is24Hour = is24Hour, onEditAlarm = onEditAlarm, modifier = Modifier.weight(1f))
            }
        }
        PpsFab(
            contentDescription = stringResource(Res.string.alarms_add_alarm),
            onClick = onAddAlarm,
            modifier =
                Modifier
                    .align(Alignment.BottomEnd)
                    .navigationBarsPadding()
                    .padding(spacing.screenMargin),
        )
    }
}

@Composable
private fun EmptyState(
    onAddAlarm: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = PpsTheme.spacing
    Column(
        modifier = modifier.fillMaxWidth().padding(horizontal = spacing.screenMargin),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(Res.string.alarms_empty_title),
            style = PpsTheme.typography.title,
            color = PpsTheme.colors.text,
            textAlign = TextAlign.Center,
        )
        PpsFilledButton(
            text = stringResource(Res.string.alarms_add_first),
            onClick = onAddAlarm,
            modifier = Modifier.padding(top = spacing.space6),
        )
    }
}

@Composable
private fun AlarmList(
    alarms: List<AlarmRow>,
    is24Hour: Boolean,
    onEditAlarm: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = PpsTheme.spacing
    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        // Room below the last row for the FAB (56 dp plus its margins).
        contentPadding = PaddingValues(start = spacing.screenMargin, end = spacing.screenMargin, bottom = LIST_BOTTOM_PADDING),
        verticalArrangement = Arrangement.spacedBy(spacing.space2),
    ) {
        items(alarms, key = { it.id }) { alarm ->
            AlarmListRow(alarm = alarm, is24Hour = is24Hour, onClick = { onEditAlarm(alarm.id) })
        }
    }
}

@Composable
private fun AlarmListRow(
    alarm: AlarmRow,
    is24Hour: Boolean,
    onClick: () -> Unit,
) {
    val colors = PpsTheme.colors
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = PpsTheme.spacing.targetWake)
                .clip(PpsTheme.shapes.md)
                .background(colors.surface)
                .clickable(role = Role.Button, onClick = onClick)
                .padding(PpsTheme.spacing.cardPadding),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(text = formatClockTime(alarm.time, is24Hour), style = PpsTheme.typography.title, color = colors.text)
        Text(
            text = alarm.label ?: repeatSummary(alarm.repeatDays),
            style = PpsTheme.typography.caption,
            color = colors.textSecondary,
        )
    }
}

private val LIST_BOTTOM_PADDING = 96.dp
