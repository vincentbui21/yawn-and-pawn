package com.yawnandpawn.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import com.yawnandpawn.app.ui.purchases.SKELETON_DELAY_MILLIS
import com.yawnandpawn.app.ui.theme.PpsTheme
import kotlinx.coroutines.delay

/**
 * EXPERIENCE.md App screens, Loading: nothing for [SKELETON_DELAY_MILLIS], so a quick read never flashes it; then the static
 * `skeleton` [blocks] (no shimmer; glass cards of [SkeletonBlock]s), one polite TalkBack stop read as [loadingLabel]; after
 * [SLOW_LOADING_MILLIS] a glass card with the text [slowText] (`text`, also polite) replaces them. Tagged
 * [PROGRESS_SKELETON_TAG] while the blocks show.
 */
@Composable
fun DelayedSkeleton(
    loadingLabel: String,
    slowText: String,
    modifier: Modifier = Modifier,
    blocks: @Composable ColumnScope.() -> Unit,
) {
    var phase by remember { mutableStateOf(LoadingPhase.Hidden) }
    LaunchedEffect(Unit) {
        delay(SKELETON_DELAY_MILLIS)
        phase = LoadingPhase.Blocks
        delay(SLOW_LOADING_MILLIS - SKELETON_DELAY_MILLIS)
        phase = LoadingPhase.Slow
    }
    when (phase) {
        LoadingPhase.Hidden -> {
            Unit
        }

        LoadingPhase.Blocks -> {
            Column(
                modifier =
                    modifier.testTag(PROGRESS_SKELETON_TAG).clearAndSetSemantics {
                        contentDescription = loadingLabel
                        liveRegion = LiveRegionMode.Polite
                    },
                verticalArrangement = Arrangement.spacedBy(PpsTheme.spacing.space3),
                content = blocks,
            )
        }

        LoadingPhase.Slow -> {
            GroupCard(modifier = modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }) {
                Text(
                    text = slowText,
                    modifier = Modifier.padding(PpsTheme.spacing.cardPadding),
                    style = PpsTheme.typography.body,
                    color = PpsTheme.colors.text,
                )
            }
        }
    }
}

/** A static `skeleton` block: `surface-variant` with `rounded.sm`, never animated. */
@Composable
fun SkeletonBlock(modifier: Modifier = Modifier) {
    Box(modifier = modifier.background(PpsTheme.colors.surfaceVariant, PpsTheme.shapes.sm))
}

private enum class LoadingPhase { Hidden, Blocks, Slow }

/** EXPERIENCE.md App screens, Loading: after this long the skeleton gives way to a short text. */
const val SLOW_LOADING_MILLIS: Long = 3_000L

/** The test tag of Progress' loading skeleton. */
const val PROGRESS_SKELETON_TAG: String = "progress-skeleton"
