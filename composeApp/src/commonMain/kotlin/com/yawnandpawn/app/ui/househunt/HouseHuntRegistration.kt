package com.yawnandpawn.app.ui.househunt

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.yawnandpawn.app.ui.components.BannerWarning
import com.yawnandpawn.app.ui.components.GroupCard
import com.yawnandpawn.app.ui.components.InlineError
import com.yawnandpawn.app.ui.components.PpsBackground
import com.yawnandpawn.app.ui.components.PpsOutlinedButton
import com.yawnandpawn.app.ui.components.PpsTextButton
import com.yawnandpawn.app.ui.components.PpsTopAppBar
import com.yawnandpawn.app.ui.components.SaveCancelPill
import com.yawnandpawn.app.ui.components.ViewfinderPlaceholder
import com.yawnandpawn.app.ui.resources.Res
import com.yawnandpawn.app.ui.resources.camera_unavailable_setup
import com.yawnandpawn.app.ui.resources.editor_back
import com.yawnandpawn.app.ui.resources.editor_cancel
import com.yawnandpawn.app.ui.resources.editor_save
import com.yawnandpawn.app.ui.resources.home_fix
import com.yawnandpawn.app.ui.resources.house_hunt_checking
import com.yawnandpawn.app.ui.resources.house_hunt_matched
import com.yawnandpawn.app.ui.resources.house_hunt_need_photo
import com.yawnandpawn.app.ui.resources.house_hunt_no_match
import com.yawnandpawn.app.ui.resources.house_hunt_photo
import com.yawnandpawn.app.ui.resources.house_hunt_photo_failed
import com.yawnandpawn.app.ui.resources.house_hunt_photos_body
import com.yawnandpawn.app.ui.resources.house_hunt_photos_title
import com.yawnandpawn.app.ui.resources.house_hunt_remove
import com.yawnandpawn.app.ui.resources.house_hunt_take_photo
import com.yawnandpawn.app.ui.resources.house_hunt_test_match
import com.yawnandpawn.app.ui.resources.symbol_house
import com.yawnandpawn.app.ui.resources.symbol_photo_camera
import com.yawnandpawn.app.ui.theme.PpsTheme
import com.yawnandpawn.app.ui.wake.HouseHuntResult
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

/** A House Hunt registration error, shown under the photos. */
enum class HouseHuntError { PhotoFailed, NoPhoto }

/** What House Hunt registration renders. */
data class HouseHuntRegistrationUiState(
    /** Reference photos taken, 0 to [MAX_PHOTOS]. */
    val photos: Int = 0,
    val cameraUnavailable: Boolean = false,
    val error: HouseHuntError? = null,
    /** "Test match": checking, matched, or doesn't match yet. */
    val testResult: HouseHuntResult = HouseHuntResult.None,
) {
    companion object {
        const val MAX_PHOTOS = 3
    }
}

/** Everything the user can do in House Hunt registration. */
sealed interface HouseHuntIntent {
    data object ShutterClicked : HouseHuntIntent

    data class RemovePhoto(
        val number: Int,
    ) : HouseHuntIntent

    data object TestMatchClicked : HouseHuntIntent

    data object SaveClicked : HouseHuntIntent

    /** Back, or "Cancel" in the pill. */
    data object Cancel : HouseHuntIntent

    data object FixCamera : HouseHuntIntent
}

/**
 * House Hunt registration, stateless (IA: from Check setup, capture 1 to 3 reference photos, test match): "House Hunt
 * photos" with "Take 1 to 3 photos of one spot far from your bed."; a glass card with the `viewfinder` and the 72 dp
 * `shutter` ("Take photo"; disabled with three photos); the photos as 72 dp thumbnails with "Remove" under each and
 * empty dashed slots; "Test match" with its result; errors ("Couldn't use that photo. Try again.", "Take at least one
 * photo.") in `error`; and the "Cancel | Save" pill in its own bottom area. Without a camera: "Camera isn't available."
 * with "Fix".
 */
@Composable
fun HouseHuntRegistrationScreen(
    state: HouseHuntRegistrationUiState,
    onIntent: (HouseHuntIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = PpsTheme.spacing
    PpsBackground(modifier = modifier) {
        Column(modifier = Modifier.fillMaxSize().imePadding()) {
            PpsTopAppBar(
                title = stringResource(Res.string.house_hunt_photos_title),
                backContentDescription = stringResource(Res.string.editor_back),
                onBack = { onIntent(HouseHuntIntent.Cancel) },
            )
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .clipToBounds()
                        .verticalScroll(rememberScrollState())
                        .padding(start = spacing.screenMargin, end = spacing.screenMargin, top = spacing.space2, bottom = spacing.space4),
                verticalArrangement = Arrangement.spacedBy(spacing.space3),
            ) {
                Text(
                    text = stringResource(Res.string.house_hunt_photos_body),
                    modifier = Modifier.padding(horizontal = spacing.space1),
                    style = PpsTheme.typography.body,
                    color = PpsTheme.colors.textSecondary,
                )
                if (state.cameraUnavailable) {
                    BannerWarning(
                        message = stringResource(Res.string.camera_unavailable_setup),
                        actionText = stringResource(Res.string.home_fix),
                        onAction = { onIntent(HouseHuntIntent.FixCamera) },
                    )
                } else {
                    CameraCard(canShoot = state.photos < HouseHuntRegistrationUiState.MAX_PHOTOS, onIntent = onIntent)
                }
                PhotosCard(state = state, onIntent = onIntent)
            }
            SaveCancelPill(
                cancelText = stringResource(Res.string.editor_cancel),
                saveText = stringResource(Res.string.editor_save),
                onCancel = { onIntent(HouseHuntIntent.Cancel) },
                onSave = { onIntent(HouseHuntIntent.SaveClicked) },
                modifier = Modifier.padding(top = spacing.space2),
            )
        }
    }
}

/** The viewfinder and the shutter (accent on glass, 3.24 in Light; never on the gradient). */
@Composable
private fun CameraCard(
    canShoot: Boolean,
    onIntent: (HouseHuntIntent) -> Unit,
) {
    val colors = PpsTheme.colors
    val spacing = PpsTheme.spacing
    GroupCard {
        Column(
            modifier = Modifier.fillMaxWidth().padding(spacing.space3),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(spacing.space3),
        ) {
            ViewfinderPlaceholder(spoken = null)
            val takePhoto = stringResource(Res.string.house_hunt_take_photo)
            Box(
                modifier =
                    Modifier
                        .size(spacing.targetWakeHero)
                        .clip(PpsTheme.shapes.full)
                        .background(if (canShoot) colors.accent else colors.disabledContainer)
                        .clickable(enabled = canShoot, role = Role.Button) { onIntent(HouseHuntIntent.ShutterClicked) }
                        .semantics { contentDescription = takePhoto },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(Res.drawable.symbol_photo_camera),
                    contentDescription = null,
                    tint = if (canShoot) colors.onAccent else colors.disabledContent,
                )
            }
        }
    }
}

/** The three photo slots, the error line, and "Test match" with its result. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PhotosCard(
    state: HouseHuntRegistrationUiState,
    onIntent: (HouseHuntIntent) -> Unit,
) {
    val colors = PpsTheme.colors
    val spacing = PpsTheme.spacing
    GroupCard {
        Column(
            modifier = Modifier.fillMaxWidth().padding(spacing.cardPadding),
            verticalArrangement = Arrangement.spacedBy(spacing.space3),
        ) {
            // Wraps to more rows at large font scales instead of squeezing "Remove".
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(spacing.space3),
                verticalArrangement = Arrangement.spacedBy(spacing.space3),
            ) {
                for (number in 1..HouseHuntRegistrationUiState.MAX_PHOTOS) {
                    if (number <= state.photos) {
                        PhotoSlot(number = number, onRemove = { onIntent(HouseHuntIntent.RemovePhoto(number)) })
                    } else {
                        EmptySlot()
                    }
                }
            }
            when (state.error) {
                HouseHuntError.PhotoFailed -> InlineError(text = stringResource(Res.string.house_hunt_photo_failed))
                HouseHuntError.NoPhoto -> InlineError(text = stringResource(Res.string.house_hunt_need_photo))
                null -> Unit
            }
            if (state.photos > 0) {
                PpsOutlinedButton(
                    text = stringResource(Res.string.house_hunt_test_match),
                    onClick = { onIntent(HouseHuntIntent.TestMatchClicked) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            val result =
                when (state.testResult) {
                    HouseHuntResult.None -> null
                    HouseHuntResult.Checking -> stringResource(Res.string.house_hunt_checking)
                    HouseHuntResult.Matched -> stringResource(Res.string.house_hunt_matched)
                    HouseHuntResult.NoMatch -> stringResource(Res.string.house_hunt_no_match)
                }
            result?.let {
                Text(
                    text = it,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    style = PpsTheme.typography.body,
                    color = if (state.testResult == HouseHuntResult.Matched) colors.success else colors.text,
                )
            }
        }
    }
}

/** A taken photo: a 72 dp `rounded.sm` thumbnail ("Photo 2" for TalkBack) with "Remove" under it. */
@Composable
private fun PhotoSlot(
    number: Int,
    onRemove: () -> Unit,
) {
    val colors = PpsTheme.colors
    val photo = stringResource(Res.string.house_hunt_photo, number)
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier =
                Modifier
                    .size(THUMBNAIL)
                    .clip(PpsTheme.shapes.sm)
                    .background(colors.surfaceVariant)
                    .semantics { contentDescription = photo },
            contentAlignment = Alignment.Center,
        ) {
            Icon(painter = painterResource(Res.drawable.symbol_house), contentDescription = null, tint = colors.text)
        }
        // TalkBack reads the thumbnail ("Photo 2") just before "Remove", so the pair is clear.
        PpsTextButton(text = stringResource(Res.string.house_hunt_remove), onClick = onRemove)
    }
}

/** An empty slot: a dashed `outline` square (decorative). */
@Composable
private fun EmptySlot() {
    val colors = PpsTheme.colors
    val shape = PpsTheme.shapes.sm
    Box(
        modifier =
            Modifier
                .size(THUMBNAIL)
                .drawBehind {
                    drawOutline(
                        outline = shape.createOutline(size, layoutDirection, this),
                        color = colors.outline,
                        style = Stroke(width = 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(DASH, DASH))),
                    )
                },
    )
}

/** DESIGN.md `viewfinder.ghostThumbnailSize`: thumbnails match the ghost shown during the check. */
private val THUMBNAIL = 72.dp
private const val DASH = 8f
