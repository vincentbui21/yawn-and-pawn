package com.yawnandpawn.app.playcatalog

/** Ready-made catalogues for the planner and runner tests. */
object TestCatalogs {
    val conversions: Map<Int, ConvertedPrices> = (1..50).associateWith { FakePlayCatalogApi.convert(it.toLong()) }

    fun applied(tier: Int): OneTimeProduct = FakePlayCatalogApi.applied(tier)

    fun withBuyOption(
        product: OneTimeProduct,
        change: (PurchaseOption) -> PurchaseOption,
    ): OneTimeProduct = product.copy(purchaseOptions = product.purchaseOptions.map(change))

    /** Product [tier] with a US price of [units] USD instead of tier.00. */
    fun wrongPrice(
        tier: Int,
        units: Long,
    ): OneTimeProduct =
        withBuyOption(applied(tier)) { option ->
            option.copy(regions = option.regions + ("US" to RegionalConfig(Price.usd(units), available = true)))
        }

    fun inState(
        tier: Int,
        state: OptionState,
    ): OneTimeProduct = withBuyOption(applied(tier)) { it.copy(state = state) }

    /**
     * The AC's partially existing catalogue: 10 products (01–10), 02 and 03 with a wrong price, 04 inactive, plus the
     * unmanaged Spike S1 product.
     */
    fun partial(): List<OneTimeProduct> =
        (1..10).map { tier ->
            when (tier) {
                2 -> wrongPrice(2, 3)
                3 -> wrongPrice(3, 1)
                4 -> inState(4, OptionState.INACTIVE)
                else -> applied(tier)
            }
        } + FakePlayCatalogApi.spikeProduct
}
