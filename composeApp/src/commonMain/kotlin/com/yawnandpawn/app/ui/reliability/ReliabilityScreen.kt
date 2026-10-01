package com.yawnandpawn.app.ui.reliability

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.yawnandpawn.app.ui.components.GroupCard
import com.yawnandpawn.app.ui.components.GroupDivider
import com.yawnandpawn.app.ui.components.PpsOutlinedButton
import com.yawnandpawn.app.ui.components.PpsTextButton
import com.yawnandpawn.app.ui.components.RowIcon
import com.yawnandpawn.app.ui.components.SubScreen
import com.yawnandpawn.app.ui.components.subScreenTransition
import com.yawnandpawn.app.ui.resources.Res
import com.yawnandpawn.app.ui.resources.editor_back
import com.yawnandpawn.app.ui.resources.home_fix
import com.yawnandpawn.app.ui.resources.reliability_battery
import com.yawnandpawn.app.ui.resources.reliability_battery_reason
import com.yawnandpawn.app.ui.resources.reliability_camera
import com.yawnandpawn.app.ui.resources.reliability_camera_reason
import com.yawnandpawn.app.ui.resources.reliability_dnd
import com.yawnandpawn.app.ui.resources.reliability_dnd_reason
import com.yawnandpawn.app.ui.resources.reliability_done_this
import com.yawnandpawn.app.ui.resources.reliability_exact
import com.yawnandpawn.app.ui.resources.reliability_exact_reason
import com.yawnandpawn.app.ui.resources.reliability_full_screen
import com.yawnandpawn.app.ui.resources.reliability_full_screen_reason
import com.yawnandpawn.app.ui.resources.reliability_manufacturer
import com.yawnandpawn.app.ui.resources.reliability_manufacturer_reason
import com.yawnandpawn.app.ui.resources.reliability_microphone
import com.yawnandpawn.app.ui.resources.reliability_microphone_reason
import com.yawnandpawn.app.ui.resources.reliability_notifications
import com.yawnandpawn.app.ui.resources.reliability_notifications_reason
import com.yawnandpawn.app.ui.resources.reliability_ok
import com.yawnandpawn.app.ui.resources.reliability_open_settings
import com.yawnandpawn.app.ui.resources.reliability_revoked_battery
import com.yawnandpawn.app.ui.resources.reliability_revoked_dnd
import com.yawnandpawn.app.ui.resources.reliability_revoked_exact
import com.yawnandpawn.app.ui.resources.reliability_revoked_full_screen
import com.yawnandpawn.app.ui.resources.reliability_revoked_manufacturer
import com.yawnandpawn.app.ui.resources.reliability_revoked_notifications
import com.yawnandpawn.app.ui.resources.reliability_ring_test
import com.yawnandpawn.app.ui.resources.reliability_test
import com.yawnandpawn.app.ui.resources.reliability_test_reason
import com.yawnandpawn.app.ui.resources.reliability_xiaomi_step_1
import com.yawnandpawn.app.ui.resources.reliability_xiaomi_step_2
import com.yawnandpawn.app.ui.resources.reliability_xiaomi_step_3
import com.yawnandpawn.app.ui.resources.settings_reliability
import com.yawnandpawn.app.ui.resources.symbol_check_circle_fill1
import com.yawnandpawn.app.ui.resources.symbol_error
import com.yawnandpawn.app.ui.resources.symbol_radio_button_unchecked
import com.yawnandpawn.app.ui.theme.PpsTheme
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/** The reliability checklist items (FR-ONB-2/3), in the order the checklist lists them. */
enum class ChecklistItem(
    internal val title: StringResource,
    internal val reason: StringResource,
    /** "Alarm may not ring: ..." when the item was OK and got turned back off; `null` for items that cannot be revoked. */
    internal val revoked: StringResource?,
) {
    Notifications(
        Res.string.reliability_notifications,
        Res.string.reliability_notifications_reason,
        Res.string.reliability_revoked_notifications,
    ),
    FullScreen(Res.string.reliability_full_screen, Res.string.reliability_full_screen_reason, Res.string.reliability_revoked_full_screen),
    ExactAlarms(Res.string.reliability_exact, Res.string.reliability_exact_reason, Res.string.reliability_revoked_exact),
    DoNotDisturb(Res.string.reliability_dnd, Res.string.reliability_dnd_reason, Res.string.reliability_revoked_dnd),
    Battery(Res.string.reliability_battery, Res.string.reliability_battery_reason, Res.string.reliability_revoked_battery),
    Manufacturer(
        Res.string.reliability_manufacturer,
        Res.string.reliability_manufacturer_reason,
        Res.string.reliability_revoked_manufacturer,
    ),
    Camera(Res.string.reliability_camera, Res.string.reliability_camera_reason, null),
    Microphone(Res.string.reliability_microphone, Res.string.reliability_microphone_reason, null),
    TestAlarm(Res.string.reliability_test, Res.string.reliability_test_reason, null),
}

/** An item's status: OK, missing (never granted) or revoked (was OK, turned back off, e.g. after an update). */
enum class ItemStatus { Ok, Missing, Revoked }

/** One `checklist-row`. */
data class ChecklistRow(
    val item: ChecklistItem,
    val status: ItemStatus,
)

/**
 * What the Reliability checklist renders: [rows] (camera and microphone only when a check or recording needs them) and,
 * with [showManufacturerSteps], the manufacturer guidance sub-screen for [manufacturerSteps] (2 to 4 numbered steps).
 */
data class ReliabilityUiState(
    val rows: List<ChecklistRow>,
    val showManufacturerSteps: Boolean = false,
    val manufacturerSteps: List<StringResource> = XIAOMI_STEPS,
) {
    val allOk: Boolean get() = rows.all { it.status == ItemStatus.Ok }

    companion object {
        /** EXPERIENCE.md long-form copy, Xiaomi (other makers follow docs/oem-guidance.md). */
        val XIAOMI_STEPS: List<StringResource> =
            listOf(Res.string.reliability_xiaomi_step_1, Res.string.reliability_xiaomi_step_2, Res.string.reliability_xiaomi_step_3)
    }
}

/** Everything the user can do on the Reliability checklist. */
sealed interface ReliabilityIntent {
    data object Back : ReliabilityIntent

    data class FixClicked(
        val item: ChecklistItem,
    ) : ReliabilityIntent

    data object RingTestAlarm : ReliabilityIntent

    data object OpenManufacturerSettings : ReliabilityIntent

    data object ManufacturerDone : ReliabilityIntent
}

/**
 * The Reliability checklist, stateless (Settings, onboarding step 5, the Home `banner-warning`): one `checklist-row` per
 * item in a `card-group`, with a status icon (`check_circle` in `success` or `error` in `error`), title, reason and
 * either "OK" or `button-outlined` "Fix" (deep-links to the system setting; status is re-checked on return). A revoked
 * item says "Alarm may not ring: ..." in `error`. `button-outlined` "Ring a test alarm" stays, even when all is OK.
 * "Fix" on "Manufacturer settings" opens its steps as a sub-screen with "Open settings" and "I've done this".
 */
@Composable
fun ReliabilityScreen(
    state: ReliabilityUiState,
    onIntent: (ReliabilityIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    AnimatedContent(
        targetState = state.showManufacturerSteps,
        modifier = modifier,
        transitionSpec = { subScreenTransition(forward = targetState) },
        label = "reliability pane",
    ) { steps ->
        if (steps) {
            ManufacturerSteps(steps = state.manufacturerSteps, onIntent = onIntent)
        } else {
            Checklist(state = state, onIntent = onIntent)
        }
    }
}

@Composable
private fun Checklist(
    state: ReliabilityUiState,
    onIntent: (ReliabilityIntent) -> Unit,
) {
    SubScreen(
        title = stringResource(Res.string.settings_reliability),
        backContentDescription = stringResource(Res.string.editor_back),
        onBack = { onIntent(ReliabilityIntent.Back) },
    ) {
        ChecklistCard(rows = state.rows, onFix = { onIntent(ReliabilityIntent.FixClicked(it)) })
        PpsOutlinedButton(
            text = stringResource(Res.string.reliability_ring_test),
            onClick = { onIntent(ReliabilityIntent.RingTestAlarm) },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** The checklist rows in one `card-group` (the checklist screen and onboarding step 6, "Make sure it rings"). */
@Composable
fun ChecklistCard(
    rows: List<ChecklistRow>,
    onFix: (ChecklistItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    GroupCard(modifier = modifier) {
        rows.forEachIndexed { index, row ->
            if (index > 0) GroupDivider()
            ChecklistRowView(row = row, onFix = { onFix(row.item) })
        }
    }
}

/** `checklist-row`: 64 dp; status icon, title (`body`), reason (`caption`), then "OK" or "Fix". */
@Composable
private fun ChecklistRowView(
    row: ChecklistRow,
    onFix: () -> Unit,
) {
    val colors = PpsTheme.colors
    val spacing = PpsTheme.spacing
    // The test alarm is not a setting to fix: until it has rung it shows a hollow ring, and the button below rings it.
    val isTest = row.item == ChecklistItem.TestAlarm
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = ROW_HEIGHT)
                .padding(start = spacing.cardPadding, end = spacing.space3, top = spacing.space2, bottom = spacing.space2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier.weight(1f).semantics(mergeDescendants = true) { },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            when {
                row.status == ItemStatus.Ok -> RowIcon(icon = Res.drawable.symbol_check_circle_fill1, tint = colors.success)
                isTest -> RowIcon(icon = Res.drawable.symbol_radio_button_unchecked, tint = colors.outline)
                else -> RowIcon(icon = Res.drawable.symbol_error, tint = colors.error)
            }
            Column(modifier = Modifier.weight(1f).padding(horizontal = spacing.space3)) {
                Text(text = stringResource(row.item.title), style = PpsTheme.typography.body, color = colors.text)
                val revoked = row.item.revoked?.takeIf { row.status == ItemStatus.Revoked }
                Text(
                    text = stringResource(revoked ?: row.item.reason),
                    style = PpsTheme.typography.caption,
                    color = if (revoked != null) colors.error else colors.textSecondary,
                )
            }
            if (row.status == ItemStatus.Ok) {
                Text(
                    text = stringResource(Res.string.reliability_ok),
                    modifier = Modifier.padding(end = spacing.space1),
                    style = PpsTheme.typography.label,
                    color = colors.success,
                )
            }
        }
        if (row.status != ItemStatus.Ok && !isTest) {
            PpsOutlinedButton(text = stringResource(Res.string.home_fix), onClick = onFix, modifier = Modifier.widthIn(min = FIX_WIDTH))
        }
    }
}

/** The manufacturer guidance: numbered steps, then "Open settings" and "I've done this". */
@Composable
private fun ManufacturerSteps(
    steps: List<StringResource>,
    onIntent: (ReliabilityIntent) -> Unit,
) {
    val colors = PpsTheme.colors
    val spacing = PpsTheme.spacing
    SubScreen(
        title = stringResource(Res.string.reliability_manufacturer),
        backContentDescription = stringResource(Res.string.editor_back),
        onBack = { onIntent(ReliabilityIntent.Back) },
    ) {
        GroupCard {
            steps.forEachIndexed { index, step ->
                if (index > 0) GroupDivider()
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = spacing.targetMin)
                            .semantics(mergeDescendants = true) { }
                            .padding(horizontal = spacing.cardPadding, vertical = spacing.space3),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(spacing.space3),
                ) {
                    Text(
                        text = (index + 1).toString(),
                        modifier = Modifier.widthIn(min = spacing.space5),
                        style = PpsTheme.typography.title,
                        color = colors.textSecondary,
                        textAlign = TextAlign.Center,
                    )
                    Text(text = stringResource(step), style = PpsTheme.typography.body, color = colors.text)
                }
            }
        }
        PpsOutlinedButton(
            text = stringResource(Res.string.reliability_open_settings),
            onClick = { onIntent(ReliabilityIntent.OpenManufacturerSettings) },
            modifier = Modifier.fillMaxWidth(),
        )
        PpsTextButton(
            text = stringResource(Res.string.reliability_done_this),
            onClick = { onIntent(ReliabilityIntent.ManufacturerDone) },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** DESIGN.md `checklist-row.height`. */
private val ROW_HEIGHT = 64.dp

/** "Fix" keeps a comfortable width next to long reasons. */
private val FIX_WIDTH = 64.dp
