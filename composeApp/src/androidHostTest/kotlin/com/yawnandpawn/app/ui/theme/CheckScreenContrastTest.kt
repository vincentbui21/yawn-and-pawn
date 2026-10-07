package com.yawnandpawn.app.ui.theme

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
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
    fun `every check-screen colour pair is a passing row of the DESIGN_md table`() {
        val missing = ContrastTable.missingPairs(rows, SUNRISE, checkScreenPairs)
        assertTrue(missing.isEmpty(), "check-screen pairs missing from the contrast table (or documented as failing): $missing")
        val violations = ContrastTable.violations(ContrastTable.rowsFor(rows, SUNRISE, checkScreenPairs), PpsTokens.colorsByName)
        assertTrue(violations.isEmpty(), violations.joinToString("\n"))
    }

    @Test
    fun `accent-text on the gradient top is a documented failure, so it is not a check-screen pair`() {
        // Where the link, "Shuffle" and "Clear" really sit is checked on the rendered screen (AccentTextPlacementTest).
        val onGradient = "accent-text" to "sunrise-gradient-top"
        assertEquals(listOf(onGradient), ContrastTable.missingPairs(rows, SUNRISE, listOf(onGradient)))
        assertTrue(onGradient !in checkScreenPairs)
    }

    // Fixtures: missingPairs reports a pair that is absent, only documented as failing, or in another theme.

    private val fixture =
        """
        ### Verified contrast (WCAG 2.x)

        | Theme | Pair | Kind | Ratio |
        |---|---|---|---|
        | Sunrise | text / bg | text | 16.68 |
        | Sunrise | accent / sunrise-gradient-top | graphic | **2.73, fails: no accent on the gradient** |
        | Light | error / bg | text | 5.95 |

        ## Typography
        """.trimIndent()

    @Test
    fun `missingPairs reports an absent pair, a documented failure and a pair of another theme`() {
        val fixtureRows = ContrastTable.parse(fixture)
        val pairs = listOf("text" to "bg", "accent" to "sunrise-gradient-top", "error" to "bg", "outline" to "surface")

        assertEquals(pairs.drop(1), ContrastTable.missingPairs(fixtureRows, SUNRISE, pairs))
        assertEquals(listOf(fixtureRows[0]), ContrastTable.rowsFor(fixtureRows, SUNRISE, listOf("text" to "bg")))
    }

    private companion object {
        const val SUNRISE = "Sunrise"
    }
}
