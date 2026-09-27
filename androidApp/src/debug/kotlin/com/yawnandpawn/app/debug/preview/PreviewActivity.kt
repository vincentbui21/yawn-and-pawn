package com.yawnandpawn.app.debug.preview

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge

/**
 * Debug-only launcher entry "Yawn & Pawn Preview" (src/debug, so release builds contain none of it): the design
 * preview of every screen and state with fake data. Edge-to-edge like MainActivity and the future wake activity.
 */
class PreviewActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent { PreviewApp() }
    }
}
