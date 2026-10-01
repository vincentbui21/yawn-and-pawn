package com.yawnandpawn.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.yawnandpawn.app.ui.resources.Res
import com.yawnandpawn.app.ui.resources.symbol_add
import com.yawnandpawn.app.ui.resources.symbol_remove
import com.yawnandpawn.app.ui.theme.PpsTheme
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource

/**
 * `stepper` (base fee, max snoozes): [valueText] in `display` with tabular figures between two 48 dp round icon buttons,
 * − and + with an `outline` ring. [onDecrease] / [onIncrease] step by one; a button at the end of the range is disabled
 * (`disabled-content`). TalkBack reads [decreaseLabel] / [increaseLabel] ("Lower Base fee") and announces the new value
 * ([valueDescription], "Base fee, $2") politely.
 */
@Composable
fun PpsStepper(
    valueText: String,
    valueDescription: String,
    decreaseLabel: String,
    increaseLabel: String,
    onDecrease: () -> Unit,
    onIncrease: () -> Unit,
    modifier: Modifier = Modifier,
    canDecrease: Boolean = true,
    canIncrease: Boolean = true,
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(PpsTheme.spacing.cardPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StepButton(icon = Res.drawable.symbol_remove, label = decreaseLabel, enabled = canDecrease, onClick = onDecrease)
        Text(
            text = valueText,
            modifier =
                Modifier
                    .weight(1f)
                    .padding(horizontal = PpsTheme.spacing.space2)
                    .semantics {
                        contentDescription = valueDescription
                        liveRegion = LiveRegionMode.Polite
                    },
            // Long localized prices ("25.000 ₫") step down to `headline` so they stay on one line on a 360 dp phone.
            style = if (valueText.length > DISPLAY_MAX_CHARS) PpsTheme.typography.headline else PpsTheme.typography.display,
            color = PpsTheme.colors.text,
            textAlign = TextAlign.Center,
        )
        StepButton(icon = Res.drawable.symbol_add, label = increaseLabel, enabled = canIncrease, onClick = onIncrease)
    }
}

@Composable
private fun StepButton(
    icon: DrawableResource,
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val colors = PpsTheme.colors
    OutlinedIconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.size(PpsTheme.spacing.targetMin),
        shape = PpsTheme.shapes.full,
        border = BorderStroke(1.dp, if (enabled) colors.outline else colors.disabledContent),
        colors =
            IconButtonDefaults.outlinedIconButtonColors(
                containerColor = Color.Transparent,
                contentColor = colors.text,
                disabledContainerColor = Color.Transparent,
                disabledContentColor = colors.disabledContent,
            ),
    ) {
        Icon(painter = painterResource(icon), contentDescription = label)
    }
}

/** The most characters the value shows in `display` between the two buttons. */
private const val DISPLAY_MAX_CHARS = 5
