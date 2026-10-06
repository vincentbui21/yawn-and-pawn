package com.yawnandpawn.app.ui.checkpicker

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.yawnandpawn.app.ui.checks.CheckType
import com.yawnandpawn.app.ui.checks.Difficulty
import com.yawnandpawn.app.ui.checks.countText
import com.yawnandpawn.app.ui.checks.description
import com.yawnandpawn.app.ui.checks.displayName
import com.yawnandpawn.app.ui.checks.icon
import com.yawnandpawn.app.ui.components.BannerWarning
import com.yawnandpawn.app.ui.components.GroupCard
import com.yawnandpawn.app.ui.components.GroupDivider
import com.yawnandpawn.app.ui.components.InlineError
import com.yawnandpawn.app.ui.components.PpsSegmentedControl
import com.yawnandpawn.app.ui.components.RowIcon
import com.yawnandpawn.app.ui.components.SubScreen
import com.yawnandpawn.app.ui.editor.CheckChip
import com.yawnandpawn.app.ui.editor.CheckMode
import com.yawnandpawn.app.ui.resources.Res
import com.yawnandpawn.app.ui.resources.camera_unavailable_setup
import com.yawnandpawn.app.ui.resources.check_memory_talkback
import com.yawnandpawn.app.ui.resources.check_move_down
import com.yawnandpawn.app.ui.resources.check_move_up
import com.yawnandpawn.app.ui.resources.check_needs_camera
import com.yawnandpawn.app.ui.resources.check_picker_your_checks
import com.yawnandpawn.app.ui.resources.editor_back
import com.yawnandpawn.app.ui.resources.editor_check_chip
import com.yawnandpawn.app.ui.resources.editor_check_mode
import com.yawnandpawn.app.ui.resources.editor_checks
import com.yawnandpawn.app.ui.resources.editor_mode_all
import com.yawnandpawn.app.ui.resources.editor_mode_random
import com.yawnandpawn.app.ui.resources.editor_no_check
import com.yawnandpawn.app.ui.resources.editor_wake_check
import com.yawnandpawn.app.ui.resources.home_fix
import com.yawnandpawn.app.ui.resources.house_hunt_need_photo
import com.yawnandpawn.app.ui.resources.house_hunt_photo_count
import com.yawnandpawn.app.ui.resources.house_hunt_photo_one
import com.yawnandpawn.app.ui.resources.qr_code_saved
import com.yawnandpawn.app.ui.resources.qr_no_code
import com.yawnandpawn.app.ui.resources.symbol_arrow_downward
import com.yawnandpawn.app.ui.resources.symbol_arrow_upward
import com.yawnandpawn.app.ui.resources.symbol_check_box
import com.yawnandpawn.app.ui.resources.symbol_check_box_outline_blank
import com.yawnandpawn.app.ui.resources.symbol_chevron_right
import com.yawnandpawn.app.ui.theme.PpsTheme
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

/** What the Check picker renders (the editor's Wake-up check sub-screen and onboarding step 5). */
data class CheckPickerUiState(
    /** The selected checks, in the order they run in All mode, with their difficulty and count. */
    val checks: List<CheckChip> = listOf(CheckChip(CheckType.Math, Difficulty.Easy)),
    val mode: CheckMode = CheckMode.Random,
    /** Save or Continue was blocked because nothing is selected: "Pick at least one check." */
    val noCheckError: Boolean = false,
    /** TalkBack is on: Memory Sequence says "Uses numbered tiles with TalkBack." */
    val talkBackOn: Boolean = false,
    /** The camera can't be used: "Camera isn't available." with "Fix" above the checks. */
    val cameraUnavailable: Boolean = false,
    /** A QR/Barcode code is registered; without one its row says "Scan a code to use this check." */
    val qrCodeSaved: Boolean = false,
    /** House Hunt reference photos taken, 0 to 3; with none its row says "Take at least one photo." */
    val houseHuntPhotos: Int = 0,
    /** The checks the "Checks" card lists, in order: every one in the preview, the pickable ones in the app (Story 3.5). */
    val types: List<CheckType> = CheckType.entries,
)

/** Everything the user can do in the Check picker. */
sealed interface CheckPickerIntent {
    data class Toggled(
        val type: CheckType,
        val selected: Boolean,
    ) : CheckPickerIntent

    data class ModeSelected(
        val mode: CheckMode,
    ) : CheckPickerIntent

    /** A selected check's row: opens its Check setup (difficulty, count, "Try it", registration). */
    data class SetupClicked(
        val type: CheckType,
    ) : CheckPickerIntent

    /** All mode: one place up or down in the order. */
    data class Moved(
        val type: CheckType,
        val up: Boolean,
    ) : CheckPickerIntent

    data object FixCamera : CheckPickerIntent
}

/**
 * The Check picker as its own pushed screen (IA: reached from the editor's Wake-up check row and onboarding): a back
 * arrow with "Wake-up check" and [CheckPickerContent].
 */
@Composable
fun CheckPickerScreen(
    state: CheckPickerUiState,
    onIntent: (CheckPickerIntent) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SubScreen(
        title = stringResource(Res.string.editor_wake_check),
        backContentDescription = stringResource(Res.string.editor_back),
        onBack = onBack,
        modifier = modifier,
    ) {
        CheckPickerContent(state = state, onIntent = onIntent)
    }
}

/**
 * The Check picker's cards, stateless (owner direction 2026-09-27: grouped cards, rows that open sub-screens): "Checks",
 * one `check-type-card` row per check (icon, name, its one line, the camera note for the camera checks, a check box);
 * "Mode" Random / All with two or more; then "Your checks", the selected ones with their setup as the value ("Medium ·
 * 2 problems", "Code saved") and a chevron to Check setup, in All mode with "Move up" / "Move down" for the order.
 * Emitted into the caller's column of cards.
 */
@Composable
fun CheckPickerContent(
    state: CheckPickerUiState,
    onIntent: (CheckPickerIntent) -> Unit,
) {
    val spacing = PpsTheme.spacing
    if (state.cameraUnavailable) {
        BannerWarning(
            message = stringResource(Res.string.camera_unavailable_setup),
            actionText = stringResource(Res.string.home_fix),
            onAction = { onIntent(CheckPickerIntent.FixCamera) },
        )
    }
    GroupCard(title = stringResource(Res.string.editor_checks)) {
        state.types.forEachIndexed { index, type ->
            if (index > 0) GroupDivider()
            CheckTypeRow(
                type = type,
                checked = state.checks.any { it.type == type },
                talkBackOn = state.talkBackOn,
                onCheckedChange = { onIntent(CheckPickerIntent.Toggled(type, it)) },
            )
        }
    }
    if (state.noCheckError) {
        InlineError(text = stringResource(Res.string.editor_no_check), modifier = Modifier.padding(horizontal = spacing.cardPadding))
    }
    if (state.checks.size > 1) {
        GroupCard(title = stringResource(Res.string.editor_check_mode)) {
            PpsSegmentedControl(
                options = CheckMode.entries,
                selected = state.mode,
                label = { mode ->
                    stringResource(if (mode == CheckMode.Random) Res.string.editor_mode_random else Res.string.editor_mode_all)
                },
                onSelect = { onIntent(CheckPickerIntent.ModeSelected(it)) },
                modifier = Modifier.fillMaxWidth().padding(spacing.cardPadding),
            )
        }
    }
    if (state.checks.isNotEmpty()) {
        GroupCard(title = stringResource(Res.string.check_picker_your_checks)) {
            state.checks.forEachIndexed { index, chip ->
                if (index > 0) GroupDivider()
                SetupRow(
                    chip = chip,
                    value = setupSummary(chip, state),
                    reorder = if (state.mode == CheckMode.All && state.checks.size > 1) index to state.checks.lastIndex else null,
                    onIntent = onIntent,
                )
            }
        }
    }
}

/** A selected check's setup as a row value: "Medium · 2 problems", "Code saved", "2 photos", or what is missing. */
@Composable
fun setupSummary(
    chip: CheckChip,
    state: CheckPickerUiState,
): String =
    when (chip.type) {
        CheckType.QrBarcode -> {
            stringResource(if (state.qrCodeSaved) Res.string.qr_code_saved else Res.string.qr_no_code)
        }

        CheckType.HouseHunt -> {
            photoCountText(state.houseHuntPhotos) ?: stringResource(Res.string.house_hunt_need_photo)
        }

        else -> {
            stringResource(Res.string.editor_check_chip, chip.difficulty.displayName(), chip.type.countText(chip.count))
        }
    }

/** "1 photo", "2 photos", or `null` for none. */
@Composable
fun photoCountText(photos: Int): String? =
    when (photos) {
        0 -> null
        1 -> stringResource(Res.string.house_hunt_photo_one)
        else -> stringResource(Res.string.house_hunt_photo_count, photos)
    }

/**
 * One check in the "Checks" card (`check-type-card` as a row of the card-group): icon, name (`body`), its one line
 * (`caption`, `text-secondary`), for camera checks "Needs the camera. If it can't be used, you'll get a fallback check."
 * and, with TalkBack on, Memory Sequence's "Uses numbered tiles with TalkBack."; a check box (`accent-text` when
 * checked, not colour alone). The whole row is one checkbox for TalkBack.
 */
@Composable
private fun CheckTypeRow(
    type: CheckType,
    checked: Boolean,
    talkBackOn: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    val colors = PpsTheme.colors
    val spacing = PpsTheme.spacing
    Row(
        modifier =
            Modifier
                .toggleable(value = checked, role = Role.Checkbox, onValueChange = onCheckedChange)
                .fillMaxWidth()
                .heightIn(min = ROW_HEIGHT)
                .padding(horizontal = spacing.cardPadding, vertical = spacing.space3),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RowIcon(icon = type.icon, tint = colors.text, modifier = Modifier.padding(end = spacing.space3))
        Column(modifier = Modifier.weight(1f).padding(end = spacing.space3)) {
            Text(text = type.displayName(), style = PpsTheme.typography.body, color = colors.text)
            Text(text = type.description(), style = PpsTheme.typography.caption, color = colors.textSecondary)
            val note =
                when {
                    type.usesCamera -> stringResource(Res.string.check_needs_camera)
                    type == CheckType.MemorySequence && talkBackOn -> stringResource(Res.string.check_memory_talkback)
                    else -> null
                }
            note?.let {
                Text(
                    text = it,
                    modifier = Modifier.padding(top = spacing.space1),
                    style = PpsTheme.typography.caption,
                    color = colors.textSecondary,
                )
            }
        }
        Icon(
            painter = painterResource(if (checked) Res.drawable.symbol_check_box else Res.drawable.symbol_check_box_outline_blank),
            contentDescription = null,
            modifier = Modifier.size(ICON),
            tint = if (checked) colors.accentText else colors.textSecondary,
        )
    }
}

/**
 * A selected check in "Your checks" (`settings-row`): icon, name, its setup as the value and a chevron; the tap opens
 * Check setup. [reorder] (index to last index, All mode only) adds 48 dp "Move up" / "Move down" buttons before it.
 */
@Composable
private fun SetupRow(
    chip: CheckChip,
    value: String,
    reorder: Pair<Int, Int>?,
    onIntent: (CheckPickerIntent) -> Unit,
) {
    val colors = PpsTheme.colors
    val spacing = PpsTheme.spacing
    val moves = moveActions(chip, reorder, onIntent)
    Row(verticalAlignment = Alignment.CenterVertically) {
        reorder?.let { (index, last) ->
            MoveButton(
                icon = Res.drawable.symbol_arrow_upward,
                label = stringResource(Res.string.check_move_up),
                enabled = index > 0,
                onClick = { onIntent(CheckPickerIntent.Moved(chip.type, up = true)) },
                modifier = Modifier.padding(start = spacing.space1),
            )
            MoveButton(
                icon = Res.drawable.symbol_arrow_downward,
                label = stringResource(Res.string.check_move_down),
                enabled = index < last,
                onClick = { onIntent(CheckPickerIntent.Moved(chip.type, up = false)) },
            )
        }
        Row(
            modifier =
                Modifier
                    .weight(1f)
                    .clickable(role = Role.Button) { onIntent(CheckPickerIntent.SetupClicked(chip.type)) }
                    .semantics { if (moves.isNotEmpty()) customActions = moves }
                    .heightIn(min = ROW_HEIGHT)
                    .padding(start = if (reorder == null) spacing.cardPadding else spacing.space1, end = spacing.cardPadding)
                    .padding(vertical = spacing.space2),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (reorder == null) RowIcon(icon = chip.type.icon, tint = colors.text, modifier = Modifier.padding(end = spacing.space3))
            Column(modifier = Modifier.weight(1f)) {
                Text(text = chip.type.displayName(), style = PpsTheme.typography.body, color = colors.text)
                Text(text = value, style = PpsTheme.typography.caption, color = colors.textSecondary)
            }
            Icon(
                painter = painterResource(Res.drawable.symbol_chevron_right),
                contentDescription = null,
                modifier = Modifier.padding(start = spacing.space2).size(ICON),
                tint = colors.textSecondary,
            )
        }
    }
}

/**
 * TalkBack: the row's actions menu offers the same "Move up" / "Move down" as its buttons, each only where the check can
 * move ([reorder]: index to last index, All mode only; Story 3.5).
 */
@Composable
private fun moveActions(
    chip: CheckChip,
    reorder: Pair<Int, Int>?,
    onIntent: (CheckPickerIntent) -> Unit,
): List<CustomAccessibilityAction> {
    val moveUp = stringResource(Res.string.check_move_up)
    val moveDown = stringResource(Res.string.check_move_down)
    val (index, last) = reorder ?: return emptyList()

    fun move(
        label: String,
        up: Boolean,
    ) = CustomAccessibilityAction(label) {
        onIntent(CheckPickerIntent.Moved(chip.type, up))
        true
    }
    return listOfNotNull(if (index > 0) move(moveUp, up = true) else null, if (index < last) move(moveDown, up = false) else null)
}

@Composable
private fun MoveButton(
    icon: DrawableResource,
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = PpsTheme.colors
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.size(PpsTheme.spacing.targetMin),
        colors = IconButtonDefaults.iconButtonColors(contentColor = colors.text, disabledContentColor = colors.disabledContent),
    ) {
        Icon(painter = painterResource(icon), contentDescription = label)
    }
}

/** DESIGN.md `settings-row.height`. */
private val ROW_HEIGHT = 56.dp

/** Chevron and check box icons. */
private val ICON = 24.dp
