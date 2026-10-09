package com.yawnandpawn.app.android.billing

import android.app.KeyguardManager
import com.yawnandpawn.app.YawnAndPawnApp
import com.yawnandpawn.app.android.ApplicationScope
import com.yawnandpawn.app.core.billing.ProductDetailsSource
import com.yawnandpawn.app.core.session.Billing
import com.yawnandpawn.app.core.session.UnlockPort
import kotlinx.coroutines.Dispatchers
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * Koin bindings of Play Billing (Story 4.12), included by `appModule`, replacing the Epic 1–4.11 stand-ins:
 * - [AndroidBilling] is the one [Billing] (over [variantBillingFacade]: Play, or in a debug build with the hidden
 *   toggle on, the fake);
 * - [AndroidProductDetailsSource] is the [ProductDetailsSource] of the price cache;
 * - [AndroidDeviceUnlocker] is the one [UnlockPort], shared by the engine and the coordinator;
 * - the live price, billing country and display prices for the confirm sheet (Story 4.13's ports).
 *
 * The billing work runs on its own app-wide scope (same rules as [ApplicationScope]): the reconnect backoff may run for a
 * minute, and it is not among the app start's finite jobs. A function, like `wakeModule()`.
 */
fun billingModule(): Module =
    module {
        single { (androidContext() as? YawnAndPawnApp)?.resumedWakeScreen ?: ResumedWakeScreen() }
        single<BillingClientFacade> { variantBillingFacade(androidContext()) }
        single {
            val keyguard = androidContext().getSystemService(KeyguardManager::class.java)
            AndroidBilling(
                facade = get(),
                userLock = get(),
                wakeScreens = get<ResumedWakeScreen>(),
                clock = get(),
                logger = get(),
                scope = ApplicationScope(get()),
                main = Dispatchers.Main,
                // Review 11: read again right before Play opens, so the sheet never waits behind a keyguard.
                keyguardLocked = { keyguard.isKeyguardLocked },
            )
        }
        single<Billing> { get<AndroidBilling>() }
        single<ProductDetailsSource> { AndroidProductDetailsSource(get()) }
        single<UnlockPort> {
            AndroidDeviceUnlocker(
                keyguard = androidContext().getSystemService(KeyguardManager::class.java),
                wakeScreens = get<ResumedWakeScreen>(),
                main = Dispatchers.Main,
                logger = get(),
            )
        }
        single { PlayLivePrices(get(), get(), get(), get()) }
        single { PlayBillingCountry(get()) }
        single { CachedDisplayPrices(get(), get(), get<ApplicationScope>()) }
    }
