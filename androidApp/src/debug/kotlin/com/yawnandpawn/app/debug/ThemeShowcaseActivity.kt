package com.yawnandpawn.app.debug

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import com.yawnandpawn.app.ui.theme.PpsThemeMode

/**
 * Debug-only host for [ThemeShowcase]. Not in release builds (src/debug). Open it with:
 * `adb shell am start -n com.yawnandpawn.app/.debug.ThemeShowcaseActivity --es mode Dark --ez wake false`
 */
class ThemeShowcaseActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val mode = PpsThemeMode.entries.firstOrNull { it.name == intent.getStringExtra(EXTRA_MODE) } ?: PpsThemeMode.System
        val wake = intent.getBooleanExtra(EXTRA_WAKE, false)
        setContent {
            ThemeShowcase(mode = mode, wake = wake, modifier = Modifier.verticalScroll(rememberScrollState()))
        }
    }

    companion object {
        const val EXTRA_MODE = "mode"
        const val EXTRA_WAKE = "wake"
    }
}
