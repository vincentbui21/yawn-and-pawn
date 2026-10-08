package com.yawnandpawn.app.playcatalog

/** What happens to one product. */
enum class PlanKind(
    val label: String,
) {
    CREATE("create"),
    UPDATE("update"),
    ACTIVATE("activate"),
    UNCHANGED("unchanged"),
    UNMANAGED("unmanaged"),
    ATTENTION("attention"),
}

/** The fields of a product a patch writes: Play's `updateMask` paths. */
enum class PatchField(
    val path: String,
) {
    LISTINGS("listings"),
    PURCHASE_OPTIONS("purchaseOptions"),
}

/**
 * The plan for one product id. [patch] holds the fields to write ([desired] carries their values; empty means no patch),
 * [activate] is true when the `buy` option must be activated afterwards, and [reasons] say why, for the output.
 */
data class ProductPlan(
    val productId: String,
    val kind: PlanKind,
    val reasons: List<String> = emptyList(),
    val patch: Set<PatchField> = emptySet(),
    val activate: Boolean = false,
    val desired: OneTimeProduct? = null,
    val regionsVersion: String? = null,
) {
    val isChange: Boolean get() = kind == PlanKind.CREATE || kind == PlanKind.UPDATE || kind == PlanKind.ACTIVATE
}

/** The whole plan: the 50 managed products in id order, then the unmanaged ones. */
data class CatalogPlan(
    val products: List<ProductPlan>,
) {
    val changes: List<ProductPlan> get() = products.filter { it.isChange }

    fun count(kind: PlanKind): Int = products.count { it.kind == kind }

    fun summary(): String = PlanKind.entries.joinToString(", ") { "${count(it)} ${it.label}" } + ". ${changes.size} changes."
}

/** Compares the products in Play with the desired catalogue. Pure; never calls the API. */
object CatalogPlanner {
    /**
     * [existing] is every one-time product of the app; [conversions] maps each managed tier (1..50) to the conversion of
     * its base price.
     */
    fun plan(
        existing: List<OneTimeProduct>,
        conversions: Map<Int, ConvertedPrices>,
    ): CatalogPlan {
        val byId = existing.associateBy { it.productId }
        val managed =
            (1..SnoozeCatalog.PRODUCT_COUNT).map { tier ->
                val converted = requireNotNull(conversions[tier]) { "no price conversion for tier $tier" }
                planProduct(tier, byId[SnoozeCatalog.productId(tier)], converted)
            }
        val unmanaged =
            existing
                .filter { SnoozeCatalog.tierOf(it.productId) == null }
                .sortedBy { it.productId }
                .map { ProductPlan(it.productId, PlanKind.UNMANAGED, listOf("not a snooze_usd_NN product; never changed")) }
        return CatalogPlan(managed + unmanaged)
    }

    private fun planProduct(
        tier: Int,
        current: OneTimeProduct?,
        converted: ConvertedPrices,
    ): ProductPlan {
        val id = SnoozeCatalog.productId(tier)
        val desired = SnoozeCatalog.desiredProduct(tier, converted, current)
        val others = current?.purchaseOptions.orEmpty().filter { it.id != SnoozeCatalog.PURCHASE_OPTION_ID }
        return when {
            current == null -> {
                ProductPlan(
                    productId = id,
                    kind = PlanKind.CREATE,
                    reasons = listOf("${SnoozeCatalog.basePrice(tier)}, ${desired.purchaseOptions.single().regions.size} regions"),
                    patch = PatchField.entries.toSet(),
                    activate = true,
                    desired = desired,
                    regionsVersion = converted.regionsVersion,
                )
            }

            others.isNotEmpty() -> {
                ProductPlan(
                    productId = id,
                    kind = PlanKind.ATTENTION,
                    reasons =
                        listOf(
                            "has purchase options this tool does not manage (${others.joinToString { it.id }}); fix it in Play Console",
                        ),
                )
            }

            else -> {
                compare(tier, current, desired, converted.regionsVersion)
            }
        }
    }

    private fun compare(
        tier: Int,
        current: OneTimeProduct,
        desired: OneTimeProduct,
        regionsVersion: String,
    ): ProductPlan {
        val id = current.productId
        val reasons = mutableListOf<String>()
        val patch = mutableSetOf<PatchField>()
        listingDifference(current)?.let {
            reasons += it
            patch += PatchField.LISTINGS
        }
        val buy = current.purchaseOptions.firstOrNull()
        val optionReasons = optionDifferences(tier, buy, desired.purchaseOptions.single())
        if (optionReasons.isNotEmpty()) {
            reasons += optionReasons
            patch += PatchField.PURCHASE_OPTIONS
        }
        val activate = buy?.state != OptionState.ACTIVE
        if (activate) reasons += activationReason(buy?.state)
        val kind =
            when {
                patch.isNotEmpty() -> PlanKind.UPDATE
                activate -> PlanKind.ACTIVATE
                else -> PlanKind.UNCHANGED
            }
        return ProductPlan(id, kind, reasons, patch, activate, desired.takeIf { patch.isNotEmpty() }, regionsVersion)
    }

    private fun listingDifference(current: OneTimeProduct): String? {
        val listing = current.listings.firstOrNull { it.languageCode == SnoozeCatalog.LISTING_LANGUAGE }
        return when {
            listing == null -> "listing ${SnoozeCatalog.LISTING_LANGUAGE} missing"
            listing != SnoozeCatalog.listing -> "listing ${SnoozeCatalog.LISTING_LANGUAGE} differs"
            else -> null
        }
    }

    private fun optionDifferences(
        tier: Int,
        current: PurchaseOption?,
        desired: PurchaseOption,
    ): List<String> {
        if (current == null) return listOf("purchase option ${SnoozeCatalog.PURCHASE_OPTION_ID} missing")
        val base = SnoozeCatalog.basePrice(tier)
        val reasons = mutableListOf<String>()
        if (!current.isBuy || !current.legacyCompatible || current.multiQuantityEnabled) {
            reasons += "purchase option is not a legacy-compatible single-quantity Buy option"
        }
        val us = current.regions[SnoozeCatalog.BASE_REGION]
        if (us?.price != base) reasons += "price ${us?.price ?: "missing"} -> $base"
        val missing = desired.regions.keys.filter { current.regions[it]?.available != true }
        if (missing.isNotEmpty()) reasons += "${missing.size} regions missing or unavailable (${missing.take(MAX_LISTED).joinToString()})"
        if (current.newRegionsUsd != base || !current.newRegionsAvailable) reasons += "new-regions price ${current.newRegionsUsd} -> $base"
        return reasons
    }

    private fun activationReason(state: OptionState?): String =
        when (state) {
            null, OptionState.DRAFT -> "not active yet"
            OptionState.INACTIVE, OptionState.INACTIVE_PUBLISHED -> "inactive; reactivate"
            else -> "state $state; activate"
        }

    private const val MAX_LISTED = 5
}
