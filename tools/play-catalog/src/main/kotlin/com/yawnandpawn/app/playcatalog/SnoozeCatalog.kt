package com.yawnandpawn.app.playcatalog

/**
 * The desired catalogue (PRD §6.3, AD-7): 50 consumable one-time products `snooze_usd_01` … `snooze_usd_50`, product
 * NN at NN.00 USD. The same ids are in `config/snooze-products.txt`, which Story 4.2's FeeLadder test also reads.
 */
object SnoozeCatalog {
    /** The app's package name (`docs/decisions/package-id.md`, the `applicationId` in `androidApp/build.gradle.kts`). */
    const val PACKAGE_NAME = "com.yawnandpawn.app"

    const val PRODUCT_COUNT = 50

    /** The one Buy purchase option of every product; legacy-compatible, so Play Billing Library 9 shows it as the offer. */
    const val PURCHASE_OPTION_ID = "buy"

    /** The app's default store-listing language (default taken in Story 4.1; owner can change). */
    const val LISTING_LANGUAGE = "en-US"

    /** EXPERIENCE.md Key strings, "Play product listing". */
    const val LISTING_TITLE = "Snooze"
    const val LISTING_DESCRIPTION = "One snooze for your alarm."

    /** The region whose price is the base USD price. */
    const val BASE_REGION = "US"

    private val managedId = Regex("""snooze_usd_(\d{2})""")

    /** `snooze_usd_01` … `snooze_usd_50`, in order. */
    val productIds: List<String> = (1..PRODUCT_COUNT).map(::productId)

    fun productId(tier: Int): String {
        require(tier in 1..PRODUCT_COUNT) { "tier $tier is outside 1..$PRODUCT_COUNT" }
        return "snooze_usd_" + tier.toString().padStart(2, '0')
    }

    /** NN for a managed id, or null for any other id (which the tool reports as unmanaged and never touches). */
    fun tierOf(productId: String): Int? =
        managedId
            .matchEntire(productId)
            ?.groupValues
            ?.get(1)
            ?.toInt()
            ?.takeIf { it in 1..PRODUCT_COUNT }

    /** The base price of [tier]: tier.00 USD. */
    fun basePrice(tier: Int): Price = Price.usd(tier.toLong())

    val listing: Listing = Listing(LISTING_LANGUAGE, LISTING_TITLE, LISTING_DESCRIPTION)

    /**
     * The product as it should be in Play, with every region Play offers priced from [converted] (the conversion of
     * [basePrice]) and the US pinned to the base price. [existing] keeps listings in other languages.
     */
    fun desiredProduct(
        tier: Int,
        converted: ConvertedPrices,
        existing: OneTimeProduct? = null,
    ): OneTimeProduct {
        val base = basePrice(tier)
        val regions =
            converted.regions.mapValues { (_, price) -> RegionalConfig(price, available = true) } +
                (BASE_REGION to RegionalConfig(base, available = true))
        val otherListings = existing?.listings.orEmpty().filter { it.languageCode != LISTING_LANGUAGE }
        return OneTimeProduct(
            productId = productId(tier),
            listings = listOf(listing) + otherListings,
            purchaseOptions =
                listOf(
                    PurchaseOption(
                        id = PURCHASE_OPTION_ID,
                        state = null,
                        isBuy = true,
                        legacyCompatible = true,
                        multiQuantityEnabled = false,
                        regions = regions.toSortedMap(),
                        newRegionsUsd = base,
                        newRegionsEur = converted.otherRegionsEur,
                        newRegionsAvailable = true,
                    ),
                ),
        )
    }
}
