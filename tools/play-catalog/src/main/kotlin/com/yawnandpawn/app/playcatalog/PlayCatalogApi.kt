package com.yawnandpawn.app.playcatalog

/**
 * The Play Developer API calls the tool makes. There is deliberately no delete: the tool can never remove a product,
 * a purchase option or an offer. Every call throws [PlayApiException] on failure; nothing is retried.
 */
interface PlayCatalogApi {
    /** Read: every one-time product of [packageName] (`monetization.onetimeproducts.list`, all pages). */
    fun listOneTimeProducts(packageName: String): List<OneTimeProduct>

    /** Read (a calculation, writes nothing): local prices for [price] in every region (`monetization.convertRegionPrices`). */
    fun convertRegionPrices(
        packageName: String,
        price: Price,
    ): ConvertedPrices

    /**
     * Write: creates ([allowMissing]) or updates [product], writing only [fields] (`monetization.onetimeproducts.patch`).
     * Returns the product as Play stored it.
     */
    fun patchOneTimeProduct(
        packageName: String,
        product: OneTimeProduct,
        fields: Set<PatchField>,
        regionsVersion: String,
        allowMissing: Boolean,
    ): OneTimeProduct

    /** Write: activates one purchase option (`monetization.onetimeproducts.purchaseOptions.batchUpdateStates`). */
    fun activatePurchaseOption(
        packageName: String,
        productId: String,
        purchaseOptionId: String,
    ): OneTimeProduct
}

/** A failed API call: [call] names it, [message] is the API's error message, [httpStatus] the status when known. */
class PlayApiException(
    val call: String,
    message: String,
    val httpStatus: Int? = null,
    cause: Throwable? = null,
) : Exception(message, cause) {
    /** 401/403: the service account is missing a Play Console permission. */
    val isPermissionProblem: Boolean get() = httpStatus == HTTP_UNAUTHORIZED || httpStatus == HTTP_FORBIDDEN

    private companion object {
        const val HTTP_UNAUTHORIZED = 401
        const val HTTP_FORBIDDEN = 403
    }
}
