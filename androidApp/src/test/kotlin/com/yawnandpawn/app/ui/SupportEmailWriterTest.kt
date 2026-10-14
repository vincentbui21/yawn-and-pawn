package com.yawnandpawn.app.ui

import androidx.compose.ui.test.junit4.createEmptyComposeRule
import com.yawnandpawn.app.StopAppRule
import com.yawnandpawn.app.core.billing.Money
import com.yawnandpawn.app.core.billing.PriceSource
import com.yawnandpawn.app.core.billing.PurchaseRecord
import com.yawnandpawn.app.core.billing.RecordStatus
import com.yawnandpawn.app.testing.aPurchaseRecord
import com.yawnandpawn.app.ui.payments.ChargeOption
import com.yawnandpawn.app.ui.payments.SupportEmail
import com.yawnandpawn.app.ui.payments.SupportEmailWriter
import com.yawnandpawn.app.ui.payments.chargeOptionOf
import com.yawnandpawn.app.ui.payments.supportEmailWriter
import com.yawnandpawn.app.ui.theme.PpsThemeMode
import kotlinx.datetime.TimeZone
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * Story 4.17: the support email in the app's strings (EXPERIENCE.md verbatim): subject "Charge question", one line each
 * for the order id, the date and time (ISO 8601 with offset), the price and the status; never the purchase token, its
 * hash, the install id, the session id, the alarm or its label. With no charge the body is empty.
 */
@RunWith(RobolectricTestRunner::class)
class SupportEmailWriterTest {
    @get:Rule(order = 0)
    val stopApp = StopAppRule()

    @get:Rule(order = 1)
    val composeRule = createEmptyComposeRule()

    private val helsinki = TimeZone.of("Europe/Helsinki")

    /** The email [supportEmailWriter] writes about [charge] (or about none) when the screen shows [charges]. */
    private fun write(
        charge: ChargeOption?,
        charges: List<ChargeOption> = listOfNotNull(charge),
    ): SupportEmail {
        var writer: SupportEmailWriter? = null
        withScreen(PpsThemeMode.Light, content = { writer = supportEmailWriter(charges) }) { composeRule.waitForIdle() }
        return checkNotNull(writer).write(charge)
    }

    private fun PurchaseRecord.charge(zone: TimeZone = TimeZone.UTC) = chargeOptionOf(this, zone)

    @Test
    fun `a paid snooze's email has its order id, time with offset, price and status, one per line`() {
        val record =
            aPurchaseRecord(
                token = "raw-purchase-token",
                sessionId = "session-7f3a",
                alarmId = "alarm-gym",
                snoozeNumber = 2,
                orderId = "GPA.3301-2345-6789-01234",
                price = Money.of(2, "USD"),
            )

        val email = write(record.charge(helsinki))

        assertEquals("Charge question", email.subject)
        assertEquals(
            "Order ID: GPA.3301-2345-6789-01234\nDate: 2027-03-03T08:00:00+02:00\nPrice: $2.00\nStatus: Snooze 2",
            email.body,
        )
        // Nothing the user cannot see: no token (raw or hashed), session, alarm or label.
        listOf("raw-purchase-token", record.tokenHash, "session-7f3a", "alarm-gym", "Gym").forEach { secret ->
            assertFalse(secret in email.body || secret in email.subject, "the email carries $secret")
        }
    }

    @Test
    fun `a payment not used says so, and an estimate is never sent as a price`() {
        val record =
            aPurchaseRecord(status = RecordStatus.Stranded, snoozeNumber = null, orderId = null, priceSource = PriceSource.Snapshot)

        val email = write(record.charge())

        assertEquals("Date: 2027-03-03T06:00:00+00:00\nStatus: Not used, refunded automatically by Google", email.body)
    }

    @Test
    fun `with no charge the email has the subject and no order details`() {
        val email = write(charge = null, charges = listOf(aPurchaseRecord().charge()))

        assertEquals("Charge question", email.subject)
        assertEquals("", email.body)
    }
}
