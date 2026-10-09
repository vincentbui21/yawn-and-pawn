package com.yawnandpawn.app.android.work

import com.yawnandpawn.app.core.billing.ProductDetailsResult
import com.yawnandpawn.app.core.billing.ProductDetailsSource
import com.yawnandpawn.app.core.error.DomainError
import com.yawnandpawn.app.core.error.Outcome

/**
 * The [ProductDetailsSource] until the Play Billing adapter arrives (Story 4.12): every fetch fails, not transiently,
 * so the price cache stays as it is and the refresh jobs do not retry. The wake screen then says prices are not loaded.
 */
class UnavailableProductDetailsSource : ProductDetailsSource {
    override suspend fun fetch(productIds: List<String>): Outcome<ProductDetailsResult, DomainError> =
        Outcome.Failure(DomainError.ProductDetailsFailed(transient = false, cause = "Play Billing not available until Story 4.12"))
}
