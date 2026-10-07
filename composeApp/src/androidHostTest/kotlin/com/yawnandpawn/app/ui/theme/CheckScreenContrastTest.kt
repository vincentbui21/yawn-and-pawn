package com.yawnandpawn.app.ui.theme

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Story 3.12: the colour pairs the check screens draw (Check screen, grace header, Fallback check picker, "Try it",
 * Success), each named where it is drawn, must be rows of the DESIGN.md "Verified contrast" table, and every such row
 * must pass. A pair missing from the table fails, so a new pair on a check screen needs its computed row first.
 */
class CheckScreenContrastTest {
    private val designMd = File(checkNotNull(System.getProperty("yawnandpawn.designMd")) { "yawnandpawn.designMd not set" })
    private val rows = ContrastTable.parse(designMd.readText())

    /** Foreground / background, Sunrise tokens, and where the check screens draw them. */
    private val checkScreenPairs =
        listOf(
            // Glass cards: the grace header, the camera-unavailable message, the picker cards, Success, "Try it" done.
            "text" to "glass+sunrise-gradient-top",
            "text-secondary" to "glass+sunrise-gradient-top", // Picker card descriptions.
            "accent" to "glass+sunrise-gradient-top", // The countdown ring on the header card.
            // On the screen: the problem, the phase line, "Scan your code", the picker title, the progress lines.
            "text" to "sunrise-gradient-top",
            "text" to "bg",
            "text-secondary" to "sunrise-gradient-top",
            "text-secondary" to "bg",
            "error" to "sunrise-gradient-top", // "Not quite. Try again." and the different-code line, high on the screen.
            "error" to "bg", // The same lines lower down.
            // Inputs.
            "text" to "surface-variant", // Number pad keys and letter tiles.
            "on-accent" to "accent", // "Check" and the number on a lit memory tile.
            "accent" to "surface", // A lit memory tile beside the others.
            "outline" to "surface", // The memory tile border.
            "text" to "surface", // The number on a memory tile (numbered variant).
            "inverse-text" to "inverse-surface", // The viewfinder's guide and torch.
            // The footer in the flat thumb zone (below the gradient's top 40%).
            "accent-text" to "bg", // "Can't do this check?", "Shuffle" and "Clear".
            "outline" to "bg", // The snooze border.
            "disabled-content" to "disabled-container", // Snooze unavailable.
        )

    @Test
    fun `every check-screen colour pair is in the DESIGN_md table and passes`() {
        val missing =
            checkScreenPairs.filter { (fg, bg) ->
                rows.none { it.theme == "Sunrise" && it.foreground == fg && it.background == bg && !it.documentedFailure }
            }
        assertTrue(missing.isEmpty(), "check-screen pairs missing from the contrast table (or documented as failing): $missing")
        val used =
            rows.filter { row ->
                row.theme == "Sunrise" &&
                    checkScreenPairs.any { it.first == row.foreground && it.second == row.background }
            }
        val violations = ContrastTable.violations(used, PpsTokens.colorsByName)
        assertTrue(violations.isEmpty(), violations.joinToString("\n"))
    }

    @Test
    fun `the fallback link is never drawn on the gradient top, where accent-text fails`() {
        // The link sits in the footer, in the flat thumb zone (AccentTextPlacementTest checks the position on screen).
        val onGradient = rows.single { it.theme == "Sunrise" && it.foreground == "accent-text" && it.background == "sunrise-gradient-top" }
        assertTrue(onGradient.documentedFailure, "accent-text on the gradient top is a documented failure, so nothing draws it there")
        assertTrue("accent-text" to "sunrise-gradient-top" !in checkScreenPairs)
    }

    @Test
    fun `a pair missing from the table is reported`() {
        val pairs = listOf("accent-text" to "surface-variant")
        val missing = pairs.filter { (fg, bg) -> rows.none { it.theme == "Sunrise" && it.foreground == fg && it.background == bg } }
        assertTrue(missing == pairs, "the check catches a missing pair")
    }
}
