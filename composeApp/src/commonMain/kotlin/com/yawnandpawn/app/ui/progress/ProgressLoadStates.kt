package com.yawnandpawn.app.ui.progress

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import com.yawnandpawn.app.ui.components.DelayedSkeleton
import com.yawnandpawn.app.ui.components.GroupCard
import com.yawnandpawn.app.ui.components.PpsTextButton
import com.yawnandpawn.app.ui.components.SkeletonBlock
import com.yawnandpawn.app.ui.components.glass
import com.yawnandpawn.app.ui.resources.Res
import com.yawnandpawn.app.ui.resources.home_try_again
import com.yawnandpawn.app.ui.resources.progress_load_failed
import com.yawnandpawn.app.ui.resources.progress_loading_a11y
import com.yawnandpawn.app.ui.resources.progress_loading_slow
import com.yawnandpawn.app.ui.theme.PpsTheme
import org.jetbrains.compose.resources.stringResource

/**
 * Progress loading (Story 6.5, EXPERIENCE.md App screens, Loading): after 300 ms the static `skeleton` of the ring card
 * and the three tiles (`surface-variant` blocks on glass, no shimmer), read once as "Loading your progress"; after 3 s
 * "Still loading your progress.".
 */
@Composable
internal fun ProgressSkeleton() {
    val spacing = PpsTheme.spacing
    DelayedSkeleton(
        loadingLabel = stringResource(Res.string.progress_loading_a11y),
        slowText = stringResource(Res.string.progress_loading_slow),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().glass(PpsTheme.shapes.md).padding(spacing.cardPadding),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            SkeletonBlock(Modifier.fillMaxWidth(RING_BLOCK_FRACTION).aspectRatio(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(spacing.space2)) {
            repeat(TILES) {
                Column(
                    modifier = Modifier.weight(1f).glass(PpsTheme.shapes.md).padding(spacing.space3),
                    verticalArrangement = Arrangement.spacedBy(spacing.space2),
                ) {
                    SkeletonBlock(Modifier.fillMaxWidth(VALUE_BLOCK_FRACTION).height(spacing.space6))
                    SkeletonBlock(Modifier.fillMaxWidth(LABEL_BLOCK_FRACTION).height(spacing.space3))
                }
            }
        }
    }
}

/** The snapshot could not be read: "Couldn't load your progress." with `button-text` "Try again" (never the empty state). */
@Composable
internal fun LoadFailed(onRetry: () -> Unit) {
    val spacing = PpsTheme.spacing
    GroupCard {
        Column(modifier = Modifier.fillMaxWidth().padding(start = spacing.cardPadding, end = spacing.cardPadding, top = spacing.cardPadding)) {
            // Announced when loading turns into the failure.
            Text(
                text = stringResource(Res.string.progress_load_failed),
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                style = PpsTheme.typography.body,
                color = PpsTheme.colors.text,
            )
            PpsTextButton(
                text = stringResource(Res.string.home_try_again),
                onClick = onRetry,
                modifier = Modifier.padding(vertical = spacing.space1),
            )
        }
    }
}

private const val TILES = 3
private const val RING_BLOCK_FRACTION = 0.7f
private const val VALUE_BLOCK_FRACTION = 0.6f
private const val LABEL_BLOCK_FRACTION = 0.8f
