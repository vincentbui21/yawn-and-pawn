package com.yawnandpawn.app.android.work

import com.yawnandpawn.app.android.ApplicationScope
import com.yawnandpawn.app.android.reminder.AndroidSkipReminderNotifier
import com.yawnandpawn.app.android.summary.AndroidWeeklySummaryNotifier
import com.yawnandpawn.app.core.billing.CachedPriceCatalog
import com.yawnandpawn.app.core.billing.ConsumeRetryTask
import com.yawnandpawn.app.core.billing.PriceCatalog
import com.yawnandpawn.app.core.billing.PriceRefreshScheduler
import com.yawnandpawn.app.core.billing.PriceRefreshTask
import com.yawnandpawn.app.core.billing.SessionStartPriceRefresh
import com.yawnandpawn.app.core.error.valueOrNull
import com.yawnandpawn.app.core.reminder.SetSkipReminder
import com.yawnandpawn.app.core.reminder.SkipReminderNotifier
import com.yawnandpawn.app.core.reminder.SkipReminderScheduler
import com.yawnandpawn.app.core.reminder.SkipReminderTask
import com.yawnandpawn.app.core.reminder.SkipTodaysAlarms
import com.yawnandpawn.app.core.session.ActiveSessionStore
import com.yawnandpawn.app.core.session.SessionLockGuard
import com.yawnandpawn.app.core.session.StoredSession
import com.yawnandpawn.app.core.session.inProgress
import com.yawnandpawn.app.core.summary.SetWeeklySummary
import com.yawnandpawn.app.core.summary.WeeklySummaryNotifier
import com.yawnandpawn.app.core.summary.WeeklySummaryScheduler
import com.yawnandpawn.app.core.summary.WeeklySummaryTask
import com.yawnandpawn.app.core.work.BackgroundTaskKind
import com.yawnandpawn.app.core.work.BackgroundWork
import com.yawnandpawn.app.core.work.KeyedBackgroundTask
import com.yawnandpawn.app.core.work.TaskResult
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * Koin bindings of deferred work and the price cache (Story 4.3, AD-17), included by `appModule`: WorkManager behind
 * [BackgroundWork], the task of each job, the cached Play prices over the price cache store (`dataModule`), the
 * scheduler of the refresh jobs and the refresh at session start (once online, Story 4.7). Play's product details come
 * from the Play Billing adapter (`billingModule()`, Story 4.12). The weekly summary (Story 6.8): its run, the scheduler
 * that keeps its work aligned, the notifier and the Settings switch's use case. The Skip reminder (0.1.8): its run, the
 * scheduler `AlarmScheduling` keeps it in step through, the notifier, the Settings switch's use case and "Skip today's
 * alarms"; Story 7.11's keyed pre-alarm job left from before the update runs as a no-op.
 *
 * A function, like `wakeModule()`: each call has its own definitions.
 */
fun workModule(): Module =
    module {
        single<BackgroundWork> { AndroidBackgroundWork(androidContext(), get(), get()) }
        // The price refresh (Story 4.3) and the consume retry of the grant ledger (Story 4.10, its one-time and 6-hour
        // periodic jobs).
        single {
            BackgroundTasks(
                mapOf(
                    BackgroundTaskKind.PriceRefresh to PriceRefreshTask(get()),
                    BackgroundTaskKind.ConsumeRetry to ConsumeRetryTask(get()),
                    BackgroundTaskKind.WeeklySummary to WeeklySummaryTask(get(), get(), get(), get(), get(), get(), get(), get()),
                    BackgroundTaskKind.SkipReminder to SkipReminderTask(get()),
                ),
                // A Story 7.11 pre-alarm job left from before 0.1.8 does nothing.
                keyed = mapOf(BackgroundTaskKind.PreAlarm to KeyedBackgroundTask { TaskResult.Done }),
            )
        }
        single<PriceCatalog> { CachedPriceCatalog(get(), get(), get(), get()) }
        single { PriceRefreshScheduler(get(), get(), get()) }
        single { SessionStartPriceRefresh(get(), get(), get<ApplicationScope>(), get()) }
        single { WeeklySummaryScheduler(get(), get(), get(), get(), get(), get()) }
        single<WeeklySummaryNotifier> { AndroidWeeklySummaryNotifier(androidContext(), get(), get()) }
        factory { SetWeeklySummary(get(), get(), get()) }
        single<SkipReminderNotifier> { AndroidSkipReminderNotifier(androidContext()) }
        // No reminder is posted during a session: the restored engine's lock, or before the engine is restored (a cold start
        // by the job or a receiver), the emergency ring or a ring or snooze stored in runtime.db.
        single {
            val scope = this
            SkipReminderScheduler(get(), get(), get(), get(), get(), get(), get(), get()) {
                val guard = scope.get<SessionLockGuard>()
                if (guard.restored.value) {
                    guard.isLocked
                } else {
                    guard.emergency.value ||
                        (scope.get<ActiveSessionStore>().load().valueOrNull() as? StoredSession.Found)?.state?.inProgress == true
                }
            }
        }
        factory { SetSkipReminder(get(), get(), get()) }
        factory { SkipTodaysAlarms(get(), get(), get(), get()) }
    }
