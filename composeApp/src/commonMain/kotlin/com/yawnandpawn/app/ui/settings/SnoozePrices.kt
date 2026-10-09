package com.yawnandpawn.app.ui.settings

import com.yawnandpawn.app.core.billing.FeeRules
import com.yawnandpawn.app.core.billing.Money
import com.yawnandpawn.app.core.billing.MoneyFormatter
import com.yawnandpawn.app.core.billing.PriceCatalogSnapshot
import com.yawnandpawn.app.core.billing.SnoozeProducts
import com.yawnandpawn.app.core.config.PendingChange
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

/**
 * The prices the snooze settings show (Story 4.5): [baseFee] is snooze 1 (the base fee B), [ladder] snoozes 1 to
 * min(3, max snoozes) at B x N. [approximate] when they are USD approximations ("Approximate. Your local price shows
 * when you're online.").
 */
data class FeePrices(
    val baseFee: String,
    val ladder: List<String>,
    val approximate: Boolean,
)

/** Maps the base fee tier to the prices shown before a purchase (AD-8): Play's own strings, else USD approximations. */
object SnoozePrices {
    /** The fee ladder preview shows at most 3 snoozes ("Snooze 1: {price1} · 2: {price2} · 3: {price3}"). */
    const val LADDER_ENTRIES: Int = 3

    /**
     * The prices of [baseFeeTier] for a ladder of min(3, [maxSnoozes]) snoozes: Play's cached `formattedPrice` of each
     * tier B x N when every one of them may be shown at [now] (not expired), otherwise all of them as [formatter]'s USD
     * amounts, so one line never mixes Play's and approximate prices (default taken, owner can change). The tiers stay
     * within the catalogue: B ≤ 10 and N ≤ 3.
     */
    fun of(
        baseFeeTier: Int,
        maxSnoozes: Int,
        prices: PriceCatalogSnapshot,
        now: Instant,
        formatter: MoneyFormatter,
    ): FeePrices {
        val base = baseFeeTier.coerceIn(FeeRules.BASE_FEE_TIERS)
        val tiers = (1..maxSnoozes.coerceIn(1, LADDER_ENTRIES)).map { base * it }
        val play = tiers.mapNotNull { prices.displayablePriceFor(SnoozeProducts.idOf(it), now)?.formattedPrice }
        if (play.size == tiers.size) return FeePrices(play.first(), play, approximate = false)
        val usd = tiers.map { formatter.format(Money.of(it, USD)) }
        return FeePrices(usd.first(), usd, approximate = true)
    }

    /** The catalogue's own currency: product NN costs NN.00 USD. */
    const val USD: String = "USD"
}

/**
 * The "Saved. Takes effect after tomorrow's {time} alarm." note of [pending] (Stories 4.5 and 4.6): the local time of
 * the occurrence it waits for, and whether that is today in [zone]. Null with no pending change, or once that occurrence
 * is no longer in the future (the value is effective; it is promoted later).
 */
fun weakeningNoteOf(
    pending: PendingChange?,
    now: Instant,
    zone: TimeZone,
): WeakeningNote? {
    val at = pending?.effectiveAfter?.scheduledAt?.takeIf { it > now } ?: return null
    val local = at.toLocalDateTime(zone)
    return WeakeningNote(time = local.time, today = local.date == now.toLocalDateTime(zone).date)
}
