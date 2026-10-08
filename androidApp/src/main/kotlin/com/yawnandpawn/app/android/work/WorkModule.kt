package com.yawnandpawn.app.android.work

import com.yawnandpawn.app.android.ApplicationScope
import com.yawnandpawn.app.core.billing.CachedPriceCatalog
import com.yawnandpawn.app.core.billing.ConsumeRetryTask
import com.yawnandpawn.app.core.billing.PriceCatalog
import com.yawnandpawn.app.core.billing.PriceRefreshScheduler
import com.yawnandpawn.app.core.billing.PriceRefreshTask
import com.yawnandpawn.app.core.billing.ProductDetailsSource
import com.yawnandpawn.app.core.billing.SessionStartPriceRefresh
import com.yawnandpawn.app.core.work.BackgroundTaskKind
import com.yawnandpawn.app.core.work.BackgroundWork
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * Koin bindings of deferred work and the price cache (Story 4.3, AD-17), included by `appModule`: WorkManager behind
 * [BackgroundWork], the task of each job, the cached Play prices over the price cache store (`dataModule`), the
 * scheduler of the refresh jobs and the refresh at session start. Play's product details are unavailable until the
 * Play Billing adapter (Story 4.12) replaces [UnavailableProductDetailsSource].
 *
 * A function, like `wakeModule()`: each call has its own definitions.
 */
fun workModule(): Module =
    module {
        single<BackgroundWork> { AndroidBackgroundWork(androidContext(), get(), get()) }
        // The price refresh (Story 4.3) and the consume retry of the grant ledger (Story 4.10, its one-time and 6-hour
        // periodic jobs).
        single {
            BackgroundTasks(
                mapOf(
                    BackgroundTaskKind.PriceRefresh to PriceRefreshTask(get()),
                    BackgroundTaskKind.ConsumeRetry to ConsumeRetryTask(get()),
                ),
            )
        }
        single<ProductDetailsSource> { UnavailableProductDetailsSource() }
        single<PriceCatalog> { CachedPriceCatalog(get(), get(), get(), get()) }
        single { PriceRefreshScheduler(get(), get(), get()) }
        single { SessionStartPriceRefresh(get(), get(), get<ApplicationScope>()) }
    }
