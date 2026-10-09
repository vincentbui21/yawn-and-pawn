package com.yawnandpawn.app.android.work

import android.os.Looper
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.yawnandpawn.app.StopAppRule
import com.yawnandpawn.app.YawnAndPawnApp
import com.yawnandpawn.app.android.ApplicationScope
import com.yawnandpawn.app.android.wake.UnlockSignals
import com.yawnandpawn.app.awaitChildren
import com.yawnandpawn.app.core.billing.PriceRefreshJobs
import com.yawnandpawn.app.core.error.DomainError
import com.yawnandpawn.app.core.error.Outcome
import com.yawnandpawn.app.core.session.UserLockState
import com.yawnandpawn.app.core.work.BackgroundJob
import com.yawnandpawn.app.core.work.BackgroundWork
import com.yawnandpawn.app.stopApp
import com.yawnandpawn.app.testAppModule
import com.yawnandpawn.app.testing.FakeUserLockState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.GlobalContext
import org.koin.core.module.Module
import org.koin.dsl.module
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * Story 4.3 review: the app start schedules the price refresh jobs off the main thread, and a process started before
 * the first unlock schedules them at the unlock instead (through [UnlockSignals]), never touching WorkManager while
 * locked. Each test boots its own application class, so the bindings are in place when `onCreate` runs.
 */
@RunWith(RobolectricTestRunner::class)
class PriceRefreshSchedulingTest {
    @get:Rule(order = 0)
    val stopAppRule = StopAppRule()

    /** Records each job and whether it was enqueued on the main thread. */
    class RecordingWork : BackgroundWork {
        val jobs: MutableList<BackgroundJob> = Collections.synchronizedList(mutableListOf())
        val onMain: MutableList<Boolean> = Collections.synchronizedList(mutableListOf())

        override fun enqueue(job: BackgroundJob): Outcome<Unit, DomainError> {
            jobs += job
            onMain += Looper.myLooper() == Looper.getMainLooper()
            return Outcome.Success(Unit)
        }
    }

    /** An app on an unlocked phone whose background work is [RecordingWork]. */
    class UnlockedStartApp : YawnAndPawnApp() {
        override val overrideModules: List<Module> =
            listOf(testAppModule, module { single<BackgroundWork> { recording } })

        override fun onCreate() {
            stopApp()
            super.onCreate()
        }

        companion object {
            var recording = RecordingWork()
        }
    }

    /** An app started before the first unlock, with the real WorkManager adapter (and a counted WorkManager lookup). */
    class LockedStartApp : YawnAndPawnApp() {
        override val overrideModules: List<Module> =
            listOf(
                testAppModule,
                module {
                    single<UserLockState> { lock }
                    single<BackgroundWork> {
                        AndroidBackgroundWork(androidContext(), get(), get()) {
                            lookups.incrementAndGet()
                            WorkManager.getInstance(androidContext())
                        }
                    }
                },
            )

        override fun onCreate() {
            stopApp()
            super.onCreate()
        }

        companion object {
            val lock = FakeUserLockState(unlocked = false)
            val lookups = AtomicInteger()
        }
    }

    @Test
    @Config(application = UnlockedStartApp::class)
    fun `the app start enqueues both price refresh jobs once, off the main thread`() {
        val koin = GlobalContext.get()
        koin.get<ApplicationScope>().awaitChildren()
        val recording = UnlockedStartApp.recording

        assertEquals(PriceRefreshJobs.ALL, recording.jobs.toList())
        assertFalse(recording.onMain.any { it }, "enqueued on the main thread: ${recording.onMain}")

        // A later unlock signal schedules nothing again in this process.
        koin.get<UnlockSignals>().initialiseAfterUnlock()
        koin.get<ApplicationScope>().awaitChildren()
        assertEquals(PriceRefreshJobs.ALL, recording.jobs.toList())
    }

    @Test
    @Config(application = LockedStartApp::class)
    fun `a locked start never touches WorkManager, and the unlock signal schedules both jobs`() {
        val app = ApplicationProvider.getApplicationContext<YawnAndPawnApp>()
        val koin = GlobalContext.get()
        koin.get<ApplicationScope>().awaitChildren()
        assertEquals(0, LockedStartApp.lookups.get(), "WorkManager looked up before the first unlock")

        WorkManagerTestInitHelper.initializeTestWorkManager(
            app,
            Configuration
                .Builder()
                .setMinimumLoggingLevel(Log.DEBUG)
                .setExecutor(SynchronousExecutor())
                .build(),
        )
        LockedStartApp.lock.unlock()
        koin.get<UnlockSignals>().initialiseAfterUnlock()
        koin.get<ApplicationScope>().awaitChildren()

        val workManager = WorkManager.getInstance(app)
        PriceRefreshJobs.ALL.forEach { job ->
            assertEquals(1, workManager.getWorkInfosForUniqueWork(job.uniqueName).get().size, job.uniqueName)
        }
    }
}
