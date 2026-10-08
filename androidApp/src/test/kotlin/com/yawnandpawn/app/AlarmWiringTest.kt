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
import com.yawnandpawn.app.core.alarm.RearmOnFire
import com.yawnandpawn.app.core.alarm.RequestCodeSequence
import com.yawnandpawn.app.core.alarm.SaveAlarm
import com.yawnandpawn.app.core.alarm.SetAlarmEnabled
import com.yawnandpawn.app.core.config.CommitmentEventRepository
import com.yawnandpawn.app.core.config.GlobalSettingsRepository
import com.yawnandpawn.app.core.config.PendingChangeRepository
import com.yawnandpawn.app.core.config.PromotePendingChanges
import com.yawnandpawn.app.core.config.RecordCommitmentEvent
import com.yawnandpawn.app.core.config.SetBaseFee
import com.yawnandpawn.app.core.config.SetMaxSnoozes
import com.yawnandpawn.app.core.id.IdGenerator
import com.yawnandpawn.app.core.id.UuidV4IdGenerator
import com.yawnandpawn.app.data.alarm.RoomAlarmRepository
import com.yawnandpawn.app.data.alarm.RoomRequestCodeSequence
import com.yawnandpawn.app.data.config.CompositePendingChangeRepository
import com.yawnandpawn.app.data.config.DataStoreGlobalSettings
import com.yawnandpawn.app.data.config.RoomCommitmentEventRepository
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
        assertSame(koin.get<AlarmWriteLock>(), koin.get<AlarmWriteLock>())
    }

    @Test
    fun `Koin binds the commitment lock - settings, pending changes over both stores, events and their use cases`() {
        val koin = GlobalContext.get()

        assertIs<DataStoreGlobalSettings>(koin.get<GlobalSettingsRepository>())
        assertIs<CompositePendingChangeRepository>(koin.get<PendingChangeRepository>())
        assertIs<RoomCommitmentEventRepository>(koin.get<CommitmentEventRepository>())
        assertNotNull(koin.get<SetBaseFee>())
        assertNotNull(koin.get<SetMaxSnoozes>())
        assertNotNull(koin.get<RecordCommitmentEvent>())
        assertSame(koin.get<PromotePendingChanges>(), koin.get<PromotePendingChanges>())
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
