package com.yawnandpawn.app.android

import com.yawnandpawn.app.core.time.BootCounter
import com.yawnandpawn.app.core.time.Clock
import com.yawnandpawn.app.core.time.MonotonicClock
import com.yawnandpawn.app.core.time.TimeZoneProvider
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module

/** Koin bindings of the time ports (AD-3). Included by `appModule`; lives here because only this package may use `Clock.System`. */
val androidTimeModule =
    module {
        single<Clock> { kotlin.time.Clock.System }
        single<MonotonicClock> { AndroidMonotonicClock() }
        single<BootCounter> { AndroidBootCounter(androidContext().contentResolver) }
        single<TimeZoneProvider> { AndroidTimeZoneProvider() }
    }
