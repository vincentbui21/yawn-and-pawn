package com.yawnandpawn.app.ui.components

import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.yawnandpawn.app.ui.resources.Res
import com.yawnandpawn.app.ui.resources.symbol_check
import com.yawnandpawn.app.ui.theme.PpsTheme
import org.jetbrains.compose.resources.painterResource

/**
 * `segmented-control`: single select, always one selected. Material 3 segmented buttons with full-radius ends,
 * `outline` borders and the selected segment in accent with a check icon (not colour alone). Each segment is a
 * radio button for TalkBack, with its selected state. Labels wrap (and every segment grows to the same height)
 * instead of clipping at large font scales.
 */
@Composable
fun <T> PpsSegmentedControl(
    options: List<T>,
    selected: T,
    label: @Composable (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = PpsTheme.colors
    val spacing = PpsTheme.spacing
    SingleChoiceSegmentedButtonRow(modifier = modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
        options.forEachIndexed { index, option ->
            val isSelected = option == selected
            SegmentedButton(
                selected = isSelected,
                onClick = { onSelect(option) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size, baseShape = PpsTheme.shapes.full),
                modifier = Modifier.weight(1f).fillMaxHeight().heightIn(min = spacing.targetMin),
                colors =
                    SegmentedButtonDefaults.colors(
                        activeContainerColor = colors.accent,
                        activeContentColor = colors.onAccent,
                        activeBorderColor = colors.outline,
                        inactiveContainerColor = colors.bg,
                        inactiveContentColor = colors.text,
                        inactiveBorderColor = colors.outline,
                    ),
                contentPadding = PaddingValues(horizontal = spacing.space2, vertical = spacing.space1),
                // The check sits inside the label row, so the label wraps beside it instead of overflowing.
                icon = {},
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (isSelected) {
                        Icon(
                            painter = painterResource(Res.drawable.symbol_check),
                            contentDescription = null,
                            modifier = Modifier.padding(end = spacing.space1).size(CHECK_ICON_SIZE),
                        )
                    }
                    Text(
                        text = label(option),
                        modifier = Modifier.weight(1f, fill = false),
                        style = PpsTheme.typography.label,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}

/** Material 3 segmented-button icon size. */
private val CHECK_ICON_SIZE = 18.dp
