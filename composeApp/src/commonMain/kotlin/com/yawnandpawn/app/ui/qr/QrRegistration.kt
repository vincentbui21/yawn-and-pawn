package com.yawnandpawn.app.ui.qr

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.yawnandpawn.app.ui.checks.CheckType
import com.yawnandpawn.app.ui.checks.displayName
import com.yawnandpawn.app.ui.components.BannerWarning
import com.yawnandpawn.app.ui.components.ConfirmDialog
import com.yawnandpawn.app.ui.components.GroupCard
import com.yawnandpawn.app.ui.components.NavRow
import com.yawnandpawn.app.ui.components.NoteInline
import com.yawnandpawn.app.ui.components.PpsFilledButton
import com.yawnandpawn.app.ui.components.PpsTextButton
import com.yawnandpawn.app.ui.components.QrGuide
import com.yawnandpawn.app.ui.components.SubScreen
import com.yawnandpawn.app.ui.components.ViewfinderPlaceholder
import com.yawnandpawn.app.ui.resources.Res
import com.yawnandpawn.app.ui.resources.camera_unavailable_setup
import com.yawnandpawn.app.ui.resources.editor_back
import com.yawnandpawn.app.ui.resources.home_fix
import com.yawnandpawn.app.ui.resources.qr_image
import com.yawnandpawn.app.ui.resources.qr_no_code
import com.yawnandpawn.app.ui.resources.qr_pdf_line
import com.yawnandpawn.app.ui.resources.qr_print
import com.yawnandpawn.app.ui.resources.qr_printable
import com.yawnandpawn.app.ui.resources.qr_printable_body
import com.yawnandpawn.app.ui.resources.qr_replace
import com.yawnandpawn.app.ui.resources.qr_replace_dialog
import com.yawnandpawn.app.ui.resources.qr_scan_again
import com.yawnandpawn.app.ui.resources.qr_torch
import com.yawnandpawn.app.ui.resources.qr_use_code
import com.yawnandpawn.app.ui.resources.qr_viewfinder
import com.yawnandpawn.app.ui.resources.settings_delete_keep
import com.yawnandpawn.app.ui.theme.PpsColorSet
import com.yawnandpawn.app.ui.theme.PpsTheme
import org.jetbrains.compose.resources.stringResource

/** Where QR registration is: looking for a code, or one was found. */
enum class QrScanStep { Scanning, Detected }

/** What QR registration renders. */
data class QrRegistrationUiState(
    val step: QrScanStep = QrScanStep.Scanning,
    /** Camera permission denied or the camera failed: "Camera isn't available." with "Fix"; the printable QR still works. */
    val cameraUnavailable: Boolean = false,
)

/** What the printable QR renders. */
data class PrintableQrUiState(
    /** A code is already registered, so printing a new one replaces it (after "Replace your QR code? ..."). */
    val codeSaved: Boolean = false,
    val showReplaceDialog: Boolean = false,
)

/** Everything the user can do in QR registration and on the printable QR. */
sealed interface QrIntent {
    data object Back : QrIntent

    data object TorchToggled : QrIntent

    data object UseCode : QrIntent

    data object ScanAgain : QrIntent

    data object PrintableClicked : QrIntent

    data object ReplaceConfirmed : QrIntent

    data object ReplaceCancelled : QrIntent

    data object PrintClicked : QrIntent

    data object FixCamera : QrIntent
}

/**
 * QR/Barcode registration, stateless (IA: from Check setup; scan a code or make a printable QR): a back arrow with
 * "QR/Barcode"; the `viewfinder` in a glass card with the square guide and the torch ("Scan a code to use this check."
 * under it) or, once a code is found, the guide's check with "Use this code" (filled) and "Scan again"; then "Make a
 * printable QR". Without a camera: "Camera isn't available." with "Fix", and the printable QR.
 * [printable] shows "Make a printable QR" (FR-PWK-13): the design preview shows it; the app hides it until Epic 7 builds it.
 */
@Composable
fun QrRegistrationScreen(
    state: QrRegistrationUiState,
    onIntent: (QrIntent) -> Unit,
    modifier: Modifier = Modifier,
    printable: Boolean = true,
) {
    val spacing = PpsTheme.spacing
    SubScreen(
        title = CheckType.QrBarcode.displayName(),
        backContentDescription = stringResource(Res.string.editor_back),
        onBack = { onIntent(QrIntent.Back) },
        modifier = modifier,
    ) {
        if (state.cameraUnavailable) {
            BannerWarning(
                message = stringResource(Res.string.camera_unavailable_setup),
                actionText = stringResource(Res.string.home_fix),
                onAction = { onIntent(QrIntent.FixCamera) },
            )
        } else {
            GroupCard {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(spacing.space3),
                    verticalArrangement = Arrangement.spacedBy(spacing.space3),
                ) {
                    val detected = state.step == QrScanStep.Detected
                    val torch = stringResource(Res.string.qr_torch)
                    ViewfinderPlaceholder(spoken = stringResource(Res.string.qr_viewfinder)) { colors ->
                        QrGuide(colors = colors, torchLabel = torch, onTorch = { onIntent(QrIntent.TorchToggled) }, detected = detected)
                    }
                    if (detected) {
                        PpsFilledButton(
                            text = stringResource(Res.string.qr_use_code),
                            onClick = { onIntent(QrIntent.UseCode) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        PpsTextButton(
                            text = stringResource(Res.string.qr_scan_again),
                            onClick = { onIntent(QrIntent.ScanAgain) },
                            modifier = Modifier.align(Alignment.CenterHorizontally),
                        )
                    } else {
                        NoteInline(text = stringResource(Res.string.qr_no_code), modifier = Modifier.padding(horizontal = spacing.space1))
                    }
                }
            }
        }
        if (printable) {
            GroupCard {
                NavRow(
                    label = stringResource(Res.string.qr_printable),
                    value = stringResource(Res.string.qr_printable_body),
                    onClick = { onIntent(QrIntent.PrintableClicked) },
                )
            }
        }
    }
}

/**
 * "Make a printable QR", stateless: "Stick it somewhere far from your bed.", the page as it prints (a light sheet with
 * the QR code, TalkBack "QR code for your alarm", and "Scan this to stop your alarm." under it) and `button-filled`
 * "Print or save as PDF" (with a code already saved it asks "Replace your QR code? The old one stops working." first:
 * the new code replaces the old one). The sheet is dark on light in both themes so the code scans: `text` on `surface` in Light,
 * `inverse-text` on `inverse-surface` in Dark (17.85 and 16.34, DESIGN.md).
 */
@Composable
fun PrintableQrScreen(
    state: PrintableQrUiState,
    onIntent: (QrIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = PpsTheme.colors
    val spacing = PpsTheme.spacing
    val dark = PpsTheme.colorSet == PpsColorSet.Dark
    val paper = if (dark) colors.inverseSurface else colors.surface
    val ink = if (dark) colors.inverseText else colors.text
    SubScreen(
        title = stringResource(Res.string.qr_printable),
        backContentDescription = stringResource(Res.string.editor_back),
        onBack = { onIntent(QrIntent.Back) },
        modifier = modifier,
    ) {
        Text(
            text = stringResource(Res.string.qr_printable_body),
            modifier = Modifier.padding(horizontal = spacing.space1),
            style = PpsTheme.typography.body,
            color = colors.textSecondary,
        )
        GroupCard {
            Column(
                modifier =
                    Modifier
                        .padding(spacing.cardPadding)
                        .fillMaxWidth()
                        .clip(PpsTheme.shapes.md)
                        .background(paper)
                        .padding(spacing.space6),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(spacing.space4),
            ) {
                val spoken = stringResource(Res.string.qr_image)
                FakeQrCode(ink = ink, modifier = Modifier.size(QR_SIZE).semantics { contentDescription = spoken })
                Text(
                    text = stringResource(Res.string.qr_pdf_line),
                    style = PpsTheme.typography.body,
                    color = ink,
                    textAlign = TextAlign.Center,
                )
            }
        }
        PpsFilledButton(
            text = stringResource(Res.string.qr_print),
            onClick = { onIntent(QrIntent.PrintClicked) },
            modifier = Modifier.fillMaxWidth(),
        )
    }
    if (state.showReplaceDialog) {
        ConfirmDialog(
            title = stringResource(Res.string.qr_replace_dialog),
            confirmText = stringResource(Res.string.qr_replace),
            safeText = stringResource(Res.string.settings_delete_keep),
            onConfirm = { onIntent(QrIntent.ReplaceConfirmed) },
            onSafe = { onIntent(QrIntent.ReplaceCancelled) },
            destructive = true,
        )
    }
}

/**
 * A made-up QR code for the design preview (the real one is generated when its story lands): a 25 x 25 module grid with
 * the three finder squares and a fixed pseudo-random pattern, in [ink].
 */
@Composable
private fun FakeQrCode(
    ink: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier) {
        val cell = size.minDimension / MODULES
        val module = Size(cell, cell)
        for (row in 0 until MODULES) {
            for (column in 0 until MODULES) {
                if (isDark(row, column)) drawRect(color = ink, topLeft = Offset(column * cell, row * cell), size = module)
            }
        }
    }
}

/** Finder squares in three corners (7 x 7 with a ring and a 3 x 3 centre), a fixed hash pattern elsewhere. */
private fun isDark(
    row: Int,
    column: Int,
): Boolean {
    val finder = finderCell(row, column) ?: finderCell(row, column - (MODULES - FINDER)) ?: finderCell(row - (MODULES - FINDER), column)
    return finder ?: (!inFinderMargin(row, column) && mixed(row * MODULES + column) % HASH_MOD < HASH_DARK)
}

/** Inside a finder square at the top left: `true` dark, `false` light, `null` outside it. */
private fun finderCell(
    row: Int,
    column: Int,
): Boolean? {
    if (row !in 0 until FINDER || column !in 0 until FINDER) return null
    val ring = row == 0 || column == 0 || row == FINDER - 1 || column == FINDER - 1
    val centre = row in 2..FINDER - 3 && column in 2..FINDER - 3
    return ring || centre
}

/** The light margin around each finder square. */
private fun inFinderMargin(
    row: Int,
    column: Int,
): Boolean {
    val far = MODULES - FINDER - 1
    return (row <= FINDER && column <= FINDER) || (row <= FINDER && column >= far) || (row >= far && column <= FINDER)
}

private const val MODULES = 25
private const val FINDER = 7

/** A fixed integer hash (multiply, shift, xor), so the made-up modules look irregular like a real code. */
private fun mixed(cell: Int): Long {
    var x = (cell.toLong() + 1) * HASH_MULTIPLIER
    x = x xor (x ushr HASH_SHIFT)
    return (x * HASH_MULTIPLIER ushr HASH_SHIFT) and HASH_MASK
}

private const val HASH_MULTIPLIER = 2_654_435_761L
private const val HASH_SHIFT = 15
private const val HASH_MASK = 0xFFFFL
private const val HASH_MOD = 9
private const val HASH_DARK = 4
private val QR_SIZE = 200.dp
