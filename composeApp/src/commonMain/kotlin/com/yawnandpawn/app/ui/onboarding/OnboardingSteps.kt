package com.yawnandpawn.app.ui.onboarding

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import com.yawnandpawn.app.ui.checkpicker.CheckPickerContent
import com.yawnandpawn.app.ui.components.GroupCard
import com.yawnandpawn.app.ui.components.GroupDivider
import com.yawnandpawn.app.ui.components.NoteInline
import com.yawnandpawn.app.ui.components.PpsStepper
import com.yawnandpawn.app.ui.components.RowIcon
import com.yawnandpawn.app.ui.components.TextCard
import com.yawnandpawn.app.ui.components.glass
import com.yawnandpawn.app.ui.editor.RepeatChoiceCard
import com.yawnandpawn.app.ui.editor.TimeWheelCard
import com.yawnandpawn.app.ui.format.formatMoney
import com.yawnandpawn.app.ui.reliability.ChecklistCard
import com.yawnandpawn.app.ui.resources.Res
import com.yawnandpawn.app.ui.resources.app_name
import com.yawnandpawn.app.ui.resources.disclosure_escape_body
import com.yawnandpawn.app.ui.resources.disclosure_ringing_body
import com.yawnandpawn.app.ui.resources.disclosure_usable_body
import com.yawnandpawn.app.ui.resources.editor_fee_ladder
import com.yawnandpawn.app.ui.resources.onboarding_analytics_body
import com.yawnandpawn.app.ui.resources.onboarding_analytics_title
import com.yawnandpawn.app.ui.resources.onboarding_base_fee_title
import com.yawnandpawn.app.ui.resources.onboarding_bright_wake
import com.yawnandpawn.app.ui.resources.onboarding_checks_title
import com.yawnandpawn.app.ui.resources.onboarding_disclosure_title
import com.yawnandpawn.app.ui.resources.onboarding_first_alarm_title
import com.yawnandpawn.app.ui.resources.onboarding_how_snooze
import com.yawnandpawn.app.ui.resources.onboarding_how_wake
import com.yawnandpawn.app.ui.resources.onboarding_mission_body
import com.yawnandpawn.app.ui.resources.onboarding_reliability_title
import com.yawnandpawn.app.ui.resources.onboarding_test_body
import com.yawnandpawn.app.ui.resources.onboarding_test_not_locked
import com.yawnandpawn.app.ui.resources.onboarding_test_title
import com.yawnandpawn.app.ui.resources.purchase_auth_tip
import com.yawnandpawn.app.ui.resources.settings_base_fee
import com.yawnandpawn.app.ui.resources.settings_base_fee_lock
import com.yawnandpawn.app.ui.resources.settings_prices_approximate
import com.yawnandpawn.app.ui.resources.stepper_lower
import com.yawnandpawn.app.ui.resources.stepper_raise
import com.yawnandpawn.app.ui.resources.stepper_value
import com.yawnandpawn.app.ui.resources.symbol_error
import com.yawnandpawn.app.ui.resources.symbol_snooze
import com.yawnandpawn.app.ui.resources.symbol_wb_sunny
import com.yawnandpawn.app.ui.theme.PpsTheme
import org.jetbrains.compose.resources.stringResource

/**
 * Step 1, the mission: the app name, "This app makes money only when you snooze. We hope you never pay us." in `title`,
 * and how it works in a glass card: "Snooze costs money." · "Waking up is free.".
 */
@Composable
internal fun MissionStep() {
    val colors = PpsTheme.colors
    Text(
        text = stringResource(Res.string.app_name),
        modifier = Modifier.padding(top = PpsTheme.spacing.space6).semantics { heading() },
        style = PpsTheme.typography.display,
        color = colors.text,
    )
    Text(text = stringResource(Res.string.onboarding_mission_body), style = PpsTheme.typography.title, color = colors.text)
    GroupCard(modifier = Modifier.padding(top = PpsTheme.spacing.space3)) {
        IconLine(icon = Res.drawable.symbol_snooze, text = stringResource(Res.string.onboarding_how_snooze))
        GroupDivider()
        IconLine(icon = Res.drawable.symbol_wb_sunny, text = stringResource(Res.string.onboarding_how_wake))
    }
}

/** Step 2, the alarm behaviour disclosure (FR-ONB-5), verbatim in three paragraphs; "I understand" is the consent. */
@Composable
internal fun DisclosureStep() {
    StepHeader(title = stringResource(Res.string.onboarding_disclosure_title))
    TextCard(
        paragraphs =
            listOf(
                stringResource(Res.string.disclosure_ringing_body),
                stringResource(Res.string.disclosure_usable_body),
                stringResource(Res.string.disclosure_escape_body),
            ),
    )
}

/**
 * Step 3, the base fee: the `stepper` over the price tiers, the fee ladder preview, the lock note, (prices never
 * loaded) "Approximate. Your local price shows when you're online.", and the purchase authentication tip.
 */
@Composable
internal fun BaseFeeStep(
    state: OnboardingUiState,
    onIntent: (OnboardingIntent) -> Unit,
) {
    val setting = stringResource(Res.string.settings_base_fee)
    val fee = formatMoney(state.baseFee)
    val notePadding = Modifier.padding(horizontal = PpsTheme.spacing.cardPadding)
    StepHeader(title = stringResource(Res.string.onboarding_base_fee_title))
    GroupCard(title = setting) {
        PpsStepper(
            valueText = fee,
            valueDescription = stringResource(Res.string.stepper_value, setting, fee),
            decreaseLabel = stringResource(Res.string.stepper_lower, setting),
            increaseLabel = stringResource(Res.string.stepper_raise, setting),
            onDecrease = { onIntent(OnboardingIntent.LowerBaseFee) },
            onIncrease = { onIntent(OnboardingIntent.RaiseBaseFee) },
            canDecrease = state.lowerFee != null,
            canIncrease = state.higherFee != null,
        )
    }
    NoteInline(
        text =
            stringResource(
                Res.string.editor_fee_ladder,
                formatMoney(state.baseFee),
                formatMoney(state.baseFee * 2),
                formatMoney(state.baseFee * FEE_LADDER_THIRD),
            ),
        modifier = notePadding,
    )
    NoteInline(text = stringResource(Res.string.settings_base_fee_lock), modifier = notePadding)
    if (state.pricesApproximate) NoteInline(text = stringResource(Res.string.settings_prices_approximate), modifier = notePadding)
    NoteInline(text = stringResource(Res.string.purchase_auth_tip), modifier = notePadding)
}

/** Step 4, the first alarm: "When should it ring?", the time wheels and the repeat quick choices (like the editor). */
@Composable
internal fun FirstAlarmStep(
    state: OnboardingUiState,
    is24Hour: Boolean,
    onIntent: (OnboardingIntent) -> Unit,
) {
    StepHeader(title = stringResource(Res.string.onboarding_first_alarm_title))
    TimeWheelCard(time = state.alarmTime, is24Hour = is24Hour, onTimeChange = { onIntent(OnboardingIntent.TimeChanged(it)) })
    RepeatChoiceCard(
        choice = state.repeatChoice,
        days = state.repeatDays,
        onChoose = { onIntent(OnboardingIntent.RepeatChosen(it)) },
        onToggleDay = { onIntent(OnboardingIntent.DayToggled(it)) },
    )
}

/** Step 5, the checks: "How will you prove you're up?" and the Check picker (a selected check's row opens Check setup). */
@Composable
internal fun ChecksStep(
    state: OnboardingUiState,
    onIntent: (OnboardingIntent) -> Unit,
) {
    StepHeader(title = stringResource(Res.string.onboarding_checks_title))
    CheckPickerContent(state = state.checks, onIntent = { onIntent(OnboardingIntent.Checks(it)) })
}

/** Step 6, "Make sure it rings": the reliability checklist (FR-ONB-2/3), "Fix" deep-links to each setting. */
@Composable
internal fun ReliabilityStep(
    state: OnboardingUiState,
    onIntent: (OnboardingIntent) -> Unit,
) {
    StepHeader(title = stringResource(Res.string.onboarding_reliability_title))
    ChecklistCard(rows = state.reliability, onFix = { onIntent(OnboardingIntent.FixClicked(it)) })
}

/** Step 7, the analytics choice (FR-ONB-6): "Share anonymous usage stats?" / "Off unless you turn it on.". */
@Composable
internal fun AnalyticsStep() {
    StepHeader(
        title = stringResource(Res.string.onboarding_analytics_title),
        body = stringResource(Res.string.onboarding_analytics_body),
    )
}

/**
 * Step 8, the test alarm: "Lock your phone. We'll ring in 10 seconds." and the bright wake screen note; when the phone
 * was not locked, "Your phone wasn't locked. Try again with it locked." on glass with the `error` icon.
 */
@Composable
internal fun TestAlarmStep(state: OnboardingUiState) {
    val colors = PpsTheme.colors
    val spacing = PpsTheme.spacing
    StepHeader(title = stringResource(Res.string.onboarding_test_title), body = stringResource(Res.string.onboarding_test_body))
    if (state.testStatus == TestAlarmStatus.NotLocked) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .glass(PpsTheme.shapes.md)
                    .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }
                    .padding(spacing.cardPadding),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RowIcon(icon = Res.drawable.symbol_error, tint = colors.error, modifier = Modifier.padding(end = spacing.space3))
            Text(text = stringResource(Res.string.onboarding_test_not_locked), style = PpsTheme.typography.body, color = colors.text)
        }
    }
    NoteInline(text = stringResource(Res.string.onboarding_bright_wake), modifier = Modifier.padding(horizontal = spacing.cardPadding))
}

/** The fee ladder preview shows snoozes 1 to 3. */
private const val FEE_LADDER_THIRD = 3
