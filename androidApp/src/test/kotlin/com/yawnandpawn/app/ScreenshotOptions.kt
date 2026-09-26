package com.yawnandpawn.app

import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.RoborazziOptions

/**
 * Options for every Roborazzi screenshot in this module.
 *
 * Baselines are recorded on Windows and verified on Linux CI too. Font anti-aliasing differs
 * slightly between the two (about 18 pixels, a 5.5e-6 diff fraction, on the empty screen), so a
 * 0.1% change threshold absorbs rendering noise while any real UI change (a changed string alone is
 * roughly 1% of the screen) still fails.
 */
@OptIn(ExperimentalRoborazziApi::class)
val screenshotOptions =
    RoborazziOptions(
        compareOptions = RoborazziOptions.CompareOptions(changeThreshold = 0.001F),
    )
