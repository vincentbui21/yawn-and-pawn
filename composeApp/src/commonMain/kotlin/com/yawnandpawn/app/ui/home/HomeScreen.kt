package com.yawnandpawn.app.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.yawnandpawn.app.ui.checks.displayName
import com.yawnandpawn.app.ui.checks.icon
import com.yawnandpawn.app.ui.components.BannerWarning
import com.yawnandpawn.app.ui.components.ConfirmDialog
import com.yawnandpawn.app.ui.components.DismissButton
import com.yawnandpawn.app.ui.components.LocalNavBarClearance
import com.yawnandpawn.app.ui.components.NoteInline
import com.yawnandpawn.app.ui.components.PpsBackground
import com.yawnandpawn.app.ui.components.PpsFilledButton
import com.yawnandpawn.app.ui.components.PpsMenu
import com.yawnandpawn.app.ui.components.PpsMenuItem
import com.yawnandpawn.app.ui.components.PpsSwitch
import com.yawnandpawn.app.ui.components.PpsTextButton
import com.yawnandpawn.app.ui.components.RowIcon
import com.yawnandpawn.app.ui.components.SessionInProgressPanel
import com.yawnandpawn.app.ui.components.entrance
import com.yawnandpawn.app.ui.components.glass
import com.yawnandpawn.app.ui.components.glassSource
import com.yawnandpawn.app.ui.components.rememberEnteredOnce
import com.yawnandpawn.app.ui.components.rememberGlassBackdrop
import com.yawnandpawn.app.ui.format.Countdown
import com.yawnandpawn.app.ui.format.countdownText
import com.yawnandpawn.app.ui.format.formatClockTime
import com.yawnandpawn.app.ui.format.formatMoney
import com.yawnandpawn.app.ui.format.repeatSummary
import com.yawnandpawn.app.ui.resources.Res
import com.yawnandpawn.app.ui.resources.alarm_card_options
import com.yawnandpawn.app.ui.resources.alarm_card_skipped
import com.yawnandpawn.app.ui.resources.alarm_card_summary
import com.yawnandpawn.app.ui.resources.alarm_card_switch
import com.yawnandpawn.app.ui.resources.alarm_delete
import com.yawnandpawn.app.ui.resources.alarm_dont_skip
import com.yawnandpawn.app.ui.resources.alarm_duplicate
import com.yawnandpawn.app.ui.resources.alarms_add_alarm
import com.yawnandpawn.app.ui.resources.alarms_add_first
import com.yawnandpawn.app.ui.resources.alarms_empty_title
import com.yawnandpawn.app.ui.resources.home_dismiss
import com.yawnandpawn.app.ui.resources.home_fallback_banner
import com.yawnandpawn.app.ui.resources.home_fix
import com.yawnandpawn.app.ui.resources.home_load_failed
import com.yawnandpawn.app.ui.resources.home_missed_note
import com.yawnandpawn.app.ui.resources.home_paid_week
import com.yawnandpawn.app.ui.resources.home_reliability_banner
import com.yawnandpawn.app.ui.resources.home_reregister
import com.yawnandpawn.app.ui.resources.home_streak_day
import com.yawnandpawn.app.ui.resources.home_streak_days
import com.yawnandpawn.app.ui.resources.home_test_skipped
import com.yawnandpawn.app.ui.resources.home_try_again
import com.yawnandpawn.app.ui.resources.home_zero_paid
import com.yawnandpawn.app.ui.resources.skip_confirm
import com.yawnandpawn.app.ui.resources.skip_keep
import com.yawnandpawn.app.ui.resources.skip_title
import com.yawnandpawn.app.ui.theme.PpsTheme
import org.jetbrains.compose.resources.stringResource

/**
 * Home (the Alarms tab), stateless: header, `card-hero` (streak and money, Story 6.3), the reliability `banner-warning`,
 * the fallback re-register info banner, the missed and test notes, the next-alarm countdown and the `card-alarm` list
 * (the nav bar's "+" adds an alarm); or the empty state (with the hero on top when there is history but no alarm); or,
 * during a session, only `panel-session-in-progress`. While the alarms are read only the header shows; if they cannot
 * be read, "Couldn't load your alarms." with "Try again" (never the empty state). Long-press on a card (or its
 * TalkBack actions) offers Duplicate and Delete; Delete asks first. Story 7.11: a card whose next ring is skipped says
 * "Next ring skipped" and offers "Don't skip" too, and "Skip this one" on a pre-alarm notification asks "Skip your {time}
 * alarm? This is logged." ("Keep it" is the default dismiss).
 */
@Composable
fun HomeScreen(
    state: HomeUiState,
    is24Hour: Boolean,
    onIntent: (HomeIntent) -> Unit,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
) {
    val spacing = PpsTheme.spacing
    PpsBackground(modifier = modifier) {
        val noList = state.isLoading || state.loadFailed || state.alarms.isEmpty()
        if (state.sessionInProgress || noList) {
            Column(modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars)) {
                HomeHeader(streakDays = null, collapse = null, modifier = Modifier.padding(bottom = spacing.space3))
                when {
                    state.sessionInProgress -> SessionInProgressPanel(onBackToAlarm = { onIntent(HomeIntent.BackToAlarm) })
                    state.isLoading -> Unit
                    state.loadFailed -> LoadFailed(onRetry = { onIntent(HomeIntent.RetryLoad) }, modifier = Modifier.weight(1f))
                    else -> EmptyHome(state = state, is24Hour = is24Hour, onIntent = onIntent, modifier = Modifier.weight(1f))
                }
            }
        } else {
            HomeList(state = state, is24Hour = is24Hour, onIntent = onIntent, listState = listState)
        }
        HomeSnackbar(
            state = state,
            is24Hour = is24Hour,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = LocalNavBarClearance.current),
        )
    }
    state.disableDialog?.let { dialog -> DisableDialog(dialog = dialog, is24Hour = is24Hour, onIntent = onIntent) }
    state.skipDialog?.let { dialog ->
        ConfirmDialog(
            title = stringResource(Res.string.skip_title, formatClockTime(dialog.time, is24Hour)),
            confirmText = stringResource(Res.string.skip_confirm),
            safeText = stringResource(Res.string.skip_keep),
            onConfirm = { onIntent(HomeIntent.SkipConfirmed) },
            onSafe = { onIntent(HomeIntent.SkipCancelled) },
        )
    }
    state.deleteDialog?.let { dialog ->
        DeleteAlarmConfirm(
            time = dialog.time,
            is24Hour = is24Hour,
            onConfirm = { onIntent(HomeIntent.DeleteConfirmed) },
            onKeep = { onIntent(HomeIntent.DeleteCancelled) },
        )
    }
}

/**
 * The alarm list under the pinned, collapsing header (owner decision 2026-09-28, Samsung Weather): the list starts below
 * the header and scrolls up underneath it; the hero collapses into the header's compact streak chip as it goes.
 */
@Composable
private fun HomeList(
    state: HomeUiState,
    is24Hour: Boolean,
    onIntent: (HomeIntent) -> Unit,
    listState: LazyListState,
) {
    val spacing = PpsTheme.spacing
    val density = LocalDensity.current
    var headerHeight by remember { mutableStateOf(0.dp) }
    // With no hero the title collapses over the header's own height of scrolling.
    val collapse =
        rememberHeaderCollapse(listState, hasHero = state.hero != null, noHeroDistance = with(density) { headerHeight.toPx() })
    val backdrop = rememberGlassBackdrop()
    val statusBarTop = WindowInsets.statusBars.getTop(density)
    val fade = with(density) { spacing.space3.toPx() }
    val opaque = PpsTheme.colors.text
    // The opening screenful fades and rises in order, like Progress (0.1.7 polish).
    val entered = rememberEnteredOnce()
    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier =
                Modifier
                    .fillMaxSize()
                    .glassSource(backdrop)
                    .fadeUnderHeader(zoneBottom = { statusBarTop + with(density) { headerHeight.toPx() } }, fade = fade, opaque = opaque),
            // Starts below the pinned header (and its fade), which sits under the status bar: the header is measured
            // inside its status-bar padding, so that inset is added here (device test round 1: the first item started
            // under the title). The last card ends above the floating nav bar (its "+" adds an alarm; owner decision
            // 2026-10-01 replaced the FAB).
            contentPadding =
                PaddingValues(
                    start = spacing.screenMargin,
                    end = spacing.screenMargin,
                    top = with(density) { statusBarTop.toDp() } + headerHeight + spacing.space3,
                    bottom = LocalNavBarClearance.current + spacing.space6,
                ),
            verticalArrangement = Arrangement.spacedBy(spacing.space3),
        ) {
            // An empty first item anchors the scroll position, so a hero or notice that arrives after the first layout is
            // inserted below it and shows, instead of above the first visible item, off screen (review fix 1).
            item(key = TOP_KEY) { }
            // The hero first, above every notice (Story 6.3); the notices only when one shows, so no empty item adds a gap.
            state.hero?.let { hero -> item(key = HERO_KEY) { HeroCard(hero, Modifier.entrance(entered, 0).collapsingHero(collapse)) } }
            if (state.hasNotices) item(key = "notices") { Notices(state, is24Hour, onIntent, Modifier.entrance(entered, 1)) }
            state.nextAlarm?.let { countdown -> item(key = "next") { NextAlarm(countdown, Modifier.entrance(entered, 2)) } }
            itemsIndexed(state.alarms, key = { _, alarm -> alarm.id }) { index, alarm ->
                AlarmCardView(
                    // Cards animate in and out when an alarm is added or removed (owner decision 2026-09-27).
                    modifier = Modifier.animateItem().entrance(entered, order = minOf(FIRST_CARD_ORDER + index, LAST_ENTRANCE_ORDER)),
                    alarm = alarm,
                    is24Hour = is24Hour,
                    onIntent = onIntent,
                )
            }
        }
        HomeHeader(
            streakDays = state.hero?.streakDays,
            collapse = collapse,
            backdrop = backdrop,
            modifier =
                Modifier
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .onSizeChanged { headerHeight = with(density) { it.height.toDp() } },
        )
    }
}

/** "Rings in ..." above the alarm cards. */
@Composable
private fun NextAlarm(
    countdown: Countdown,
    modifier: Modifier = Modifier,
) {
    Text(
        text = countdownText(countdown),
        modifier = modifier.padding(top = PpsTheme.spacing.space2),
        style = PpsTheme.typography.title,
        color = PpsTheme.colors.text,
    )
}

/** The empty first item of the Home list. */
private const val TOP_KEY = "top"

/** Entrance order of the first alarm card (after the hero, the notices and the countdown). */
private const val FIRST_CARD_ORDER = 3

/** Cards further down enter with this one, so the last ones on screen do not wait long. */
private const val LAST_ENTRANCE_ORDER = 8

/** Some banner or note shows. */
private val HomeUiState.hasNotices: Boolean
    get() = reliabilityProblem || reregisterCheck != null || missedAlarmAt != null || testSkipped

/** Banners and the notes, stacked under the hero. */
@Composable
private fun Notices(
    state: HomeUiState,
    is24Hour: Boolean,
    onIntent: (HomeIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = PpsTheme.spacing
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(spacing.space3)) {
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
                DismissButton(
                    label = stringResource(Res.string.home_dismiss),
                    onClick = { onIntent(HomeIntent.MissedNoteDismissed(state.missedSessionId)) },
                )
            }
        }
        if (state.testSkipped) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                NoteInline(text = stringResource(Res.string.home_test_skipped), modifier = Modifier.weight(1f))
                DismissButton(label = stringResource(Res.string.home_dismiss), onClick = { onIntent(HomeIntent.TestNoteDismissed) })
            }
        }
    }
}

/**
 * `card-hero` (glass, not tappable): streak number in `display` (`accent-text`), "days on time" in `body`, the money line
 * in `text-secondary` ("Nothing paid this week. Keep it that way." or "{paid} paid this week", one total per currency
 * joined by the formatter). TalkBack reads it as one element, "{streak} days on time. {money line}".
 */
@Composable
private fun HeroCard(
    hero: HomeHero,
    modifier: Modifier = Modifier,
) {
    val colors = PpsTheme.colors
    val streak = hero.streakDays.toString()
    val label = stringResource(if (hero.streakDays == 1) Res.string.home_streak_day else Res.string.home_streak_days)
    val moneyLine =
        if (hero.paidThisWeek.isEmpty()) {
            stringResource(Res.string.home_zero_paid)
        } else {
            stringResource(Res.string.home_paid_week, formatMoney(hero.paidThisWeek))
        }
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .glass(PpsTheme.shapes.md)
                .padding(PpsTheme.spacing.cardPadding)
                .clearAndSetSemantics { contentDescription = "$streak $label. $moneyLine" },
    ) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(text = streak, style = PpsTheme.typography.display, color = colors.accentText)
            Text(
                text = label,
                modifier = Modifier.padding(start = PpsTheme.spacing.space2, bottom = PpsTheme.spacing.space1),
                style = PpsTheme.typography.body,
                color = colors.text,
            )
        }
        Text(text = moneyLine, style = PpsTheme.typography.body, color = colors.textSecondary)
    }
}

/**
 * `card-alarm`: glass, `rounded.md`; time in `title`, repeat days and label in `caption`, check icons (20 dp,
 * `text-secondary`) and the enable `switch` on the right. The card is one button (tap edits; long-press opens the menu
 * with Duplicate and Delete, which TalkBack also offers as the card's actions); the switch is its own control
 * ("7:30 AM alarm"). A skipped next ring (Story 7.11) adds "Don't skip" first, in the menu and as a TalkBack action.
 */
@Composable
private fun AlarmCardView(
    alarm: AlarmCard,
    is24Hour: Boolean,
    onIntent: (HomeIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = PpsTheme.spacing
    val time = formatClockTime(alarm.time, is24Hour)
    var menuOpen by remember { mutableStateOf(false) }
    val duplicateLabel = stringResource(Res.string.alarm_duplicate)
    val deleteLabel = stringResource(Res.string.alarm_delete)
    val dontSkipLabel = stringResource(Res.string.alarm_dont_skip)
    val onDuplicate = { onIntent(HomeIntent.DuplicateClicked(alarm.id)) }
    val onDelete = { onIntent(HomeIntent.DeleteClicked(alarm.id)) }
    val onDontSkip = { onIntent(HomeIntent.DontSkipClicked(alarm.id)) }
    val actions =
        listOfNotNull(
            (dontSkipLabel to onDontSkip).takeIf { alarm.skipped },
            duplicateLabel to onDuplicate,
            deleteLabel to onDelete,
        )
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
                    .combinedClickable(
                        role = Role.Button,
                        onLongClickLabel = stringResource(Res.string.alarm_card_options),
                        onLongClick = { menuOpen = true },
                        onClick = { onIntent(HomeIntent.EditAlarm(alarm.id)) },
                    ).semantics { customActions = cardActions(actions) }
                    .padding(spacing.cardPadding),
            verticalArrangement = Arrangement.spacedBy(spacing.space1),
        ) {
            CardTexts(alarm = alarm, time = time)
        }
        PpsSwitch(
            checked = alarm.enabled,
            onCheckedChange = { onIntent(HomeIntent.AlarmToggled(alarm.id, it)) },
            label = stringResource(Res.string.alarm_card_switch, time),
            modifier = Modifier.padding(end = spacing.cardPadding),
        )
        // Anchored to the card; it adds nothing to the layout (a child of the Row, which has no spacing between children).
        PpsMenu(expanded = menuOpen, onDismiss = { menuOpen = false }) {
            actions.forEach { (label, action) ->
                PpsMenuItem(label = label, onClick = {
                    menuOpen = false
                    action()
                })
            }
        }
    }
}

/**
 * Home empty: "No alarms yet." with `button-filled` "Add your first alarm"; notices still show above it, and with history
 * but no alarm the hero above them (Story 6.3; it does not collapse into the header).
 */
@Composable
private fun EmptyHome(
    state: HomeUiState,
    is24Hour: Boolean,
    onIntent: (HomeIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = PpsTheme.spacing
    // It scrolls when the hero and the notices leave too little room (200% font scale), so the button never clips
    // (review fix 4); otherwise the centred block fills the rest of the screen, as before.
    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val screenHeight = maxHeight
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .heightIn(min = screenHeight)
                    .padding(start = spacing.screenMargin, end = spacing.screenMargin, bottom = LocalNavBarClearance.current),
        ) {
            state.hero?.let { hero -> HeroCard(hero, modifier = Modifier.padding(bottom = spacing.space3)) }
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
}

/** Home when the alarms could not be read: "Couldn't load your alarms." with `button-text` "Try again" (resubscribes). */
@Composable
private fun LoadFailed(
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = PpsTheme.spacing
    Column(
        modifier =
            modifier.fillMaxWidth().padding(
                start = spacing.screenMargin,
                end = spacing.screenMargin,
                bottom = LocalNavBarClearance.current,
            ),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(Res.string.home_load_failed),
            style = PpsTheme.typography.title,
            color = PpsTheme.colors.text,
            textAlign = TextAlign.Center,
        )
        PpsTextButton(
            text = stringResource(Res.string.home_try_again),
            onClick = onRetry,
            modifier = Modifier.padding(top = spacing.space4),
        )
    }
}

/**
 * The card's time in `title`, the repeat summary and label in `caption` ("Next ring skipped" in its place while the next
 * ring is skipped, Story 7.11), and its check icons.
 */
@Composable
private fun CardTexts(
    alarm: AlarmCard,
    time: String,
) {
    val colors = PpsTheme.colors
    Text(text = time, style = PpsTheme.typography.title, color = if (alarm.enabled) colors.text else colors.textSecondary)
    val repeat = repeatSummary(alarm.repeatDays)
    val summary = alarm.label?.let { stringResource(Res.string.alarm_card_summary, repeat, it) } ?: repeat
    Text(
        text = if (alarm.skipped) stringResource(Res.string.alarm_card_skipped) else summary,
        style = PpsTheme.typography.caption,
        color = colors.textSecondary,
    )
    if (alarm.checks.isNotEmpty()) {
        Row(horizontalArrangement = Arrangement.spacedBy(PpsTheme.spacing.space2)) {
            alarm.checks.forEach { check -> RowIcon(icon = check.icon, tint = colors.textSecondary) }
        }
    }
}

/** The menu's actions ("Don't skip", Duplicate, Delete) as TalkBack actions of the card, so they need no long-press. */
private fun cardActions(actions: List<Pair<String, () -> Unit>>): List<CustomAccessibilityAction> =
    actions.map { (label, action) ->
        CustomAccessibilityAction(label) {
            action()
            true
        }
    }
