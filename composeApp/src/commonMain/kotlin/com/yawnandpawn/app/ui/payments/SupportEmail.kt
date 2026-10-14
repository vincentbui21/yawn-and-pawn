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
 * The support email about each of [charges] and about none, in the app's strings: subject "Charge question" and, about
 * a charge, the body "Order ID: …", "Date: …", "Price: …", "Status: …" ([supportEmailFacts]); about none, an empty
 * body. Resolved in composition, so opening the email never waits on a resource read.
 */
@Composable
fun supportEmailWriter(charges: List<ChargeOption>): SupportEmailWriter {
    val subject = stringResource(Res.string.problem_email_subject)
    val bodies = charges.associate { charge -> charge.key to supportEmailBody(supportEmailFacts(charge).map { line(it) }) }
    return SupportEmailWriter { charge -> SupportEmail(subject = subject, body = charge?.let { bodies[it.key] }.orEmpty()) }
}

@Composable
private fun line(fact: SupportEmailFact): String =
    when (fact) {
        is SupportEmailFact.OrderId -> stringResource(Res.string.problem_email_order_id, fact.orderId)
        is SupportEmailFact.Date -> stringResource(Res.string.problem_email_date, fact.iso)
        is SupportEmailFact.Price -> stringResource(Res.string.problem_email_price, fact.price)
        is SupportEmailFact.Status -> stringResource(Res.string.problem_email_status, statusText(fact.status))
    }

@Composable
private fun statusText(status: ChargeStatus): String =
    when (status) {
        is ChargeStatus.Snooze -> stringResource(Res.string.purchase_snooze_number, status.number)
        ChargeStatus.NotUsed -> stringResource(Res.string.purchase_stranded)
    }
