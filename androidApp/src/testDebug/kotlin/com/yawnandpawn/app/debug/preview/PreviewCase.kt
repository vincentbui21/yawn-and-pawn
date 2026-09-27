package com.yawnandpawn.app.debug.preview

import androidx.activity.compose.setContent
import androidx.test.core.app.ActivityScenario
import com.yawnandpawn.app.ui.theme.PpsThemeMode

/** One screenshot or check of a preview state: its theme and font scale. */
data class PreviewCase(
    val item: PreviewItem,
    val mode: PpsThemeMode,
    val largeFont: Boolean,
) {
    val name: String
        get() =
            buildString {
                append(item.id)
                append(if (item.wake) "_sunrise" else "_${mode.name.lowercase()}")
                if (largeFont) append("_font200")
            }

    override fun toString(): String = name

    companion object {
        /**
         * App screens in Light and Dark, wake screens in Sunrise, and every primary state again at 200% font scale
         * (Light for app screens). [tall] picks the full-editor states (they get a taller window).
         */
        fun all(tall: Boolean): List<PreviewCase> =
            PreviewCatalog.items.filter { it.tall == tall }.flatMap { item ->
                val base =
                    if (item.wake) {
                        listOf(PreviewCase(item, PpsThemeMode.Light, largeFont = false))
                    } else {
                        listOf(PreviewCase(item, PpsThemeMode.Light, false), PreviewCase(item, PpsThemeMode.Dark, false))
                    }
                base + if (item.primary) listOf(PreviewCase(item, PpsThemeMode.Light, largeFont = true)) else emptyList()
            }
    }
}

/** Shows [case] in the real [PreviewActivity] (edge-to-edge, like on the phone) and runs [block] while it is resumed. */
fun withPreview(
    case: PreviewCase,
    is24Hour: Boolean = false,
    block: () -> Unit,
) {
    ActivityScenario.launch(PreviewActivity::class.java).use { scenario ->
        scenario.onActivity { activity ->
            activity.setContent { PreviewFrame(mode = case.mode, largeFont = case.largeFont) { case.item.render(is24Hour) } }
        }
        block()
    }
}
