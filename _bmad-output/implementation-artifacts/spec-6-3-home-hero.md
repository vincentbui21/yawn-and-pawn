---
title: 'Story 6.3: Streak and money hero card on Home'
type: 'feature'
created: '2026-10-26'
status: 'review'
baseline_commit: '05e4a9e6'
route: 'dispatch'
story_key: '6-3-streak-and-money-hero-card-on-home'
review_loop_iteration: 1
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-6-context.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-6-2-core-stats-money-outcomes.md'
  - '{project-root}/.claude/skills/pps-design/SKILL.md'
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** Home already draws `card-hero` (`HomeHero`, `HeroCard`, the collapsing header and its compact streak chip), but `HomeViewModel` never sets `hero`, so in production the streak and this week's money never show (FR-MSG-2, FR-PRG-2). The preview model also holds a single `Money?`, so a week with payments in two currencies cannot be shown honestly (AD-8).

**Approach:** `HomeViewModel` collects 6.2's `ObserveProgress` (its `Resumed` signal is the refresh) and sets `HomeUiState.hero = HomeHero(currentStreak, paidThisWeek)` when Home has at least one alarm or at least one history row. `HomeHero.paidThisWeek` is the **list** model, one `Money` per currency, as on the `design/preview-epic-6` branch. `HomeScreen` moves the hero above the notices, shows it over the empty state when there is history but no alarm, formats the line through the existing `formatMoney(List<Money>)` (`MoneyFormatter.formatTotals`), and gives TalkBack one element, "{streak} days on time. {money line}". There are no new strings, tokens or components.

Defaults taken (unattended fast-mode run; the owner can change them):
- **Currency separator, `" + "` and not `" · "`:** the AC's example "€8.98 · $1.00 paid this week" conflicts with EXPERIENCE.md Key strings "Money, mixed currencies": "{amount1} + {amount2}". It also conflicts with the code: `TOTALS_SEPARATOR = " + "` in `core/.../billing/MoneyFormatter.kt` (epic-6-context.md wrongly says `formatTotals` joins with " · "). The AC also requires key strings to match EXPERIENCE.md verbatim, so the line uses `formatTotals` unchanged: "€8.98 + $1.00 paid this week", in currency-code order (6.2 sorts `paidThisWeek` by code). **Owner question, listed for v2 wording (memory: wording sign-off deferred).**
- **Money line colour, `text-secondary` for the whole line:** the AC says both "the money line in `text-secondary`" and "the price text uses `text` colour". DESIGN.md `card-hero` ("$X paid this week" in `text-secondary`) and the approved round 1 `home-paid` preview use `text-secondary`. That is a neutral text colour, never accent, green or red, which is what UX-DR7 guards. No span recolouring.
- **Hero above the notices:** the AC (twice) and 6.10 item 1 put the hero above the reliability `banner-warning` and the notes. Today's code (and the approved `home-missed` and `home-reliability` preview states) put the notices first. The AC wins, and the preview baselines are re-recorded.
- **History but no alarms:** the hero shows at the top of the empty state, above the notices, "No alarms yet." and "Add your first alarm". The approved `home-empty` preview (no history) keeps its pixels. The new state is shown to the owner in the review screenshots (memory: owner sees screens; fast mode: shown at review, not blocking).
- **While the snapshot is loading, or after a read failure, the hero is hidden,** never shown with a guessed 0. A failure is logged and retried with Home's existing backoff (`missedRetryDelay`: 1 s doubling to 1 min).
- **TalkBack text is built from the existing resources** (`home_streak_day(s)`, `home_paid_week`, `home_zero_paid`) as "{n} {label}. {money line}". No new string resource.
- **Delete all data:** records still owed a consume survive (Story 5.9), so the hero can show money right after a delete. Accepted (epic-6-context.md risk) and noted here.

## Boundaries & Constraints

**Always:**
- Every number comes from `ObserveProgress` (6.2). There is no stats code in `:composeApp` (AD-18).
- `card-hero` is `glass`, `rounded.md`, 16 dp padding (`spacing.cardPadding`), and not tappable. The streak is in `display` (tabular already) with `colors.accentText`, which is `accent-dark` `#F5A04E` in Dark, then "days on time" / "day on time" in `body` / `text`, and the money line in `body` / `textSecondary`. These are the existing `HeroCard` styles; keep them.
- The hero collapses into the compact pinned chip on scroll (UX-DR30, `rememberHeaderCollapse`, `collapsingHero`) as it does today, and the streak number never animates on Home.
- Formatting goes only through `formatMoney(List<Money>)` → `moneyFormatter.formatTotals` (AD-8, `MoneyFormattingScanTest`). No currency symbol appears in any string resource.
- The time signal is listened to only while Home is shown: the progress flow is part of `state`'s upstream (`WhileSubscribed`), never launched in `init`.

**Never:** new strings, tokens, radii, components or animations; a tap target on the hero; a zero amount ("€0.00 paid this week"); stats computed in the ViewModel; a change to `ObserveProgress`'s rules (6.2 owns them).

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior |
|----------|--------------|---------------------------|
| Fresh install | no alarms, no history | no hero; "No alarms yet." + "Add your first alarm" (approved `home-empty`) |
| First alarm | 1 alarm, no history | hero "0 days on time", "Nothing paid this week. Keep it that way." |
| History only | 0 alarms, ≥ 1 history row (any outcome) | hero at the top of the empty state, then notices, "No alarms yet.", "Add your first alarm" |
| Streak 1 | 1 good day | "1" + "day on time" |
| One currency | Consumed EUR 3.00 (Intent price) this ISO week | "€3.00 paid this week" (the formatter's locale form) |
| Two currencies | EUR 2.99 + EUR 5.99 + USD 1.00 this week | "€8.98 + $1.00 paid this week" (code order: EUR, USD) |
| Not counted | stranded, or estimated price, or last week | "Nothing paid this week. Keep it that way." |
| Session recorded | a history row is upserted while Home shows | streak updates, no restart |
| Purchase changes | a record is put while Home shows | money line updates |
| Midnight / new week | the clock crosses Sunday 24:00 local and a time tick arrives | this week's money resets |
| Back to foreground | the date changed while away; `Resumed` | recomputed (refresh), even with no tick |
| Zone change | the zone changes and a tick arrives | days and week recomputed in the new zone |
| Loading | snapshot not emitted yet | no hero (no 0 flash) |
| Read failure | history or records flow throws | no hero, "load progress" logged, retried after 1 s, 2 s … ≤ 1 min |
| Alarms failed to load | `loadFailed` | no hero ("Couldn't load your alarms." + "Try again" as today) |
| Session active | `sessionInProgress` | only `panel-session-in-progress` (as today) |
| Banners | reliability problem + missed note + re-register | hero first, then banner(s) and notes, then countdown and cards |
| TalkBack | streak 12, nothing paid | one node: "12 days on time. Nothing paid this week. Keep it that way.", no button role, no click action |

</frozen-after-approval>

## Code Map

- `composeApp/src/commonMain/kotlin/com/yawnandpawn/app/ui/home/HomeContract.kt`: `HomeHero(streakDays: Int, paidThisWeek: Money?)` becomes `paidThisWeek: List<Money> = emptyList()`. This is the same change as the `design/preview-epic-6` worktree (`agent-aa3515578a96fa686`), so take theirs verbatim if it is merged.
- `composeApp/.../ui/home/HomeViewModel.kt`: constructor (8 positional parameters, then `uiTypeOf`, `evaluationContext`, `testNote`); `resumes: MutableSharedFlow<Unit>`; `ticks`; the `state` `combine` of 5 flows (the maximum typed overload); `render()`; `missedRetryDelay`; `actions.logFailure`.
- `composeApp/.../ui/home/HomeScreen.kt`: `HomeScreen` (the `noList` branch with `EmptyHome`, else `HomeList`); `HomeList` `LazyColumn` items: `"notices"`, then `HERO_KEY`, then `"next"`, then the cards; `Notices`; `EmptyHome`; `HeroCard` (`semantics(mergeDescendants = true) {}` today; the money line is `hero.paidThisWeek?.let { … formatMoney(it) }`).
- `composeApp/.../ui/home/HomeHeader.kt`: `rememberHeaderCollapse(listState, hasHero, …)` reads the `HERO_KEY` item's offset, and its chip uses item 0's scroll when `firstVisibleItemIndex == 0`. With the hero as item 0 both still hold. `CompactStreak` already clears its semantics.
- `composeApp/.../ui/format/Money.kt`: `formatMoney(amounts: List<Money>)` → `moneyFormatter.formatTotals` (`" + "`, first-seen order).
- `composeApp/.../ui/UiModule.kt` line 39: `viewModel { HomeViewModel(get(), …, testNote = get()) }`.
- `androidApp/src/main/kotlin/com/yawnandpawn/app/YawnAndPawnApp.kt`: `DayTicker` and `ObserveProgress` factories (added by 6.2).
- `androidApp/src/debug/kotlin/com/yawnandpawn/app/debug/preview/PreviewSamples.kt` lines 95 and 100 (`paidThisWeek = null` / `price(3)`) and `PreviewProgressSamples.kt` line 103 (`paidThisMonth = price(9)`). These must follow the list model, or the debug build breaks. The preview branch's diff does **not** touch them yet.
- Tests: `composeApp/src/commonTest/.../ui/home/HomeViewModelTest.kt` (fixture at about line 145; "the time signal is only listened to while Home is shown" at about line 569), the `HomeTestNoteTest.kt` pattern for a focused file, `composeApp/src/commonTest/.../ui/UiModuleTest.kt` (binds `FakePurchaseRecordRepository` and `FakeSessionHistoryRepository` already), `androidApp/src/test/kotlin/com/yawnandpawn/app/ui/HomeSamples.kt` and `HomeScreenshotTest.kt` (`home(name, state, mode)` helper), the `*SemanticsTest.kt` pattern in the same folder, and `androidApp/src/testDebug/.../preview/PreviewScreenshotTest.kt` (baselines in `androidApp/src/test/screenshots/preview/`).

## Tasks & Acceptance

**Execution:**
- [x] `HomeContract.kt`: `HomeHero.paidThisWeek: List<Money> = emptyList()`, with the KDoc "one total per currency, by currency code (AD-8); empty for nothing paid ('Nothing paid this week. Keep it that way.')". If the preview branch is merged, nothing to do.
- [x] `HomeViewModel.kt`:
  - Add the constructor parameter `private val progress: ObserveProgress? = null` after `testNote` (the app binds it; null shows no hero, so existing tests compile unchanged). Update the class KDoc (Story 6.3 paragraph).
  - Add:
    ```kotlin
    /** The stats behind the hero (Story 6.3); null while loading or after a failed read (logged, retried with the missed note's backoff). */
    private val snapshot: Flow<ProgressSnapshot?> =
        progress?.let { observe ->
            observe(refresh = resumes)
                .map<ProgressSnapshot, ProgressSnapshot?> { it }
                .retryWhen { cause, attempt ->
                    actions.logFailure("load progress", cause)
                    emit(null)
                    delay(missedRetryDelay(attempt))
                    true
                }.onStart { emit(null) }
        } ?: flowOf(null)
    ```
  - `state` = `combine(<the existing 5-flow combine, unchanged>, snapshot) { home, stats -> home.withHero(stats) }.stateIn(…)`.
  - Add the pure helper `internal fun HomeUiState.withHero(stats: ProgressSnapshot?): HomeUiState`: the hero is `HomeHero(stats.currentStreak, stats.paidThisWeek)` only when `stats != null && !isLoading && !loadFailed && (alarms.isNotEmpty() || stats.hasHistory)`, otherwise `null`.
- [x] `UiModule.kt`: `viewModel { HomeViewModel(get(), get(), get(), get(), get(), get(), get(), get(), testNote = get(), progress = get()) }`. In `UiModuleTest`, add `factory { DayTicker(get(), get(), get()) }` and `factory { ObserveProgress(get(), get(), get(), EmptyCoroutineContext) }` to the test module.
- [x] `HomeScreen.kt`:
  - `HomeList`: emit `HERO_KEY` **first**, then `"notices"`, but only when at least one notice shows (no empty item, so no stray spacing gap), then `"next"` and the cards.
  - `EmptyHome`: when `state.hero != null`, show `HeroCard(hero)` at the top (no collapse modifier, because the empty state does not scroll), then `Notices`, then the centred "No alarms yet." block.
  - `HeroCard`: the money line `if (hero.paidThisWeek.isEmpty()) home_zero_paid else stringResource(home_paid_week, formatMoney(hero.paidThisWeek))`. Replace `semantics(mergeDescendants = true) {}` with `clearAndSetSemantics { contentDescription = "$streak $label. $moneyLine" }`, using the same three resolved strings, with no role and no click. Keep every style.
  - Update the `HomeScreen` KDoc order ("`card-hero`, the reliability banner …").
- [x] `PreviewSamples.kt`: `homeList` uses `paidThisWeek = emptyList()`, `homePaid` uses `listOf(price(3))`. If the preview branch adds a two-currency state (`home-paid-currencies` or similar), keep it. In `PreviewProgressSamples.kt`, `paidThisMonth = listOf(price(9))` (needed only if 6.5 has not done it and the preview branch has not merged).
- [x] `composeApp/src/commonTest/.../ui/home/HomeHeroTest.kt` (new; uses `:testing` `FakeSessionHistoryRepository`, `FakePurchaseRecordRepository`, `FakeClock`, `FakeTimeZoneProvider`, `FakeTimeChangeSignal` and the real `ObserveProgress` with `EmptyCoroutineContext`; the state is collected in `backgroundScope` as `HomeViewModelTest` does). One test per matrix row from "Fresh install" to "Alarms failed to load", plus:
  - the pure `withHero` table: the matrix gating rows, without a ViewModel;
  - "the time signal is only listened to while Home is shown" with `progress` bound: subscribers are above 0 while collected and 0 after `collector.cancel()` and 5 s;
  - an estimated-price record and a stranded record: not in the line (an end-to-end check of the 6.2 rule through the `:testing` fakes, which the 6.2 core tests cannot use).
- [x] `androidApp/src/test/kotlin/com/yawnandpawn/app/ui/HomeSamples.kt`: add `heroZero` (1 alarm, `HomeHero(0)`), `heroOne` (`HomeHero(1)`), `heroPaidOne` (`HomeHero(12, listOf(Money(2_990_000 + 5_990_000, "EUR")))`), `heroPaidTwo` (`HomeHero(12, listOf(Money(8_980_000, "EUR"), Money(1_000_000, "USD")))`), `heroReliability` (`heroPaidOne` with `reliabilityProblem = true` and a missed note), and `heroNoAlarms` (no alarms, `HomeHero(3)`).
- [x] `HomeScreenshotTest.kt`: `home_hero_zero`, `home_hero_one`, `home_hero_paid_one`, `home_hero_paid_two`, `home_hero_reliability` and `home_hero_no_alarms`, each `_light`, `_dark` and `_light_font200` (18 new baselines). At 200%, the two-currency line wraps and nothing clips.
- [x] `androidApp/src/test/kotlin/com/yawnandpawn/app/ui/HomeHeroSemanticsTest.kt` (new): exactly one node has the content description "12 days on time. Nothing paid this week. Keep it that way.". Also check "1 day on time. €3.00 paid this week" (with the formatter's locale output) and the two-currency text. The node has no `Role` and no `OnClick` action. The compact chip has no text node (it is already cleared).
- [x] Re-record the Roborazzi baselines listed under Verification and review each diff. The only expected change is the hero moving above the notices (and the empty notices gap gone).

**Acceptance Criteria:**
- Given Home with at least one alarm or one history row, when it renders, then `card-hero` is the first item under the collapsing header, above the reliability banner, the notes, the countdown and the cards. It shows `ObserveProgress`'s current streak in `display` (tabular figures, `accent-text` / `accent-dark`), "days on time" (or "day on time" for 1) in `body`, and the money line in `text-secondary`. It is glass, `rounded.md`, has 16 dp padding, is not tappable, collapses into the compact pinned chip on scroll, and never counts up.
- Given nothing counted was paid this ISO week, when the line renders, then it reads exactly "Nothing paid this week. Keep it that way.".
- Given counted payments this week in one or more currencies, when the line renders, then it reads "{paid} paid this week". `{paid}` is each currency's total, formatted by `MoneyFormatter` and joined by `formatTotals` in currency-code order, and no string resource contains a currency symbol.
- Given no alarms and no history, or an active session, when Home renders, then there is no hero.
- Given the hero, when a session is recorded, a purchase record changes, the local date rolls over (with a tick), the zone changes (with a tick), or Home resumes, then the streak and line update without a restart (`HomeHeroTest`).
- Given TalkBack, when the hero is focused, then it is one element reading "{streak} days on time. {money line}", with no button role.
- Given Roborazzi and semantic tests, when they run, then the 18 new baselines and the re-recorded ones pass in Light, Dark and Light 200% with nothing clipped. The `pps-design` Done checklist below is ticked, there are no new strings, and `CopyRulesTest` passes.

## pps-design Done checklist

- [x] Only tokens from `DESIGN.md` used: the existing `HeroCard` (`glass`, `shapes.md`, `cardPadding`, `display`/`body`, `accentText`/`text`/`textSecondary`); no raw hex, radius or size.
- [x] Light, Dark checked with screenshots (`HomeScreenshotTest` hero cases, preview `home_*`); Sunrise not relevant.
- [x] Every colour pair is in the `DESIGN.md` contrast table (no new pair: `accent-text`/`text`/`text-secondary` on `glass`).
- [x] Touch targets ≥ 48 dp: the hero is not tappable; no new target.
- [x] 200% font scale (the two-currency line wraps, nothing clipped) and TalkBack (one element, "{streak} days on time. {money line}", no button role).
- [x] Reduced motion: no new animation; the header collapse's existing reduced path is unchanged; the streak never counts up on Home.
- [x] Copy matches `EXPERIENCE.md > Voice and Tone`: "days on time" / "day on time", "{paid} paid this week", "Nothing paid this week. Keep it that way.", "{amount1} + {amount2}"; no new string.
- [x] State rows: loading (no hero), read failure (no hero, logged, retried), empty without history (no hero), history without alarms (hero over the empty state), session (panel only), load failure (no hero).
- [x] "I'm up" / snooze: not applicable.
- [x] Previews and Roborazzi baselines updated (18 new, the preview `home_*` ones re-recorded).

## Design Notes

**Why `refresh = resumes`:** the Android `TimeChangeSignal` receiver is registered only while Home collects. A date change while the app was in the background is caught either way: by `onStart` when `WhileSubscribed` restarted the upstream (away more than 5 s), or by the `Resumed` refresh (away less than 5 s). `DayTicker`'s `distinctUntilChanged` makes repeated resumes free.

**Why combine outside the 5-flow combine:** kotlinx `combine` has typed overloads only up to 5 flows. Wrapping keeps the existing Home logic untouched, and `withHero` stays a pure, table-testable function.

**Two time-signal subscribers:** with `progress` bound, Home's countdown and `DayTicker` each collect `TimeChangeSignal`, so Android registers two receivers while Home shows. This is harmless, and both stop with `WhileSubscribed`. The existing subscriber test (no `progress`) still expects exactly 1.

## Risks

- **6.2 not merged:** `ObserveProgress`, `ProgressSnapshot` and `DayTicker` names come from `spec-6-2-core-stats-money-outcomes.md`. Re-check them after 6.2 merges.
- **Preview branch overlap:** `design/preview-epic-6` changes `HomeContract.kt` and `HomeScreen.kt` (the same list model and the same `formatMoney(list)` line), and its `HomeHero` KDoc already says " + ". Rebase on it if it merged first, and keep its pixels for any new preview state. If it merged without fixing `PreviewSamples.kt` / `PreviewProgressSamples.kt`, the debug build is broken until this story (or 6.5) fixes them.
- **Separator conflict (" · " vs " + "):** the AC text and epic-6-context.md say " · ", while EXPERIENCE.md, the code and the preview branch say " + ". The default is " + ". If the owner wants " · ", the fix is a one-line change of `TOTALS_SEPARATOR` plus EXPERIENCE.md, and it also changes Purchase history and the session line.
- **Reordering the hero above the notices** changes approved preview pixels (`home-missed`, `home-reliability`, `home-test-skipped`), and removing the empty notices item shifts every hero state by one `space3`. The AC requires the order; call it out in the review screenshots.
- **The hero appears a moment after the list on cold start** (the snapshot arrives after the alarms). It is a fade-free pop-in. Accepted, since a 0 streak shown first would be wrong.
- **Money after "Delete all data"** (Story 5.9 keeps records still owed a consume): rare; accepted.

## Verification

**Commands (JDK 17, `--no-daemon --max-workers=2`, targeted only; never `qualityGate` locally):**
- `:composeApp:testAndroidHostTest --tests "*home*" --tests "*UiModuleTest*" --tests "*CopyRulesTest*" --tests "*MoneyFormattingScanTest*"`: pass.
- `:androidApp:recordRoborazziDebug --tests "*HomeScreenshotTest*" --tests "*PreviewScreenshotTest*"`: records the 18 new `home_hero_*` baselines and re-records the preview `home_list_*`, `home_paid_*`, `home_half_collapsed_*`, `home_collapsed_*`, `home_days_away_*`, `home_missed_*`, `home_reliability_*`, `home_test_skipped_*` and `home_disable_dialog_*` (review every diff; `home_empty_*` and `home_session_*` must not change). Then run `:androidApp:testDebugUnitTest --tests "*Home*" --tests "*Preview*"`: pass.
- `:data:testAndroidHostTest --tests "*SessionLockGuardScanTest*" --tests "*PurchaseStateReaderScanTest*"`: pass (no core change expected here).
- `spotlessCheck detekt`: pass.

## Implementation Notes

- Branched `story/6-3-home-hero` from origin/main at 05e4a9e6, which already has 6.1, 6.2 and the round 5 preview models. Baseline is 05e4a9e6.
- 6.2's real names match this spec: `ObserveProgress(refresh)`, `ProgressSnapshot.currentStreak`, `paidThisWeek: List<Money>` (sorted by ISO code in `totalsPerCurrency`), `hasHistory`, and `DayTicker(clock, zones, timeChanges)`. The app binds both as factories that compute on `Dispatchers.Default`.
- Already on main, so not changed here:
  - `HomeHero.paidThisWeek: List<Money> = emptyList()`, with the " + " KDoc;
  - `HeroCard`'s `formatMoney(list)` line;
  - `PreviewSamples.kt` and `PreviewProgressSamples.kt`, already on the list model;
  - round 5's two-currency Home state (`home_paid_two_currencies`), kept as it is.
- `formatTotals` keeps the order it is given (`totalsByCurrency`). 6.2 passes it a list already sorted by currency code, so the line comes out in code order: "€8.98 + $1.00 paid this week". No `formatPerCurrency` helper was needed.
- Files changed:
  - `HomeViewModel.kt`: the `progress` parameter, the `snapshot` StateFlow, an outer `combine`, and `withHero`.
  - `HomeScreen.kt`:
    - an empty `"top"` anchor item comes first;
    - the hero follows, and the notices item is added only when a notice shows;
    - the empty state shows the hero and scrolls;
    - the hero uses `clearAndSetSemantics`.
  - `UiModule.kt`: `progress = get()`.
  - Comments only: `HomeHeader.kt`, `PreviewCatalog.kt` and `HomeInsetsTest.kt`.
- Tests:
  - `HomeHeroTest`: 20 tests.
  - `HomeHeroSemanticsTest`: 6 tests.
  - `HomeSamples`: 7 new states.
  - `HomeScreenshotTest`: 19 new cases.
  - `UiModuleTest`: `DayTicker` and `ObserveProgress` run on the fakes, and the Koin-built Home shows `HomeHero(0)`.
- No new strings, tokens, components or animations. `strings.xml` and `YawnAndPawnApp.kt` are untouched.
- Verified (JDK 17, `--no-daemon --max-workers=2`):
  - The full `:androidApp:testDebugUnitTest` (2546 tests) and `:composeApp:testAndroidHostTest` (599 tests): 0 failures, with Roborazzi verifying every baseline.
  - `:data:testAndroidHostTest` (`SessionLockGuardScanTest`, `PurchaseStateReaderScanTest`): pass.
  - `spotlessCheck detekt`, including the `NoDirectTimeAccess` rule: pass.
  - One earlier targeted run had a single `OnboardingFlowTest` failure (a DataStore read in the double-tap test, which does not reach Home). It passed on its own rerun and in the full run.
  - The full `qualityGate` was not run locally.

## Spec Change Log

- **Anchor item (review fix 1).** The list keeps an empty first item (`"top"`), and the notices item is added only when a notice shows.
  - Why: `LazyList` keeps its position by the key of the first visible item. Without a stable item 0, a hero or banner that arrives after the first layout (the stats come on `Dispatchers.Default`, after the alarms) would be inserted above the first visible item, off screen.
  - The anchor keeps the existing top gap under the header. A hero list with no notices therefore keeps main's pixels, and so does a list with no hero and no notices. The previews' `scrollItem` indices (1 = the hero, 2 = the countdown) stay as they are.
  - No empty notices item sits between the hero and the countdown.
- **Snapshot kept while Home is away (review fix 3).** The snapshot is a `StateFlow`: `stateIn(WhileSubscribed())`, with no `onStart { emit(null) }`.
  - Why: coming back to Home keeps the last real hero until the new snapshot arrives, instead of hiding it and showing it again.
  - Only a new ViewModel starts with no hero (null).
  - A failed read still hides the hero, never leaving a stale streak on screen.
  - The upstream still stops together with Home's own (the subscriber test expects 0 listeners 5 s after Home goes).
- **The empty state scrolls (review fix 4).** It uses `verticalScroll` with `heightIn(min = the screen)`, so the centred block still fills the rest of the screen.
  - Why: with the hero and every notice at 200%, "Add your first alarm" no longer clips.
  - `home_empty_*` keeps its pixels.
- **More baselines than the Verification list names.** With the anchor, a notice-only state (no hero) gains one `space3` above its first notice, so it lines up with the list states. Re-recorded for that reason:
  - `HomeScreenshotTest`: `home_reliability_*`, `home_missed_note_*`, `home_reregister_*`, `home_reregister_reliability_*` and `home_test_note_*`;
  - `HomeInsetsTest`'s `home_reliability_light_w360_status_bar`;
  - the preview `home_test_skipped_*` (a tall item, so it is captured by `PreviewEditorScreenshotTest`).
  - `home_missed_*` and `home_reliability_*` (the preview) change because the hero moves above the notices.
- `withHero` uses `takeIf` / `let` instead of a boolean, so the snapshot needs no smart cast. The rule is the same.
- With the stats bound, the subscriber test expects 2 listeners while Home shows (the countdown's ticks and `DayTicker`), then 0.

## Review Triage Log

Pass 1: one combined reviewer (blind hunter, edge-case hunter, verification gap; fast mode).

| # | Severity | Finding | Verdict / route |
|---|----------|---------|-----------------|
| 1 | HIGH | With the hero as item 0 and the notices item optional, a hero or banner arriving after the first layout is inserted above `LazyList`'s key anchor, so it ends up scrolled above the screen. This happens on cold start, on a return after more than 5 s, and after a retry. | Patched: an always-present empty `"top"` item keeps the anchor (see the Spec Change Log). |
| 2 | HIGH | `EditorLockScreenshotTest`'s Home baselines would shift by `space3`, and nothing re-recorded them. | Patched by 1: list states with no notices keep main's pixels. `EditorLockScreenshotTest` was re-recorded and verified, with no change. |
| 3 | MEDIUM | `onStart { emit(null) }` hides the hero each time Home comes back, then shows it again: a flicker. | Patched: the snapshot is a `StateFlow` that keeps the last value. New test "coming back to Home keeps the last hero". |
| 4 | MEDIUM | With history and no alarm, the hero plus every notice at 200% could clip "Add your first alarm" in the empty state, which does not scroll. | Patched: the empty state scrolls. New baseline `home_hero_no_alarms_notices_light_font200`. |
| 5 | MEDIUM | No test proves the app hands `progress` to Home. | Patched: `UiModuleTest` collects the Koin-built Home and expects `HomeHero(0)`. |
| 6 | LOW | No test covers a hero already showing that goes away on a failed read. | Patched: new `HomeHeroTest` case (hidden, logged once, back after 1 s). |
| 7 | LOW | The "hero above the notices" order is checked only by pixels. | Patched: a `HomeHeroSemanticsTest` bounds check (hero, then banner, then note). |
| 8 | LOW | Stale comments: `PreviewCatalog` "1 = the hero", "always in Epic 1" in `HomeHeader` and `HomeInsetsTest`, and an odd line break in the `HomeScreen` KDoc. | Patched. |

## Coordinator note (2026-10-26): currency separator
Several currencies are always joined with " + ", using the existing `TOTALS_SEPARATOR` and EXPERIENCE.md "Money, mixed currencies", and sorted by currency code. For example: "€3.27 + $2.18 paid this week". Do not add a " · " variant. If a sorted helper is needed, the first story to land adds `formatPerCurrency` with " + ", and the others reuse it.

## Owner decision (2026-10-26)
The owner approved all three round 5 defaults ("OK with all"):
- money in two currencies is joined with " + ", one line per currency on Progress;
- "/ 30" is hidden once the streak passes 30;
- Day detail uses stored facts only (Q1 option A): "Snoozed · {price}" and "{check} solved".
These are final for this build.
