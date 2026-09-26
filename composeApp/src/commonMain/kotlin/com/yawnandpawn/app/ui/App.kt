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
import org.jetbrains.compose.resources.stringResource

/**
 * Root composable. Placeholder until the real theme (Story 1.3) and navigation arrive:
 * shows the app name with MaterialTheme defaults only.
 */
@Composable
fun App(modifier: Modifier = Modifier) {
    MaterialTheme {
        Surface(modifier = modifier.fillMaxSize()) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    text = stringResource(Res.string.app_name),
                    style = MaterialTheme.typography.headlineMedium,
                )
            }
        }
    }
}
