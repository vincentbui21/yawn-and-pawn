package com.yawnandpawn.app.playcatalog

// The parts of a Play one-time product the tool reads and writes, independent of the Google API client
// (`GooglePlayCatalogApi` maps them to and from `com.google.api.services.androidpublisher.model`).

/** An amount in a currency, as the Play API's `Money`: whole [units] plus [nanos] (10^-9 of a unit). */
data class Price(
    val currencyCode: String,
    val units: Long,
    val nanos: Int = 0,
) {
    override fun toString(): String {
        val cents = nanos / NANOS_PER_CENT
        val remainder = nanos % NANOS_PER_CENT
        val fraction =
            if (remainder == 0) cents.toString().padStart(2, '0') else nanos.toString().padStart(NANO_DIGITS, '0').trimEnd('0')
        return "$currencyCode $units.$fraction"
    }

    companion object {
        private const val NANOS_PER_CENT = 10_000_000
        private const val NANO_DIGITS = 9

        fun usd(units: Long): Price = Price("USD", units)
    }
}

/** A store listing of a product in one language. */
data class Listing(
    val languageCode: String,
    val title: String,
    val description: String,
)

/** Play's purchase option states. The state is output only; it changes only through the activate/deactivate calls. */
enum class OptionState {
    DRAFT,
    ACTIVE,
    INACTIVE,
    INACTIVE_PUBLISHED,
    UNKNOWN,
    ;

    companion object {
        fun parse(value: String?): OptionState = entries.firstOrNull { it.name == value } ?: UNKNOWN
    }
}

/**
 * A price and availability in one region of a purchase option. [rawAvailability] keeps Play's value when it is not
 * `AVAILABLE` (for example `NO_LONGER_AVAILABLE`), so a region the tool keeps is written back exactly as it was.
 */
data class RegionalConfig(
    val price: Price,
    val available: Boolean,
    val rawAvailability: String? = null,
)

/**
 * One purchase option of a product. [isBuy] is false for a rent option. [state] is null in a write
 * (output only). [regions] maps a region code (`US`, `FI`, …) to its price.
 *
 * [extras] holds the option's fields this tool does not model (for example `taxAndComplianceSettings` with the EEA
 * withdrawal right, or `offerTags`), exactly as Play returned them. A patch of `purchaseOptions` replaces the whole
 * option, so they are written back unchanged and an owner's Play Console setting is never reset.
 */
data class PurchaseOption(
    val id: String,
    val state: OptionState?,
    val isBuy: Boolean,
    val legacyCompatible: Boolean,
    val multiQuantityEnabled: Boolean,
    val regions: Map<String, RegionalConfig>,
    val newRegionsUsd: Price?,
    val newRegionsEur: Price?,
    val newRegionsAvailable: Boolean,
    val extras: Map<String, Any?> = emptyMap(),
)

/** A one-time product with the fields the tool manages. */
data class OneTimeProduct(
    val productId: String,
    val listings: List<Listing>,
    val purchaseOptions: List<PurchaseOption>,
)

/** The answer of `monetization.convertRegionPrices` for one USD price. */
data class ConvertedPrices(
    val regionsVersion: String,
    val regions: Map<String, Price>,
    val otherRegionsUsd: Price?,
    val otherRegionsEur: Price?,
)
