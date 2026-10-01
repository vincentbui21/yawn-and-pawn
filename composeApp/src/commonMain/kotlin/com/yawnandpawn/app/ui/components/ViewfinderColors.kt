package com.yawnandpawn.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.yawnandpawn.app.ui.resources.Res
import com.yawnandpawn.app.ui.resources.symbol_check
import com.yawnandpawn.app.ui.resources.symbol_flashlight_on
import com.yawnandpawn.app.ui.theme.PpsColorSet
import com.yawnandpawn.app.ui.theme.PpsTheme
import org.jetbrains.compose.resources.painterResource

/**
 * The colours of the `viewfinder` placeholder: a dark camera feed with light guides and icons. Light and Sunrise use
 * `inverse-surface` / `inverse-text` (16.84 and 16.68); Dark, whose inverse pair is light, uses `bg` / `text` (16.34) with a
 * hairline `glass-edge` [frame] so the feed stands out from the dark glass card.
 */
class ViewfinderColors(
    val feed: Color,
    val overlay: Color,
    val frame: Color? = null,
)

@Composable
fun viewfinderColors(): ViewfinderColors {
    val colors = PpsTheme.colors
    return if (PpsTheme.colorSet == PpsColorSet.Dark) {
        ViewfinderColors(feed = colors.bg, overlay = colors.text, frame = colors.glassEdge)
    } else {
        ViewfinderColors(feed = colors.inverseSurface, overlay = colors.inverseText)
    }
}

/**
 * `viewfinder` placeholder (House Hunt, QR/Barcode and their registration): the camera preview area, 4:3, inside a
 * `rounded.md` frame. The design preview has no camera, so the feed is a flat fill. [spoken] is its TalkBack label
 * ("Camera viewfinder. Point at your code."), `null` for none; [overlay] draws the guides and buttons over it.
 */
@Composable
fun ViewfinderPlaceholder(
    spoken: String?,
    modifier: Modifier = Modifier,
    overlay: @Composable BoxScope.(ViewfinderColors) -> Unit = {},
) {
    val colors = viewfinderColors()
    Box(
        modifier =
            modifier
                .fillMaxWidth()
                .aspectRatio(VIEWFINDER_RATIO)
                .clip(PpsTheme.shapes.md)
                .background(colors.feed)
                .then(colors.frame?.let { Modifier.border(PpsTheme.spacing.hairline, it, PpsTheme.shapes.md) } ?: Modifier)
                .then(if (spoken != null) Modifier.semantics { contentDescription = spoken } else Modifier),
    ) { overlay(colors) }
}

/**
 * The QR/Barcode overlay of a [ViewfinderPlaceholder]: the centred square guide (with a check once a code is found,
 * [detected]) and the 48 dp torch toggle ([torchLabel], "Torch") top right.
 */
@Composable
fun BoxScope.QrGuide(
    colors: ViewfinderColors,
    torchLabel: String,
    onTorch: () -> Unit,
    detected: Boolean = false,
) {
    Box(
        modifier =
            Modifier
                .align(Alignment.Center)
                .fillMaxSize(GUIDE_FRACTION)
                .aspectRatio(1f)
                .border(GUIDE_STROKE, colors.overlay, PpsTheme.shapes.md),
        contentAlignment = Alignment.Center,
    ) {
        if (detected) {
            Icon(
                painter = painterResource(Res.drawable.symbol_check),
                contentDescription = null,
                modifier = Modifier.size(PpsTheme.spacing.targetMin),
                tint = colors.overlay,
            )
        }
    }
    IconButton(
        onClick = onTorch,
        modifier = Modifier.align(Alignment.TopEnd).padding(PpsTheme.spacing.space2).size(PpsTheme.spacing.targetMin),
        colors = IconButtonDefaults.iconButtonColors(contentColor = colors.overlay),
    ) {
        Icon(painter = painterResource(Res.drawable.symbol_flashlight_on), contentDescription = torchLabel)
    }
}

private const val VIEWFINDER_RATIO = 4f / 3f
private const val GUIDE_FRACTION = 0.6f
private val GUIDE_STROKE = 2.dp
