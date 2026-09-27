package com.yawnandpawn.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntSize
import com.yawnandpawn.app.ui.theme.PpsColorSet
import com.yawnandpawn.app.ui.theme.PpsTheme

/**
 * `background-gradient` (owner decision 2026-09-27): the theme's `gradient-top` fading into `bg` over the top 60% of the
 * screen (Sunrise: `sunrise-gradient-top` into `bg-sunrise` over the top 40%), flat `bg` below. Every screen draws its
 * content on it. Accent never sits directly on the gradient.
 */
@Composable
fun PpsBackground(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit = {},
) {
    val colors = PpsTheme.colors
    val stop = if (PpsTheme.colorSet == PpsColorSet.Sunrise) SUNRISE_GRADIENT_STOP else APP_GRADIENT_STOP
    Box(
        modifier =
            modifier
                .fillMaxSize()
                .background(colors.bg)
                .drawBehind {
                    drawRect(
                        brush = Brush.verticalGradient(listOf(colors.gradientTop, colors.bg), startY = 0f, endY = size.height * stop),
                    )
                },
        content = content,
    )
}

/**
 * What glass over moving content blurs: the content drawn by [glassSource], recorded into a graphics layer each frame.
 * A glass surface may read it only when it is not itself inside that content (a sibling drawn after it).
 */
@Stable
class GlassBackdrop internal constructor(
    internal val layer: GraphicsLayer,
) {
    internal var origin by mutableStateOf(Offset.Zero)
}

/** A [GlassBackdrop] for the content of one screen. */
@Composable
fun rememberGlassBackdrop(): GlassBackdrop {
    val layer = rememberGraphicsLayer()
    return remember(layer) { GlassBackdrop(layer) }
}

/** Records this content into [backdrop] (and draws it as usual) so a `glass-bar` over it can blur it. */
fun Modifier.glassSource(backdrop: GlassBackdrop): Modifier =
    onGloballyPositioned { backdrop.origin = it.positionInRoot() }
        .drawWithContent {
            backdrop.layer.record { this@drawWithContent.drawContent() }
            drawLayer(backdrop.layer)
        }

/**
 * A glass surface in [shape]: the translucent fill ([strong] = `glass-strong` for bars and sheets over moving content,
 * otherwise `glass`) with a `glass-edge` hairline. With a [backdrop] and Android 12+, the content beneath is drawn
 * blurred by `glass-blur` behind the fill; below API 31 the fill alone.
 */
@Composable
fun Modifier.glass(
    shape: Shape,
    strong: Boolean = false,
    backdrop: GlassBackdrop? = null,
): Modifier {
    val colors = PpsTheme.colors
    val spacing = PpsTheme.spacing
    val fill = if (strong) colors.glassStrong else colors.glass
    val blurLayer = if (backdrop != null && isBackdropBlurSupported) rememberGraphicsLayer() else null
    var origin by remember { mutableStateOf(Offset.Zero) }
    val blurred =
        if (backdrop != null && blurLayer != null) {
            Modifier
                .onGloballyPositioned { origin = it.positionInRoot() }
                .drawBehind {
                    val radius = spacing.glassBlur.toPx()
                    blurLayer.renderEffect = BlurEffect(radius, radius, TileMode.Clamp)
                    blurLayer.record(size = IntSize(size.width.toInt(), size.height.toInt())) {
                        translate(backdrop.origin.x - origin.x, backdrop.origin.y - origin.y) { drawLayer(backdrop.layer) }
                    }
                    drawLayer(blurLayer)
                }
        } else {
            Modifier
        }
    return this
        .clip(shape)
        .then(blurred)
        .background(fill, shape)
        .border(spacing.hairline, colors.glassEdge, shape)
}

/**
 * `card-group`: related rows together in one glass card (`rounded.md`, hairline edge). Put [GroupDivider] between rows;
 * rows bring their own `card-padding` sides. [title] is an optional section title above the card.
 */
@Composable
fun GroupCard(
    modifier: Modifier = Modifier,
    title: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        if (title != null) SectionTitle(title)
        Column(modifier = Modifier.fillMaxWidth().glass(PpsTheme.shapes.md), content = content)
    }
}

/** A short section title above a card (`label`, `text-secondary`), a heading for TalkBack. */
@Composable
fun SectionTitle(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text,
        modifier =
            modifier
                .padding(start = PpsTheme.spacing.cardPadding, bottom = PpsTheme.spacing.space2)
                .semantics { heading() },
        style = PpsTheme.typography.label,
        color = PpsTheme.colors.textSecondary,
    )
}

/** The hairline between two rows of a [GroupCard], inset by `card-padding` on both sides. */
@Composable
fun GroupDivider(modifier: Modifier = Modifier) {
    val spacing = PpsTheme.spacing
    Box(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(horizontal = spacing.cardPadding)
                .height(spacing.hairline)
                .background(PpsTheme.colors.glassEdge),
    )
}

/** App screens: the gradient fades out by 60% of the height. */
private const val APP_GRADIENT_STOP = 0.6f

/** Wake screens: the sunrise gradient stays in the top 40% (the thumb zone below is flat). */
private const val SUNRISE_GRADIENT_STOP = 0.4f
