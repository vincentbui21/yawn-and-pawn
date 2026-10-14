package com.yawnandpawn.app.ui.payments

import com.yawnandpawn.app.core.billing.Money
import com.yawnandpawn.app.core.billing.PurchaseRecord
import com.yawnandpawn.app.core.billing.RecordStatus
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.UtcOffset
import kotlinx.datetime.offsetAt
import kotlinx.datetime.toLocalDateTime

/** What a charge was for, as Purchase history words it. */
sealed interface ChargeStatus {
    /** A paid snooze: "Snooze {n}". */
    data class Snooze(
        val number: Int,
    ) : ChargeStatus

    /** Paid but not used: "Not used, refunded automatically by Google". */
    data object NotUsed : ChargeStatus
}

/**
 * One charge the support email can be about (Story 4.17): only what the user can see in the app or in Google Play's
 * order history. It holds no purchase token, install id, session id, alarm or label, so none can reach the email.
 *
 * @property key tells the charges apart in the picker (the record's token hash); never shown and never in the email.
 * @property orderId Play's order id ("GPA.…"), as on the user's Google Play receipt; null when Play gave none.
 * @property purchasedAt when it was bought, in the phone's zone, with that zone's [offset] at the time.
 * @property price the amount charged; null when it is not known (only an estimate, Story 4.10), so it is never shown.
 * @property status what it was for; null for a paid snooze whose number is not known.
 */
data class ChargeOption(
    val key: String,
    val orderId: String?,
    val purchasedAt: LocalDateTime,
    val offset: UtcOffset,
    val price: Money?,
    val status: ChargeStatus?,
)

/** The charge of [record] in [zone]: the amount only when it is what was charged, the status as Purchase history shows it. */
fun chargeOptionOf(
    record: PurchaseRecord,
    zone: TimeZone,
): ChargeOption =
    ChargeOption(
        key = record.tokenHash,
        orderId = record.orderId?.trim()?.takeIf { it.isNotEmpty() },
        purchasedAt = record.purchasedAt.toLocalDateTime(zone),
        offset = zone.offsetAt(record.purchasedAt),
        price = record.price.takeIf { record.priceSource.isAmountPaid },
        status =
            if (record.status == RecordStatus.Stranded) {
                ChargeStatus.NotUsed
            } else {
                record.snoozeNumber?.let { ChargeStatus.Snooze(it) }
            },
    )

/** [ChargeOption.purchasedAt] as ISO 8601 with its offset, to the second ("2026-10-09T07:32:05+03:00"). */
fun ChargeOption.isoPurchaseTime(): String {
    val time = purchasedAt.time
    val offsetText = if (offset.totalSeconds == 0) "+00:00" else offset.toString()
    return "${purchasedAt.date}T${two(time.hour)}:${two(time.minute)}:${two(time.second)}$offsetText"
}

private fun two(value: Int): String = value.toString().padStart(2, '0')

/**
 * [address] with a line-break opportunity (a zero-width space) after each "@" and ".", so a long address wraps only
 * there, never mid-word. For display only: copying uses the plain address.
 */
fun breakableAddress(address: String): String = address.replace("@", "@​").replace(".", ".​")

/** A support email ready for the email app: subject and body (the body is empty when it is about no charge). */
data class SupportEmail(
    val subject: String,
    val body: String,
)

/** Writes the support email about a charge, or about none (EXPERIENCE.md Long-form copy). */
fun interface SupportEmailWriter {
    fun write(charge: ChargeOption?): SupportEmail
}

/**
 * The support email's text in the app's language (EXPERIENCE.md Key strings and Long-form copy), resolved once where
 * the screen is shown; [write] fills it from the [ChargeOption] it is given, so the body never depends on which charges
 * the screen has loaded. Each line template holds [SLOT] where its value goes.
 *
 * @property subject "Charge question".
 * @property orderIdLine "Order ID: {orderId}".
 * @property dateLine "Date: {purchaseTime}".
 * @property priceLine "Price: {price}".
 * @property statusLine "Status: {status}".
 * @property snoozeStatus "Snooze {n}".
 * @property notUsedStatus "Not used, refunded automatically by Google".
 */
data class SupportEmailTemplates(
    val subject: String,
    val orderIdLine: String,
    val dateLine: String,
    val priceLine: String,
    val statusLine: String,
    val snoozeStatus: String,
    val notUsedStatus: String,
) : SupportEmailWriter {
    override fun write(charge: ChargeOption?): SupportEmail =
        SupportEmail(subject = subject, body = charge?.let { supportEmailBody(supportEmailFacts(it).map(::line)) }.orEmpty())

    private fun line(fact: SupportEmailFact): String =
        when (fact) {
            is SupportEmailFact.OrderId -> orderIdLine.fill(fact.orderId)
            is SupportEmailFact.Date -> dateLine.fill(fact.iso)
            is SupportEmailFact.Price -> priceLine.fill(fact.price)
            is SupportEmailFact.Status -> statusLine.fill(statusText(fact.status))
        }

    private fun statusText(status: ChargeStatus): String =
        when (status) {
            is ChargeStatus.Snooze -> snoozeStatus.fill(status.number.toString())
            ChargeStatus.NotUsed -> notUsedStatus
        }

    private fun String.fill(value: String): String = replace(SLOT, value)

    companion object {
        /** Where a template's value goes: a private-use character that no string or value contains. */
        const val SLOT: String = ""
    }
}

/** A snackbar on "Problem with a charge?" or its picker. */
enum class ChargeSnackbar {
    /** "No browser found.": nothing could open Google Play's order history. */
    NoBrowser,

    /** "Address copied.": the support address is on the clipboard (only where the phone shows no confirmation). */
    AddressCopied,
}

/**
 * What "Problem with a charge?" renders (Story 4.17).
 *
 * @property charges the 10 most recent charges, newest first.
 * @property loaded the charges have been read once (or the read failed); before that "Email support" does nothing, so
 * it never writes without the order details it is about to have.
 * @property picking the "Email support" picker is open over the screen.
 * @property selectedKey the charge picked in the picker; the newest when it opens.
 * @property snackbar the snackbar showing, cleared after 4 s.
 * @property noEmailApp no email app could open the email: the note with [supportEmail] and "Copy address" shows until
 * the user leaves the screen or pane, or an email opens.
 * @property supportEmail the address the note names and copies.
 */
data class ProblemWithChargeUiState(
    val charges: List<ChargeOption> = emptyList(),
    val loaded: Boolean = false,
    val picking: Boolean = false,
    val selectedKey: String? = null,
    val snackbar: ChargeSnackbar? = null,
    val noEmailApp: Boolean = false,
    val supportEmail: String = "",
) {
    /** The picked charge, or the newest when the picked one is gone; null with no charges. */
    val selected: ChargeOption? get() = charges.firstOrNull { it.key == selectedKey } ?: charges.firstOrNull()
}

/** How many charges the picker offers: the most recent ones (Story 4.17). */
const val SUPPORT_PICKER_CHARGES = 10
