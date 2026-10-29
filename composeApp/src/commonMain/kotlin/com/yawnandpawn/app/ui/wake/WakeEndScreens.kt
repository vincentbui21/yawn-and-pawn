package com.yawnandpawn.app.ui.wake

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.traversalIndex
import androidx.compose.ui.text.style.TextAlign
import com.yawnandpawn.app.ui.checks.description
import com.yawnandpawn.app.ui.checks.displayName
import com.yawnandpawn.app.ui.checks.icon
import com.yawnandpawn.app.ui.components.DismissButton
import com.yawnandpawn.app.ui.components.NoteInline
import com.yawnandpawn.app.ui.components.RowIcon
import com.yawnandpawn.app.ui.components.glass
import com.yawnandpawn.app.ui.format.formatClockTime
import com.yawnandpawn.app.ui.format.formatMoney
import com.yawnandpawn.app.ui.resources.Res
import com.yawnandpawn.app.ui.resources.fallback_picker_close
import com.yawnandpawn.app.ui.resources.fallback_picker_title
import com.yawnandpawn.app.ui.resources.recordings_pause
import com.yawnandpawn.app.ui.resources.recordings_play
import com.yawnandpawn.app.ui.resources.recordings_replay
import com.yawnandpawn.app.ui.resources.snoozed_next_ring
import com.yawnandpawn.app.ui.resources.success_after_snooze
import com.yawnandpawn.app.ui.resources.success_done
import com.yawnandpawn.app.ui.resources.success_paid_this_morning
import com.yawnandpawn.app.ui.resources.success_pending_not_used
import com.yawnandpawn.app.ui.resources.success_test
import com.yawnandpawn.app.ui.resources.success_zero_snooze_first
import com.yawnandpawn.app.ui.resources.symbol_pause
import com.yawnandpawn.app.ui.resources.symbol_play_arrow
import com.yawnandpawn.app.ui.resources.symbol_replay
import com.yawnandpawn.app.ui.theme.PpsTheme
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

/**
 * The Fallback check picker, always Sunrise: "Pick a fallback check", a close button ("Back to check") and one
 * `check-type-card` per non-camera check, Math first. A card starts that check; the alarm keeps ringing.
 */
@Composable
fun FallbackPickerScreen(
    state: FallbackPickerUiState,
    onIntent: (WakeIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    WakeSurface(modifier = modifier) {
        val colors = PpsTheme.colors
        val spacing = PpsTheme.spacing
        Column(
            modifier = Modifier.fillMaxSize().wakeContentPadding().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(spacing.space3),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(Res.string.fallback_picker_title),
                    modifier = Modifier.weight(1f).semantics { heading() },
                    style = PpsTheme.typography.headline,
                    color = colors.text,
                )
                DismissButton(
                    label = stringResource(Res.string.fallback_picker_close),
                    onClick = { onIntent(WakeIntent.FallbackPickerClosed) },
                    tint = colors.text,
                )
            }
            state.options.forEach { type ->
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = spacing.targetWake)
                            .glass(PpsTheme.shapes.md)
                            .clickable(role = Role.Button) { onIntent(WakeIntent.FallbackChosen(type)) }
                            .padding(spacing.cardPadding),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RowIcon(icon = type.icon, tint = colors.text)
                    Column(modifier = Modifier.padding(start = spacing.space4)) {
                        Text(text = type.displayName(), style = PpsTheme.typography.title, color = colors.text)
                        Text(text = type.description(), style = PpsTheme.typography.body, color = colors.textSecondary)
                    }
                }
            }
        }
    }
}

/**
 * Success, always Sunrise: zero snooze shows the streak number (`display`), "days in a row" and "Up on time." (owner
 * decisions 2026-09-28; Story 6.4) with a 1.5 s confetti celebration every time it shows (owner decision 2026-10-29:
 * every on-time Success has the confetti, also with no number and when shown again); when the streak grew
 * ([SuccessKind.OnTime.countFrom]) the number also counts up. With no streak known, "You're up. That's what counts.",
 * still with the confetti. After a snooze "You're up. That's what counts." with the amount paid, a test "Test finished.
 * Your alarm works.", no confetti, then "Done" (thumb zone). One success haptic for every kind, when [claimHaptic]
 * allows it (the caller plays it once per session). TalkBack reads the headline first, then the streak, then "Done".
 *
 * [basic] is the production "Done" (Story 3.3): a 72 dp `button-wake-primary`; without it, the round 1 preview's 64 dp.
 */
@Composable
fun SuccessScreen(
    state: SuccessUiState,
    onIntent: (WakeIntent) -> Unit,
    modifier: Modifier = Modifier,
    basic: Boolean = false,
    claimHaptic: () -> Boolean = { true },
) {
    // Up on time (zero snoozes, not a test): the confetti every time, the count-up only with a countFrom (owner decision
    // 2026-10-29). After a snooze or a test, none.
    val kind = state.kind
    val celebration = if (kind is SuccessKind.OnTime || kind == SuccessKind.UpNoStreak) rememberCelebration() else null
    SuccessHaptic(claimHaptic)
    WakeSurface(modifier = modifier, overlay = { celebration?.let { Confetti(it) } }) {
        val colors = PpsTheme.colors
        val spacing = PpsTheme.spacing
        Column(modifier = Modifier.fillMaxSize().wakeContentPadding()) {
            Column(
                modifier = Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(spacing.space4, Alignment.CenterVertically),
            ) {
                // On a glass card: the streak number in accent-text passes on glass (5.11), not on the sunrise gradient.
                // TalkBack: the headline first (traversal index -1 in this group), then the streak, then "Done".
                Column(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .semantics { isTraversalGroup = true }
                            .glass(PpsTheme.shapes.md)
                            .padding(spacing.space6),
                    verticalArrangement = Arrangement.spacedBy(spacing.space3),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    when (kind) {
                        is SuccessKind.OnTime -> {
                            // Owner decision 2026-09-28: the number, "days in a row" under it, then "Up on time."
                            if (kind.streakDays > 0 && celebration != null) StreakCount(kind.streakDays, kind.countFrom, celebration)
                            Headline(stringResource(Res.string.success_zero_snooze_first))
                        }

                        SuccessKind.UpNoStreak -> {
                            Headline(stringResource(Res.string.success_after_snooze))
                        }

                        is SuccessKind.AfterSnooze -> {
                            Headline(stringResource(Res.string.success_after_snooze))
                            kind.paidThisMorning.takeIf { it.isNotEmpty() }?.let { paid ->
                                Text(
                                    text = stringResource(Res.string.success_paid_this_morning, formatMoney(paid)),
                                    style = PpsTheme.typography.body,
                                    color = colors.textSecondary,
                                )
                            }
                        }

                        SuccessKind.Test -> {
                            Headline(stringResource(Res.string.success_test))
                        }
                    }
                    if (state.pendingNotUsed) NoteInline(text = stringResource(Res.string.success_pending_not_used))
                }
                // Story 7.3: the message "After I'm up", between the headline card and "Done", while it is this session's.
                state.motivation?.let { MotivationPlayerCard(it, onIntent) }
            }
            WakePrimaryButton(text = stringResource(Res.string.success_done), onClick = { onIntent(WakeIntent.DoneClicked) }, hero = basic)
        }
    }
}

/**
 * `motivation-player` (Story 7.3): a `surface-sunrise` card (`rounded.md`) with a 48 dp play / pause button, the
 * progress bar in accent over the `outline-subtle` track (accent on surface 3.37:1, as the countdown ring) and a 48 dp
 * replay button, both icons in `text-sunrise`. TalkBack reads "Play message" or "Pause message", the progress, then
 * "Replay message".
 */
@Composable
internal fun MotivationPlayerCard(
    state: MotivationPlayerUi,
    onIntent: (WakeIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = PpsTheme.colors
    val spacing = PpsTheme.spacing
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .clip(PpsTheme.shapes.md)
                .background(colors.surface)
                .padding(horizontal = spacing.space2, vertical = spacing.space2),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(spacing.space2),
    ) {
        PlayerButton(
            icon = if (state.playing) Res.drawable.symbol_pause else Res.drawable.symbol_play_arrow,
            label = stringResource(if (state.playing) Res.string.recordings_pause else Res.string.recordings_play),
            onClick = { onIntent(WakeIntent.MotivationPlayPauseClicked) },
        )
        val progress = state.progress.coerceIn(0f, 1f)
        Box(
            modifier =
                Modifier
                    .weight(1f)
                    .height(spacing.space1)
                    .clip(PpsTheme.shapes.full)
                    .background(colors.outlineSubtle)
                    .semantics { progressBarRangeInfo = ProgressBarRangeInfo(progress, 0f..1f) },
        ) {
            Box(modifier = Modifier.fillMaxWidth(progress).fillMaxHeight().background(colors.accent))
        }
        PlayerButton(
            icon = Res.drawable.symbol_replay,
            label = stringResource(Res.string.recordings_replay),
            onClick = { onIntent(WakeIntent.MotivationReplayClicked) },
        )
    }
}

@Composable
private fun PlayerButton(
    icon: DrawableResource,
    label: String,
    onClick: () -> Unit,
) {
    IconButton(
        onClick = onClick,
        modifier = Modifier.size(PpsTheme.spacing.targetMin),
        colors = IconButtonDefaults.iconButtonColors(contentColor = PpsTheme.colors.text),
    ) {
        Icon(painter = painterResource(icon), contentDescription = label)
    }
}

/**
 * The success haptic pattern when the screen enters composition, if [claim] allows it: the caller keeps the played flag
 * per session, so leaving and re-entering composition or a recreated activity does not play it again.
 */
@Composable
private fun SuccessHaptic(claim: () -> Boolean) {
    val haptics = LocalHapticFeedback.current
    val currentClaim by rememberUpdatedState(claim)
    LaunchedEffect(Unit) {
        if (currentClaim()) haptics.performHapticFeedback(HapticFeedbackType.Confirm)
    }
}

@Composable
private fun Headline(text: String) {
    Text(
        text = text,
        modifier =
            Modifier.semantics {
                heading()
                traversalIndex = HEADLINE_TRAVERSAL_INDEX
            },
        style = PpsTheme.typography.headline,
        color = PpsTheme.colors.text,
        textAlign = TextAlign.Center,
    )
}

/** Snoozed, always Sunrise: "Snoozed. Next ring at {time}." for 3 s, then the screen turns off. No actions. */
@Composable
fun SnoozedScreen(
    state: SnoozedUiState,
    is24Hour: Boolean,
    modifier: Modifier = Modifier,
) {
    WakeSurface(modifier = modifier) {
        Column(
            modifier = Modifier.fillMaxSize().wakeContentPadding(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = stringResource(Res.string.snoozed_next_ring, formatClockTime(state.nextRingAt, is24Hour)),
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                style = PpsTheme.typography.headline,
                color = PpsTheme.colors.text,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** The Success headline is read first in its card (the onboarding pattern): TalkBack then reads the streak and "Done". */
private const val HEADLINE_TRAVERSAL_INDEX = -1f
