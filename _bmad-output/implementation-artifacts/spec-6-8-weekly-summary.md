---
title: 'Story 6.8: Weekly summary notification on Sunday evening'
type: 'feature'
created: '2026-10-26'
status: 'review'
baseline_commit: '3b18f2eb'
route: 'dispatch'
story_key: '6-8-weekly-summary-notification-on-sunday-evening'
review_loop_iteration: 0
depends_on:
  - '6-1 (merged): `sessionDay`, `isCounted` in core/.../stats/WakeStats.kt — needed by the scheduling half (on-time count)'
  - '6-2 (merged): `moneyPaid(records, period, today, zone)` (only `priceSource.isAmountPaid` records, per currency, sorted by code) — needed by the money half'
  - '6-4 or 6-2: a one-shot history read (`SessionHistoryRepository.all()` from 6.4; add it here with the same signature if 6.4 has not merged)'
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-6-context.md'
  - '{project-root}/.claude/skills/pps-design/SKILL.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-6-1-streaks-on-time-rate-and-time-to-up-in-core-stats.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-4-3-cache-play-prices-for-offline-display.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-5-6-anonymous-usage-stats-off-until-the-user-says-yes.md'
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** the user only sees progress by opening the app. FR-PRG-5 promises a short Sunday-evening note with the week's on-time mornings and what was paid. The Settings "Weekly summary" switch exists in the approved round 2 layout but is hidden (`BuiltSettingsRows`) and does nothing.

**Approach:**
- Pure rules in `core.stats` decide the next Sunday 19:00 local, the ISO week, whether a run is on time (24 h late or more skips the week) and what the week says.
- The AD-17 `BackgroundWork` port gains a delayed, replaceable, cancellable one-time job `weekly-summary` (WorkManager adapter), run by a new `BackgroundTaskKind.WeeklySummary` task.
- A core scheduler keeps that job aligned. It is called on app start, after the first unlock, from `SystemEventsReceiver` (boot, `TIME_SET`, `TIMEZONE_CHANGED`), on toggle and after every run.
- The worker posts on a new "Weekly summary" channel only with `POST_NOTIFICATIONS`, and stores the last handled ISO week so a week never posts twice.
- The switch is stored in the settings DataStore through a guarded core use case and shown in Settings.
- Tapping the notification opens Progress.

**Split by dependency (build order):**
- **Scheduling half, needs only 6.1:**
  - `IsoWeek`, `nextWeeklySummaryAt`, `lastWeeklySummaryAt`, `weeklySummaryDecision`;
  - the port change and adapter, the scheduler, the store, the toggle use case and the Settings row;
  - the receiver, app-start and unlock wiring;
  - the channel and the tap to Progress;
  - the on-time count (6.1's `sessionDay` and `isCounted`).
- **Money half, needs 6.2:** `WeeklySummary.paid` through 6.2's `moneyPaid(…, ThisWeek, …)`, the two "paid" text variants and their tests. Until 6.2 merges, the scheduling half can be built and tested with `paid = emptyList()`. Do not open the PR before the money half is in.

## Boundaries & Constraints

**Always:**
- The target is Sunday 19:00 **local** (the `TimeZoneProvider` zone when computed), strictly after now. The week is the ISO week (Monday 00:00 to Sunday 24:00 local) of the target's local date.
- A run summarises the week of the latest Sunday 19:00 at or before now, only if it is at most 24 h after that target and that week was not handled yet. Otherwise it posts nothing.
- One unique one-time work `weekly-summary`. No periodic work, no foreground work, nothing through `AlarmScheduler`, nothing on the wake path (NFR-8, AD-17).
- `enqueue(BackgroundJob)` and its KEEP/UPDATE behaviour for price refresh and consume retry are unchanged.
- WorkManager is touched only after the first unlock (the adapter already refuses while locked). The scheduler does nothing while locked, because the unlock signals call it again.
- Posts only when `POST_NOTIFICATIONS` is granted and app notifications are enabled. It never requests the permission (no Activity in the background).
- Money: per currency, only records whose price is the amount paid (`priceSource.isAmountPaid`) with status granted, consumed or reused, never across currencies. Each amount goes through `MoneyFormatter` and the amounts are joined with " · " in currency-code order. No currency symbol in any resource.
- Text is verbatim from EXPERIENCE.md Key strings. The channel is "Weekly summary" at default importance; the title is the app name; the small icon is monochrome with no tint; the notification auto-cancels.
- The switch: default on, stored in device-protected DataStore (the settings file) through `SetWeeklySummary` inside `SessionLockGuard.whenIdle`. Off cancels the work at once; on enqueues it.

**Never:** no `app.db` / `runtime.db` migration; no new DataStore file; no new permission or dependency; no stored derived stat other than the last handled week id; no read of a purchase state (`PurchaseStateReaderScanTest`); no direct clock or zone read (`NoDirectTimeAccess`).

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior |
|----------|--------------|---------------------------|
| Next target | now Sat 23:00 / Sun 18:59 / Sun 19:00 exactly | Sun 19:00 next day / same day / a week later |
| DST spring | Europe/Berlin, now Sat 2027-03-27 12:00 CET (11:00Z) | 2027-03-28T17:00Z (19:00 CEST); delay 30 h, not 31 h |
| DST autumn | Europe/Berlin, now Sat 2027-10-30 12:00 CEST (10:00Z) | 2027-10-31T18:00Z (19:00 CET) |
| Zone change | now 2027-03-07T10:00Z; Asia/Ho_Chi_Minh vs America/New_York | 2027-03-07T12:00Z vs 2027-03-08T00:00Z |
| On-time run | Sun 19:00 to Mon 19:00 (≤ 24 h), week not handled | summarise that ISO week |
| Late run | Mon 19:01 or later (phone locked since boot until Tuesday) | post nothing; week stored as handled |
| Duplicate | second run, same week handled | post nothing |
| Nothing to send | week with only Test / Skipped sessions (or none) | post nothing; week stored |
| Nothing paid, n ≥ 2 on time | | "{n} on-time mornings, nothing paid. Nice." |
| Nothing paid, 1 on time | | "1 on-time morning, nothing paid. Nice." |
| Nothing paid, 0 on time | only Missed (or Snoozed with no known amount) | "0 on-time mornings this week. Next week's a fresh start." |
| Paid | EUR 2.99 + USD 1.00 + EUR 5.99, 3 on time | "3 on-time mornings, €8.98 · $1.00 paid this week." (en-US) |
| Paid, 1 on time | | "1 on-time morning, {paid} paid this week." |
| Paid, 0 on time | | "0 on-time mornings, {paid} paid this week." |
| Permission missing | no `POST_NOTIFICATIONS` / notifications off | post nothing, never request; week stored |
| Switch off | toggle off | unique work cancelled at once; a run racing it posts nothing |
| Switch on | toggle on | work enqueued (or kept if a run is due now) |
| App start during a due run | process started by WorkManager on Sun 19:00 | the running or pending run is kept (KEEP), never cancelled |
| After a run | run finished | next week's work chained after it (APPEND_OR_REPLACE); the run ends SUCCEEDED |
| Boot / time / zone change | `BOOT_COMPLETED`, `TIME_SET`, `TIMEZONE_CHANGED` | sync after the alarms are re-armed; locked boot does nothing |
| Tap | notification tapped | MainActivity opens Progress (or `panel-session-in-progress` during a session); auto-cancelled |
| Delete all data | settings DataStore cleared | switch reads on (default), last week forgotten; next sync re-aligns |

</frozen-after-approval>

## Code Map

- `core/.../work/BackgroundWork.kt` -- the `fun interface BackgroundWork { enqueue(job) }`, `BackgroundTaskKind { PriceRefresh, ConsumeRetry }`, `BackgroundJob`, `TaskResult`, `BackgroundTask`.
- `androidApp/.../android/work/AndroidBackgroundWork.kt`:
  - `enqueue`: one-time KEEP, periodic UPDATE;
  - `userLock` guard;
  - `watch(result, name)`;
  - `TAG`, `BACKOFF_SECONDS`.
- `androidApp/.../android/work/BackgroundTaskWorker.kt` -- `BackgroundTasks` map, `KEY_TASK`, `MAX_ATTEMPTS`.
- `androidApp/.../android/work/WorkModule.kt` -- `workModule()` binds `BackgroundWork` and `BackgroundTasks`.
- `core/.../billing/PriceRefresh.kt` -- the `PriceRefreshScheduler` pattern (lock-aware `start()`, logs failures).
- `androidApp/.../YawnAndPawnApp.kt` (line ~460: `scope.launch { prices.start() }`).
- `androidApp/.../android/wake/UnlockSignals.kt` `initialiseAfterUnlock()` (line ~106: `scope.launch { prices.start() }`).
- `androidApp/.../android/SystemEventsReceiver.kt` -- `runWithinBudget { scheduling.rescheduleAll(); rearm.afterSystemEvent() }`, `ACTIONS` (`ACTION_TIME_CHANGED` is `TIME_SET`).
- `androidApp/.../android/wake/WakeNotifier.kt` -- the channel pattern (`ensureChannel`, `R.string.notification_channel_*`, `R.drawable.ic_stat_alarm`, `NOTIFICATION_ID = 1_014`).
- `androidApp/src/main/res/values/strings.xml` -- notification strings (checked by `CopyRulesTest` with the compose strings).
- `androidApp/.../reliability/AndroidReliability.kt` -- `NotificationManagerCompat.areNotificationsEnabled()` and `POST_NOTIFICATIONS` checks.
- `androidApp/.../MainActivity.kt` -- `onCreate` → `setContent { App() }`; standard launch mode; no tab extra today.
- `composeApp/.../ui/nav/AppNavHost.kt` (`rememberNavBackStack`, `sessionLock()`, `shownStack`, `backStack.selectTab`), `TabNavigation.kt` (`AppTab`, `selectTab`, `applySessionLock`).
- `composeApp/.../ui/settings/`:
  - `SettingsContract.kt`: `SettingsRow.WeeklySummary`, `weeklySummary = true`, `SettingsIntent.WeeklySummaryToggled`;
  - `SettingsScreen.kt` line ~224: the switch row in the card with "Share anonymous usage stats";
  - `SettingsRoute.kt`: `BuiltSettingsRows` without `WeeklySummary`;
  - `SettingsViewModel.kt`: the usage stats pattern `consent` flow plus `setUsageStats`. `combine` is already at 5 flows.
- `composeApp/src/commonMain/composeResources/values/strings.xml` -- `settings_weekly_summary` "Weekly summary" (exists).
- `core/.../telemetry/Consent.kt` -- `SetAnalyticsConsent` (guarded use case pattern).
- `data/.../settings/SettingsDataStore.kt` (`store`, `clear()`, device-protected `datastore/settings.preferences_pb`), `DataStoreReRegisterDismissals.kt` (read retry and `StorageFailure` pattern).
- `data/.../reset/AndroidLocalDataEraser.kt` -- `settings.clear()` removes every key, the new ones too.
- `core/.../billing/MoneyFormatter.kt` -- `format(money)`. **Note:** `formatTotals` joins with " + " in first-seen order; the epic needs " · " in currency-code order (see Design Notes).
- `core/.../billing/PurchaseRecords.kt` -- `PurchaseRecordRepository.all()` (one-shot), `PriceSource.isAmountPaid`.
- Test doubles implementing `BackgroundWork`:
  - `testing/.../PriceFakes.kt` `FakeBackgroundWork`;
  - `testing/.../PurchaseLedgerFakes.kt` `RecordingBackgroundWork`;
  - `core/src/commonTest/.../billing/PriceRefreshTest.kt` `Work`;
  - `core/src/commonTest/.../billing/PurchaseLedgerTestDoubles.kt` `MemoryWork`;
  - `androidApp/src/test/.../work/PriceRefreshSchedulingTest.kt` `RecordingWork`.
- `androidApp/src/test/.../TestYawnAndPawnApp.kt` binds `FakeBackgroundWork`. `RestoreEntryPointsTest` asserts `FakeBackgroundWork.enqueued` (keep the weekly calls in separate lists).
- `data/src/androidHostTest/.../SessionLockGuardScanTest.kt` -- expected writer set (add `SetWeeklySummary`).

## Tasks & Acceptance

**Execution (scheduling half, needs only 6.1):**

- [ ] `core/src/commonMain/kotlin/com/yawnandpawn/app/core/stats/WeeklySummary.kt` (new, pure; `core.stats` is a full lock-scan package, so no writes here):
  ```kotlin
  /** An ISO-8601 week: weeks start on Monday; week 1 holds the year's first Thursday. Stored as "2027-W01". */
  data class IsoWeek(val year: Int, val week: Int) : Comparable<IsoWeek> {
      fun monday(): LocalDate
      override fun toString(): String            // "YYYY-Www", zero-padded, no String.format (common code)
      companion object {
          fun of(date: LocalDate): IsoWeek       // via the Thursday of date's week: year = thursday.year, week = (thursday.dayOfYear - 1) / 7 + 1
          fun parse(text: String): IsoWeek?      // null for anything else
      }
  }

  object WeeklySummaryRule {
      val DAY: DayOfWeek = DayOfWeek.SUNDAY
      val TIME: LocalTime = LocalTime(19, 0)
      /** A run later than this after its target skips that week (FR-PRG-5). */
      val LATE_AFTER: Duration = 24.hours
  }

  /** The first Sunday 19:00 in [zone] strictly after [now] (built from local dates, so DST weeks are right). */
  fun nextWeeklySummaryAt(now: Instant, zone: TimeZone): Instant
  /** The latest Sunday 19:00 in [zone] at or before [now]. */
  fun lastWeeklySummaryAt(now: Instant, zone: TimeZone): Instant

  sealed interface WeeklySummaryDecision {
      /** Summarise [week], whose Sunday is [sunday]. */
      data class Summarise(val week: IsoWeek, val sunday: LocalDate) : WeeklySummaryDecision
      /** More than LATE_AFTER past the target: [week] is skipped (stored as handled). */
      data class SkipLate(val week: IsoWeek) : WeeklySummaryDecision
      data object AlreadyHandled : WeeklySummaryDecision
  }
  /** For a run at [now]: target = lastWeeklySummaryAt(now, zone); its week == [lastHandled] → AlreadyHandled; now − target > LATE_AFTER → SkipLate; else Summarise. */
  fun weeklySummaryDecision(now: Instant, zone: TimeZone, lastHandled: IsoWeek?): WeeklySummaryDecision
  /** A run is due now: the decision would be Summarise (used by the scheduler to keep a due run instead of replacing it). */
  fun weeklySummaryDue(now: Instant, zone: TimeZone, lastHandled: IsoWeek?): Boolean

  /** The summary of one ISO week. [onTime] = days of the week with at least one counted session, all OnTime (Story 6.1 rules; the calendar's "On time" days). */
  data class WeeklySummary(val week: IsoWeek, val onTime: Int, val paid: List<Money>, val shouldSend: Boolean)

  /** [weekOf] is any date in the week. shouldSend = the week has at least one counted session. [paid] needs 6.2 (money half). */
  fun weeklySummary(history: List<SessionHistoryRow>, records: List<PurchaseRecord>, weekOf: LocalDate, zone: TimeZone): WeeklySummary

  /** Which text the notification uses. */
  sealed interface WeeklySummaryText {
      data class NothingPaid(val onTime: Int) : WeeklySummaryText   // onTime ≥ 1; the poster picks the singular for 1
      data class Paid(val onTime: Int, val paid: List<Money>) : WeeklySummaryText
      data object NoOnTime : WeeklySummaryText                        // onTime 0 and nothing paid
  }
  fun WeeklySummary.text(): WeeklySummaryText
  ```
- [ ] `core/.../work/BackgroundWork.kt`:
  - `BackgroundTaskKind.WeeklySummary` (update the KDoc).
  - `BackgroundWork` is no longer a `fun interface`. There are no SAM-lambda users; the six classes above implement it. Add:
  ```kotlin
  /** How [BackgroundWork.scheduleWeeklySummary] hands the job over. */
  enum class SchedulePolicy {
      /** Replace any pending or running `weekly-summary` (toggle on, time or zone change, next week's target). WorkManager REPLACE. */
      Replace,
      /** Keep a pending or running one; enqueue only when there is none (a run is due now). WorkManager KEEP. */
      KeepPending,
      /** From inside the running summary: chained after it, so the run is never cancelled by its successor. WorkManager APPEND_OR_REPLACE. */
      AfterRun,
  }
  /** One-time unique work [WeeklySummaryJobs.NAME] for [BackgroundTaskKind.WeeklySummary], due at [at] (delay = at − now, never negative), no network needed. */
  fun scheduleWeeklySummary(at: Instant, policy: SchedulePolicy = SchedulePolicy.Replace): Outcome<Unit, DomainError>
  /** Cancels the unique work [WeeklySummaryJobs.NAME]; nothing pending is not an error. */
  fun cancelWeeklySummary(): Outcome<Unit, DomainError>
  ```
  and `object WeeklySummaryJobs { const val NAME = "weekly-summary" }`.
- [ ] `androidApp/.../work/AndroidBackgroundWork.kt`:
  - Constructor adds `clock: Clock` before the `workManager` default parameter. Koin: `AndroidBackgroundWork(androidContext(), get(), get(), get())`.
  - `scheduleWeeklySummary` uses the same lock guard and try/catch as `enqueue`. It builds `OneTimeWorkRequestBuilder<BackgroundTaskWorker>().setInitialDelay(delay.inWholeMilliseconds, MILLISECONDS).setInputData(KEY_TASK = WeeklySummary).setBackoffCriteria(...).addTag(TAG)` with no network constraint, then calls `enqueueUniqueWork(NAME, policy → REPLACE / KEEP / APPEND_OR_REPLACE, request)` and `watch(...)`.
  - `cancelWeeklySummary` runs `cancelUniqueWork(NAME)` under the same guards.
  - `enqueue` is untouched.
- [ ] `core/src/commonMain/kotlin/com/yawnandpawn/app/core/summary/WeeklySummaryStore.kt` (new package `core.summary`, outside the lock scan's full packages, so only use cases are scanned):
  ```kotlin
  data class WeeklySummaryPrefs(val enabled: Boolean = true, val lastHandledWeek: IsoWeek? = null)
  interface WeeklySummaryStore {
      /** Emits on every change; an unreadable store is logged and reads as the defaults (on). */
      fun observe(): Flow<WeeklySummaryPrefs>
      suspend fun read(): Outcome<WeeklySummaryPrefs, DomainError>
      suspend fun setEnabled(on: Boolean): Outcome<Unit, DomainError>
      suspend fun setLastHandledWeek(week: IsoWeek): Outcome<Unit, DomainError>
  }
  /** Posts the summary notification (Android adapter). */
  interface WeeklySummaryNotifier {
      /** POST_NOTIFICATIONS granted and app notifications enabled. Never requests anything. */
      fun canPost(): Boolean
      fun post(summary: WeeklySummary): Outcome<Unit, DomainError>
  }
  ```
- [ ] `core/.../summary/WeeklySummaryScheduler.kt` (new):
  ```kotlin
  class WeeklySummaryScheduler(work: BackgroundWork, store: WeeklySummaryStore, clock: Clock, zones: TimeZoneProvider, userLock: UserLockState, logger: Logger) {
      /**
       * Aligns the unique work: locked → nothing (Success); off → cancel; weeklySummaryDue(now) → KeepPending at now;
       * else Replace at nextWeeklySummaryAt(now). A store read failure counts as on (default) and is logged; a work failure is logged and returned.
       */
      suspend fun sync(): Outcome<Unit, DomainError>
      /** The last step of a run: AfterRun at nextWeeklySummaryAt(now) while enabled; nothing when off. */
      suspend fun afterRun(): Outcome<Unit, DomainError>
  }
  ```
  Serialize calls with a `Mutex`. Log as `LogEvent.OperationFailed("schedule weekly summary", …)`.
- [ ] `core/.../summary/WeeklySummaryTask.kt` (new) -- `class WeeklySummaryTask(history: SessionHistoryRepository, records: PurchaseRecordRepository, store, notifier, scheduler, clock, zones, logger) : BackgroundTask`. `run()`:
  1. `store.read()`. When off, return `Done` with no post and no reschedule (toggle-off already cancelled).
  2. `weeklySummaryDecision(now, zone, lastHandledWeek)`.
  3. `Summarise`: `history.all()` and `records.all()`. On a read failure, log it, call `afterRun()` and return `Failed`. Otherwise build `weeklySummary(rows, records, sunday, zone)` and post if `shouldSend && notifier.canPost()`; a post failure is logged. Then `setLastHandledWeek(week)`, whether it posted or not.
  4. `SkipLate`: `setLastHandledWeek(week)`, no post.
  5. `AlreadyHandled`: nothing.
  6. Last step, in every non-off case: `scheduler.afterRun()`. Return `Done`.
  
  It never throws; a storage exception is a `StorageFailure` from the ports.
- [ ] `core/.../summary/SetWeeklySummary.kt` (new use case):
  ```kotlin
  class SetWeeklySummary(store: WeeklySummaryStore, scheduler: WeeklySummaryScheduler, sessionLock: SessionLockGuard) {
      /** Stores [on] and aligns the work (cancel when off, enqueue when on), both inside whenIdle; SessionActive during a session. */
      suspend operator fun invoke(on: Boolean): Outcome<Unit, DomainError> = sessionLock.whenIdle { store.setEnabled(on).flatMap { scheduler.sync() } }
  }
  ```
  Both calls go inside `whenIdle`: the scan's `\w*[Ss]chedul(?:ing|er)\.(sync|…)` regex flags `scheduler.sync(` outside it.
- [ ] `data/src/commonMain/kotlin/com/yawnandpawn/app/data/settings/DataStoreWeeklySummaryStore.kt` (new) -- over `SettingsDataStore.store`, with keys `booleanPreferencesKey("weekly_summary_enabled")` (absent = true) and `stringPreferencesKey("weekly_summary_last_week")` (`IsoWeek.toString()` / `parse`; unparsable = null). The read retry, logging and `StorageFailure` boundary follow `DataStoreReRegisterDismissals`. Bind in `dataModule`.
- [ ] `testing/.../WeeklySummaryFakes.kt` (new) -- `FakeWeeklySummaryStore` (with `failRead`, `failWrite`) and `FakeWeeklySummaryNotifier` (`posted: List<WeeklySummary>`, `canPost` var). `FakeBackgroundWork` and `RecordingBackgroundWork` gain `weeklySchedules: List<Pair<Instant, SchedulePolicy>>`, `weeklyCancels: Int` and `failWeekly: DomainError?`, kept **out of** `enqueued`. Core test doubles (`Work`, `MemoryWork`) and `RecordingWork` implement the two members trivially.
- [ ] `androidApp/src/main/kotlin/com/yawnandpawn/app/android/summary/AndroidWeeklySummaryNotifier.kt` (new) -- `class AndroidWeeklySummaryNotifier(context, moneyFormatter: MoneyFormatter, logger) : WeeklySummaryNotifier`:
  - `canPost()` = `NotificationManagerCompat.areNotificationsEnabled()` && (API < 33 || `POST_NOTIFICATIONS` granted).
  - `post()`:
    - creates channel `CHANNEL_ID = "weekly_summary"` if missing: name `R.string.notification_channel_weekly_summary`, `IMPORTANCE_DEFAULT`;
    - builds `NotificationCompat.Builder(context, CHANNEL_ID)` with `setSmallIcon(R.drawable.ic_stat_alarm)` (monochrome, no `setColor`), `setContentTitle(getString(R.string.app_name))`, `setContentText(text)`, `BigTextStyle(text)`, `setAutoCancel(true)` and `setContentIntent(openProgress)`;
    - calls `notify(NOTIFICATION_ID = 1_015, …)`.
  - Exceptions (SecurityException, a throwing service) become a `Failure` and are logged.
  - Text by `summary.text()`:
    - `NothingPaid(1)` → `weekly_summary_nothing_paid_one`;
    - `NothingPaid(n)` → `weekly_summary_nothing_paid` (n);
    - `Paid(1, p)` → `weekly_summary_paid_one` (p);
    - `Paid(n, p)` → `weekly_summary_paid` (n, p);
    - `NoOnTime` → `weekly_summary_none`.
  
  `{paid}` = `moneyFormatter.formatPerCurrency(paid)` (below).
- [ ] `core/.../billing/MoneyFormatter.kt` -- add, if 6.3 has not already:
  ```kotlin
  /** One formatted total per currency, sorted by ISO currency code, joined with " · " (Epic 6: "€8.98 · $1.00 paid this week"). Empty for none. */
  fun MoneyFormatter.formatPerCurrency(amounts: List<Money>): String
  const val PER_CURRENCY_SEPARATOR = " · "
  ```
  It sums through `totalsByCurrency` and sorts by `currency`. Leave `formatTotals` (" + ", Success and Purchase history) alone.
- [ ] `androidApp/src/main/res/values/strings.xml`, verbatim EXPERIENCE.md Key strings (no new copy):
  - `notification_channel_weekly_summary` "Weekly summary"
  - `weekly_summary_nothing_paid` "%1$d on-time mornings, nothing paid. Nice."
  - `weekly_summary_nothing_paid_one` "1 on-time morning, nothing paid. Nice."
  - `weekly_summary_paid` "%1$d on-time mornings, %2$s paid this week."
  - `weekly_summary_paid_one` "1 on-time morning, %1$s paid this week."
  - `weekly_summary_none` "0 on-time mornings this week. Next week's a fresh start."
- [ ] Open Progress from the tap:
  - `composeApp/.../ui/nav/AppOpenRequests.kt` (new): `class AppOpenRequests { val tab: StateFlow<AppTab?>; fun open(tab: AppTab); fun consume() }`, a Koin single in `UiModule`.
  - `MainActivity`: `const val EXTRA_OPEN_TAB = "openTab"` with value `"progress"`. Read it in `onCreate` and `onNewIntent`, call `requests.open(AppTab.Progress)`, then `intent.removeExtra(...)` so a recreation does not reopen.
  - `AppNavHost`: after the restore and onboarding early returns, `LaunchedEffect(request, locked)`. When a request exists: if not locked and onboarding is complete, call `backStack.selectTab(it)`; consume it in every case. Locked shows `panel-session-in-progress`, as the lock already does.
  - The notifier's `PendingIntent.getActivity(context, REQUEST_OPEN_PROGRESS, Intent(context, MainActivity::class.java).putExtra(EXTRA_OPEN_TAB, "progress").addFlags(NEW_TASK | CLEAR_TOP | SINGLE_TOP), FLAG_IMMUTABLE | FLAG_UPDATE_CURRENT)`.
- [ ] Wiring:
  - `WorkModule`: `BackgroundTaskKind.WeeklySummary to WeeklySummaryTask(...)`, plus `single { WeeklySummaryScheduler(get(), get(), get(), get(), get(), get()) }`, `single<WeeklySummaryNotifier> { AndroidWeeklySummaryNotifier(androidContext(), get(), get()) }` and `factory { SetWeeklySummary(get(), get(), get()) }` (or `UiModule`, beside `SetAnalyticsConsent`).
  - `YawnAndPawnApp.onCreate`: `scope.launch { weekly.sync() }` after `prices.start()`.
  - `UnlockSignals.initialiseAfterUnlock()`: `scope.launch { weekly.sync() }` beside `prices.start()`.
  - `SystemEventsReceiver`: inside the same `runWithinBudget` block, **after** `rescheduleAll()` and `rearm.afterSystemEvent()`, call `get<WeeklySummaryScheduler>().sync()` for `BOOT_COMPLETED`, `TIME_SET`, `TIMEZONE_CHANGED` (and `MY_PACKAGE_REPLACED`, harmless). Locked, it returns at once. Its failure is logged and never affects the alarms.
- [ ] Settings:
  - `SettingsViewModel` gains `weeklySummaries: WeeklySummaryStore` and `setWeeklySummary: SetWeeklySummary`. Its `weekly` flow uses `observe().catch { log; emit(default) }.map { it.enabled }`. Fold it with `consent` into one flow (`combine(consent, weekly, ::Pair)`), so the main `combine` stays at 5 flows, and render `weeklySummary`.
  - `WeeklySummaryToggled(on)` goes to `onGeneralIntent`, then `setWeeklySummary(on)`: a failure is logged (`"save weekly summary"`) and the switch follows the store, as usage stats does.
  - `BuiltSettingsRows += SettingsRow.WeeklySummary`; update the KDoc.
  - Update the `UiModule` binding.
- [ ] `data/src/androidHostTest/.../SessionLockGuardScanTest.kt` -- add `"SetWeeklySummary"` (comment: "The weekly summary switch (Story 6.8)") to the expected writer set.

**Execution (money half, needs 6.2 merged):**
- [ ] `weeklySummary(...)`: `paid = moneyPaid(records, MoneyPeriod.ThisWeek, today = weekOf, zone)` (6.2's name for the period). It counts only `isAmountPaid` records with status granted, consumed or reused, sorted by currency code. `text()`'s `Paid` variants then become reachable.
- [ ] Tests marked "(money)" below.

**Acceptance Criteria:**
- Given the switch on (default) and a first launch after unlock, when the app starts, then one unique `weekly-summary` work is ENQUEUED with an initial delay of `nextWeeklySummaryAt(now) − now`.
- Given a run at Sunday 19:00 with 5 on-time days and nothing paid, when it runs, then one notification on channel "Weekly summary" reads "5 on-time mornings, nothing paid. Nice." with the app name as title, auto-cancel, and a tap intent to MainActivity with the Progress extra. The week is stored, and next week's work is chained after the run, which ends SUCCEEDED.
- Given a run more than 24 h after its Sunday 19:00, or a second run for a handled week, or a week with only Test sessions, or no notification permission, when it runs, then nothing is posted and no permission is requested.
- Given the switch, when it is turned off, then the unique work is cancelled at once; when turned on, it is enqueued. During a session the use case changes nothing (`SessionActive`).
- Given `BOOT_COMPLETED`, `TIME_SET` or `TIMEZONE_CHANGED` after unlock, when the receiver runs, then the alarms are re-armed first and the work is then re-aligned to the new local Sunday 19:00.
- Given price refresh and consume retry, when they are enqueued, then their WorkManager policies (KEEP / UPDATE) and behaviour are unchanged (existing `BackgroundWorkWiringTest`, `PriceRefreshSchedulingTest` and ledger tests pass).
- (money) Given EUR 2.99 + USD 1.00 + EUR 5.99 paid this week and 3 on-time days, when it posts, then the text is "3 on-time mornings, €8.98 · $1.00 paid this week." (en-US).

## Test list

**Core (`:core:jvmTest`, test-local builders):**
- `stats/IsoWeekTest`:
  - week ids: 2026-12-28 → 2026-W53, 2027-01-03 → 2026-W53, 2027-01-04 → 2027-W01, 2020-12-31 → 2020-W53, 2021-01-03 → 2020-W53, 2024-12-30 → 2025-W01;
  - `toString` / `parse` round trip ("2027-W01"), and garbage gives null;
  - `monday()`;
  - a seeded property test: for 1,000 dates, `of(d).monday() ≤ d < monday + 7`.
- `stats/WeeklySummaryScheduleTest`:
  - `nextWeeklySummaryAt` for Sat 23:00, Sun 18:59, Sun 19:00 exactly;
  - Berlin spring and autumn DST (the matrix instants);
  - Asia/Ho_Chi_Minh vs America/New_York for 2027-03-07T10:00Z;
  - `lastWeeklySummaryAt` mirrors it (Sun 19:00 exactly → itself);
  - a seeded property test: 1,000 instants × 4 zones (UTC, Europe/Berlin, America/New_York, Asia/Ho_Chi_Minh) give a Sunday 19:00 local strictly after now, less than 8 days ahead.
- `stats/WeeklySummaryDecisionTest` -- the **WorkManager late-run skip rule**:
  - run at Sun 19:00 → Summarise;
  - Mon 18:59 → Summarise;
  - Mon 19:00 exactly (24 h) → Summarise;
  - Mon 19:01 → SkipLate;
  - Tue 08:00 (locked since boot) → SkipLate;
  - week already handled → AlreadyHandled;
  - clock set back so a run fires Sun 18:59 → the previous week is SkipLate or AlreadyHandled, never this week;
  - zone change between scheduling and run → decided in the current zone;
  - Berlin DST Sunday (2027-03-28 and 2027-10-31): 24 h measured in elapsed instants.
  
  `weeklySummaryDue` agrees with the decision.
- `stats/WeeklySummaryTest` (scheduling half):
  - a week with only Test, only Skipped or no sessions → `shouldSend = false`;
  - Missed only → `onTime 0`, `shouldSend true`;
  - two OnTime sessions on one day → 1;
  - OnTime and Snoozed on one day → 0;
  - a session Sunday 23:59 local in the week, Monday 00:00 in the next;
  - a 23:50 Sunday alarm finished Monday 00:10 → Sunday's week;
  - the Berlin DST weeks (2027-03-22..28, 2027-10-25..31) have 7 dates;
  - a zone change moves a 06:30 Monday session into the previous week.
- (money) `stats/WeeklySummaryTest`:
  - EUR 2.99 + USD 1.00 + EUR 5.99 → `[EUR 8.98, USD 1.00]`;
  - stranded excluded;
  - an estimate-only price excluded;
  - a purchase Monday 00:00 counts in the new week.
- `stats/WeeklySummaryTextTest`:
  - `NothingPaid(1)`, `NothingPaid(5)`;
  - `NoOnTime` (0 with nothing paid);
  - (money) `Paid(0, …)`, `Paid(1, …)`, `Paid(3, …)`.
- `summary/WeeklySummarySchedulerTest`:
  - off → cancel;
  - on and not due → Replace at next;
  - due and unhandled → KeepPending at now;
  - due but handled → Replace at next;
  - locked → no call;
  - store read failure → treated as on, logged;
  - work failure → logged and returned;
  - `afterRun` → AfterRun at next, nothing when off;
  - concurrent `sync` calls are serialized.
- `summary/WeeklySummaryTaskTest`:
  - a Sunday 19:00 run posts once, stores the week, and calls `afterRun` last;
  - a duplicate run posts nothing;
  - a late run posts nothing, stores the week, calls `afterRun`;
  - only Test sessions → no post, week stored;
  - `canPost` false → no post, week stored;
  - off → no post, no `afterRun`;
  - a history read failure → logged, no post, `afterRun`, `Failed`;
  - a post failure → logged, week stored.
- `summary/SetWeeklySummaryTest`:
  - on → stored, then `sync` (Replace);
  - off → stored, then cancel;
  - during a session → `SessionActive`, nothing written, no work call;
  - a store write failure → no work call.
- `billing/MoneyFormatterTest`: `formatPerCurrency` sorts by code and joins with " · "; an empty list gives "".

**Data (`:data:testAndroidHostTest`):**
- `settings/DataStoreWeeklySummaryStoreTest`:
  - defaults (on, null);
  - set and read back;
  - `observe` emits on change;
  - an unparsable week reads as null;
  - an IO read error is logged and reads as defaults;
  - `SettingsDataStore.clear()` → defaults (Delete all data).
- `SessionLockGuardScanTest` (with `SetWeeklySummary` added), `PurchaseStateReaderScanTest`, `SessionHistoryWriterScanTest` -- pass.

**androidApp Robolectric (`:androidApp:testDebugUnitTest`):**
- `work/WeeklySummaryWorkTest` (new; `WorkManagerTestInitHelper` + `SynchronousExecutor` + `TestDriver`, `FakeClock`, `FakeTimeZoneProvider`, fake history and records, shadow `NotificationManager`):
  1. App start with default on → `getWorkInfosForUniqueWork("weekly-summary")` has one ENQUEUED with `initialDelayMillis == next − now`.
  2. Delay for the Berlin DST spring weekend (30 h).
  3. A run at Sunday 19:00 (`setInitialDelayMet`) posts "5 on-time mornings, nothing paid. Nice."; singular "1 on-time morning, nothing paid. Nice."; zero on-time "0 on-time mornings this week. Next week's a fresh start."; (money) two currencies "3 on-time mornings, €8.98 · $1.00 paid this week." (en-US locale).
  4. Only Test sessions → nothing posted.
  5. A late run on Tuesday → nothing posted.
  6. A second run, same week → nothing more.
  7. Toggle off (`SetWeeklySummary(false)`) → no ENQUEUED work left.
  8. Notifications denied (shadow `areNotificationsEnabled = false` / permission denied on API 33+) → nothing posted, and the notification permission launcher is never called.
  9. The posted notification:
     - channel `weekly_summary`, name "Weekly summary", `IMPORTANCE_DEFAULT`;
     - title "Yawn & Pawn";
     - small icon set, `color == 0` (no tint);
     - `FLAG_AUTO_CANCEL`;
     - `contentIntent` targets `MainActivity` with `EXTRA_OPEN_TAB = "progress"`, immutable.
  10. After a run: the run is SUCCEEDED (not CANCELLED), and exactly one new ENQUEUED (or BLOCKED → ENQUEUED) work has next week's delay.
  11. An app start inside the due window (Sun 19:30, unhandled) keeps the pending work's id (KEEP).
- `work/BackgroundWorkWiringTest`: every existing test unchanged (price refresh one-time KEEP, periodic UPDATE, consume retry, unknown kind "WeeklySummaryFromTheFuture" still fails). Add: `WeeklySummary` is registered in `BackgroundTasks`.
- `SystemEventsReceiverTest`:
  - `BOOT_COMPLETED`, `TIME_SET`, `TIMEZONE_CHANGED` → `FakeBackgroundWork.weeklySchedules` gets one Replace, after `rescheduleAll`;
  - `LOCKED_BOOT_COMPLETED` → none;
  - a failing weekly sync still re-arms every alarm.
- `RestoreEntryPointsTest`: unchanged assertions on `enqueued` still pass (weekly calls tracked separately).
- `WeeklySummaryOpenTest` (new, or in `MainActivityTest`):
  - MainActivity launched with the extra → the Progress tab is selected (onboarding completed);
  - with an active session → `panel-session-in-progress`;
  - recreation does not reopen Progress;
  - `onNewIntent` with the extra → Progress.
- Settings:
  - `SettingsViewModelTest`: the switch follows the store (default on); a toggle calls the use case; a failure is logged and the switch stays as stored; an unreadable store shows on.
  - `UsageStatsSettingsTest` / `DefaultsSettingsSemanticsTest`: "Weekly summary" is a switch row, ≥ 48 dp, read by TalkBack as "Weekly summary, switch, on".

**Baselines to re-record:**
- Settings main-screen captures rendered with `BuiltSettingsRows`, which now show the "Weekly summary" row in the card with "Share anonymous usage stats":
  - `DefaultsSettingsScreenshotTest` (main-screen states);
  - `SnoozeSettingsScreenshotTest` (main-screen states);
  - `UsageStatsSettingsTest` if it captures.
  
  Sub-screen captures must verify **unchanged**.
- The round 2 preview `settings-top` / `settings-bottom` baselines must verify **unchanged**: the preview already shows the row.
- No notification screenshot is possible in Robolectric. Put the posted text and channel in the PR description; the owner sees the real one in 6.10 item 9.

## Scan tests to respect

- **SessionLockGuardScanTest:**
  - `core.stats` is a full package, so `WeeklySummary.kt` holds pure functions only.
  - The runtime classes live in the new `core.summary` package, where only use cases are scanned.
  - `SetWeeklySummary` does both its store write and `scheduler.sync()` inside `whenIdle`, and is added to the expected set.
  - `WeeklySummaryScheduler` and `WeeklySummaryTask` are not use cases (no `operator fun invoke`). Keep it that way.
- **PurchaseStateReaderScanTest:** money comes only from 6.2's `moneyPaid` over `PurchaseRecord.status` and `priceSource`. Never name `purchaseState` / `PlayPurchaseState`.
- **NoDirectTimeAccess (detekt):**
  - every "now" is `clock.now()` and every zone is `zones.current()` (ports, `FakeClock` in tests);
  - `AndroidBackgroundWork` computes the delay from the injected `Clock`, never `System.currentTimeMillis()`;
  - no `TimeZone.currentSystemDefault()` in core or composeApp.
- **CopyRulesTest:** the six new androidApp strings: no em dash, no currency symbol (`%s` for `{paid}`), no banned word, and "Nice." and "Next week's a fresh start." verbatim.
- Dependency and permission allowlists: unchanged (WorkManager is already allowlisted; `POST_NOTIFICATIONS` and `RECEIVE_BOOT_COMPLETED` are already declared).

## Design Notes

- **Self-cancel and the app-start race:**
  - WorkManager REPLACE cancels a **running** work of the same name. Re-enqueueing with REPLACE from inside the run would cancel the run itself.
  - An app start caused by WorkManager launching the process for the run would cancel the run before it posts.
  - Hence the three policies:
    - the run chains next week with APPEND_OR_REPLACE (WorkManager sets the dependent's enqueue time when it unblocks, so the delay counts from the run's end, a few ms late);
    - `sync()` uses KEEP while a run is due (`weeklySummaryDue`);
    - REPLACE otherwise (time and zone changes, toggle on outside the due window).
- **"Last posted week" stores the last *handled* week** (posted, nothing to send, late, or no permission). It is a superset of the AC's "last posted", so a duplicate run never posts twice, and the due-window check stays exact.
- **On-time mornings = good days** (at least one counted session, all OnTime). This matches the calendar's "On time" days and the word "mornings". Two on-time alarms on one morning count once (DEFAULT TAKEN).
- **"{paid}" joining:** the epic context says `MoneyFormatter.formatTotals` joins with " · ". The code joins with " + " in first-seen order (Success, Purchase history). Epic 6 needs " · " in currency-code order, hence the new `formatPerCurrency`. **6.3 needs the same helper:** whichever story lands first adds it, and the other reuses it.
- **Small icon:** reuse the monochrome `ic_stat_alarm` (no new icon asset, no tint). If the owner wants a distinct glyph, that is a v2 asset (DEFAULT TAKEN).
- **Storage:** both keys live in the existing settings DataStore (`datastore/settings.preferences_pb`, device-protected, already in every backup rule, cleared by Delete all data).
  - No new file, so `BackupRulesTest` and `BackupRulesCoverageTest` need no change.
  - A restore brings back the switch and the last handled week, and the restart's `sync()` re-aligns.
  - After Delete all data, the switch reads on (default) and the next `sync()` (app start or unlock) enqueues if needed.
- **No Notifications section:** EXPERIENCE.md and the approved round 2 Settings put "Weekly summary" in the card with "Share anonymous usage stats". The AC's "Notifications section (created here if Epic 5 did not add it)" is satisfied by that approved card. No new card or title.
- **Tap during onboarding:** the request is dropped, so onboarding is never skipped. Tap during a session: the session lock shows the panel and the request is dropped, never deferred.

## Defaults taken

- The run stores the last handled week (posted or skipped), not only the last posted one.
- On-time count = good days.
- The small icon reuses `ic_stat_alarm`.
- No new Settings section: the approved card.
- A tap while locked or in onboarding is dropped.
- The scheduling half can be built before 6.2; the PR waits for the money half.
- One-shot `SessionHistoryRepository.all()` (from 6.4, else added here identically).

## Risks

- **ColorOS / WorkManager late runs:**
  - Oppo battery rules can delay the work past 24 h. The week is then skipped by design, so a tester may never see a summary.
  - 6.10 item 9 checks real delivery with the clock at Sunday 18:58.
  - If the owner sees misses on the Oppo, the fix is policy (a wider window), not code.
  - WorkManager waits for the first unlock (Story 4.3 device row), so a phone that was locked over Sunday evening misses the week.
- **APPEND_OR_REPLACE semantics:** a chained work fails if its prerequisite fails. The task returns `Done` except on a history read failure, and every app start, unlock and system event repairs the chain through `sync()`. Test 10 pins the SUCCEEDED + ENQUEUED outcome on WorkManager 2.11.
- **Port change:** `BackgroundWork` stops being a `fun interface` and gains two members. Six implementations change. Price refresh and consume retry must be untouched; their existing tests are the guard.
- **Zone change while due:** a zone change inside the due window keeps the pending work (KEEP). If it targets the old zone's Sunday, it runs then and decides in the new zone. The rare result is one week skipped as late. Accept it.
- **Money depends on 6.2:** if 6.2's `moneyPaid` signature differs, adapt the call. Keep the rule (`isAmountPaid`, statuses, per currency, sorted) and the 6.2 tests as the source of truth.
- **Lane collisions:**
  - `SettingsViewModel` and `SystemEventsReceiver` are 6.8-only;
  - `YawnAndPawnApp` / Koin modules and `strings.xml` are shared;
  - `MoneyFormatter.kt` is shared with 6.3;
  - `SessionHistory.kt` is shared with 6.2 and 6.4.
  
  Rebase before review.

## pps-design Done checklist (tick in the story file when built)

- [ ] Only `DESIGN.md` tokens (the existing `SwitchRow` in the approved card; no new UI).
- [ ] Light, Dark and 200% Settings screenshots with the row.
- [ ] Every colour pair is in the `DESIGN.md` contrast table (existing switch row).
- [ ] Touch target ≥ 48 dp (switch row).
- [ ] TalkBack: "Weekly summary, switch, on/off"; the notification is plain text (system UI).
- [ ] Reduced motion: no animation added.
- [ ] Copy verbatim from `EXPERIENCE.md > Voice and Tone > Key strings`: "Weekly summary", the 5 summary texts; no currency symbol in resources.
- [ ] State rows: on, off, save failed (logged, switch follows store), unreadable (shows on).
- [ ] "I'm up" / snooze: not applicable.
- [ ] Previews: round 2 Settings unchanged; production baselines re-recorded.

## Run constraints

- Work in your own worktree (absolute paths). JDK 17: `JAVA_HOME=C:/Users/BuiTua/AppData/Local/Programs/jdk17/jdk-17.0.20.1+1`. Company PC without admin rights; install nothing.
- Other Gradle lanes share this PC. Write all code first, run Gradle only at the end, always with `--no-daemon --max-workers=2`, targeted tasks with quoted test filters (never `qualityGate`).
- Never use a phone or adb. Never commit, push or open a PR unless the coordinator says so. The repo is public: no secrets, personal data or device serials.

## Verification

**Commands (JDK 17, `--no-daemon --max-workers=2`, targeted only):**
- `:core:jvmTest --tests "*stats*" --tests "*summary*" --tests "*billing*" --tests "*work*"`; `:core:koverVerifyStats` -- pass.
- `:data:testAndroidHostTest --tests "*WeeklySummary*" --tests "*ScanTest*" --tests "*DeleteAllData*"` -- pass.
- `:composeApp:testAndroidHostTest --tests "*settings*" --tests "*nav*" --tests "*CopyRulesTest*" --tests "*UiModuleTest*"` -- pass.
- `:androidApp:recordRoborazziDebug --tests "*Settings*"` then `:androidApp:testDebugUnitTest --tests "*WeeklySummary*" --tests "*BackgroundWork*" --tests "*PriceRefresh*" --tests "*SystemEventsReceiver*" --tests "*RestoreEntryPoints*" --tests "*MainActivity*" --tests "*Settings*" --tests "*Preview*" --tests "*Backup*"` -- pass.
- `spotlessCheck detekt` -- pass.

## Spec Change Log

**Contracts as built (real names win).** 6.1, 6.2, 6.3, 6.4, 6.5 and 6.6 are merged (base 3b18f2eb).

| Spec guess | As built | What 6.8 did |
|---|---|---|
| `moneyPaid(records, ThisWeek, today, zone)` | same, already sorted by currency code, amount-paid records only | used as is |
| `SessionHistoryRepository.all()` (6.4 or here) | in 6.4 | used as is |
| `MoneyFormatter.formatPerCurrency` with " · " | not added (coordinator note) | `{paid}` = existing `formatTotals` (" + ", `TOTALS_SEPARATOR`); `moneyPaid` is already sorted by code, so the order is by code. Example: "3 on-time mornings, €8.98 + $1.00 paid this week." The frozen Boundaries line saying " · " is superseded by the coordinator note |
| `isoWeekOf` | in 6.2 (`MoneyStats.kt`) | reused for the week's dates |

**Deviations from the plan above.**
1. **Port:** `scheduleWeeklySummary(delay: Duration, policy: SchedulePolicy)` (no default policy) instead of `(at: Instant, policy = Replace)`. Core computes the delay from its `Clock`, so `AndroidBackgroundWork` takes no `Clock`; its constructor and every existing price refresh / consume retry test are unchanged. `enqueue` now goes through a private `guarded(name) {}` shared with the two new members (same lock check, same failure messages, same KEEP/UPDATE).
2. **Scheduler entry points:** `start()` is added for app start and the unlock signals: once per process, always KEEP (enqueue only when nothing is pending, at once when a run is due). So an app start or unlock never replaces, and a run WorkManager started the process for is never cancelled. `sync()` (receiver and switch) is as specified: REPLACE, or KEEP at delay 0 while a run is due and unhandled. `afterRun()` is APPEND_OR_REPLACE.
3. **Decision:** `AlreadyHandled` when the last handled week is the target week **or the week after** (the clock was set back after a run, so an older week is never summarised again). A last handled week further ahead is ignored (left from a clock set far forward), so summaries resume. The spec had `==`, which would re-post the previous week after a small clock change backwards.
4. **Task result:** always `Done`. A failed run would fail the next week's run chained after it (APPEND_OR_REPLACE fails dependents). A history or records read failure is logged, posts nothing, leaves the week open and still chains. A store read failure posts nothing (the week may be handled) and only chains. The week is stored **before** the post, and the store-and-post step runs `NonCancellable` (review M2), so a run cut short never posts twice and a REPLACE landing mid-step cannot leave the week stored but unposted.
5. **`AppOpenRequests.handled(tab)`** instead of `consume()`: `PurchaseLedgerWriterScanTest` flags any `.consume` call. `openRequested(tab)` (review L4) closes a screen pushed over a tab (checklist, Purchase history, Payments, ...) and then selects the tab; nothing changes under the session lock, onboarding or the editor.
6. **Settings:** `SettingsViewModel` takes a trailing optional `weeklySummary: WeeklySummarySwitch? = null` (store + use case), so the existing ViewModel tests are unchanged; the consent and weekly flows are folded into one pair, so `combine` stays at 5 flows.
7. **Receiver:** re-aligns after `BOOT_COMPLETED`, `TIME_SET`, `TIMEZONE_CHANGED` and `MY_PACKAGE_REPLACED` only (`WEEKLY_SUMMARY_ACTIONS`); not after `LOCKED_BOOT_COMPLETED` or the exact-alarm permission change.
8. **MainActivity:** the extra is ignored (and removed) with saved state and on a launch from Recents (`FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY`).
9. **Notifier:** platform `Notification.Builder` (as `WakeNotifier`); a refused post is a `DomainError.BackgroundWorkFailure` (no new error type). Request code and notification id 1_015.
10. **Tests:** the spec's per-text WorkManager runs are one full run per case plus direct `post()` checks of the other texts through the real Android strings. The tap during a session or onboarding is covered both by pure stack tests and by Robolectric `WeeklySummaryOpenTest` cases (review M3).

**Strings** (androidApp, verbatim EXPERIENCE.md Key strings): `notification_channel_weekly_summary`, `weekly_summary_nothing_paid`, `weekly_summary_nothing_paid_one`, `weekly_summary_paid`, `weekly_summary_paid_one`, `weekly_summary_none`. No new copy.

**Baselines re-recorded** (main Settings screen now shows the "Weekly summary" switch): `settings_defaults_light`, `settings_defaults_dark`, `settings_defaults_changed_light`, `settings_snooze_light`, `settings_snooze_dark`, `settings_usage_stats_{off,on}_{light,dark,light_font200}`. Sub-screens and the round 2 preview baselines verify unchanged.

## Review Triage Log

One combined reviewer (adversarial, edge cases, spec compliance), read only, 2026-10-26: 1 HIGH, 2 MEDIUM, 4 LOW.

| ID | Finding | Decision |
|---|---|---|
| H1 | `SystemEventsReceiverTest`: `BOOT_COMPLETED`'s unlock signal also starts the bound scheduler (an extra KEEP), so "3 REPLACE" failed | Fixed: the test checks the REPLACE calls (and the alarms armed at each) |
| M1 | Storing the week before posting: a `sync()` REPLACE cancelling the running work mid-step could leave the week stored but never posted | Fixed: store and post run `NonCancellable` |
| M2 | No Robolectric case for a tap during a session or onboarding; the effect's order after `shownStack` was only implicit | Fixed: two `WeeklySummaryOpenTest` cases, and a comment in `AppNavHost` pinning the order |
| L1 | A tap with a pushed screen on top (checklist, Purchase history, ...) did nothing | Fixed: `openRequested` closes the pushed screen, then selects Progress; the editor still keeps its draft |
| L2 | `start()` is once per process: after "Delete all data" with the switch off, the switch reads on but nothing is enqueued until the next process start or system event | Deferred (risk noted): a fix touches the money-sensitive `DeleteAllData`; the next app start, boot, time or zone change re-aligns |
| L3 | The weekly sync runs inside the alarms' receiver budget, so a slow WorkManager call is logged as "reschedule alarms took longer" | Deferred: the alarms are re-armed first and a single `goAsync` budget covers both; log wording only |
| L4 | A test comment said "clock set back" for a later run | Fixed |

## Coordinator note (2026-10-26): currency separator
Several currencies are always joined with " + ", using the existing `TOTALS_SEPARATOR` and EXPERIENCE.md "Money, mixed currencies", and sorted by currency code. For example: "€3.27 + $2.18 paid this week". Do not add a " · " variant. If a sorted helper is needed, the first story to land adds `formatPerCurrency` with " + ", and the others reuse it.
