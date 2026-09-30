package com.yawnandpawn.app.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.unit.dp
import com.yawnandpawn.app.ui.components.glass
import com.yawnandpawn.app.ui.theme.PpsTheme
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

/**
 * The app root: [content] for the [selected] tab above the `nav-bar`. The nav bar is hidden while [showNavBar] is
 * false (the session lock shows only `panel-session-in-progress`). [content] gets the insets the shell already
 * handles consumed, so its own status-bar padding still applies exactly once.
 */
@Composable
fun AppShell(
    selected: AppTab,
    onSelect: (AppTab) -> Unit,
    modifier: Modifier = Modifier,
    showNavBar: Boolean = true,
    content: @Composable () -> Unit,
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        // The screens draw the background gradient; the nav bar is glass over its flat bottom.
        containerColor = PpsTheme.colors.bg,
        contentColor = PpsTheme.colors.text,
        bottomBar = { if (showNavBar) PpsNavBar(selected = selected, onSelect = onSelect) },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(bottomOnly(padding)).consumeWindowInsets(bottomOnly(padding))) {
            content()
        }
    }
}

/** The screens draw their own status-bar inset (top bars, Home header), so the shell only pads the bottom. */
private fun bottomOnly(padding: PaddingValues) = PaddingValues(bottom = padding.calculateBottomPadding())

/**
 * `nav-bar`: Material 3 navigation bar on `glass-bar` (`glass-strong` with a hairline edge), three items with
 * Material Symbols Rounded icons; the selected
 * icon is fill 1 in `accent-text`, labels always shown (`text` selected, `text-secondary` otherwise). No indicator
 * pill, so no colour pair outside the contrast table.
 */
@Composable
fun PpsNavBar(
    selected: AppTab,
    onSelect: (AppTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = PpsTheme.colors
    NavigationBar(
        modifier = modifier.glass(RectangleShape, strong = true),
        containerColor = Color.Transparent,
        contentColor = colors.text,
        tonalElevation = 0.dp,
    ) {
        AppTab.entries.forEach { tab ->
            val isSelected = tab == selected
            NavigationBarItem(
                selected = isSelected,
                onClick = { onSelect(tab) },
                icon = { Icon(painter = painterResource(if (isSelected) tab.selectedIcon else tab.icon), contentDescription = null) },
                label = { Text(text = stringResource(tab.label), style = PpsTheme.typography.label) },
                alwaysShowLabel = true,
                colors =
                    NavigationBarItemDefaults.colors(
                        selectedIconColor = colors.accentText,
                        selectedTextColor = colors.text,
                        unselectedIconColor = colors.textSecondary,
                        unselectedTextColor = colors.textSecondary,
                        indicatorColor = Color.Transparent,
                    ),
            )
        }
    }
}
