package com.yawnandpawn.app.detekt

import dev.detekt.api.Rule
import dev.detekt.test.lint

/** Lints [body] as a file in [packageName] and returns the number of findings. */
fun Rule.findingsIn(
    body: String,
    packageName: String = "com.yawnandpawn.app.ui.home",
): Int = lint("package $packageName\n\n$body\n").size
