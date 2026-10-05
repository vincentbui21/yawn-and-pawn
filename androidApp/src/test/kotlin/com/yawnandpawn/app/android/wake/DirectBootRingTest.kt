package com.yawnandpawn.app.android.wake

import android.app.AlarmManager
import android.app.Application
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.Looper
import android.os.UserManager
import androidx.test.core.app.ApplicationProvider
import com.yawnandpawn.app.StopAppRule
import com.yawnandpawn.app.android.AndroidLogger
import com.yawnandpawn.app.android.ApplicationScope
import com.yawnandpawn.app.android.crash.FirebaseStartup
import com.yawnandpawn.app.appModule
import com.yawnandpawn.app.awaitChildren
import com.yawnandpawn.app.core.alarm.AlarmRepository
import com.yawnandpawn.app.core.alarm.AlarmScheduler
import com.yawnandpawn.app.core.alarm.RequestCodes
import com.yawnandpawn.app.core.crash.CrashReporter
import com.yawnandpawn.app.core.error.Outcome
import com.yawnandpawn.app.core.session.ActiveSessionStore
import com.yawnandpawn.app.core.session.Billing
import com.yawnandpawn.app.core.session.SessionEngine
import com.yawnandpawn.app.core.session.SessionState
import com.yawnandpawn.app.core.session.SnoozeAvailability
import com.yawnandpawn.app.core.session.SnoozeAvailabilityPolicy
import com.yawnandpawn.app.core.session.UnavailableReason
import com.yawnandpawn.app.core.time.Clock
import com.yawnandpawn.app.data.dataModule
import com.yawnandpawn.app.restartKoin
import com.yawnandpawn.app.stopApp
import com.yawnandpawn.app.testAppModule
import com.yawnandpawn.app.testing.FakeAlarmScheduler
import com.yawnandpawn.app.testing.FakeBilling
import com.yawnandpawn.app.testing.FakeClock
import com.yawnandpawn.app.testing.FakeCrashReporter
import com.yawnandpawn.app.testing.SchedulerCall
import com.yawnandpawn.app.testing.aSession
import com.yawnandpawn.app.testing.aSessionConfig
import com.yawnandpawn.app.testing.anAlarm
import com.yawnandpawn.app.ui.uiModule
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.GlobalContext
import org.koin.core.context.startKoin
import org.koin.dsl.module
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import org.robolectric.shadows.ShadowMediaPlayer
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration
import kotlin.time.Instant

/**
 * Story 2.3: the alarm rings after a reboot before the first unlock. Credential-protected storage is closed then, so
 * every context here (the Application and its `applicationContext`, each service, the context a receiver gets) throws
 * on any access to it ([LockedStorageContextImpl]); everything must resolve under `createDeviceProtectedStorageContext()`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(shadows = [LockedStorageContextImpl::class])
class DirectBootRingTest {
    @get:Rule(order = 0)
    val stopAppRule = StopAppRule()

    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val systemSound = "system:Morning|content://media/internal/audio/media/7"

    private fun lockUser() {
        shadowOf(app.getSystemService(UserManager::class.java)).setUserUnlocked(false)
        LockedStorageContextImpl.locked = true
    }

    private fun unlockUser() {
        shadowOf(app.getSystemService(UserManager::class.java)).setUserUnlocked(true)
        LockedStorageContextImpl.locked = false
    }

    @After
    fun openStorage() = LockedStorageContextImpl.reset()

    @Test
    fun `while locked every context closes credential-protected storage and the device-protected one stays open`() {
        lockUser()
        val service = Robolectric.buildService(PlainService::class.java).create().get()
        val contexts = listOf(app, app.applicationContext, app.baseContext, service, service.applicationContext)
        val closed =
            listOf<Pair<String, (Context) -> Any?>>(
                "filesDir" to { it.filesDir },
                "cacheDir" to { it.cacheDir },
                "codeCacheDir" to { it.codeCacheDir },
                "noBackupFilesDir" to { it.noBackupFilesDir },
                "dataDir" to { it.dataDir },
                "getDir" to { it.getDir("x", Context.MODE_PRIVATE) },
                "openFileInput" to { it.openFileInput("x") },
                "openFileOutput" to { it.openFileOutput("x", Context.MODE_PRIVATE) },
                "getDatabasePath" to { it.getDatabasePath("x.db") },
                "getSharedPreferences" to { it.getSharedPreferences("x", Context.MODE_PRIVATE) },
                "deleteDatabase" to { it.deleteDatabase("x.db") },
                "databaseList" to { it.databaseList() },
            )

        contexts.forEach { context ->
            closed.forEach { (what, access) ->
                assertFailsWith<IllegalStateException>("$what on $context") { access(context) }
                // A missing file may still fail on the device-protected context, but never as locked storage.
                val device = runCatching { access(context.createDeviceProtectedStorageContext()) }.exceptionOrNull()
                assertFalse(device is IllegalStateException, "$what on the device-protected context: $device")
            }
        }
        assertTrue(LockedStorageContextImpl.touched.isNotEmpty())
    }

    /** A service with no behaviour of its own: its base context is what the guard must close. */
    class PlainService : Service() {
        override fun onBind(intent: Intent?): IBinder? = null
    }

    private fun idle() {
        shadowOf(Looper.getMainLooper()).idle()
        GlobalContext.get().get<ApplicationScope>().awaitChildren()
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun awaitUntil(
        what: String,
        condition: () -> Boolean,
    ) {
        repeat(500) {
            shadowOf(Looper.getMainLooper()).idle()
            if (condition()) return
            Thread.sleep(10)
        }
        fail("timed out waiting until $what")
    }

    @Test
    fun `after a locked boot the alarm rings with sound and notification, touching only device-protected storage`() {
        lockUser()
        stopApp()
        ShadowMediaPlayer.setMediaInfoProvider { ShadowMediaPlayer.MediaInfo(3_600_000, 0) }
        val billing = FakeBilling()
        val koin =
            startKoin {
                androidContext(app)
                modules(
                    listOf(appModule, dataModule, uiModule, testAppModule) +
                        module {
                            single<CrashReporter> { FakeCrashReporter() }
                            single<Billing> { billing }
                            single { WakeServiceStarts(Duration.ZERO) }
                        },
                )
            }.koin
        val firebase = koin.get<FirebaseStartup>().also { it.start() }
        // The alarm the user saved (a system ringtone, which needs the media provider) is in the device-protected app.db.
        val alarm = anAlarm(id = "alarm-a", requestCode = 1000).copy(soundRef = systemSound)
        assertEquals(Outcome.Success(Unit), runBlocking { koin.get<AlarmRepository>().upsert(alarm) })

        app.sendBroadcast(Intent(Intent.ACTION_LOCKED_BOOT_COMPLETED).setPackage(app.packageName))
        idle()
        val armed =
            assertNotNull(
                shadowOf(app.getSystemService(AlarmManager::class.java)).scheduledAlarms.singleOrNull {
                    shadowOf(it.operation).requestCode == 1000
                },
                "rescheduleAll re-armed the alarm before the unlock",
            )
        // The occurrence fires: the alarm receiver starts the wake service, which the system starts.
        app.sendBroadcast(Intent(shadowOf(armed.operation).savedIntent))
        idle()
        val start = assertNotNull(shadowOf(app).nextStartedService, "the alarm receiver starts the wake service")
        Robolectric.buildService(WakeService::class.java, start).create().startCommand(0, 1)
        val engine = koin.get<SessionEngine>()
        val player = koin.get<AndroidAlarmPlayer>()
        try {
            awaitUntil("the alarm rings") { engine.state.value is SessionState.Ringing && player.sound != null }
        } catch (e: AssertionError) {
            val logs = ShadowLog.getLogsForTag(AndroidLogger.TAG).map { it.msg }
            throw AssertionError("${e.message}; state ${engine.state.value}; touched ${LockedStorageContextImpl.touched}; logs $logs", e)
        }

        val ringing = engine.state.value as SessionState.Ringing
        assertTrue(ringing.session.beforeFirstUnlock)
        assertEquals(systemSound, ringing.session.config.soundRef, "the frozen config keeps the chosen sound")
        assertEquals(AlarmSound.Default, player.sound, "the system ringtone becomes the default built-in sound before the unlock")
        assertTrue(
            shadowOf(app.getSystemService(NotificationManager::class.java)).allNotifications.isNotEmpty(),
            "the ringing notification",
        )
        assertEquals(
            SnoozeAvailability.Unavailable(UnavailableReason.BeforeFirstUnlock),
            koin.get<SnoozeAvailabilityPolicy>().availability(ringing.session),
        )
        assertFalse(firebase.started, "Firebase waits for the unlock")
        assertEquals(emptyList(), billing.launched, "no billing while locked")
        assertEquals(emptyList<String>(), LockedStorageContextImpl.touched.toList(), "no credential-protected storage was touched")
    }

    @Test
    fun `a session stored before the reboot is restored while locked with the before-unlock behaviour`() {
        lockUser()
        val app = WakeApp()
        val stored = SessionState.Ringing(aSession(config = aSessionConfig().copy(soundRef = systemSound)))
        assertEquals(Outcome.Success(Unit), runBlocking { app.koin.get<ActiveSessionStore>().commit(stored) })
        assertFalse(stored.session.beforeFirstUnlock, "stored by a process that ran unlocked")

        app.app.sendBroadcast(Intent(Intent.ACTION_LOCKED_BOOT_COMPLETED).setPackage(app.app.packageName))
        idle()
        val slot =
            assertNotNull(
                shadowOf(app.app.getSystemService(AlarmManager::class.java)).scheduledAlarms.singleOrNull {
                    shadowOf(it.operation).requestCode == RequestCodes.SESSION_SLOT
                },
            )
        app.app.sendBroadcast(Intent(shadowOf(slot.operation).savedIntent))
        idle()
        app.startService(assertNotNull(shadowOf(app.app).nextStartedService))
        app.awaitRinging()

        val restored = assertIs<SessionState.Ringing>(app.engine.state.value).session
        assertTrue(restored.beforeFirstUnlock)
        assertTrue(restored.startedBeforeUnlock, "history will record direct_boot")
        assertEquals(AlarmSound.Default, app.player.sound)
        assertEquals(
            SnoozeAvailability.Unavailable(UnavailableReason.BeforeFirstUnlock),
            app.koin.get<SnoozeAvailabilityPolicy>().availability(restored),
        )
    }

    @Test
    fun `BOOT_COMPLETED after the unlock repeats the locked boot's scheduling exactly`() {
        lockUser()
        val scheduler = FakeAlarmScheduler()
        // A fixed wall clock: the two boots must ask for the very same slot.
        val clock = FakeClock(Instant.parse("2027-03-08T05:00:00Z"))
        restartKoin(
            app,
            module {
                single<AlarmScheduler> { scheduler }
                single<Clock> { clock }
            },
        )
        val koin = GlobalContext.get()
        runBlocking {
            koin.get<AlarmRepository>().upsert(anAlarm(id = "alarm-a", requestCode = 1000))
            koin.get<ActiveSessionStore>().commit(SessionState.Ringing(aSession()))
        }

        app.sendBroadcast(Intent(Intent.ACTION_LOCKED_BOOT_COMPLETED).setPackage(app.packageName))
        idle()
        val locked = scheduler.calls
        scheduler.clearCalls()
        unlockUser()
        app.sendBroadcast(Intent(Intent.ACTION_BOOT_COMPLETED).setPackage(app.packageName))
        idle()

        assertTrue(locked.isNotEmpty())
        assertEquals(locked, scheduler.calls, "identical calls: alarm and session slot")
        assertTrue(scheduler.armed.containsKey(RequestCodes.SESSION_SLOT), "the session slot is armed")
        assertTrue(scheduler.calls.any { it is SchedulerCall.ArmSessionSlot } && scheduler.calls.size >= 2, "$locked")
    }
}
