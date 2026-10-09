package com.yawnandpawn.app.android.wake

import com.yawnandpawn.app.core.billing.DisplayPrices
import com.yawnandpawn.app.core.billing.Money
import com.yawnandpawn.app.core.billing.PriceCatalog
import com.yawnandpawn.app.core.billing.PriceCatalogSnapshot
import com.yawnandpawn.app.core.billing.PurchaseCoordinator
import com.yawnandpawn.app.core.time.Clock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

/**
 * "Use it" and "Not now" on the already-paid sheet (Story 4.13). Story 4.11's [PurchaseCoordinator] holds the stranded
 * token and dispatches `ReuseAccepted` / `ReuseDeclined`; the UI never sees the token.
 */
interface ReuseChoices {
    suspend fun acceptReuse()

    suspend fun declineReuse()

    companion object {
        /** The coordinator's choices; [coordinator] is looked up at the tap (it depends on the engine, which depends on the sheet). */
        fun of(coordinator: () -> PurchaseCoordinator): ReuseChoices =
            object : ReuseChoices {
                override suspend fun acceptReuse() {
                    coordinator().acceptReuse()
                }

                override suspend fun declineReuse() {
                    coordinator().declineReuse()
                }
            }
    }
}

/**
 * The cached display price for the snooze button and the confirm sheet (Story 4.13 over Story 4.3's [PriceCatalog]):
 * the latest snapshot's entry while it may be shown at [clock]'s now (an expired one reads as not loaded). Display only:
 * Pay always asks Play for the live price.
 */
class CatalogDisplayPrices(
    catalog: PriceCatalog,
    private val clock: Clock,
    scope: CoroutineScope,
) : DisplayPrices {
    /** The cache as it is now, followed for the life of the process. */
    val snapshot: StateFlow<PriceCatalogSnapshot> = catalog.observe().stateIn(scope, SharingStarted.Eagerly, PriceCatalogSnapshot.EMPTY)

    override fun priceOf(productId: String): Money? = snapshot.value.displayablePriceFor(productId, clock.now())?.price
}
