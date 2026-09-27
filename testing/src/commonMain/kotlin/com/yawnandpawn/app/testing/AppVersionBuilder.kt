package com.yawnandpawn.app.testing

import com.yawnandpawn.app.core.AppVersion

/** Builds an [AppVersion] with test defaults. Fakes for core ports live next to it (TimeFakes, AlarmFakes). */
fun anAppVersion(
    major: Int = 0,
    minor: Int = 1,
    patch: Int = 0,
): AppVersion = AppVersion(major, minor, patch)
