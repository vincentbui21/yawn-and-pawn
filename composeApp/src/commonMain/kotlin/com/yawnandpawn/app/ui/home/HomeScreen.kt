package com.yawnandpawn.app.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.yawnandpawn.app.ui.checks.displayName
import com.yawnandpawn.app.ui.checks.icon
import com.yawnandpawn.app.ui.components.BannerWarning
import com.yawnandpawn.app.ui.components.ConfirmDialog
import com.yawnandpawn.app.ui.components.DismissButton
import com.yawnandpawn.app.ui.components.NoteInline
import com.yawnandpawn.app.ui.components.PpsBackground
import com.yawnandpawn.app.ui.components.PpsFab
import com.yawnandpawn.app.ui.components.PpsFilledButton
import com.yawnandpawn.app.ui.components.PpsSwitch
import com.yawnandpawn.app.ui.components.RowIcon
import com.yawnandpawn.app.ui.components.glass
import com.yawnandpawn.app.ui.format.Countdown
import com.yawnandpawn.app.ui.format.countdownText
import com.yawnandpawn.app.ui.format.formatClockTime
import com.yawnandpawn.app.ui.format.formatMoney
import com.yawnandpawn.app.ui.format.repeatSummary
import com.yawnandpawn.app.ui.resources.Res
import com.yawnandpawn.app.ui.resources.alarm_card_summary
import com.yawnandpawn.app.ui.resources.alarm_card_switch
import com.yawnandpawn.app.ui.resources.alarms_add_alarm
import com.yawnandpawn.app.ui.resources.alarms_add_first
import com.yawnandpawn.app.ui.resources.alarms_empty_title
import com.yawnandpawn.app.ui.resources.app_name
import com.yawnandpawn.app.ui.resources.disable_keep_on
import com.yawnandpawn.app.ui.resources.disable_turn_off
import com.yawnandpawn.app.ui.resources.disable_under_lock_hours
import com.yawnandpawn.app.ui.resources.disable_under_lock_minutes
import com.yawnandpawn.app.ui.resources.home_dismiss
import com.yawnandpawn.app.ui.resources.home_fallback_banner
import com.yawnandpawn.app.ui.resources.home_fix
import com.yawnandpawn.app.ui.resources.home_missed_note
import com.yawnandpawn.app.ui.resources.home_paid_week
import com.yawnandpawn.app.ui.resources.home_reliability_banner
import com.yawnandpawn.app.ui.resources.home_reregister
import com.yawnandpawn.app.ui.resources.home_streak_day
import com.yawnandpawn.app.ui.resources.home_streak_days
import com.yawnandpawn.app.ui.resources.home_zero_paid
import com.yawnandpawn.app.ui.resources.session_back_to_alarm
import com.yawnandpawn.app.ui.resources.session_in_progress_title
import com.yawnandpawn.app.ui.theme.PpsTheme
import org.jetbrains.compose.resources.stringResource

/**
 * Home (the Alarms tab), stateless: header, the reliability `banner-warning`, the fallback re-register info banner, the
 * missed note, `card-hero` (streak and money), the next-alarm countdown, the `card-alarm` list and the `fab`; or the
 * empty state; or, during a session, only `panel-session-in-progress`.
 */
@Composable
fun HomeScreen(
    state: HomeUiState,
    is24Hour: Boolean,
    onIntent: (HomeIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = PpsTheme.colors
    val spacing = PpsTheme.spacing
    PpsBackground(modifier = modifier) {
        Column(modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars)) {
            Text(
                text = stringResource(Res.string.app_name),
                modifier = Modifier.padding(horizontal = spacing.screenMargin, vertical = spacing.space4).semantics { heading() },
                style = PpsTheme.typography.headline,
                color = colors.text,
            )
            when {
                state.sessionInProgress -> SessionPanel(onBackToAlarm = { onIntent(HomeIntent.BackToAlarm) })
                state.alarms.isEmpty() -> EmptyHome(state = state, is24Hour = is24Hour, onIntent = onIntent, modifier = Modifier.weight(1f))
                else -> HomeList(state = state, is24Hour = is24Hour, onIntent = onIntent, modifier = Modifier.weight(1f))
            }
        }
        if (!state.sessionInProgress) {
            PpsFab(
                contentDescription = stringResource(Res.string.alarms_add_alarm),
                onClick = { onIntent(HomeIntent.AddAlarm) },
                modifier = Modifier.align(Alignment.BottomEnd).navigationBarsPadding().padding(spacing.screenMargin),
            )
        }
    }
    state.disableDialog?.let { dialog -> DisableDialog(dialog = dialog, is24Hour = is24Hour, onIntent = onIntent) }
}

@Composable
private fun HomeList(
    state: HomeUiState,
    is24Hour: Boolean,
    onIntent: (HomeIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = PpsTheme.spacing
    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        // Room below the last card for the FAB (56 dp plus its margins).
        contentPadding = PaddingValues(start = spacing.screenMargin, end = spacing.screenMargin, bottom = LIST_BOTTOM_PADDING),
        verticalArrangement = Arrangement.spacedBy(spacing.space3),
    ) {
        item(key = "notices") { Notices(state = state, is24Hour = is24Hour, onIntent = onIntent) }
        state.hero?.let { hero -> item(key = "hero") { HeroCard(hero) } }
        state.nextAlarm?.let { countdown ->
            item(key = "next") {
                Text(
                    text = countdownText(countdown),
                    modifier = Modifier.padding(top = spacing.space2),
                    style = PpsTheme.typography.title,
                    color = PpsTheme.colors.text,
                )
            }
        }
        items(state.alarms, key = { it.id }) { alarm ->
            AlarmCardView(
                // Cards animate in and out when an alarm is added or removed (owner decision 2026-09-27).
                modifier = Modifier.animateItem(),
                alarm = alarm,
                is24Hour = is24Hour,
                onClick = { onIntent(HomeIntent.EditAlarm(alarm.id)) },
                onToggle = { onIntent(HomeIntent.AlarmToggled(alarm.id, it)) },
            )
        }
    }
}

/** Banners and the missed note, stacked above the hero. */
@Composable
private fun Notices(
    state: HomeUiState,
    is24Hour: Boolean,
    onIntent: (HomeIntent) -> Unit,
) {
    val spacing = PpsTheme.spacing
    Column(verticalArrangement = Arrangement.spacedBy(spacing.space3)) {
        if (state.reliabilityProblem) {
            BannerWarning(
                message = stringResource(Res.string.home_reliability_banner),
                actionText = stringResource(Res.string.home_fix),
                onAction = { onIntent(HomeIntent.FixSettings) },
            )
        }
        state.reregisterCheck?.let { check ->
            BannerWarning(
                message = stringResource(Res.string.home_fallback_banner, check.displayName()),
                actionText = stringResource(Res.string.home_reregister),
                onAction = { onIntent(HomeIntent.ReregisterClicked) },
                info = true,
                onDismiss = { onIntent(HomeIntent.ReregisterDismissed) },
                dismissLabel = stringResource(Res.string.home_dismiss),
            )
        }
        state.missedAlarmAt?.let { time ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                NoteInline(
                    text = stringResource(Res.string.home_missed_note, formatClockTime(time, is24Hour)),
                    modifier = Modifier.weight(1f),
                )
                DismissButton(label = stringResource(Res.string.home_dismiss), onClick = { onIntent(HomeIntent.MissedNoteDismissed) })
            }
        }
    }
}

/** `card-hero` (glass): streak number in `display` (`accent-text`), "days on time" in `body`, the money line in `text-secondary`. */
@Composable
private fun HeroCard(hero: HomeHero) {
    val colors = PpsTheme.colors
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .glass(PpsTheme.shapes.md)
                .padding(PpsTheme.spacing.cardPadding)
                .semantics(mergeDescendants = true) { },
    ) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(text = hero.streakDays.toString(), style = PpsTheme.typography.display, color = colors.accentText)
            Text(
                text = stringResource(if (hero.streakDays == 1) Res.string.home_streak_day else Res.string.home_streak_days),
                modifier = Modifier.padding(start = PpsTheme.spacing.space2, bottom = PpsTheme.spacing.space1),
                style = PpsTheme.typography.body,
                color = colors.text,
            )
        }
        Text(
            text =
                hero.paidThisWeek?.let { stringResource(Res.string.home_paid_week, formatMoney(it)) }
                    ?: stringResource(Res.string.home_zero_paid),
            style = PpsTheme.typography.body,
            color = colors.textSecondary,
        )
    }
}

/**
 * `card-alarm`: glass, `rounded.md`; time in `title`, repeat days and label in `caption`, check icons (20 dp,
 * `text-secondary`) and the enable `switch` on the right. The card is one button (tap edits); the switch is its own
 * control ("7:30 AM alarm").
 */
@Composable
private fun AlarmCardView(
    alarm: AlarmCard,
    is24Hour: Boolean,
    onClick: () -> Unit,
    onToggle: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = PpsTheme.colors
    val spacing = PpsTheme.spacing
    val time = formatClockTime(alarm.time, is24Hour)
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .heightIn(min = spacing.targetWake)
                .glass(PpsTheme.shapes.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier =
                Modifier
                    .weight(1f)
                    .clickable(role = Role.Button, onClick = onClick)
                    .padding(spacing.cardPadding),
            verticalArrangement = Arrangement.spacedBy(spacing.space1),
        ) {
            Text(text = time, style = PpsTheme.typography.title, color = if (alarm.enabled) colors.text else colors.textSecondary)
            val repeat = repeatSummary(alarm.repeatDays)
            Text(
                text = alarm.label?.let { stringResource(Res.string.alarm_card_summary, repeat, it) } ?: repeat,
                style = PpsTheme.typography.caption,
                color = colors.textSecondary,
            )
            if (alarm.checks.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(spacing.space2)) {
                    alarm.checks.forEach { check -> RowIcon(icon = check.icon, tint = colors.textSecondary) }
                }
            }
        }
        PpsSwitch(
            checked = alarm.enabled,
            onCheckedChange = onToggle,
            label = stringResource(Res.string.alarm_card_switch, time),
            modifier = Modifier.padding(end = spacing.cardPadding),
        )
    }
}

/** Home empty: "No alarms yet." with `button-filled` "Add your first alarm" (notices still show above it). */
@Composable
private fun EmptyHome(
    state: HomeUiState,
    is24Hour: Boolean,
    onIntent: (HomeIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = PpsTheme.spacing
    Column(modifier = modifier.fillMaxWidth().padding(horizontal = spacing.screenMargin)) {
        Notices(state = state, is24Hour = is24Hour, onIntent = onIntent)
        Column(
            modifier = Modifier.weight(1f).fillMaxWidth(),
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
                onClick = { onIntent(HomeIntent.AddAlarm) },
                modifier = Modifier.padding(top = spacing.space6),
            )
        }
    }
}

/** `panel-session-in-progress`: replaces Home content during a session. */
@Composable
private fun SessionPanel(onBackToAlarm: () -> Unit) {
    val spacing = PpsTheme.spacing
    Column(
        modifier =
            Modifier
                .padding(horizontal = spacing.screenMargin)
                .fillMaxWidth()
                .glass(PpsTheme.shapes.md)
                .padding(spacing.cardPadding),
        verticalArrangement = Arrangement.spacedBy(spacing.space4),
    ) {
        Text(
            text = stringResource(Res.string.session_in_progress_title),
            modifier = Modifier.semantics { heading() },
            style = PpsTheme.typography.headline,
            color = PpsTheme.colors.text,
        )
        PpsFilledButton(
            text = stringResource(Res.string.session_back_to_alarm),
            onClick = onBackToAlarm,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** `dialog-confirm` for switching an alarm off under the commitment lock; "Keep it on" is the default dismiss. */
@Composable
private fun DisableDialog(
    dialog: DisableUnderLock,
    is24Hour: Boolean,
    onIntent: (HomeIntent) -> Unit,
) {
    val time = formatClockTime(dialog.time, is24Hour)
    val title =
        when (val ringsIn = dialog.ringsIn) {
            is Countdown.Minutes -> {
                stringResource(Res.string.disable_under_lock_minutes, time, ringsIn.minutes)
            }

            is Countdown.HoursMinutes -> {
                stringResource(Res.string.disable_under_lock_hours, time, ringsIn.hours)
            }

            is Countdown.DaysHours -> {
                stringResource(
                    Res.string.disable_under_lock_hours,
                    time,
                    ringsIn.days * HOURS_PER_DAY + ringsIn.hours,
                )
            }
        }
    ConfirmDialog(
        title = title,
        confirmText = stringResource(Res.string.disable_turn_off),
        safeText = stringResource(Res.string.disable_keep_on),
        onConfirm = { onIntent(HomeIntent.DisableConfirmed) },
        onSafe = { onIntent(HomeIntent.DisableCancelled) },
        destructive = true,
    )
}

private const val HOURS_PER_DAY = 24
private val LIST_BOTTOM_PADDING = 96.dp
