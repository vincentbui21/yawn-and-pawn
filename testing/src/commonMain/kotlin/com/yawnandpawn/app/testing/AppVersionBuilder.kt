package com.yawnandpawn.app.testing

import com.yawnandpawn.app.core.AppVersion

/** Builds an [AppVersion] with test defaults. Fakes for core ports join this package as ports appear. */
fun anAppVersion(
    major: Int = 0,
    minor: Int = 1,
    patch: Int = 0,
): AppVersion = AppVersion(major, minor, patch)
