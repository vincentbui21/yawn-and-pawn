package com.yawnandpawn.app.ui.daydetail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import com.yawnandpawn.app.core.billing.Money
import com.yawnandpawn.app.ui.components.DelayedSkeleton
import com.yawnandpawn.app.ui.components.SkeletonBlock
import com.yawnandpawn.app.ui.components.glass
import com.yawnandpawn.app.ui.format.formatMoney
import com.yawnandpawn.app.ui.resources.Res
import com.yawnandpawn.app.ui.resources.progress_loading_a11y
import com.yawnandpawn.app.ui.resources.progress_loading_slow
import com.yawnandpawn.app.ui.resources.purchase_price_refunded_a11y
import com.yawnandpawn.app.ui.resources.purchase_stranded
import com.yawnandpawn.app.ui.theme.PpsTheme
import com.yawnandpawn.app.ui.theme.TABULAR_FIGURES
import org.jetbrains.compose.resources.stringResource

// Story 6.7: the parts of Day detail the approved round 2 design did not have (DayDetailScreen.kt keeps the approved ones).

/**
 * The session's payments Google refunded on its own (Story 6.7), in a `glass` card like the timeline: one line each,
 * "Not used, refunded automatically by Google" in `body` / `text` with the amount on the right in `text-secondary` (as
 * in Purchase history; no amount when it is only an estimate). One TalkBack stop each, "{note}, {price} refunded".
 */
@Composable
internal fun StrandedPayments(stranded: List<Money?>) {
    val colors = PpsTheme.colors
    val spacing = PpsTheme.spacing
    val note = stringResource(Res.string.purchase_stranded)
    Column(modifier = Modifier.fillMaxWidth().glass(PpsTheme.shapes.md).padding(vertical = spacing.space2)) {
        stranded.forEach { price ->
            val amount = price?.let { formatMoney(it) }
            val refunded = amount?.let { stringResource(Res.string.purchase_price_refunded_a11y, it) }
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = STRANDED_ROW_HEIGHT)
                        .clearAndSetSemantics { contentDescription = listOfNotNull(note, refunded).joinToString(", ") }
                        .padding(horizontal = spacing.cardPadding, vertical = spacing.space2),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(spacing.space3),
            ) {
                Text(text = note, modifier = Modifier.weight(1f), style = PpsTheme.typography.body, color = colors.text)
                if (amount != null) {
                    Text(
                        text = amount,
                        style = PpsTheme.typography.body.copy(fontFeatureSettings = TABULAR_FIGURES),
                        color = colors.textSecondary,
                        maxLines = 1,
                        softWrap = false,
                    )
                }
            }
        }
    }
}

/**
 * Day detail loading (EXPERIENCE.md App screens, Loading): after 300 ms the static skeleton of a hero card and a tile
 * row, read once as "Loading your progress"; after 3 s "Still loading your progress.".
 */
@Composable
internal fun DayDetailSkeleton() {
    val spacing = PpsTheme.spacing
    DelayedSkeleton(
        loadingLabel = stringResource(Res.string.progress_loading_a11y),
        slowText = stringResource(Res.string.progress_loading_slow),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().glass(PpsTheme.shapes.md).padding(spacing.cardPadding),
            verticalArrangement = Arrangement.spacedBy(spacing.space2),
        ) {
            SkeletonBlock(Modifier.fillMaxWidth(HERO_TITLE_FRACTION).height(spacing.space6))
            SkeletonBlock(Modifier.fillMaxWidth(HERO_LINE_FRACTION).height(spacing.space3))
            SkeletonBlock(Modifier.fillMaxWidth(HERO_FIGURE_FRACTION).height(spacing.space8))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(spacing.space2)) {
            repeat(TILES) {
                Column(
                    modifier = Modifier.weight(1f).glass(PpsTheme.shapes.md).padding(spacing.space3),
                    verticalArrangement = Arrangement.spacedBy(spacing.space2),
                ) {
                    SkeletonBlock(Modifier.fillMaxWidth(TILE_VALUE_FRACTION).height(spacing.space6))
                    SkeletonBlock(Modifier.fillMaxWidth(TILE_LABEL_FRACTION).height(spacing.space3))
                }
            }
        }
    }
}

/** As the timeline rows. */
private val STRANDED_ROW_HEIGHT = 44.dp

private const val TILES = 3
private const val HERO_TITLE_FRACTION = 0.4f
private const val HERO_LINE_FRACTION = 0.6f
private const val HERO_FIGURE_FRACTION = 0.5f
private const val TILE_VALUE_FRACTION = 0.6f
private const val TILE_LABEL_FRACTION = 0.8f
