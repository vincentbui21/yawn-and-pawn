package com.yawnandpawn.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.yawnandpawn.app.ui.resources.Res
import com.yawnandpawn.app.ui.resources.app_name
import com.yawnandpawn.app.ui.theme.PpsTheme
import com.yawnandpawn.app.ui.theme.PpsThemeMode
import org.jetbrains.compose.resources.stringResource

/**
 * Root composable. Placeholder until navigation arrives: shows the app name in [PpsTheme]
 * (System mode, so it follows the device light/dark setting).
 */
@Composable
fun App(
    modifier: Modifier = Modifier,
    themeMode: PpsThemeMode = PpsThemeMode.System,
) {
    PpsTheme(mode = themeMode) {
        Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    text = stringResource(Res.string.app_name),
                    style = PpsTheme.typography.headline,
                )
            }
        }
    }
}
