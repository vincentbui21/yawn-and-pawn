package com.yawnandpawn.app.data

import android.content.Context
import com.yawnandpawn.app.core.alarm.AlarmRepository
import com.yawnandpawn.app.core.alarm.RequestCodeSequence
import com.yawnandpawn.app.data.alarm.RoomAlarmRepository
import com.yawnandpawn.app.data.alarm.RoomRequestCodeSequence
import com.yawnandpawn.app.data.db.AppDatabase
import com.yawnandpawn.app.data.db.buildAppDatabase
import org.koin.dsl.module

/** Koin bindings of :data (AD-13). Needs the Android `Context` (registered by `androidContext` in the app). */
val dataModule =
    module {
        single { buildAppDatabase(get<Context>()) }
        single { get<AppDatabase>().alarmDao() }
        single { get<AppDatabase>().requestCodeSequenceDao() }
        single<AlarmRepository> { RoomAlarmRepository(get()) }
        single<RequestCodeSequence> { RoomRequestCodeSequence(get()) }
    }
