package com.yawnandpawn.app.ui

import com.yawnandpawn.app.core.session.PurchaseOutcome
import com.yawnandpawn.app.ui.wake.PaymentOutcomeRow
import com.yawnandpawn.app.ui.wake.PaymentPendingRow
import com.yawnandpawn.app.ui.wake.paymentOutcome
import com.yawnandpawn.app.ui.wake.text
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Story 4.14 (FR-MSG-4): each row of EXPERIENCE.md's Payment outcomes table that has a message shows exactly it, verbatim,
 * through the one string resource of its `WakeMessage`. The rows with "none" (a paid snooze, "Use it", "Not now") have no
 * outcome at all; that no message shows for them is tested on the real screen (`PaymentOutcomeMessagesTest`).
 */
class PaymentOutcomeCopyTest {
    private val composeResources =
        File(checkNotNull(System.getProperty("yawnandpawn.composeResources")) { "yawnandpawn.composeResources not set" })

    /** The repository root, from `composeApp/src/commonMain/composeResources`. */
    private val root = composeResources.resolve("../../../..").canonicalFile

    private val experience =
        File(root, "_bmad-output/planning-artifacts/ux-designs/ux-pay-per-snooze-2026-09-26/EXPERIENCE.md").readText()

    private val strings =
        CopyRules
            .stringFiles(composeResources)
            .filter { it.parentFile?.name == "values" }
            .flatMap(CopyRules::read)
            .associate { it.key to it.text }

    /** The Payment outcomes table: its first column → its message column (the quoted text, or null for "none"). */
    private val table: Map<String, String?> =
        experience
            .substringAfter("### Payment outcomes")
            .lineSequence()
            .drop(1)
            .dropWhile { !it.startsWith("|") }
            .takeWhile { it.startsWith("|") }
            .drop(2)
            .associate { line ->
                val cells = line.split("|").map(String::trim)
                cells[1] to Regex("^\"(.*)\"$").find(cells[2])?.groupValues?.get(1)
            }

    private val rows: Map<String, PaymentOutcomeRow> =
        mapOf(
            "Unlock cancelled or failed" to paymentOutcome(PurchaseOutcome.UnlockFailed),
            "Play sheet cancelled" to paymentOutcome(PurchaseOutcome.Cancelled),
            "Billing error" to paymentOutcome(PurchaseOutcome.Failed),
            "No connection" to paymentOutcome(PurchaseOutcome.Offline),
            "Pending" to PaymentPendingRow,
        )

    private val withoutMessage = setOf("Purchased", "Already paid, \"Use it\"", "Already paid, \"Not now\"")

    private fun textOf(row: PaymentOutcomeRow): String = strings.getValue(row.message.text.key)

    @Test
    fun `every row of the EXPERIENCE table with a message shows it verbatim, and the others have no outcome`() {
        assertEquals(rows.keys + withoutMessage, table.keys, "the table's rows")
        rows.forEach { (label, row) -> assertEquals(table.getValue(label), textOf(row), label) }
        withoutMessage.forEach { label -> assertNull(table.getValue(label), label) }
    }

    @Test
    fun `every payment where nothing was charged says No charge, and a lost Play result never does`() {
        (PurchaseOutcome.entries - PurchaseOutcome.Unconfirmed).forEach { outcome ->
            assertTrue(textOf(paymentOutcome(outcome)).endsWith("No charge."), "$outcome")
        }
        // Review: a charge can still follow a lost result, so it says the pending copy instead.
        assertFalse(textOf(paymentOutcome(PurchaseOutcome.Unconfirmed)).contains("No charge"))
        assertEquals(table.getValue("Pending"), textOf(paymentOutcome(PurchaseOutcome.Unconfirmed)))
    }
}
