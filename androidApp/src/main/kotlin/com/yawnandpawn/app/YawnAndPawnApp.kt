package com.yawnandpawn.app

import android.app.Application
import com.yawnandpawn.app.android.androidTimeModule
import com.yawnandpawn.app.data.dataModule
import com.yawnandpawn.app.ui.uiModule
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin
import org.koin.dsl.module

/** Koin bindings of :androidApp (platform adapters, core wiring). Later stories add their bindings here. */
val appModule =
    module {
        includes(androidTimeModule)
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
