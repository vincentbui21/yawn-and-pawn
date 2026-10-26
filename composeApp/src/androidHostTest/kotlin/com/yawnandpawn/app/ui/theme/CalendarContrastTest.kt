package com.yawnandpawn.app.ui.theme

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Story 6.6: every colour pair the Progress calendar card draws, in Light and Dark, must be a passing row of the DESIGN.md
 * "Verified contrast" table. The card is `glass`, measured on the gradient's top as every glass card in the table is.
 */
class CalendarContrastTest {
    private val designMd = File(checkNotNull(System.getProperty("yawnandpawn.designMd")) { "yawnandpawn.designMd not set" })
    private val rows = ContrastTable.parse(designMd.readText())

    /** Foreground / background, and where the calendar draws them. */
    private val calendarPairs =
        listOf(
            // The outcome-marker shapes under the dates.
            "success" to GLASS, // On time: the filled dot.
            "snoozed" to GLASS, // Snoozed: the dot with a clock.
            "missed" to GLASS, // Missed: the hollow ring.
            "outline" to GLASS, // Skipped or Test: the small neutral dot.
            "accent" to GLASS, // Today's outlined pill.
            "text" to GLASS, // The month title, today's and the selected date, the selected day's ring, the month arrows.
            "text-secondary" to GLASS, // The other dates and the weekday initials.
            "disabled-content" to GLASS, // A disabled "Previous month" or "Next month".
            "inverse-text" to "inverse-surface", // The label chip under the grid.
        )

    @Test
    fun `every calendar colour pair is a passing row of the DESIGN_md table in Light and Dark`() {
        listOf("Light", "Dark").forEach { theme ->
            val missing = ContrastTable.missingPairs(rows, theme, calendarPairs)
            assertTrue(missing.isEmpty(), "$theme calendar pairs missing from the contrast table (or documented as failing): $missing")
            val violations = ContrastTable.violations(ContrastTable.rowsFor(rows, theme, calendarPairs), PpsTokens.colorsByName)
            assertTrue(violations.isEmpty(), violations.joinToString("\n"))
        }
    }

    private companion object {
        const val GLASS = "glass+gradient-top"
    }
}
