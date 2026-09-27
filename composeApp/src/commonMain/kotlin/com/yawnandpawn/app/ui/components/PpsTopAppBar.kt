package com.yawnandpawn.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import com.yawnandpawn.app.ui.resources.Res
import com.yawnandpawn.app.ui.resources.symbol_arrow_back
import com.yawnandpawn.app.ui.theme.PpsTheme
import org.jetbrains.compose.resources.painterResource

/**
 * `top-app-bar` of a pushed screen: flat on `bg`, 48 dp back arrow ([backContentDescription] for TalkBack),
 * title in `headline`. The title wraps instead of clipping at large font scales.
 */
@Composable
fun PpsTopAppBar(
    title: String,
    backContentDescription: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = PpsTheme.spacing
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .background(PpsTheme.colors.bg)
                .windowInsetsPadding(WindowInsets.statusBars)
                .heightIn(min = spacing.targetWake)
                .padding(start = spacing.space1, end = spacing.screenMargin, top = spacing.space2, bottom = spacing.space2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(
            onClick = onBack,
            modifier = Modifier.size(spacing.targetMin),
            colors = IconButtonDefaults.iconButtonColors(contentColor = PpsTheme.colors.text),
        ) {
            Icon(painter = painterResource(Res.drawable.symbol_arrow_back), contentDescription = backContentDescription)
        }
        Text(
            text = title,
            modifier = Modifier.padding(start = spacing.space1).semantics { heading() },
            style = PpsTheme.typography.headline,
            color = PpsTheme.colors.text,
        )
    }
}
