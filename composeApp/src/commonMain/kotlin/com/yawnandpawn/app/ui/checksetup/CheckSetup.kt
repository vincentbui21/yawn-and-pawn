package com.yawnandpawn.app.ui.checksetup

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import com.yawnandpawn.app.ui.checkpicker.photoCountText
import com.yawnandpawn.app.ui.checks.CheckType
import com.yawnandpawn.app.ui.checks.Difficulty
import com.yawnandpawn.app.ui.checks.countLabel
import com.yawnandpawn.app.ui.checks.defaultCount
import com.yawnandpawn.app.ui.checks.description
import com.yawnandpawn.app.ui.checks.displayName
import com.yawnandpawn.app.ui.checks.hasDifficulty
import com.yawnandpawn.app.ui.checks.icon
import com.yawnandpawn.app.ui.components.BannerWarning
import com.yawnandpawn.app.ui.components.GroupCard
import com.yawnandpawn.app.ui.components.GroupDivider
import com.yawnandpawn.app.ui.components.NavRow
import com.yawnandpawn.app.ui.components.NoteInline
import com.yawnandpawn.app.ui.components.PpsStepper
import com.yawnandpawn.app.ui.components.PpsTextButton
import com.yawnandpawn.app.ui.components.RadioRow
import com.yawnandpawn.app.ui.components.RowIcon
import com.yawnandpawn.app.ui.components.SubScreen
import com.yawnandpawn.app.ui.resources.Res
import com.yawnandpawn.app.ui.resources.camera_unavailable_setup
import com.yawnandpawn.app.ui.resources.check_memory_talkback
import com.yawnandpawn.app.ui.resources.check_needs_camera
import com.yawnandpawn.app.ui.resources.check_try_it
import com.yawnandpawn.app.ui.resources.editor_back
import com.yawnandpawn.app.ui.resources.editor_difficulty
import com.yawnandpawn.app.ui.resources.home_fix
import com.yawnandpawn.app.ui.resources.house_hunt_need_photo
import com.yawnandpawn.app.ui.resources.house_hunt_not_restored
import com.yawnandpawn.app.ui.resources.house_hunt_photos_title
import com.yawnandpawn.app.ui.resources.house_hunt_retake
import com.yawnandpawn.app.ui.resources.qr_code_saved
import com.yawnandpawn.app.ui.resources.qr_no_code
import com.yawnandpawn.app.ui.resources.qr_printable
import com.yawnandpawn.app.ui.resources.qr_printable_body
import com.yawnandpawn.app.ui.resources.qr_your_code
import com.yawnandpawn.app.ui.resources.stepper_lower
import com.yawnandpawn.app.ui.resources.stepper_raise
import com.yawnandpawn.app.ui.resources.stepper_value
import com.yawnandpawn.app.ui.theme.PpsTheme
import com.yawnandpawn.app.ui.wake.CheckContent
import org.jetbrains.compose.resources.stringResource

/** What Check setup renders for one check (FR-PWK-12). */
data class CheckSetupUiState(
    val type: CheckType,
    val difficulty: Difficulty = Difficulty.Medium,
    /** Problems, words or rounds (Math, Word Unscramble, Memory Sequence), within [countRange]. */
    val count: Int = type.defaultCount,
    /** The counts the stepper allows: the core type's `countRange` in the app (Story 3.5, Math 1 to 10). */
    val countRange: IntRange = MIN_COUNT..MAX_COUNT,
    /** QR/Barcode: a code is registered. */
    val codeSaved: Boolean = false,
    /** QR/Barcode: "Make a printable QR" is offered (Epic 7; the app hides it until then, Story 3.10). */
    val printable: Boolean = true,
    /** House Hunt: reference photos taken, 0 to 3. */
    val photoCount: Int = 0,
    /** House Hunt: the photos weren't restored from a backup ("Retake them."). */
    val photosLost: Boolean = false,
    /** The camera can't be used: "Camera isn't available." with "Fix" (camera checks only). */
    val cameraUnavailable: Boolean = false,
    /** TalkBack is on: Memory Sequence says "Uses numbered tiles with TalkBack." */
    val talkBackOn: Boolean = false,
) {
    /** "Try it" needs what the check checks against: a code for QR/Barcode, a photo for House Hunt, the camera. */
    val canTry: Boolean
        get() =
            when (type) {
                CheckType.QrBarcode -> codeSaved && !cameraUnavailable
                CheckType.HouseHunt -> photoCount > 0 && !cameraUnavailable && !photosLost
                else -> true
            }

    companion object {
        const val MIN_COUNT = 1
        const val MAX_COUNT = 5
    }
}

/** The "Try it" preview: the check being tried, or [done] once it is solved. */
data class CheckPreviewUiState(
    val content: CheckContent,
    val done: Boolean = false,
)

/** Everything the user can do in Check setup. */
sealed interface CheckSetupIntent {
    data object Back : CheckSetupIntent

    data class DifficultySelected(
        val difficulty: Difficulty,
    ) : CheckSetupIntent

    data class CountChanged(
        val count: Int,
    ) : CheckSetupIntent

    /** "Try it": a no-stakes preview of the check. */
    data object TryItClicked : CheckSetupIntent

    /** QR/Barcode "Your code": opens QR registration. */
    data object ScanCodeClicked : CheckSetupIntent

    data object PrintableQrClicked : CheckSetupIntent

    /** House Hunt photos (also "Retake" on the not-restored banner): opens House Hunt registration. */
    data object PhotosClicked : CheckSetupIntent

    data object FixCamera : CheckSetupIntent
}

/**
 * Check setup, stateless (IA: from the Check picker; owner direction 2026-09-27, grouped cards and rows that open
 * sub-screens): a back arrow with the check's name; its one line (and the camera note, or Memory Sequence's TalkBack
 * note); for Math, Word Unscramble and Memory Sequence "Difficulty" Easy / Medium / Hard and a `stepper` for "Problems" /
 * "Words" / "Rounds"; for QR/Barcode "Your code" (opens QR registration) and "Make a printable QR"; for House Hunt
 * "House Hunt photos" (opens its registration); then "Try it", a no-stakes preview (FR-PWK-12), available once the check
 * has what it needs. Camera trouble shows "Camera isn't available." with "Fix", lost photos their banner with "Retake".
 */
@Composable
fun CheckSetupScreen(
    state: CheckSetupUiState,
    onIntent: (CheckSetupIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val type = state.type
    SubScreen(
        title = type.displayName(),
        backContentDescription = stringResource(Res.string.editor_back),
        onBack = { onIntent(CheckSetupIntent.Back) },
        modifier = modifier,
    ) {
        CheckSetupContent(state = state, onIntent = onIntent)
    }
}

/** Check setup's cards and "Try it", emitted into the caller's column (the editor's sub-screen, Story 3.5). */
@Composable
fun ColumnScope.CheckSetupContent(
    state: CheckSetupUiState,
    onIntent: (CheckSetupIntent) -> Unit,
) {
    val type = state.type
    Banners(state = state, onIntent = onIntent)
    AboutCard(state = state)
    when (type) {
        CheckType.QrBarcode -> QrRows(state = state, onIntent = onIntent)
        CheckType.HouseHunt -> PhotoRow(state = state, onIntent = onIntent)
        else -> if (type.hasDifficulty) DifficultyAndCount(state = state, onIntent = onIntent)
    }
    PpsTextButton(
        text = stringResource(Res.string.check_try_it),
        onClick = { onIntent(CheckSetupIntent.TryItClicked) },
        enabled = state.canTry,
        modifier = Modifier.align(Alignment.CenterHorizontally),
    )
}

@Composable
private fun Banners(
    state: CheckSetupUiState,
    onIntent: (CheckSetupIntent) -> Unit,
) {
    if (state.photosLost && state.type == CheckType.HouseHunt) {
        BannerWarning(
            message = stringResource(Res.string.house_hunt_not_restored),
            actionText = stringResource(Res.string.house_hunt_retake),
            onAction = { onIntent(CheckSetupIntent.PhotosClicked) },
        )
    }
    if (state.cameraUnavailable && state.type.usesCamera) {
        BannerWarning(
            message = stringResource(Res.string.camera_unavailable_setup),
            actionText = stringResource(Res.string.home_fix),
            onAction = { onIntent(CheckSetupIntent.FixCamera) },
        )
    }
}

/** The check's icon and one line in a card, with the camera or TalkBack note under it. */
@Composable
private fun AboutCard(state: CheckSetupUiState) {
    val colors = PpsTheme.colors
    val spacing = PpsTheme.spacing
    GroupCard {
        Row(
            modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) { }.padding(spacing.cardPadding),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RowIcon(icon = state.type.icon, tint = colors.text, modifier = Modifier.padding(end = spacing.space3))
            Text(text = state.type.description(), style = PpsTheme.typography.body, color = colors.text)
        }
    }
    val note =
        when {
            state.type.usesCamera -> stringResource(Res.string.check_needs_camera)
            state.type == CheckType.MemorySequence && state.talkBackOn -> stringResource(Res.string.check_memory_talkback)
            else -> null
        }
    note?.let { NoteInline(text = it, modifier = Modifier.padding(horizontal = spacing.cardPadding)) }
}

/** "Difficulty" Easy / Medium / Hard as radio rows, then the count `stepper` ("Problems", within the check's own range). */
@Composable
private fun DifficultyAndCount(
    state: CheckSetupUiState,
    onIntent: (CheckSetupIntent) -> Unit,
) {
    // Radio rows, not a segmented control: "Medium" must not break at 200% on a 360 dp phone.
    GroupCard(title = stringResource(Res.string.editor_difficulty)) {
        Difficulty.entries.forEachIndexed { index, difficulty ->
            if (index > 0) GroupDivider()
            RadioRow(
                label = difficulty.displayName(),
                selected = state.difficulty == difficulty,
                onSelect = { onIntent(CheckSetupIntent.DifficultySelected(difficulty)) },
            )
        }
    }
    state.type.countLabel()?.let { labelRes ->
        val label = stringResource(labelRes)
        val value = state.count.toString()
        GroupCard(title = label) {
            PpsStepper(
                valueText = value,
                valueDescription = stringResource(Res.string.stepper_value, label, value),
                decreaseLabel = stringResource(Res.string.stepper_lower, label),
                increaseLabel = stringResource(Res.string.stepper_raise, label),
                onDecrease = { onIntent(CheckSetupIntent.CountChanged(state.count - 1)) },
                onIncrease = { onIntent(CheckSetupIntent.CountChanged(state.count + 1)) },
                canDecrease = state.count > state.countRange.first,
                canIncrease = state.count < state.countRange.last,
            )
        }
    }
}

/** QR/Barcode: "Your code" ("Code saved" or "Scan a code to use this check.") and "Make a printable QR". */
@Composable
private fun QrRows(
    state: CheckSetupUiState,
    onIntent: (CheckSetupIntent) -> Unit,
) {
    GroupCard {
        NavRow(
            label = stringResource(Res.string.qr_your_code),
            value = stringResource(if (state.codeSaved) Res.string.qr_code_saved else Res.string.qr_no_code),
            onClick = { onIntent(CheckSetupIntent.ScanCodeClicked) },
        )
        if (state.printable) {
            GroupDivider()
            NavRow(
                label = stringResource(Res.string.qr_printable),
                value = stringResource(Res.string.qr_printable_body),
                onClick = { onIntent(CheckSetupIntent.PrintableQrClicked) },
            )
        }
    }
}

/** House Hunt: "House Hunt photos" with "2 photos" (or "Take at least one photo."). */
@Composable
private fun PhotoRow(
    state: CheckSetupUiState,
    onIntent: (CheckSetupIntent) -> Unit,
) {
    val photos = if (state.photosLost) null else photoCountText(state.photoCount)
    GroupCard {
        NavRow(
            label = stringResource(Res.string.house_hunt_photos_title),
            value = photos ?: stringResource(Res.string.house_hunt_need_photo),
            onClick = { onIntent(CheckSetupIntent.PhotosClicked) },
        )
    }
}
