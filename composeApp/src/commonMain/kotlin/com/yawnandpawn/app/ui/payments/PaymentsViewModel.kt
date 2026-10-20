package com.yawnandpawn.app.ui.payments

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yawnandpawn.app.core.billing.FeeRules
import com.yawnandpawn.app.core.billing.PriceCatalog
import com.yawnandpawn.app.core.billing.SnoozeProducts
import com.yawnandpawn.app.core.log.LogEvent
import com.yawnandpawn.app.core.log.Logger
import com.yawnandpawn.app.core.time.Clock
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * "How payments & refunds work" (Story 5.8, review fix 2): the cap the screen states. [cap] is Google Play's own
 * formatted price of the top product ([FeeRules.PRICE_CAP_TIER], `snooze_usd_50`) from the cached [priceCatalog], so
 * the sentence names what a snooze can really cost in the user's country, tax included where Play includes it. Null
 * while that price is not cached or has expired ([com.yawnandpawn.app.core.billing.PriceCachePolicy]); the screen then
 * names the $50 price tier instead. An unreadable cache is logged and counts as unknown. Nothing waits for a refresh.
 */
class PaymentsViewModel(
    priceCatalog: PriceCatalog,
    private val clock: Clock,
    private val logger: Logger,
) : ViewModel() {
    val cap: StateFlow<String?> =
        priceCatalog
            .observe()
            .map { prices -> prices.displayablePriceFor(CAP_PRODUCT, clock.now())?.formattedPrice }
            .catch { cause ->
                logger.log(LogEvent.OperationFailed(LOAD_CAP_PRICE, cause.message ?: cause::class.simpleName.orEmpty()))
                emit(null)
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), null)

    companion object {
        /** The product priced at the cap, `snooze_usd_50`. */
        val CAP_PRODUCT: String = SnoozeProducts.idOf(FeeRules.PRICE_CAP_TIER)

        /** The operation logged when the cached prices cannot be read. */
        const val LOAD_CAP_PRICE = "load cap price"

        private const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}
