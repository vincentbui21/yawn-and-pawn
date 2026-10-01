package com.yawnandpawn.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.yawnandpawn.app.ui.App

/**
 * The single activity of the main app; hosts the Compose UI from :composeApp. It draws edge-to-edge (transparent
 * system bars over the theme `bg`, icons light or dark with the system setting), and the manifest sets
 * `adjustResize`, so the IME insets reach Compose and the editor's Save stays above the keyboard.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            App()
        }
    }
}
