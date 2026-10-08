package com.yawnandpawn.app.playcatalog

/**
 * An in-memory Play: products by id, a fixed set of regions, every call recorded. Writes behave like Play: a patch
 * creates only with `allowMissing`, writes only the masked fields, and a new purchase option starts as DRAFT.
 */
class FakePlayCatalogApi(
    products: List<OneTimeProduct> = emptyList(),
    val regionsVersion: String = "2025/03",
) : PlayCatalogApi {
    val products: MutableMap<String, OneTimeProduct> = products.associateBy { it.productId }.toMutableMap()

    /** Every call, in order: `list`, `convert USD n.00`, `patch id [fields]`, `activate id/option`. */
    val calls = mutableListOf<String>()

    val writes: List<String> get() = calls.filter { it.startsWith("patch") || it.startsWith("activate") }

    /** Fails the call whose description starts with this prefix, with HTTP [failStatus]. */
    var failOn: String? = null
    var failStatus: Int = 500

    override fun listOneTimeProducts(packageName: String): List<OneTimeProduct> {
        record("list")
        return products.values.sortedBy { it.productId }
    }

    override fun convertRegionPrices(
        packageName: String,
        price: Price,
    ): ConvertedPrices {
        record("convert $price")
        return convert(price.units, regionsVersion)
    }

    override fun patchOneTimeProduct(
        packageName: String,
        product: OneTimeProduct,
        fields: Set<PatchField>,
        regionsVersion: String,
        allowMissing: Boolean,
    ): OneTimeProduct {
        record("patch ${product.productId} ${fields.map { it.path }.sorted()}${if (allowMissing) " allowMissing" else ""}")
        check(regionsVersion == this.regionsVersion) { "regions version $regionsVersion" }
        val current = products[product.productId]
        if (current == null && !allowMissing) throw PlayApiException("patch", "HTTP 404: product not found", 404)
        val base = current ?: OneTimeProduct(product.productId, emptyList(), emptyList())
        val states = base.purchaseOptions.associate { it.id to it.state }
        val stored =
            base.copy(
                listings = if (PatchField.LISTINGS in fields) product.listings else base.listings,
                purchaseOptions =
                    if (PatchField.PURCHASE_OPTIONS in fields) {
                        product.purchaseOptions.map { it.copy(state = states[it.id] ?: OptionState.DRAFT) }
                    } else {
                        base.purchaseOptions
                    },
            )
        products[product.productId] = stored
        return stored
    }

    override fun activatePurchaseOption(
        packageName: String,
        productId: String,
        purchaseOptionId: String,
    ): OneTimeProduct {
        record("activate $productId/$purchaseOptionId")
        val current = products.getValue(productId)
        val stored =
            current.copy(
                purchaseOptions =
                    current.purchaseOptions.map {
                        if (it.id == purchaseOptionId) it.copy(state = OptionState.ACTIVE) else it
                    },
            )
        products[productId] = stored
        return stored
    }

    private fun record(call: String) {
        calls += call
        val fail = failOn
        if (fail != null && call.startsWith(fail)) throw PlayApiException(call, "HTTP $failStatus: injected failure", failStatus)
    }

    companion object {
        /** Three regions: the US in USD, Finland in EUR (0.9 × USD) and Japan in JPY (150 × USD). */
        fun convert(
            usd: Long,
            regionsVersion: String = "2025/03",
        ): ConvertedPrices =
            ConvertedPrices(
                regionsVersion = regionsVersion,
                regions =
                    mapOf(
                        "US" to Price.usd(usd),
                        "FI" to Price("EUR", usd * 9 / 10, (usd * 9 % 10).toInt() * 100_000_000),
                        "JP" to Price("JPY", usd * 150),
                    ),
                otherRegionsUsd = Price.usd(usd),
                otherRegionsEur = Price("EUR", usd),
            )

        /** Product [tier] exactly as the tool would store it, active. */
        fun applied(tier: Int): OneTimeProduct {
            val desired = SnoozeCatalog.desiredProduct(tier, convert(tier.toLong()))
            return desired.copy(purchaseOptions = desired.purchaseOptions.map { it.copy(state = OptionState.ACTIVE) })
        }

        /** The Spike S1 product, created by hand (Story 1.5) and deactivated. */
        val spikeProduct: OneTimeProduct =
            OneTimeProduct(
                productId = "spike_s1_test",
                listings = listOf(Listing("en-US", "Spike S1 test", "Test product for Spike S1. Not for sale.")),
                purchaseOptions =
                    listOf(
                        PurchaseOption(
                            id = "default",
                            state = OptionState.INACTIVE,
                            isBuy = true,
                            legacyCompatible = true,
                            multiQuantityEnabled = false,
                            regions = mapOf("US" to RegionalConfig(Price("USD", 0, 490_000_000), available = true)),
                            newRegionsUsd = null,
                            newRegionsEur = null,
                            newRegionsAvailable = false,
                        ),
                    ),
            )
    }
}
