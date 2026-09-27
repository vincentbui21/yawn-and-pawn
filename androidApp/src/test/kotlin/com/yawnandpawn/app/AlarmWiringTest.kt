package com.yawnandpawn.app

import com.yawnandpawn.app.core.alarm.AlarmRepository
import com.yawnandpawn.app.core.alarm.AlarmWriteLock
import com.yawnandpawn.app.core.alarm.DeleteAlarm
import com.yawnandpawn.app.core.alarm.DuplicateAlarm
import com.yawnandpawn.app.core.alarm.SaveAlarm
import com.yawnandpawn.app.core.alarm.SetAlarmEnabled
import com.yawnandpawn.app.core.id.IdGenerator
import com.yawnandpawn.app.core.id.UuidV4IdGenerator
import com.yawnandpawn.app.data.alarm.RoomAlarmRepository
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext
import org.koin.core.context.stopKoin
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertSame

@RunWith(RobolectricTestRunner::class)
class AlarmWiringTest {
    @After
    fun tearDown() {
        stopKoin()
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
}
