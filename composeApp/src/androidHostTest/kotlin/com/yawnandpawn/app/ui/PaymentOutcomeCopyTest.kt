package com.yawnandpawn.app.ui

import com.yawnandpawn.app.core.session.PurchaseOutcome
import com.yawnandpawn.app.ui.wake.PaymentResult
import com.yawnandpawn.app.ui.wake.paymentOutcome
import com.yawnandpawn.app.ui.wake.text
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Story 4.14 (FR-MSG-4): each row of EXPERIENCE.md's Payment outcomes table shows exactly its message, verbatim, through
 * the one string resource of its `WakeMessage`; the rows with "none" show no message.
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

    private val rows =
        mapOf(
            "Unlock cancelled or failed" to PaymentResult.NotCharged(PurchaseOutcome.UnlockFailed),
            "Play sheet cancelled" to PaymentResult.NotCharged(PurchaseOutcome.Cancelled),
            "Billing error" to PaymentResult.NotCharged(PurchaseOutcome.Failed),
            "No connection" to PaymentResult.NotCharged(PurchaseOutcome.Offline),
            "Pending" to PaymentResult.Pending,
            "Purchased" to PaymentResult.Purchased,
            "Already paid, \"Use it\"" to PaymentResult.EarlierPaymentUsed,
            "Already paid, \"Not now\"" to PaymentResult.EarlierPaymentDeclined,
        )

    @Test
    fun `every row of the EXPERIENCE table is mapped, with its message verbatim or none`() {
        assertEquals(rows.keys, table.keys, "the table's rows")
        rows.forEach { (label, result) ->
            val expected = table.getValue(label)
            val message = paymentOutcome(result).message
            if (expected == null) {
                assertNull(message, label)
            } else {
                assertEquals(expected, strings[checkNotNull(message) { label }.text.key], label)
            }
        }
    }

    @Test
    fun `every failed or cancelled payment says No charge`() {
        PurchaseOutcome.entries.forEach { outcome ->
            val message = checkNotNull(paymentOutcome(PaymentResult.NotCharged(outcome)).message) { "$outcome" }
            assertEquals(true, strings.getValue(message.text.key).endsWith("No charge."), "$outcome")
        }
    }
}
