package com.yawnandpawn.app.debug.preview

import android.content.Intent

/**
 * The stable deep-link id of a preview state: its screenshot id in kebab-case ("progress_empty" is `progress-empty`).
 * Used by `adb shell am start ... --es state <id>` and listed in docs/design-preview/states.md.
 */
val PreviewItem.stateId: String get() = id.replace('_', '-')

/** How the preview was started: an item or tap-through to open directly, the theme and the 200% font scale. */
data class PreviewLaunch(
    val state: String? = null,
    val dark: Boolean = false,
    val largeFont: Boolean = false,
) {
    companion object {
        const val EXTRA_STATE = "state"
        const val EXTRA_THEME = "theme"
        const val EXTRA_FONT_200 = "font200"

        /** `--es state <id>`, `--es theme light|dark`, `--ez font200 true`; anything else opens the menu in Light. */
        fun from(intent: Intent?): PreviewLaunch =
            PreviewLaunch(
                state = intent?.getStringExtra(EXTRA_STATE)?.trim()?.takeIf { it.isNotEmpty() },
                dark = intent?.getStringExtra(EXTRA_THEME).equals("dark", ignoreCase = true),
                largeFont = intent?.getBooleanExtra(EXTRA_FONT_200, false) ?: false,
            )
    }
}

/** Round 3, the setup flows (onboarding, checks, registration, recordings). */
internal const val SETUP_ROUND = 3

/** The heading of each round in the menu (and in states.md). */
internal val ROUND_HEADINGS: Map<Int, String> =
    mapOf(1 to "Round 1 · The daily loop", 2 to "Round 2 · Progress and settings", SETUP_ROUND to "Round 3 · Setup flows")

/** Does [query] match this item: case-insensitive on its state, its screen, its round or its id. */
internal fun PreviewItem.matches(query: String): Boolean =
    query.isBlank() ||
        listOf(title, group, ROUND_HEADINGS[round].orEmpty(), "round $round", stateId).any { it.contains(query.trim(), ignoreCase = true) }

/** Does [query] match this tap-through: its title, "tap through", its round or its id. */
internal fun Flow.matches(query: String): Boolean =
    query.isBlank() ||
        listOf(
            title,
            "Tap through",
            ROUND_HEADINGS[round].orEmpty(),
            "round $round",
            stateId,
        ).any { it.contains(query.trim(), ignoreCase = true) }

/**
 * docs/design-preview/states.md: every deep-link id with its round, screen and state, and the adb command. Kept in
 * sync by `PreviewMenuTest` (run it with `PREVIEW_WRITE_STATES=true` to rewrite the file after changing the menu).
 */
internal fun statesMarkdown(): String =
    buildString {
        appendLine("# Design preview: states")
        appendLine()
        appendLine("Every state of the debug design preview, with its deep-link id. Generated from `PreviewCatalog` and the")
        appendLine("tap-throughs; `PreviewMenuTest` fails when it is out of date. Rewrite it with")
        appendLine("`PREVIEW_WRITE_STATES=true ./gradlew :androidApp:testDebugUnitTest --tests \"*PreviewMenuTest\"`.")
        appendLine()
        appendLine("Open one directly on the phone (debug build installed; Back returns to the menu):")
        appendLine()
        appendLine("```sh")
        appendLine(
            "adb -s 4d804fdd shell am start -S -n com.yawnandpawn.app/.debug.preview.PreviewActivity " +
                "--es state progress-empty --es theme dark --ez font200 true",
        )
        appendLine("```")
        appendLine()
        appendLine("`--es theme light|dark` (default light) and `--ez font200 true` (default off) are optional. In Git Bash set")
        appendLine("`MSYS_NO_PATHCONV=1` first. Wake screens are always Sunrise, whatever the theme.")
        ROUND_HEADINGS.forEach { (round, heading) ->
            val flows = Flow.entries.filter { it.round == round }
            val items = PreviewCatalog.items.filter { it.round == round }
            if (flows.isEmpty() && items.isEmpty()) return@forEach
            appendLine()
            appendLine("## $heading")
            appendLine()
            appendLine("| Id | Screen | State |")
            appendLine("|---|---|---|")
            flows.forEach { appendLine("| `${it.stateId}` | Tap through | ${it.title} |") }
            items.forEach { appendLine("| `${it.stateId}` | ${it.group} | ${it.title}${if (it.wake) " (Sunrise)" else ""} |") }
        }
    }
