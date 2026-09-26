package com.yawnandpawn.app.debug

import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.yawnandpawn.app.ui.theme.PpsThemeMode

/** Launches [ThemeShowcaseActivity] with the given theme extras and runs [block] while it is resumed. */
fun withShowcase(
    mode: PpsThemeMode = PpsThemeMode.System,
    wake: Boolean = false,
    block: () -> Unit,
) {
    val intent =
        Intent(ApplicationProvider.getApplicationContext(), ThemeShowcaseActivity::class.java)
            .putExtra(ThemeShowcaseActivity.EXTRA_MODE, mode.name)
            .putExtra(ThemeShowcaseActivity.EXTRA_WAKE, wake)
    ActivityScenario.launch<ThemeShowcaseActivity>(intent).use { block() }
}
