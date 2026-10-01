package com.yawnandpawn.app

import android.app.Application
import com.yawnandpawn.app.android.AndroidLogger
import com.yawnandpawn.app.android.androidTimeModule
import com.yawnandpawn.app.core.alarm.AlarmWriteLock
import com.yawnandpawn.app.core.alarm.DeleteAlarm
import com.yawnandpawn.app.core.alarm.DuplicateAlarm
import com.yawnandpawn.app.core.alarm.SaveAlarm
import com.yawnandpawn.app.core.alarm.SetAlarmEnabled
import com.yawnandpawn.app.core.id.IdGenerator
import com.yawnandpawn.app.core.id.UuidV4IdGenerator
import com.yawnandpawn.app.core.log.Logger
import com.yawnandpawn.app.data.dataModule
import com.yawnandpawn.app.ui.uiModule
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin
import org.koin.dsl.module

/** Koin bindings of :androidApp (platform adapters, core wiring). Later stories add their bindings here. */
val appModule =
    module {
        includes(androidTimeModule)
        single<IdGenerator> { UuidV4IdGenerator() }
        single<Logger> { AndroidLogger() }
        // Alarm use cases (Story 1.7); the repository comes from dataModule, Clock from androidTimeModule.
        // One lock shared by every alarm use case: it serializes their read-modify-write.
        single { AlarmWriteLock() }
        factory { SaveAlarm(get(), get(), get(), get()) }
        factory { SetAlarmEnabled(get(), get(), get()) }
        factory { DeleteAlarm(get(), get()) }
        factory { DuplicateAlarm(get(), get(), get(), get()) }
    }

class YawnAndPawnApp : Application() {
    override fun onCreate() {
        super.onCreate()
        startKoin {
            androidContext(this@YawnAndPawnApp)
            modules(appModule, dataModule, uiModule)
        }
    }
}
