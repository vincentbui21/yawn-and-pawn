package com.yawnandpawn.app.ui.onboarding

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import com.yawnandpawn.app.ui.components.PpsBackground
import com.yawnandpawn.app.ui.components.PpsFilledButton
import com.yawnandpawn.app.ui.components.PpsOutlinedButton
import com.yawnandpawn.app.ui.components.PpsTextButton
import com.yawnandpawn.app.ui.components.ProgressDots
import com.yawnandpawn.app.ui.components.subScreenTransition
import com.yawnandpawn.app.ui.resources.Res
import com.yawnandpawn.app.ui.resources.editor_back
import com.yawnandpawn.app.ui.resources.onboarding_continue
import com.yawnandpawn.app.ui.resources.onboarding_no_thanks
import com.yawnandpawn.app.ui.resources.onboarding_share
import com.yawnandpawn.app.ui.resources.onboarding_skip_test
import com.yawnandpawn.app.ui.resources.onboarding_start
import com.yawnandpawn.app.ui.resources.onboarding_step
import com.yawnandpawn.app.ui.resources.onboarding_understand
import com.yawnandpawn.app.ui.resources.reliability_ring_test
import com.yawnandpawn.app.ui.resources.symbol_arrow_back
import com.yawnandpawn.app.ui.theme.PpsTheme
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

/**
 * Onboarding, stateless (EXPERIENCE.md IA and F1; owner direction 2026-09-27: grouped glass cards on the gradient, rows
 * that open sub-screens, screens that slide): eight steps, one job each. A top row with the back arrow (from step 2)
 * and the `progress-dots` on a small glass capsule; the step's headline, body and cards scroll; its actions sit in their
 * own bottom area on the flat end of the gradient. Steps slide forward and back (250 ms emphasized, instant with
 * animations off).
 */
@Composable
fun OnboardingScreen(
    state: OnboardingUiState,
    is24Hour: Boolean,
    onIntent: (OnboardingIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    PpsBackground(modifier = modifier) {
        Column(modifier = Modifier.fillMaxSize()) {
            TopRow(step = state.step, onBack = { onIntent(OnboardingIntent.Back) })
            AnimatedContent(
                targetState = state.step,
                modifier = Modifier.weight(1f),
                transitionSpec = { subScreenTransition(forward = targetState.ordinal > initialState.ordinal) },
                label = "onboarding step",
            ) { step ->
                Column(modifier = Modifier.fillMaxSize()) {
                    Column(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .weight(1f)
                                .clipToBounds()
                                .verticalScroll(rememberScrollState())
                                .padding(horizontal = PpsTheme.spacing.screenMargin, vertical = PpsTheme.spacing.space2),
                        verticalArrangement = Arrangement.spacedBy(PpsTheme.spacing.space3),
                    ) {
                        StepContent(step = step, state = state, is24Hour = is24Hour, onIntent = onIntent)
                    }
                    Actions(step = step, state = state, onIntent = onIntent)
                }
            }
        }
    }
}

/** The back arrow (not on the first step) and the dots ("Step 3 of 8"), under the status bar. */
@Composable
private fun TopRow(
    step: OnboardingStep,
    onBack: () -> Unit,
) {
    val spacing = PpsTheme.spacing
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.statusBars)
                .heightIn(min = spacing.targetWake)
                .padding(horizontal = spacing.space1, vertical = spacing.space2),
    ) {
        if (step != OnboardingStep.Mission) {
            IconButton(
                onClick = onBack,
                modifier = Modifier.align(Alignment.CenterStart).size(spacing.targetMin),
                colors = IconButtonDefaults.iconButtonColors(contentColor = PpsTheme.colors.text),
            ) {
                Icon(painter = painterResource(Res.drawable.symbol_arrow_back), contentDescription = stringResource(Res.string.editor_back))
            }
        }
        ProgressDots(
            count = STEP_COUNT,
            current = step.ordinal,
            description = stringResource(Res.string.onboarding_step, step.ordinal + 1, STEP_COUNT),
            modifier = Modifier.align(Alignment.Center),
        )
    }
}

@Composable
private fun ColumnScope.StepContent(
    step: OnboardingStep,
    state: OnboardingUiState,
    is24Hour: Boolean,
    onIntent: (OnboardingIntent) -> Unit,
) {
    when (step) {
        OnboardingStep.Mission -> MissionStep()
        OnboardingStep.Disclosure -> DisclosureStep()
        OnboardingStep.BaseFee -> BaseFeeStep(state = state, onIntent = onIntent)
        OnboardingStep.FirstAlarm -> FirstAlarmStep(state = state, is24Hour = is24Hour, onIntent = onIntent)
        OnboardingStep.Checks -> ChecksStep(state = state, onIntent = onIntent)
        OnboardingStep.Reliability -> ReliabilityStep(state = state, onIntent = onIntent)
        OnboardingStep.Analytics -> AnalyticsStep()
        OnboardingStep.TestAlarm -> TestAlarmStep(state = state)
    }
}

/**
 * The step's actions in their own bottom area (never over the cards): one `button-filled` ("Let's set it up", "I
 * understand", "Continue", "Ring a test alarm"), with "Skip for now" under the test alarm; the analytics choice is two
 * equal `button-outlined`s, "Share" and "No thanks" (neither pre-selected; off unless turned on).
 */
@Composable
private fun Actions(
    step: OnboardingStep,
    state: OnboardingUiState,
    onIntent: (OnboardingIntent) -> Unit,
) {
    val spacing = PpsTheme.spacing
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(start = spacing.screenMargin, end = spacing.screenMargin, top = spacing.space2, bottom = spacing.space4),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(spacing.space2),
    ) {
        val wide = Modifier.fillMaxWidth()
        val next = { onIntent(OnboardingIntent.Next) }
        when (step) {
            OnboardingStep.Mission -> {
                PpsFilledButton(text = stringResource(Res.string.onboarding_start), onClick = next, modifier = wide)
            }

            OnboardingStep.Disclosure -> {
                PpsFilledButton(text = stringResource(Res.string.onboarding_understand), onClick = next, modifier = wide)
            }

            OnboardingStep.Checks -> {
                PpsFilledButton(
                    text = stringResource(Res.string.onboarding_continue),
                    onClick = next,
                    modifier = wide,
                    enabled = state.checks.checks.isNotEmpty(),
                )
            }

            OnboardingStep.Analytics -> {
                PpsOutlinedButton(
                    text = stringResource(Res.string.onboarding_share),
                    onClick = { onIntent(OnboardingIntent.UsageStatsChosen(share = true)) },
                    modifier = wide,
                )
                PpsOutlinedButton(
                    text = stringResource(Res.string.onboarding_no_thanks),
                    onClick = { onIntent(OnboardingIntent.UsageStatsChosen(share = false)) },
                    modifier = wide,
                )
            }

            OnboardingStep.TestAlarm -> {
                PpsFilledButton(
                    text = stringResource(Res.string.reliability_ring_test),
                    onClick = { onIntent(OnboardingIntent.RingTestAlarm) },
                    modifier = wide,
                )
                PpsTextButton(
                    text = stringResource(Res.string.onboarding_skip_test),
                    onClick = { onIntent(OnboardingIntent.SkipTestAlarm) },
                )
            }

            OnboardingStep.BaseFee, OnboardingStep.FirstAlarm, OnboardingStep.Reliability -> {
                PpsFilledButton(text = stringResource(Res.string.onboarding_continue), onClick = next, modifier = wide)
            }
        }
    }
}

/** A step's headline (`headline`, a heading for TalkBack) and optional body line. */
@Composable
internal fun StepHeader(
    title: String,
    body: String? = null,
) {
    val colors = PpsTheme.colors
    Column(
        modifier = Modifier.padding(top = PpsTheme.spacing.space2, bottom = PpsTheme.spacing.space2),
        verticalArrangement = Arrangement.spacedBy(PpsTheme.spacing.space2),
    ) {
        Text(text = title, modifier = Modifier.semantics { heading() }, style = PpsTheme.typography.headline, color = colors.text)
        if (body != null) Text(text = body, style = PpsTheme.typography.body, color = colors.textSecondary)
    }
}

/** A row of a card with a leading icon and a line of `body` text. */
@Composable
internal fun IconLine(
    icon: DrawableResource,
    text: String,
) {
    val spacing = PpsTheme.spacing
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(
                    min = spacing.targetMin,
                ).padding(horizontal = spacing.cardPadding, vertical = spacing.space3),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painter = painterResource(icon),
            contentDescription = null,
            modifier = Modifier.padding(end = spacing.space3),
            tint = PpsTheme.colors.textSecondary,
        )
        Text(text = text, style = PpsTheme.typography.body, color = PpsTheme.colors.text)
    }
}

/** EXPERIENCE.md IA: onboarding has 8 steps. */
private val STEP_COUNT = OnboardingStep.entries.size
