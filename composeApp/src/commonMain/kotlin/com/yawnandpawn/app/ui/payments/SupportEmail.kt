package com.yawnandpawn.app.ui.payments

import androidx.compose.runtime.Composable
import com.yawnandpawn.app.ui.format.formatMoney
import com.yawnandpawn.app.ui.resources.Res
import com.yawnandpawn.app.ui.resources.problem_email_date
import com.yawnandpawn.app.ui.resources.problem_email_order_id
import com.yawnandpawn.app.ui.resources.problem_email_price
import com.yawnandpawn.app.ui.resources.problem_email_status
import com.yawnandpawn.app.ui.resources.problem_email_subject
import com.yawnandpawn.app.ui.resources.purchase_snooze_number
import com.yawnandpawn.app.ui.resources.purchase_stranded
import org.jetbrains.compose.resources.stringResource

/**
 * The facts of [charge] the support email carries, one line each, in order (EXPERIENCE.md Long-form copy): the order
 * id, the purchase date and time (ISO 8601 with offset), the price and the status. A fact that is not known (no order
 * id, an amount that is only an estimate, no snooze number) has no line. Built only from [ChargeOption], which holds
 * nothing the user cannot see: never a purchase token, install id, session id, alarm or label.
 */
fun supportEmailFacts(charge: ChargeOption): List<SupportEmailFact> =
    listOfNotNull(
        charge.orderId?.let { SupportEmailFact.OrderId(it) },
        SupportEmailFact.Date(charge.isoPurchaseTime()),
        charge.price?.let { SupportEmailFact.Price(formatMoney(it)) },
        charge.status?.let { SupportEmailFact.Status(it) },
    )

/** One line of the support email's body. */
sealed interface SupportEmailFact {
    data class OrderId(
        val orderId: String,
    ) : SupportEmailFact

    data class Date(
        val iso: String,
    ) : SupportEmailFact

    data class Price(
        val price: String,
    ) : SupportEmailFact

    data class Status(
        val status: ChargeStatus,
    ) : SupportEmailFact
}

/** The body of the support email: [lines] one per line; empty when the email is about no charge. */
fun supportEmailBody(lines: List<String>): String = lines.joinToString(separator = "\n")

/**
 * The support email's templates in the app's strings ([SupportEmailTemplates]), resolved in composition so opening the
 * email never waits on a resource read; each line keeps [SupportEmailTemplates.SLOT] where [SupportEmailTemplates.write]
 * puts the charge's value.
 */
@Composable
fun supportEmailTemplates(): SupportEmailTemplates {
    val slot = SupportEmailTemplates.SLOT
    return SupportEmailTemplates(
        subject = stringResource(Res.string.problem_email_subject),
        orderIdLine = stringResource(Res.string.problem_email_order_id, slot),
        dateLine = stringResource(Res.string.problem_email_date, slot),
        priceLine = stringResource(Res.string.problem_email_price, slot),
        statusLine = stringResource(Res.string.problem_email_status, slot),
        snoozeStatus = stringResource(Res.string.purchase_snooze_number, slot),
        notUsedStatus = stringResource(Res.string.purchase_stranded),
    )
}
