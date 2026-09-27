@file:Suppress("TooManyFunctions") // One file per wake surface keeps each check next to its private tiles and keys.

package com.yawnandpawn.app.ui.wake

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.yawnandpawn.app.ui.checks.description
import com.yawnandpawn.app.ui.checks.displayName
import com.yawnandpawn.app.ui.components.PpsTextButton
import com.yawnandpawn.app.ui.resources.Res
import com.yawnandpawn.app.ui.resources.camera_unavailable
import com.yawnandpawn.app.ui.resources.check_wrong_answer
import com.yawnandpawn.app.ui.resources.fallback_link
import com.yawnandpawn.app.ui.resources.house_hunt_checking
import com.yawnandpawn.app.ui.resources.house_hunt_matched
import com.yawnandpawn.app.ui.resources.house_hunt_no_match
import com.yawnandpawn.app.ui.resources.house_hunt_take_photo
import com.yawnandpawn.app.ui.resources.math_answer
import com.yawnandpawn.app.ui.resources.math_check
import com.yawnandpawn.app.ui.resources.math_delete_digit
import com.yawnandpawn.app.ui.resources.math_plus
import com.yawnandpawn.app.ui.resources.math_progress
import com.yawnandpawn.app.ui.resources.math_times
import com.yawnandpawn.app.ui.resources.memory_progress
import com.yawnandpawn.app.ui.resources.memory_tile
import com.yawnandpawn.app.ui.resources.memory_watch
import com.yawnandpawn.app.ui.resources.memory_your_turn
import com.yawnandpawn.app.ui.resources.qr_header
import com.yawnandpawn.app.ui.resources.qr_torch
import com.yawnandpawn.app.ui.resources.qr_viewfinder
import com.yawnandpawn.app.ui.resources.qr_wrong_code
import com.yawnandpawn.app.ui.resources.symbol_backspace
import com.yawnandpawn.app.ui.resources.symbol_flashlight_on
import com.yawnandpawn.app.ui.resources.symbol_house
import com.yawnandpawn.app.ui.resources.symbol_photo_camera
import com.yawnandpawn.app.ui.resources.word_clear
import com.yawnandpawn.app.ui.resources.word_letter
import com.yawnandpawn.app.ui.resources.word_progress
import com.yawnandpawn.app.ui.resources.word_shuffle
import com.yawnandpawn.app.ui.resources.word_slot_empty
import com.yawnandpawn.app.ui.resources.word_slot_filled
import com.yawnandpawn.app.ui.theme.PpsTheme
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

/**
 * A check, always Sunrise: the grace header (`countdown-ring`), the check itself (scrolls at large font scales) and the
 * footer pinned in the thumb zone (`fallback-link` when offered, then `button-snooze`). The confirm sheet and payment
 * snackbars sit over it. Every value is fixed state; timers, camera and validation belong to later stories.
 */
@Composable
fun CheckScreen(
    state: CheckUiState,
    onIntent: (WakeIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    WakeSurface(modifier = modifier) {
        val spacing = PpsTheme.spacing
        Column(modifier = Modifier.fillMaxSize().wakeContentPadding()) {
            Column(
                modifier = Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(spacing.space4),
            ) {
                GraceHeader(grace = state.grace)
                state.note?.let { WakeNoteView(note = it) }
                when (val content = state.content) {
                    is CheckContent.Math -> MathCheck(content, onIntent)
                    is CheckContent.WordUnscramble -> WordCheck(content, onIntent)
                    is CheckContent.MemorySequence -> MemoryCheck(content, onIntent)
                    is CheckContent.QrBarcode -> QrCheck(content, onIntent)
                    is CheckContent.HouseHunt -> HouseHuntCheck(content, onIntent)
                }
            }
            Column(
                modifier = Modifier.fillMaxWidth().padding(top = spacing.space3),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(spacing.space2),
            ) {
                state.message?.let { WakeSnackbar(message = it) }
                if (state.showFallbackLink) {
                    PpsTextButton(text = stringResource(Res.string.fallback_link), onClick = { onIntent(WakeIntent.FallbackLinkClicked) })
                }
                SnoozeButton(offer = state.snooze, onClick = { onIntent(WakeIntent.SnoozeClicked) })
            }
        }
        state.sheet?.let { sheet ->
            SnoozeConfirmSheet(
                sheet = sheet,
                onUpper = { onIntent(WakeIntent.SheetUpperClicked) },
                onDismiss = { onIntent(WakeIntent.SheetDismissed) },
            )
        }
    }
}

@Composable
private fun ProgressLine(text: String) {
    Text(text = text, style = PpsTheme.typography.label, color = PpsTheme.colors.textSecondary)
}

@Composable
private fun WrongAnswer() {
    Text(
        text = stringResource(Res.string.check_wrong_answer),
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        style = PpsTheme.typography.body,
        color = PpsTheme.colors.error,
    )
}

/**
 * Math: "Problem {n} of {count}", the problem (read as "47 plus 38"), the read-only answer in `display`, and the 3x3+1
 * `number-pad-key` grid (64 dp keys, 8 dp gaps) with backspace ("Delete digit") and "Check".
 */
@Composable
private fun MathCheck(
    content: CheckContent.Math,
    onIntent: (WakeIntent) -> Unit,
) {
    val colors = PpsTheme.colors
    val spacing = PpsTheme.spacing
    Column(verticalArrangement = Arrangement.spacedBy(spacing.space3), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(modifier = Modifier.fillMaxWidth()) {
            ProgressLine(stringResource(Res.string.math_progress, content.problemNumber, content.problemCount))
        }
        val symbol = if (content.operator == MathOperator.Plus) "+" else "×"
        val spoken =
            stringResource(
                if (content.operator ==
                    MathOperator.Plus
                ) {
                    Res.string.math_plus
                } else {
                    Res.string.math_times
                },
                content.left,
                content.right,
            )
        Text(
            text = "${content.left} $symbol ${content.right}",
            modifier = Modifier.clearAndSetSemantics { contentDescription = spoken }.semantics { heading() },
            style = PpsTheme.typography.display,
            color = colors.text,
        )
        val answerSpoken = stringResource(Res.string.math_answer, content.answer)
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = spacing.targetWake)
                    .clip(PpsTheme.shapes.sm)
                    .border(1.dp, if (content.wrong) colors.error else colors.outline, PpsTheme.shapes.sm)
                    .clearAndSetSemantics { contentDescription = answerSpoken },
            contentAlignment = Alignment.Center,
        ) {
            Text(text = content.answer, style = PpsTheme.typography.display, color = colors.text)
        }
        if (content.wrong) WrongAnswer()
        NumberPad(onIntent = onIntent)
    }
}

@Composable
private fun NumberPad(onIntent: (WakeIntent) -> Unit) {
    val spacing = PpsTheme.spacing
    val rows = listOf(listOf(1, 2, 3), listOf(4, 5, 6), listOf(7, 8, 9))
    Column(verticalArrangement = Arrangement.spacedBy(spacing.space2)) {
        rows.forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(spacing.space2)) {
                row.forEach { digit -> PadKey(label = digit.toString(), onClick = { onIntent(WakeIntent.DigitTapped(digit)) }) }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(spacing.space2)) {
            PadKey(label = null, spoken = stringResource(Res.string.math_delete_digit), onClick = { onIntent(WakeIntent.DeleteDigit) })
            PadKey(label = "0", onClick = { onIntent(WakeIntent.DigitTapped(0)) })
            PadKey(label = stringResource(Res.string.math_check), onClick = { onIntent(WakeIntent.SubmitAnswer) }, accent = true)
        }
    }
}

/** `number-pad-key`: 64 dp square, `rounded.md`, `surface-variant` fill, digit in `title`; "Check" is accent. */
@Composable
private fun PadKey(
    label: String?,
    onClick: () -> Unit,
    spoken: String? = null,
    accent: Boolean = false,
) {
    val colors = PpsTheme.colors
    val size = PpsTheme.spacing.targetWake
    Box(
        modifier =
            Modifier
                .sizeIn(minWidth = size + PpsTheme.spacing.space6, minHeight = size)
                .clip(PpsTheme.shapes.md)
                .background(if (accent) colors.accent else colors.surfaceVariant)
                .clickable(role = Role.Button, onClick = onClick)
                .then(if (spoken != null) Modifier.semantics { contentDescription = spoken } else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        if (label != null) {
            Text(
                text = label,
                style = PpsTheme.typography.title,
                color = if (accent) colors.onAccent else colors.text,
                textAlign = TextAlign.Center,
            )
        } else {
            Icon(painter = painterResource(Res.drawable.symbol_backspace), contentDescription = null, tint = colors.text)
        }
    }
}

/**
 * Word Unscramble: "Word {n} of {count}", the answer slots (dashed `outline` border when empty, "Slot {n}, empty"), the
 * `letter-tile`s ("Letter {letter}") and "Shuffle" / "Clear".
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WordCheck(
    content: CheckContent.WordUnscramble,
    onIntent: (WakeIntent) -> Unit,
) {
    val colors = PpsTheme.colors
    val spacing = PpsTheme.spacing
    Column(verticalArrangement = Arrangement.spacedBy(spacing.space4)) {
        ProgressLine(stringResource(Res.string.word_progress, content.wordNumber, content.wordCount))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(spacing.space2), verticalArrangement = Arrangement.spacedBy(spacing.space2)) {
            content.slots.forEachIndexed { index, letter ->
                val spoken =
                    if (letter == null) {
                        stringResource(Res.string.word_slot_empty, index + 1)
                    } else {
                        stringResource(Res.string.word_slot_filled, index + 1, letter.toString())
                    }
                LetterTile(letter = letter, spoken = spoken, dashed = letter == null, onClick = { onIntent(WakeIntent.SlotTapped(index)) })
            }
        }
        if (content.wrong) WrongAnswer()
        FlowRow(horizontalArrangement = Arrangement.spacedBy(spacing.space2), verticalArrangement = Arrangement.spacedBy(spacing.space2)) {
            content.pool.forEachIndexed { index, letter ->
                if (letter != null) {
                    LetterTile(
                        letter = letter,
                        spoken = stringResource(Res.string.word_letter, letter.toString()),
                        dashed = false,
                        onClick = { onIntent(WakeIntent.LetterTapped(index)) },
                    )
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(spacing.space2)) {
            PpsTextButton(text = stringResource(Res.string.word_shuffle), onClick = { onIntent(WakeIntent.ShuffleLetters) })
            PpsTextButton(text = stringResource(Res.string.word_clear), onClick = {
                onIntent(WakeIntent.ClearLetters)
            }, contentColor = colors.accentText)
        }
    }
}

/** `letter-tile`: 48 dp, `rounded.sm`, `surface-variant` with `outline` border (dashed for an empty slot), letter in `title`. */
@Composable
private fun LetterTile(
    letter: Char?,
    spoken: String,
    dashed: Boolean,
    onClick: () -> Unit,
) {
    val colors = PpsTheme.colors
    val size = PpsTheme.spacing.targetMin
    val radius = PpsTheme.shapes.sm
    Box(
        modifier =
            Modifier
                .sizeIn(minWidth = size, minHeight = size)
                .clip(radius)
                .then(
                    if (dashed) {
                        Modifier.drawBehind {
                            drawOutline(
                                outline = radius.createOutline(this.size, layoutDirection, this),
                                color = colors.outline,
                                style = Stroke(width = 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(DASH, DASH))),
                            )
                        }
                    } else {
                        Modifier.background(colors.surfaceVariant).border(1.dp, colors.outline, radius)
                    },
                ).clickable(role = Role.Button, onClick = onClick)
                .clearAndSetSemantics {
                    contentDescription = spoken
                },
        contentAlignment = Alignment.Center,
    ) {
        if (letter != null) Text(text = letter.toString(), style = PpsTheme.typography.title, color = colors.text)
    }
}

/**
 * Memory Sequence: "Watch the sequence" (input disabled, the lit tile in accent) or "Your turn", "Round {n} of {count}",
 * and the 3x3 `memory-tile` grid (64 dp, "Tile {number}"; numbers shown on every tile in the TalkBack variant).
 */
@Composable
private fun MemoryCheck(
    content: CheckContent.MemorySequence,
    onIntent: (WakeIntent) -> Unit,
) {
    val colors = PpsTheme.colors
    val spacing = PpsTheme.spacing
    val watching = content.phase == MemoryPhase.Watch
    Column(verticalArrangement = Arrangement.spacedBy(spacing.space3), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(
            modifier = Modifier.fillMaxWidth(),
        ) { ProgressLine(stringResource(Res.string.memory_progress, content.round, content.roundCount)) }
        Text(
            text = stringResource(if (watching) Res.string.memory_watch else Res.string.memory_your_turn),
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            style = PpsTheme.typography.headline,
            color = colors.text,
        )
        if (content.wrong) WrongAnswer()
        Column(verticalArrangement = Arrangement.spacedBy(spacing.space2)) {
            for (row in 0 until GRID) {
                Row(horizontalArrangement = Arrangement.spacedBy(spacing.space2)) {
                    for (column in 1..GRID) {
                        val tile = row * GRID + column
                        val lit = tile == content.litTile
                        val spoken = stringResource(Res.string.memory_tile, tile)
                        Box(
                            modifier =
                                Modifier
                                    .size(spacing.targetWake + spacing.space5)
                                    .clip(PpsTheme.shapes.md)
                                    .background(if (lit) colors.accent else colors.surface)
                                    .border(1.dp, colors.outline, PpsTheme.shapes.md)
                                    .clickable(enabled = !watching, role = Role.Button) { onIntent(WakeIntent.TileTapped(tile)) }
                                    .semantics {
                                        contentDescription = spoken
                                        if (watching) disabled()
                                    },
                            contentAlignment = Alignment.Center,
                        ) {
                            if (content.numbered || lit) {
                                Text(
                                    text = tile.toString(),
                                    modifier = Modifier.clearAndSetSemantics { },
                                    style = PpsTheme.typography.title,
                                    color = if (lit) colors.onAccent else colors.text,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** QR/Barcode: "Scan your code", the `viewfinder` with its square guide and 48 dp torch, or the camera-unavailable message. */
@Composable
private fun QrCheck(
    content: CheckContent.QrBarcode,
    onIntent: (WakeIntent) -> Unit,
) {
    val colors = PpsTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(PpsTheme.spacing.space3)) {
        Text(
            text = stringResource(Res.string.qr_header),
            modifier = Modifier.semantics { heading() },
            style = PpsTheme.typography.headline,
            color = colors.text,
        )
        if (!content.cameraAvailable) {
            CameraUnavailable()
            return@Column
        }
        Viewfinder(spoken = stringResource(Res.string.qr_viewfinder)) {
            Box(
                modifier =
                    Modifier
                        .align(Alignment.Center)
                        .fillMaxSize(GUIDE_FRACTION)
                        .aspectRatio(1f)
                        .border(2.dp, colors.inverseText, PpsTheme.shapes.md),
            )
            IconButton(
                onClick = { onIntent(WakeIntent.TorchToggled) },
                modifier = Modifier.align(Alignment.TopEnd).padding(PpsTheme.spacing.space2).size(PpsTheme.spacing.targetMin),
                colors = IconButtonDefaults.iconButtonColors(contentColor = colors.inverseText),
            ) {
                Icon(painter = painterResource(Res.drawable.symbol_flashlight_on), contentDescription = stringResource(Res.string.qr_torch))
            }
        }
        if (content.wrongCode) {
            Text(
                text = stringResource(Res.string.qr_wrong_code),
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                style = PpsTheme.typography.body,
                color = colors.error,
            )
        }
    }
}

/**
 * House Hunt: name and description, the `viewfinder` with the 72 dp ghost thumbnail of the reference photo (top left,
 * `rounded.sm`), the match result, and the 72 dp `shutter` ("Take photo") centred below.
 */
@Composable
private fun HouseHuntCheck(
    content: CheckContent.HouseHunt,
    onIntent: (WakeIntent) -> Unit,
) {
    val colors = PpsTheme.colors
    val spacing = PpsTheme.spacing
    Column(verticalArrangement = Arrangement.spacedBy(spacing.space3), horizontalAlignment = Alignment.CenterHorizontally) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = content.type.displayName(),
                modifier = Modifier.semantics { heading() },
                style = PpsTheme.typography.headline,
                color = colors.text,
            )
            Text(text = content.type.description(), style = PpsTheme.typography.body, color = colors.textSecondary)
        }
        if (!content.cameraAvailable) {
            CameraUnavailable()
            return@Column
        }
        Viewfinder(spoken = null) {
            Box(
                modifier =
                    Modifier
                        .padding(spacing.space2)
                        .size(GHOST_SIZE)
                        .clip(PpsTheme.shapes.sm)
                        .background(colors.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                Icon(painter = painterResource(Res.drawable.symbol_house), contentDescription = null, tint = colors.text)
            }
        }
        val result =
            when (content.result) {
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
                color = if (content.result == HouseHuntResult.Matched) colors.success else colors.text,
            )
        }
        val takePhoto = stringResource(Res.string.house_hunt_take_photo)
        Box(
            modifier =
                Modifier
                    .size(spacing.targetWakeHero)
                    .clip(PpsTheme.shapes.full)
                    .background(colors.accent)
                    .clickable(role = Role.Button) { onIntent(WakeIntent.ShutterClicked) }
                    .semantics { contentDescription = takePhoto },
            contentAlignment = Alignment.Center,
        ) {
            Icon(painter = painterResource(Res.drawable.symbol_photo_camera), contentDescription = null, tint = colors.onAccent)
        }
    }
}

/**
 * `viewfinder` placeholder: the camera preview area inside a `rounded.md` frame. The design preview has no camera, so
 * the feed is a flat `inverse-surface` fill.
 */
@Composable
private fun Viewfinder(
    spoken: String?,
    overlay: @Composable androidx.compose.foundation.layout.BoxScope.() -> Unit,
) {
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .aspectRatio(VIEWFINDER_RATIO)
                .clip(PpsTheme.shapes.md)
                .background(PpsTheme.colors.inverseSurface)
                .then(if (spoken != null) Modifier.semantics { contentDescription = spoken } else Modifier),
        content = overlay,
    )
}

@Composable
private fun CameraUnavailable() {
    Text(
        text = stringResource(Res.string.camera_unavailable),
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(PpsTheme.shapes.md)
                .background(PpsTheme.colors.surface)
                .padding(PpsTheme.spacing.cardPadding)
                .semantics { liveRegion = LiveRegionMode.Polite },
        style = PpsTheme.typography.body,
        color = PpsTheme.colors.text,
    )
}

private const val GRID = 3
private const val DASH = 8f
private const val GUIDE_FRACTION = 0.6f
private const val VIEWFINDER_RATIO = 4f / 3f
private val GHOST_SIZE = 72.dp
