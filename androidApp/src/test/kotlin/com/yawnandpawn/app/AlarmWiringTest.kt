package com.yawnandpawn.app

import com.yawnandpawn.app.android.AndroidAlarmScheduler
import com.yawnandpawn.app.android.ApplicationScope
import com.yawnandpawn.app.android.wake.WakeAlarmFiredHandler
import com.yawnandpawn.app.core.alarm.AlarmFiredHandler
import com.yawnandpawn.app.core.alarm.AlarmRepository
import com.yawnandpawn.app.core.alarm.AlarmScheduler
import com.yawnandpawn.app.core.alarm.AlarmScheduling
import com.yawnandpawn.app.core.alarm.AlarmWriteLock
import com.yawnandpawn.app.core.alarm.DeleteAlarm
import com.yawnandpawn.app.core.alarm.DuplicateAlarm
import com.yawnandpawn.app.core.alarm.RearmOnFire
import com.yawnandpawn.app.core.alarm.RequestCodeSequence
import com.yawnandpawn.app.core.alarm.SaveAlarm
import com.yawnandpawn.app.core.alarm.SetAlarmEnabled
import com.yawnandpawn.app.core.id.IdGenerator
import com.yawnandpawn.app.core.id.UuidV4IdGenerator
import com.yawnandpawn.app.data.alarm.RoomAlarmRepository
import com.yawnandpawn.app.data.alarm.RoomRequestCodeSequence
import com.yawnandpawn.app.stopApp
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertSame

@RunWith(RobolectricTestRunner::class)
class AlarmWiringTest {
    @After
    fun tearDown() {
        stopApp()
    }

    @Test
    fun `Koin resolves the alarm repository, the id generator every alarm use case and one shared write lock`() {
        val koin = GlobalContext.get()

        assertIs<RoomAlarmRepository>(koin.get<AlarmRepository>())
        assertIs<UuidV4IdGenerator>(koin.get<IdGenerator>())
        assertNotNull(koin.get<SaveAlarm>())
        assertNotNull(koin.get<SetAlarmEnabled>())
        assertNotNull(koin.get<DeleteAlarm>())
        assertNotNull(koin.get<DuplicateAlarm>())
        assertSame(koin.get<AlarmWriteLock>(), koin.get<AlarmWriteLock>())
    }

    @Test
    fun `Koin binds the scheduler, the sequence, the sync helper, the fire handler and one application scope`() {
        val koin = GlobalContext.get()

        assertIs<AndroidAlarmScheduler>(koin.get<AlarmScheduler>())
        assertIs<RoomRequestCodeSequence>(koin.get<RequestCodeSequence>())
        assertIs<WakeAlarmFiredHandler>(koin.get<AlarmFiredHandler>(), "a fire re-arms, then rings through the wake service")
        assertSame(koin.get<RearmOnFire>(), koin.get<RearmOnFire>())
        assertSame(koin.get<AlarmScheduling>(), koin.get<AlarmScheduling>())
        assertSame(koin.get<ApplicationScope>(), koin.get<ApplicationScope>())
    }
}
