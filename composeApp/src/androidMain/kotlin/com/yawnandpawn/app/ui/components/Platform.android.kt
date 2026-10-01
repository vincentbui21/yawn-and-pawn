package com.yawnandpawn.app.ui.components

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.SoundPool
import android.os.Build
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import com.yawnandpawn.app.ui.R

actual val isBackdropBlurSupported: Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

@Composable
actual fun rememberReducedMotion(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }
}

@Composable
actual fun SystemBarIcons(darkIcons: Boolean) {
    val view = LocalView.current
    if (view.isInEditMode) return
    DisposableEffect(view, darkIcons) {
        val window = view.context.findActivity()?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        val previousStatus = controller?.isAppearanceLightStatusBars
        val previousNavigation = controller?.isAppearanceLightNavigationBars
        controller?.isAppearanceLightStatusBars = darkIcons
        controller?.isAppearanceLightNavigationBars = darkIcons
        onDispose {
            if (controller != null && previousStatus != null && previousNavigation != null) {
                controller.isAppearanceLightStatusBars = previousStatus
                controller.isAppearanceLightNavigationBars = previousNavigation
            }
        }
    }
}

private tailrec fun Context.findActivity(): Activity? =
    when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }

@Composable
actual fun rememberWheelTickSound(): () -> Unit {
    val context = LocalContext.current
    val player = remember(context) { WheelTickPlayer(context.applicationContext) }
    DisposableEffect(player) { onDispose { player.release() } }
    return player::play
}

/**
 * Plays `res/raw/wheel_tick.wav` (12 ms, 3.2 kHz, generated for this app, no licence) through [SoundPool] on the
 * sonification stream at a low level, only when the ringer is in normal mode.
 */
private class WheelTickPlayer(
    context: Context,
) {
    private val audio = context.getSystemService(AudioManager::class.java)
    private val pool =
        SoundPool
            .Builder()
            .setMaxStreams(2)
            .setAudioAttributes(
                AudioAttributes
                    .Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            ).build()
    private val soundId = pool.load(context, R.raw.wheel_tick, 1)

    fun play() {
        if (audio?.ringerMode != AudioManager.RINGER_MODE_NORMAL) return
        pool.play(soundId, TICK_VOLUME, TICK_VOLUME, 0, 0, 1f)
    }

    fun release() = pool.release()
}

/** Quiet: a third of the sonification volume. */
private const val TICK_VOLUME = 0.3f
