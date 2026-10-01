package com.yawnandpawn.app.ui.home

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.yawnandpawn.app.ui.components.GlassBackdrop
import com.yawnandpawn.app.ui.components.glass
import com.yawnandpawn.app.ui.components.rememberReducedMotion
import com.yawnandpawn.app.ui.resources.Res
import com.yawnandpawn.app.ui.resources.app_name
import com.yawnandpawn.app.ui.resources.home_streak_day
import com.yawnandpawn.app.ui.resources.home_streak_days
import com.yawnandpawn.app.ui.theme.PpsTheme
import org.jetbrains.compose.resources.stringResource
import kotlin.math.roundToInt

/** The Home list key of `card-hero`, whose scroll position drives the collapsing header. */
internal const val HERO_KEY = "hero"

/**
 * How far the Home header has collapsed (owner decision 2026-09-28, Samsung Weather): [hero] 0 at rest to 1 once the
 * `card-hero` has scrolled fully under the pinned header, and [chip] 0 to 1 over the first 16 dp of scrolling (the
 * title's glass chip). Continuous and tied to the scroll position; with reduced motion each is an instant switch.
 */
class HeaderCollapse internal constructor(
    private val heroState: State<Float>,
    private val chipState: State<Float>,
) {
    val hero: Float get() = heroState.value
    val chip: Float get() = chipState.value
}

@Composable
internal fun rememberHeaderCollapse(listState: LazyListState): HeaderCollapse {
    val reduced = rememberReducedMotion()
    val chipDistance = with(LocalDensity.current) { CHIP_FADE_DISTANCE.toPx() }
    return remember(listState, reduced, chipDistance) {
        val hero =
            derivedStateOf {
                val item = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == HERO_KEY }
                val raw =
                    when {
                        item != null -> (-item.offset.toFloat() / item.size.coerceAtLeast(1)).coerceIn(0f, 1f)
                        listState.firstVisibleItemIndex >= 1 -> 1f
                        else -> 0f
                    }
                if (reduced) (if (raw >= HALF) 1f else 0f) else raw
            }
        val chip =
            derivedStateOf {
                val item = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == HERO_KEY }
                val scrolled =
                    when {
                        listState.firstVisibleItemIndex == 0 -> listState.firstVisibleItemScrollOffset.toFloat()
                        item != null -> -item.offset.toFloat()
                        else -> chipDistance
                    }
                val raw = (scrolled / chipDistance).coerceIn(0f, 1f)
                if (reduced) (if (raw > 0f) 1f else 0f) else raw
            }
        HeaderCollapse(hero, chip)
    }
}

/**
 * The pinned Home header: "Yawn & Pawn" top-left (like "Lahti"), gaining a frosted `glass-bar` chip behind it as
 * content scrolls under it and shrinking from `headline` to `title` size as the hero collapses, and a compact streak
 * chip ("12 days on time") fading in beside it. Both chips are `glass-strong` and blur [backdrop] on Android 12+. The
 * compact chip stays on the title's row whenever it fits beside the shrunk title (it wraps only at large font scales);
 * the row height never changes with the scroll. The compact chip is decorative for TalkBack (the hero card says it).
 */
@Composable
internal fun HomeHeader(
    streakDays: Int?,
    collapse: HeaderCollapse?,
    modifier: Modifier = Modifier,
    backdrop: GlassBackdrop? = null,
) {
    val colors = PpsTheme.colors
    val spacing = PpsTheme.spacing
    val titleScale = { 1f - (1f - TITLE_MIN_SCALE) * (collapse?.hero ?: 0f) }
    Layout(
        modifier = modifier.padding(start = spacing.screenMargin - spacing.space3, end = spacing.screenMargin, top = spacing.space3),
        content = {
            Box(
                modifier =
                    Modifier.graphicsLayer {
                        val scale = titleScale()
                        scaleX = scale
                        scaleY = scale
                        transformOrigin = TransformOrigin(0f, HALF)
                    },
            ) {
                Box(
                    modifier =
                        Modifier
                            .matchParentSize()
                            .graphicsLayer { alpha = collapse?.chip ?: 0f }
                            .glass(PpsTheme.shapes.full, strong = true, backdrop = backdrop),
                )
                Text(
                    text = stringResource(Res.string.app_name),
                    modifier = Modifier.padding(horizontal = spacing.space3, vertical = spacing.space1).semantics { heading() },
                    style = PpsTheme.typography.headline,
                    color = colors.text,
                )
            }
            if (streakDays != null && collapse != null) CompactStreak(streakDays, collapse, backdrop)
        },
        measurePolicy = headerMeasurePolicy(spacing.space2, titleScale),
    )
}

/** Title then compact chip: on one row when the chip fits beside the fully collapsed title, else under it. */
private fun headerMeasurePolicy(
    gapDp: androidx.compose.ui.unit.Dp,
    titleScale: () -> Float,
) = androidx.compose.ui.layout.MeasurePolicy { measurables, constraints ->
    val loose = constraints.copy(minWidth = 0, minHeight = 0)
    val title = measurables[0].measure(loose)
    val compact = measurables.getOrNull(1)?.measure(loose)
    val gap = gapDp.roundToPx()
    // Decided on the fully collapsed title, so it never flips (or changes the height) while scrolling.
    val fits = compact == null || (title.width * TITLE_MIN_SCALE).roundToInt() + gap + compact.width <= constraints.maxWidth
    val height =
        when {
            compact == null -> title.height
            fits -> maxOf(title.height, compact.height)
            else -> title.height + gap + compact.height
        }
    layout(constraints.maxWidth, height) {
        title.placeRelative(0, 0)
        if (compact != null) {
            if (fits) {
                // Follows the shrinking title, read at placement so scrolling only re-places it.
                compact.placeRelative((title.width * titleScale()).roundToInt() + gap, (title.height - compact.height) / 2)
            } else {
                compact.placeRelative(0, title.height + gap)
            }
        }
    }
}

/** The compact "{streak} days on time" chip; fades and rises in during the second half of the collapse. */
@Composable
private fun CompactStreak(
    streakDays: Int,
    collapse: HeaderCollapse,
    backdrop: GlassBackdrop?,
) {
    val colors = PpsTheme.colors
    val spacing = PpsTheme.spacing
    Row(
        modifier =
            Modifier
                .graphicsLayer {
                    // Cross-fade: the compact chip appears only once the hero is mostly gone, so the two
                    // never show the streak twice at once.
                    val shown = ((collapse.hero - COMPACT_START) / (1f - COMPACT_START)).coerceIn(0f, 1f)
                    alpha = shown
                    translationY = (1f - shown) * COMPACT_RISE.toPx()
                }.glass(PpsTheme.shapes.full, strong = true, backdrop = backdrop)
                .padding(horizontal = spacing.space3, vertical = spacing.space1)
                .clearAndSetSemantics { },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = streakDays.toString(), style = PpsTheme.typography.title, color = colors.accentText)
        Text(
            text = stringResource(if (streakDays == 1) Res.string.home_streak_day else Res.string.home_streak_days),
            modifier = Modifier.padding(start = spacing.space1),
            style = PpsTheme.typography.label,
            color = colors.text,
        )
    }
}

/**
 * The header zone (owner feedback 2026-09-28): scrolled content fades out as it enters it, from fully visible at
 * [zoneBottom] plus [fade] to gone at [zoneBottom] (the bottom of the pinned chips), so no card text shows behind or
 * above the chips. At rest the list starts exactly below the fade, so nothing is dimmed.
 */
internal fun Modifier.fadeUnderHeader(
    zoneBottom: () -> Float,
    fade: Float,
    opaque: Color,
): Modifier =
    graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
        .drawWithContent {
            drawContent()
            val start = zoneBottom()
            drawRect(
                brush = Brush.verticalGradient(listOf(Color.Transparent, opaque), startY = start, endY = start + fade),
                blendMode = BlendMode.DstIn,
            )
        }

/** The hero fades (gone at 60%), shrinks a little towards its top-left corner and lags behind the scroll as it collapses. */
internal fun Modifier.collapsingHero(collapse: HeaderCollapse): Modifier =
    graphicsLayer {
        val progress = collapse.hero
        alpha = (1f - progress * HERO_FADE_SPEED).coerceIn(0f, 1f)
        val scale = 1f - HERO_SHRINK * progress
        scaleX = scale
        scaleY = scale
        transformOrigin = TransformOrigin(0f, 0f)
        translationY = size.height * HERO_LAG * progress
    }

private const val HALF = 0.5f

/** The collapsed title is `title` size (20 sp) instead of `headline` (28 sp), like the small pinned "Lahti". */
private const val TITLE_MIN_SCALE = 20f / 28f
private const val HERO_SHRINK = 0.1f
private const val HERO_LAG = 0.2f

/** The hero is fully faded at 60% of its collapse; the compact chip fades in from 50%. */
private const val HERO_FADE_SPEED = 1f / 0.6f
private const val COMPACT_START = 0.5f
private val CHIP_FADE_DISTANCE = 16.dp
private val COMPACT_RISE = 8.dp
