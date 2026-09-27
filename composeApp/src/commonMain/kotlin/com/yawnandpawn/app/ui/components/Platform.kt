package com.yawnandpawn.app.ui.components

import androidx.compose.runtime.Composable

/** A real background blur behind glass is available (Android 12+, API 31, `RenderEffect`); below that glass is fill only. */
expect val isBackdropBlurSupported: Boolean

/**
 * The phone asks for no motion (animator duration scale 0, "Remove animations"). Decorative loops (the "I'm up" pulse)
 * stop; every other animation already follows the system scale and becomes an instant state change.
 */
@Composable
expect fun rememberReducedMotion(): Boolean

/**
 * Status and navigation bar icons dark ([darkIcons], on a light background: Light and Sunrise) or light (Dark), whatever
 * the phone's own dark-mode setting. Restores the previous icons when it leaves the composition, so a wake screen
 * inside the app hands the app theme's icons back.
 */
@Composable
expect fun SystemBarIcons(darkIcons: Boolean)
