package com.yawnandpawn.app.debug.preview

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.key

/**
 * Debug-only launcher entry "Yawn & Pawn Preview" (src/debug, so release builds contain none of it): the design
 * preview of every screen and state with fake data. Edge-to-edge like MainActivity and the future wake activity.
 * `--es state <id>` (docs/design-preview/states.md), `--es theme light|dark` and `--ez font200 true` open a state
 * directly.
 */
class PreviewActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        show(intent)
    }

    // `am start` on the running preview (without -S) brings a new deep link here: start over at that state.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        show(intent)
    }

    private fun show(intent: Intent?) {
        val launch = PreviewLaunch.from(intent)
        setContent { key(launch) { PreviewApp(launch) } }
    }
}
