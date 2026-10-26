---
title: 'Story 6.4: Zero-snooze celebration on the Success screen'
type: 'feature'
created: '2026-10-26'
status: 'review'
baseline_commit: '0d85be9e95e8072ac9762db78b628814435f55d7'
route: 'dispatch'
story_key: '6-4-zero-snooze-celebration-on-the-success-screen'
review_loop_iteration: 1
depends_on:
  - '6-1-streaks-on-time-rate-and-time-to-up-in-core-stats (merged: `currentStreak` in core/.../stats/WakeStats.kt)'
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-6-context.md'
  - '{project-root}/.claude/skills/pps-design/SKILL.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-6-1-streaks-on-time-rate-and-time-to-up-in-core-stats.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-3-3-success-screen-after-the-check-basic.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-5-2-bright-wake-screen.md'
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** the Success screen already has the approved celebration (count-up, bounce, 1.5 s confetti; `ui/wake/Celebration.kt`, round 1 items 19 and 20), but production passes `SuccessKind.OnTime(streakDays = 0)` with `basic = true`, so a zero-snooze morning only says "Up on time." The streak never shows, and a paid morning and an on-time morning look almost the same (FR-MSG-3, FR-PRG-2, UX-DR71).

**Approach:** read `session_history` once per session, after the wake screen's first frame (NFR-7), and keep it in memory in a small core holder (`CelebrationStreaks`). When Success shows a real zero-snooze session, a pure rule computes `streakBefore = currentStreak(snapshot without this session)` and `streakAfter = currentStreak(snapshot + this session as OnTime)` with the same `today` and zone (Story 6.1). Success shows `streakAfter`, then "days in a row", then "Up on time.", and animates only when the streak grew and only once per session. If the snapshot could not be loaded, it shows "You're up. That's what counts." with no number. Paid and test mornings are unchanged.

## Boundaries & Constraints

**Always:**
- The streak uses Story 6.1's `currentStreak(history, today, zone)` and nothing else. `today` and the zone come from `Clock` and `TimeZoneProvider` when Success first shows, and both values use the same `today`.
- The history read happens after the first frame of a wake screen that shows an ongoing session (Ring states). It never happens in `WakeService` before or during `startForeground`, and the first frame never waits for it.
- At most one read per session id (idempotent). The holder keeps the snapshot of the latest session only.
- The Success decision is made once per session, at its first composition. Recomposition, rotation and recreation show the same decision. The count-up and confetti play at most once per session (a claim saved like the existing `claimHaptic`).
- There is exactly one success haptic per session for every kind (on time, after a snooze, test, unknown). With reduced motion, the final state shows at once and the haptic still fires.
- Success is at the phone's own brightness (Story 5.2 option C, already `brightness.show(full = false)`). `FLAG_KEEP_SCREEN_ON` is cleared while Success shows and added again if a new ring replaces it.
- Strings: only existing resources (`success_zero_snooze_first`, `success_days_in_a_row`, `success_day_in_a_row`, `success_after_snooze`, `success_paid_this_morning`, `success_test`, `success_done`). No new string.

**Never:** no stored streak or derived stat (AD-18); no write to history (`SessionRecorder` stays the only writer); no loading state or spinner on Success; no wrong streak (fallback instead); no change to the paid and test paths; no animation on Home; no new dependency, token, component or permission; no `app.db` migration.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior |
|----------|--------------|---------------------------|
| Streak grows | snapshot: 11 good days ending yesterday; this session 0 snoozes, real | "12" (count-up from 11, bounce), "days in a row", "Up on time.", ~1.5 s confetti, one haptic, done within 3 s |
| First ever | empty history; 0 snoozes | "1" (count-up from 0), "day in a row", "Up on time.", confetti |
| Same day again | today already good (an earlier OnTime session today) | "{n}", "days in a row", "Up on time.", no count-up, no confetti, still one success haptic |
| Same day after a paid morning | an earlier Snoozed or Missed session today, so `streakAfter == 0` | "Up on time." only (no number, no animation) |
| 23:50 alarm done 00:10 | `scheduledAt` yesterday 23:50, Success at 00:10 | the session belongs to yesterday; `today` = the new date; the streak counts as usual |
| Snapshot already has this session | the start row (outcome null) or the end row was written before the read | that row is dropped (matched by `sessionId`) before both computations |
| Read in flight | Success shows before the read returns | "Up on time." only (no number, no animation), logged; never flips later |
| Read failed / never requested | read error, or the process was restored with no wake frame since | "You're up. That's what counts.", no number, no animation, one haptic, logged through `Logger` |
| After snooze | `snoozesGranted > 0` | unchanged (Story 4.15): "You're up. That's what counts." + "{paid} paid this morning", no animation |
| Test | `config.testMode` | unchanged: "Test finished. Your alarm works.", no streak, no read |
| Recreated / rotated | Success recreated for the same session | same text, final state, no confetti, no second haptic |
| Reduced motion | animator scale 0 | final state at once, no count-up, bounce or confetti; the haptic fires |
| New ring over Success | another alarm rings while Success shows | ringing screen as today, `FLAG_KEEP_SCREEN_ON` set again |

</frozen-after-approval>

## Code Map

- `androidApp/.../android/wake/WakeActivity.kt` -- `setContent` block (around lines 300-345: `session`, `current`, `brightness.show`), `SuccessShown(success)` (line ~352: `brightness.show(full = false)`, `successUiState(...)`, `basic = true`, `claimHaptic`), `showOverLockScreen()` adds `FLAG_KEEP_SCREEN_ON` once in `onCreate`; `SnoozedClose` clears it.
- `androidApp/.../android/wake/WakeScreenEnd.kt` -- per-session UI state saved in a Bundle (`claimHaptic`, `hapticPlayedFor`, `KEY_HAPTIC`). Add the celebration claim the same way.
- `androidApp/.../android/wake/WakeService.kt` -- **not changed.** It only shows that `AlarmFired`, `TestAlarmFired` and the restore paths run before any screen exists (see Design Notes, "Where the read starts").
- `composeApp/.../ui/wake/SuccessMapping.kt` -- `successUiState(session, pendingStillListed)`, `knownPaid`.
- `composeApp/.../ui/wake/WakeContract.kt` -- `SuccessKind.OnTime(streakDays)`, `AfterSnooze`, `Test`, `SuccessUiState`.
- `composeApp/.../ui/wake/Celebration.kt` -- `rememberCelebration()` (plays the haptic itself today), `StreakCount` (counts from `streakDays - 1`), `Confetti`.
- `composeApp/.../ui/wake/WakeEndScreens.kt` -- `SuccessScreen(state, onIntent, modifier, basic, claimHaptic)`. `basic` turns the celebration off today and makes "Done" the 72 dp hero. `SuccessHaptic(claim)` and `Headline`.
- `core/.../history/SessionHistory.kt` -- `SessionHistoryRepository` (no read of all rows yet; 6.2 adds `observeAll()`).
- `data/.../history/SessionHistoryDao.kt`, `RoomSessionHistoryRepository.kt` -- the Room adapter.
- `testing/.../FakeSessionHistoryRepository.kt`; core doubles `core/src/commonTest/.../session/SessionEngineTestDoubles.kt` (`InMemoryHistory`) and `core/src/commonTest/.../history/MissedNotesTest.kt` (anonymous object); `androidApp/src/test/.../wake/WakeActivityTest.kt` (`CountingHistory`, line ~479).
- `core/.../stats/WakeStats.kt` (Story 6.1) -- `sessionDay`, `isCounted`, `currentStreak(history: List<SessionHistoryRow>, today: LocalDate, zone: TimeZone): Int`.
- `androidApp/.../android/wake/WakeModule.kt` -- `wakeModule()` Koin singles. `ApplicationScope` is already injected in `WakeActivity` (`appScope`).
- Debug previews: `androidApp/src/debug/.../preview/PreviewSamples.kt` (lines 315, 317), `PreviewWakeFlow.kt` (line 178), `PreviewCatalog.kt` (line 114).

## Tasks & Acceptance

**Execution:**

- [x] `core/src/commonMain/kotlin/com/yawnandpawn/app/core/history/SessionHistory.kt` -- add a one-shot read to the port:
  ```kotlin
  /** Every row, in no particular order (Epic 6 stats; one read). A read only; a storage error is a StorageFailure. */
  suspend fun all(): Outcome<List<SessionHistoryRow>, DomainError>
  ```
  If 6.2 has merged with `observeAll()`, still add `all()`. Story 6.8's worker uses it too. Do not build on `observeAll().first()`, because a flow error is thrown, not returned.
- [x] `data/.../history/SessionHistoryDao.kt` -- `@Query("SELECT * FROM session_history") abstract suspend fun allRows(): List<SessionHistoryEntity>`; `RoomSessionHistoryRepository.all()` maps the rows with the same `StorageFailure` boundary as `find`.
- [x] `testing/.../FakeSessionHistoryRepository.kt` -- `all()` over `rows`, plus `var failAll: DomainError? = null`, `val allCalls: Int`, and `var allGate: CompletableDeferred<Unit>? = null` (suspends `all()` until completed, for the first-frame test). Add `all()` to `InMemoryHistory`, the `MissedNotesTest` object and `WakeActivityTest.CountingHistory` (counted in a separate `allCalls`, not in `calls`).
- [x] `core/src/commonMain/kotlin/com/yawnandpawn/app/core/stats/SuccessStreak.kt` (new) -- the pure rule:
  ```kotlin
  /** What the zero-snooze Success shows (Story 6.4). */
  sealed interface SuccessStreak {
      /** The streak before and after this session, from a loaded snapshot. after == 0: "Up on time." only. */
      data class Known(val before: Int, val after: Int) : SuccessStreak { val grew: Boolean get() = after > before }
      /** The snapshot was still loading: "Up on time." only, no number (never a wrong one). */
      data object Pending : SuccessStreak
      /** No snapshot (read failed, or never requested in this process): "You're up. That's what counts." */
      data object Unknown : SuccessStreak
      companion object { val NONE = Known(before = 0, after = 0) }
  }

  /**
   * [snapshot] without any row of [sessionId], then with this session added as OnTime (a row built from [alarmId],
   * [scheduledAt], 0 snoozes): currentStreak of each, with the same [today] and [zone] (Story 6.1).
   */
  fun successStreak(snapshot: List<SessionHistoryRow>, sessionId: String, alarmId: String, scheduledAt: Instant, today: LocalDate, zone: TimeZone): SuccessStreak.Known
  ```
- [x] `core/src/commonMain/kotlin/com/yawnandpawn/app/core/stats/CelebrationStreaks.kt` (new) -- the in-memory holder (core, reads only, so it passes the `stats` full-package lock scan):
  ```kotlin
  class CelebrationStreaks(
      private val history: SessionHistoryRepository,
      private val clock: Clock,
      private val zones: TimeZoneProvider,
      private val logger: Logger,
  ) {
      /** Reads history once for [sessionId] (later calls for the same id do nothing); keeps only the latest session's snapshot. */
      suspend fun prepare(sessionId: String)
      /** The decision for the completed zero-snooze real [session]; made once per session id and then kept (memo). Logs Pending/Unknown once. */
      fun streakFor(session: SessionData): SuccessStreak
  }
  ```
  The state is `MutableStateFlow<Snapshot>`, where `Snapshot` is `NotRequested`, `Loading(id)`, `Loaded(id, rows)` or `Failed(id, error)`, and `MutableStateFlow<Pair<String, SuccessStreak>?>` holds the memo. Atomic `update {}` keeps it thread-safe. `prepare` runs off the main thread. `streakFor` is synchronous: it reads `clock.now()` and `zones.current()` and never suspends. Log `LogEvent.OperationFailed("load celebration streak", "<not loaded | still loading | diagnostic>")`.
- [x] `composeApp/.../ui/wake/WakeContract.kt` -- `SuccessKind.OnTime(val streakDays: Int, val countFrom: Int?)`, where `countFrom` non-null means count up from it with confetti and null means a still final state. Add `data object UpNoStreak : SuccessKind`, which reads "You're up. That's what counts." with no paid line.
- [x] `composeApp/.../ui/wake/SuccessMapping.kt` --
  ```kotlin
  fun successUiState(session: SessionData, pendingStillListed: Boolean = false, streak: SuccessStreak = SuccessStreak.NONE, celebrate: Boolean = true): SuccessUiState
  ```
  It maps: test → `Test`; snoozes > 0 → `AfterSnooze` (unchanged); `Unknown` → `UpNoStreak`; `Pending` or `Known(after = 0)` → `OnTime(0, null)`; `Known(after > 0)` → `OnTime(after, countFrom = before.takeIf { grew && celebrate })`. The `pendingNotUsed` rule is unchanged.
- [x] `composeApp/.../ui/wake/Celebration.kt` -- `rememberCelebration(animate: Boolean)` starts at 1f when `!animate` or reduced motion, and **no longer plays the haptic**. `StreakCount(streakDays, countFrom, celebration)` shows `countFrom` before `COUNT_AT`, then `streakDays`. With `countFrom == null` the celebration is never running, so there is no bounce and no confetti. TalkBack reads the final number only, as today.
- [x] `composeApp/.../ui/wake/WakeEndScreens.kt` `SuccessScreen` --
  - `basic` no longer turns the celebration off. It now only means the production "Done" (72 dp hero); keep the name to limit churn and update its KDoc.
  - `SuccessHaptic(claimHaptic)` always runs, for every kind (one haptic per session).
  - The celebration is created only for `OnTime` with `streakDays > 0`, as `rememberCelebration(animate = countFrom != null)`. `Confetti` is drawn only while it runs.
  - The `UpNoStreak` branch shows `Headline(success_after_snooze)`.
  - TalkBack order: the card `Column` gets `semantics { isTraversalGroup = true }` and the headline gets `traversalIndex = -1f` (the onboarding `HEADLINE_TRAVERSAL_INDEX` pattern). Initial focus is the headline, then "{n} days in a row", then "Done".
- [x] `androidApp/.../wake/WakeScreenEnd.kt` -- `fun claimCelebration(sessionId: String): Boolean`, true once per session. Save it in the Bundle (`KEY_CELEBRATED`) like `KEY_HAPTIC`.
- [x] `androidApp/.../wake/WakeActivity.kt` --
  - Inject `CelebrationStreaks`.
  - Start the read: in the non-Success branch, when `session != null` (an ongoing session is shown) and `!session.config.testMode`, run `LaunchedEffect(session.sessionId) { withFrameNanos { }; appScope.launch { celebrations.prepare(session.sessionId) } }`. That is after the first drawn frame, on the app scope so it outlives the activity, never awaited.
  - In `SuccessShown`: `val streak = remember(success.sessionId) { if (zero-snooze real) celebrations.streakFor(success) else SuccessStreak.NONE }`; `val celebrate = remember(success.sessionId) { end.claimCelebration(success.sessionId) }`; then `successUiState(success, pendingStillListed = …, streak = streak, celebrate = celebrate)`, still with `basic = true`.
  - Keep-screen-on: `SideEffect { window.clearFlags(FLAG_KEEP_SCREEN_ON) }` in `SuccessShown`, and `SideEffect { window.addFlags(FLAG_KEEP_SCREEN_ON) }` in the other branch, so a ring that replaces Success keeps the screen on.
- [x] `androidApp/.../wake/WakeModule.kt` (or `appModule`, wherever `SessionHistoryRepository` is reachable) -- `single { CelebrationStreaks(get(), get(), get(), get()) }`. Check that `UiModuleTest` / Koin `verify` style tests still pass.
- [x] Debug previews -- `PreviewSamples.successOnTime = SuccessUiState(SuccessKind.OnTime(streakDays = 12, countFrom = 11))`, `successFirst = OnTime(0, null)`, `PreviewWakeFlow` `OnTime(STREAK, countFrom = STREAK - 1)`. Preview pixels stay the same.
- [x] `_bmad-output/planning-artifacts/epics.md` (Q4 default, owner-approved; no correct-course) -- the brightness wording edit, done in this PR before any code:
  - Story 6.4 AC (line ~3340): "**Then** the screen closes, the screen brightness set by the Epic 5 "Bright wake screen" setting is restored, and `FLAG_KEEP_SCREEN_ON` is cleared…" → "**Then** the screen closes; Success shows at the phone's own brightness (Story 5.2 option C: "Bright wake screen" covers only the ringing screen), and `FLAG_KEEP_SCREEN_ON` is cleared so the system screen timeout applies while Success is visible".
  - Story 6.10 item 5 (line ~3539): "5. "Done" on Success restores the screen brightness set before the alarm." → "5. Success shows at the phone's own brightness (Bright wake screen raises only the ringing screen), and "Done" closes it."
  - Optional, for consistency: UX-DR87 (line ~357), from "→ Done restores brightness." to "→ Success at the phone's own brightness."

**Acceptance Criteria:**
- Given a real zero-snooze session after 11 good days, when Success shows, then "12", "days in a row" and "Up on time." show, the number counts up from 11 with the bounce and ~1.5 s of confetti, one success haptic fires, and all of it ends within 3 s.
- Given a second on-time session on a day that is already good, when Success shows, then the same text shows with no count-up, no confetti and one haptic only.
- Given the wake screen for a ringing session, when its first frame is drawn, then no history read happened before it, and exactly one `all()` follows for that session id.
- Given a read error, or no snapshot requested in this process, when a zero-snooze session completes, then Success shows "You're up. That's what counts." with no number and no animation, and the failure is logged.
- Given Success shown with the celebration, when the activity is recreated or rotated, then the final state shows with no second confetti or haptic.
- Given animator duration scale 0, when the celebration would play, then the final state shows at once and the haptic fires.
- Given Success, when it shows, then the brightness is the phone's own, `FLAG_KEEP_SCREEN_ON` is cleared, Back does nothing, and TalkBack's first focus is the headline, then the streak, then "Done".
- Given after-snooze and test sessions, when Success shows, then the Story 3.3 and 4.15 screens are unchanged (their baselines verify unchanged).

## Test list

**Core (`:core:jvmTest`, test-local builders; `:core` cannot use `:testing`):**
- `core/src/commonTest/.../stats/SuccessStreakTest.kt`:
  - grows 11 → 12;
  - first ever 0 → 1;
  - same day already good → `Known(n, n)`, so `grew` is false;
  - earlier Snoozed today → `after = 0`;
  - earlier Missed today → `after = 0`;
  - the snapshot holding this session's start row (null outcome) or its OnTime end row gives the same result as without it;
  - Test and Skipped rows change nothing;
  - 23:50 scheduled, `today` the next date → counted on the earlier day;
  - Europe/Berlin across 2027-03-28 (DST) → one day;
  - zone change Asia/Ho_Chi_Minh → Europe/London moves a 06:30 session to the previous date (same rule as 6.1).
- `core/src/commonTest/.../stats/CelebrationStreaksTest.kt` (with `InMemoryHistory` or a core double plus a fixed clock and zone):
  - `prepare` twice for one id → one read;
  - a new id → a new read, and the old snapshot is dropped;
  - `streakFor` covers Loaded → Known, a read in flight (gated) → Pending, failed → Unknown and logged, never requested → Unknown and logged;
  - the decision is memoized: a later load does not change it;
  - test and snoozed sessions are not asked;
  - the logs come once per session.

**Data (`:data:testAndroidHostTest`):**
- `RoomSessionHistoryRepositoryTest`: `all()` returns every row; an empty table gives an empty list; a closed or broken database gives `StorageFailure`.
- `SessionHistoryWriterScanTest` and `SessionLockGuardScanTest` pass unchanged (reads only; nothing to add).

**composeApp (`:composeApp:testAndroidHostTest`):**
- `SuccessMappingTest`: every `SuccessStreak` × `celebrate` mapping; the test and snooze paths ignore the streak; `NONE` keeps today's results, so existing assertions pass with `OnTime(0, null)`.
- `CopyRulesTest` passes (no new string; the emoji rule already limits emoji to `success_zero_snooze`).

**androidApp Robolectric (`:androidApp:testDebugUnitTest`):**
- `WakeActivityTest`:
  - (new) "the history read starts only after the ringing screen's first frame": `allCalls == 0` after the session rings and the app scope is idle, before the activity launches. Then, with `allGate` held, "I'm up" exists while `all()` is still suspended, and `allCalls == 1` afterwards. Update the existing "no repository or session store call happens before the first frame" test to count `all()` separately.
  - (new) a real zero-snooze completion with a fake history of 11 good days shows "12", "days in a row" and "Up on time." (wait for `allCalls == 1` before tapping).
  - (new) a read failure (`failAll`) shows "You're up. That's what counts." and logs `load celebration streak`.
- `WakeActivitySuccessTest`:
  - recreation keeps the decision and does not replay (claim saved): exactly one haptic and the final number;
  - Success clears `FLAG_KEEP_SCREEN_ON` (`shadowOf(window).getFlag`), and a new ring sets it again;
  - Back does nothing.
- `WakeBrightnessTest`: Success stays at the phone's own brightness (existing; re-run).
- `SuccessScreenTest`:
  - TalkBack order is headline, streak, "Done" for `OnTime(12, 11)`;
  - one haptic for each kind, including `UpNoStreak`;
  - reduced motion (animator scale 0) shows the final number at once with the haptic;
  - `OnTime(5, null)` is the same picture at 32 ms and 1 s (no animation).
- `SuccessScreenshotTest` (Sunrise, 100% and 200%), all new baselines:
  - `wake_success_streak_grow_start_sunrise` (count-up start, 11)
  - `wake_success_streak_grow_end_sunrise` (12, calm)
  - `wake_success_streak_same_day_sunrise`
  - `wake_success_streak_first_sunrise` ("1", "day in a row")
  - `wake_success_snapshot_missing_sunrise`
  - each also as `_font200`
- Existing tests that complete a real on-time session and assert "Up on time." (`QrCheckScreenTest`, `MathCheckScreenTest`, `MemoryWakeCheckTest`, `WordWakeCheckTest`, `OneTimeAlarmFlowTest`, `SnoozedScreenTest`, `WakeActivitySuccessTest`, `WakeActivityTest`, `WakeBrightnessTest`, `CheckSemanticsSuiteTest`): they still pass, because every non-Unknown on-time variant contains "Up on time." Re-run them all. Any test that reaches Success with no wake frame for the session must call `celebrations.prepare(id)` or expect "You're up. That's what counts."

**Baselines to re-record:**
- Changed: `a11y_success_on_time_w360_h640_font200` (`CheckFontScaleTest` "Success on time with a streak": `basic = true` no longer hides the number; update its KDoc and `OnTime(12, null)`).
- New: the 10 `wake_success_streak_*` / `wake_success_snapshot_missing_*` above.
- Must verify **unchanged** (regression signal):
  - `wake_success_on_time_sunrise(_font200)`
  - `wake_success_after_snooze*`
  - `wake_success_after_snooze_paid*`
  - `wake_success_pending_not_used*`
  - `wake_success_test*`
  - `a11y_success_w360_h640_font200`
  - preview `success_*` (`SuccessCelebrationTest` with `countFrom = 11`)

## Scan tests to respect

- **SessionLockGuardScanTest:** `core.stats` is a full package. `CelebrationStreaks` and `SuccessStreak` only read (`history.all()`; `all` is not a write verb). No entry is needed.
- **PurchaseStateReaderScanTest:** nothing here names a purchase state. The Success paid line keeps `knownPaid`.
- **NoDirectTimeAccess (detekt):** `today` and the zone come only from the injected `Clock` and `TimeZoneProvider` (`clock.now().toLocalDateTime(zones.current()).date`). No `Clock.System`, `TimeZone.currentSystemDefault()` or `System.currentTimeMillis()` outside `com.yawnandpawn.app.android`. The frame wait uses Compose's `withFrameNanos`, not a clock.
- **SessionHistoryWriterScanTest:** reads only.

## Design Notes

- **Where the read starts (DEFAULT TAKEN, owner can change):** the epic text says "`WakeService` loads … after the ringing screen is shown." The service cannot know when a frame is drawn, and a read launched from `dispatchAlarmFired` would run while the activity draws its first frame. So the trigger is the wake screen's own first drawn frame for an ongoing session, and the work runs on the app scope in a core holder. `WakeService` is not changed.
  - This covers `AlarmFired`.
  - It covers `ProcessRestored`: the recreated wake screen's first frame for the restored session, ringing or in its check.
  - It skips `TestAlarmFired`, because a test never shows a streak, so no read is needed.
  - A real alarm that preempts a test is a new session, and its screen triggers its own read.
- **`before` is computed at Success, not at load:** the AC writes `streakBefore = currentStreak(snapshot, today, zone)` at load time. Computing both values at Success with one `today` gives the same number unless midnight passes during the session. In that case the load-time value would compare streaks on two different days and could animate wrongly.
- **Pending (read still in flight) shows "Up on time." with no number (DEFAULT TAKEN):** this is the Story 3.3 "before streaks" string. It is truthful, never a loading state, and never wrong. The AC's fallback "You're up. That's what counts." stays for a failed or absent snapshot, as written.
- **`streakAfter == 0`** (an earlier Snoozed or Missed session the same day) shows "Up on time." only, never "0 days in a row".
- **One haptic for all kinds:** EXPERIENCE.md has "success pattern on completion" and UX-DR71 "one success haptic". The celebration haptic and the standard success haptic are the same single `Confirm` per session (`claimHaptic`). The paid path therefore keeps exactly the standard pattern.
- **"Done" stays the 72 dp `button-wake-primary` of Story 3.3** (`basic = true` in production). The round 1 preview's 64 dp "Done" stays preview-only.
- **Snapshot staleness:** the snapshot is taken at the first ring. Only one session runs at a time, so the only row it can miss is the end row of a session that ended seconds before this ring (its async `recordEnd`). See Risks.

## Defaults taken

- Q4 (epic context): bright wake is option C, so Success is at the phone's own brightness. The `epics.md` edit above is in this PR.
- The one-shot `SessionHistoryRepository.all()` is added here (works with only 6.1 merged). 6.2's `observeAll()` is independent.
- The read is triggered by the wake screen's first frame, not by `WakeService`. Test sessions do not read.
- Pending shows "Up on time." with no number. Failed or never requested shows "You're up. That's what counts."
- `streakAfter == 0` shows "Up on time." only.
- `basic` keeps its name and now only sets the "Done" size.

## Risks

- **NFR-7:**
  - A history read that contends with the first frame would slow the ring screen. The read is gated on `withFrameNanos` after composition and runs on the app scope.
  - The new WakeActivityTest proves that `all()` is not called before the first frame.
  - `PriceRefreshAtSessionStartTest` and the existing first-frame test must stay green.
- **Test flakiness:** Success decisions in Robolectric depend on whether `prepare` finished. Tests that assert a number must wait for `allCalls == 1` before tapping "I'm up". Tests asserting "Up on time." pass for Known and Pending alike.
- **Back-to-back sessions:** if a Snoozed session ended seconds before this ring and its end row is not yet written, the snapshot sees it as running (ignored), and a same-day streak could show one day too high. This is very rare (two alarms of the same morning within seconds). Accept it and note it for 6.10 item 2.
- **Screenshot churn:** one existing baseline changes (`a11y_success_on_time…`). Everything else must verify unchanged; a diff there means the wrong path changed.
- **Lane collisions:** `WakeActivity`, `SuccessMapping` and `WakeEndScreens` are 6.4-only in Epic 6. `SessionHistory.kt` and `FakeSessionHistoryRepository` are also touched by 6.2 (`observeAll()`). Rebase before review; both additions are independent.

## pps-design Done checklist (tick in the story file when built)

- [x] Only `DESIGN.md` tokens (the approved round 1 celebration; no new token, radius or size).
- [x] Sunrise checked with screenshots at 100% and 200% (Light/Dark not relevant: wake screens are always Sunrise).
- [x] Every colour pair is in the `DESIGN.md` contrast table (`accent-text` number on glass, as in 5.11).
- [x] Touch targets ≥ 48 dp ("Done" 72 dp).
- [x] 200% font scale and TalkBack: headline first, then "{n} days in a row", then "Done"; the number is read as the final value.
- [x] Reduced motion: final state at once, haptic kept.
- [x] Copy matches `EXPERIENCE.md > Voice and Tone` verbatim: "Up on time.", "days in a row", "day in a row", "You're up. That's what counts.", "{paid} paid this morning", "Test finished. Your alarm works.", "Done". No new string.
- [x] State rows: grows, same day, first, after `streakAfter == 0`, pending, unknown, after snooze, test.
- [x] "I'm up" / snooze: not changed.
- [x] Previews keep their pixels; new Roborazzi baselines recorded.

## Run constraints

- Work in your own worktree (absolute paths). JDK 17: `JAVA_HOME=C:/Users/BuiTua/AppData/Local/Programs/jdk17/jdk-17.0.20.1+1`. Company PC without admin rights; install nothing.
- Other Gradle lanes share this PC. Write all code first, then run Gradle at the end, always with `--no-daemon --max-workers=2` and targeted tasks with quoted test filters (never `qualityGate`).
- Never use a phone or adb. Never commit, push or open a PR unless the coordinator says so. The repo is public: no secrets, personal data or device serials.

## Verification

**Commands (JDK 17, `--no-daemon --max-workers=2`, targeted only):**
- `:core:jvmTest --tests "*stats*" --tests "*history*" --tests "*session*"` -- pass; `:core:koverVerifyStats` (from 6.1) -- pass.
- `:data:testAndroidHostTest --tests "*SessionHistory*" --tests "*ScanTest*"` -- pass.
- `:composeApp:testAndroidHostTest --tests "*wake*" --tests "*CopyRulesTest*" --tests "*UiModuleTest*"` -- pass.
- `:androidApp:recordRoborazziDebug --tests "*Success*" --tests "*CheckFontScaleTest*"` then `:androidApp:testDebugUnitTest --tests "*Success*" --tests "*WakeActivity*" --tests "*WakeBrightness*" --tests "*CheckScreenTest*" --tests "*WakeCheckTest*" --tests "*OneTimeAlarmFlow*" --tests "*SnoozedScreen*" --tests "*CheckFontScale*" --tests "*CheckSemanticsSuite*" --tests "*PriceRefreshAtSessionStart*" --tests "*Preview*"` -- pass.
- `spotlessCheck detekt` -- pass.

## Spec Change Log

Implementation notes (2026-10-26, fast mode; no change to the frozen intent):
- `CelebrationStreaks.streakFor` returns `SuccessStreak.NONE` for a test or snoozed session without keeping or logging it, so a wrong caller cannot spoil the real decision. `WakeActivity` asks it for every Success; the mapping ignores the streak on those paths anyway.
- `prepare` maps a thrown exception (not only a returned `StorageFailure`) to Failed, so the snapshot never stays Loading. `streakFor` maps any exception while deciding to Unknown and logs it (review item 2).
- The read waits for two `withFrameNanos` calls instead of one: the first one resumes inside the first frame, before it is drawn (review item 1).
- New `CelebrationStreaks.request(id)` (memory only, no I/O), called by the wake screen as soon as it shows a real session, before the frame waits. A session that completes before the read starts (a restored Loud placeholder answered at once) then shows "Up on time." (Pending, logged "not started"), not the missing-snapshot fallback; the I/O matrix's "never requested" row keeps meaning no wake screen showed the session in this process. Found by `WakeActivityTest` "opened on a restored loud placeholder step…" after the two-frame wait.
- Rebased onto 6.2 (#83): `ProgressTestDoubles.MutableHistory` gains `all()` next to 6.2's `observeAll()`; every other double already had both after the rebase.
- The keep-screen-on flag is changed only when it differs (`keepScreenOn(on)`), because a `SideEffect` runs on every recomposition of the ringing screen. It is not set again on a finishing screen or on Snoozed, which clears it itself.
- Koin: `CelebrationStreaks` is in a small `celebrationModule()` that `wakeModule()` includes (detekt `LongMethod` on `wakeModule`). The read and keep-screen-on effects sit in `CelebrationAndScreenOn` (detekt complexity on `onCreate`).
- `FakeSessionHistoryRepository.allCalls` is backed by a `MutableStateFlow`, so a test thread polling it sees the count from the app scope's thread.
- An extra test checks that a test session never reads history. `CheckSemanticsSuiteTest` gains an `UpNoStreak` variant.

## Review Triage Log

Pass 1 (one combined reviewer focused on the wake path; fast mode). No HIGH findings.

| # | Severity | Finding | Verdict | Evidence / route |
|---|----------|---------|---------|------------------|
| 1 | medium | `withFrameNanos {}` resumes inside the first frame before it is drawn, so the Room read could compete with the first draw (NFR-7) | patched | `CelebrationRead` now waits for two frame callbacks before it launches the read on the app scope. |
| 2 | low | `streakFor` runs in composition on the main thread; anything thrown while deciding (zone, date maths) would crash Success | patched | `decideOnce` catches it, returns Unknown ("You're up. That's what counts.") and logs `load celebration streak`. Test: `a failure while deciding is Unknown and logged, never thrown`. |
| 3 | low | The first-frame test did not prove ordering (it would pass with the read in `onCreate`) | patched | The test now holds the compose clock: the screen is composed, the looper and app scope are idle, and `allCalls == 0`. After `advanceTimeBy` the read starts. |
| 4 | low | `FakeSessionHistoryRepository.allCalls` is a plain Int written on `Dispatchers.Default` and polled from the main thread | patched | Backed by a `MutableStateFlow`. Map iteration in the fake stays unsynchronised: `recordStart` finishes inside `dispatch` before the read, so it cannot overlap in these tests. |
| 5 | low | `awaitChildren()` after the activity exists might hang if a child resumes on Main | checked | The new WakeActivity and WakeActivitySuccess tests ran green (see Verification). |
| 6 | low | The keep-screen-on `SideEffect` could set the flag again on a finishing screen after `SnoozedClose` | patched | Guarded with `!isFinishing`. |
| 7 | low | The branch base was behind `origin/main` (#82) | patched | Rebased onto `origin/main` before the final runs (after 6.2, #83: 05e4a9e6). |

Verification run (after the rebase on 6.2): `:core:jvmTest :core:koverVerify` pass; `:data:testAndroidHostTest` (SessionHistory, ScanTest, DataModule, DeleteAllData) pass; `:composeApp:testAndroidHostTest` (wake, CopyRules, UiModule, PurchaseHistory, Progress, Home) pass; `:androidApp:verifyRoborazziDebug` with Success, Wake, Celebration, check screens, OneTimeAlarmFlow, SnoozedScreen, CheckFontScale (`a11y_*`), CheckSemanticsSuite, PriceRefreshAtSessionStart, Preview, TalkBackF5, SessionWiring, TestAlarmFlow, Home and Progress: 1,344 tests, 0 failures. `spotlessCheck` and `detekt` pass (`SessionHistoryDao` now carries `@Suppress("TooManyFunctions")`: 6.2's `observeAll` plus this story's `allRows` make 12 queries). An earlier run had one `QrCheckScreenTest` failure ("no camera scan is running") under load; it passed on the re-run with no change to the QR path.

Pass 2 (independent review of PR #85): no HIGH findings; one MEDIUM and three LOWs, all test gaps, and all patched with tests. No code bug was found. `WakeActivitySuccessTest` (18 tests) and `SuccessScreenTest` (14 tests) pass; `spotlessCheck` and `detekt` pass.
- MEDIUM: the first Success showing on the real app path celebrates. `WakeActivitySuccessTest` "the first Success showing of a grown streak celebrates…": the frames differ over 2 s, "12" shows and exactly one CONFIRM haptic plays.
- LOW: the celebration ends within 3 s. In `SuccessScreenTest` nothing changes between 2 s and 3 s.
- LOW: Pending on the wake screen. With the read held, Success shows "Up on time." with no number, and still none after the read lands.
- LOW: a recreated Success keeps `FLAG_KEEP_SCREEN_ON` cleared, and an emergency ring over Success sets it again.
- Item 5 (retry after a failed read) was not taken: one read per session stays.
