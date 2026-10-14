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
import com.yawnandpawn.app.ui.payments.SupportEmailTemplates
import com.yawnandpawn.app.ui.payments.chargeOptionOf
import com.yawnandpawn.app.ui.payments.supportEmailTemplates
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

    /**
     * The email the app's templates ([supportEmailTemplates], resolved in composition like the screen does) write about
     * [charge], or about none. The templates know no charge list, so any charge the user picks gets its lines.
     */
    private fun write(charge: ChargeOption?): SupportEmail {
        var templates: SupportEmailTemplates? = null
        withScreen(PpsThemeMode.Light, content = { templates = supportEmailTemplates() }) { composeRule.waitForIdle() }
        return checkNotNull(templates).write(charge)
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
        // Nothing the user cannot see: no token (raw or hashed), session or alarm. The alarm's label is checked through
        // the real path in MainActivityTest.
        listOf("raw-purchase-token", record.tokenHash, "session-7f3a", "alarm-gym").forEach { secret ->
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
        val email = write(charge = null)

        assertEquals("Charge question", email.subject)
        assertEquals("", email.body)
    }

    @Test
    fun `a charge the screen has not loaded still gets all its lines, never an empty body`() {
        // Review M2: the body comes from the charge given, not from a map of the charges loaded so far.
        val unseen = aPurchaseRecord(token = "never-listed", snoozeNumber = 5, orderId = "GPA.9").charge()

        assertEquals("Order ID: GPA.9\nDate: 2027-03-03T06:00:00+00:00\nPrice: $1.00\nStatus: Snooze 5", write(unseen).body)
    }
}
