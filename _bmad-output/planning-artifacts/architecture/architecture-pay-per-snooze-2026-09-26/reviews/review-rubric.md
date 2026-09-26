# Rubric Review — Architecture Spine, Pay Per Snooze

- **Reviewer:** rubric walker (BMAD good-spine checklist, `bmad-architecture/references/reviewer-gate.md`)
- **Target:** `ARCHITECTURE-SPINE.md` (status: draft, altitude: feature, 2026-09-26)
- **Driving spec:** `prds/prd-pay-per-snooze-2026-09-26/prd.md` v0.2; UX `EXPERIENCE.md` skimmed (State Patterns, Payment outcomes, Session Integrity, Key Flows, Open Questions)
- **Deterministic pass:** `lint_spine.py --workspace …` → `ok: true`, 0 findings. Everything below is semantic.
- **Spine not edited.**

## Verdict

**Not ready for epics — revise, then re-gate.** The paradigm, module graph, storage split, money type, billing reconciler, tokens pipeline and quality gate are strong and mostly enforceable. The weak spot is the one the spine itself calls normative: the **AD-2 transition table**. It double-counts the snooze index (wrong price charged), contradicts FR-SES-7's "no extra grace window", has no rows for Direct Boot, test alarms, stranded-purchase reuse, user interaction or backup-alarm firing, leaves "snooze available" pointing at a rule AD-7 does not contain, and declares every unlisted pair "a bug", which in an autonomous Ralph loop turns ordinary timer races into crashes. The pending-then-purchased case is unresolved between the PRD, the UX and the spine. Several operations-envelope and consent/telemetry gaps would also let stories diverge.

| Severity | Count |
|---|---|
| Critical | 2 |
| High | 11 |
| Medium | 11 |
| Low | 7 |
| **Total** | **31** |

---

## Checklist walk

| Checklist item | Result |
|---|---|
| Fixes the real divergence points for the level below and misses none | **Partial.** Module boundaries, ports, money, errors, DI, tokens, nav and quality gate are fixed. Missed: commitment lock / config freeze, app lock during session, snooze availability, timer persistence across reboot, Direct Boot flow, test-alarm mode, background work scheduler, consent-gating mechanics (C1–H11, M8). |
| Every AD's Rule is enforceable and prevents its divergence | **Partial.** AD-1, AD-5 (manifest test), AD-6, AD-8, AD-10, AD-13, AD-14 are enforceable. AD-2 is normative but incomplete and internally inconsistent (C1, C2, H3, H6, H10). AD-7 is referenced for a rule it does not state (H1). AD-15's "enforced by review" has no reviewer in a Ralph loop (H9). AD-3 monotonic-only rule fails across reboot (H4). |
| Nothing under Deferred could let two units diverge | **Fail on one item.** "Payment-over-lock-screen … AD-2 transitions don't change" is not safe: the UX unlock step introduces an event and an intent-lifecycle question (M6). Other deferrals are safe. |
| Named tech is plausible-current | **Plausible, unverified.** All versions are coherent for 2026-09 (AGP 9 + separate `:androidApp`, KSP 2.3.x independent versioning, kotlinx-datetime ≥ 0.7 with `kotlin.time.Instant`, PBL 9.1). No online verification was done in this review; detekt 2.0 alpha against Kotlin 2.4 is the main compatibility risk (M9, L4). |
| Ratifies brownfield codebase | N/A (greenfield; only `_bmad`, `docs`). |
| Covers the driving spec's capabilities | **Partial.** Gaps: §6.2 commitment lock, FR-ALM-8 test alarm, FR-ALM-11 Direct Boot session behaviour, FR-SES-3 app lock, FR-RNG-7 availability, FR-RNG-10 reuse, FR-PRG-5 weekly summary scheduling, FR-SND-1 loudness gate, FR-SET-5 order id, §6.4 reboot timer rule. |
| Parent spine inherited | N/A. |
| Every owned dimension decided, deferred or open | **Partial.** Deployment/environments are sketched (debug/release, Firebase projects, secrets, tracks) but billing test environment, versioning/release cut, staged rollout/hotfix, privacy-policy/support hosting, and post-launch monitoring ownership are silent (M10). There is no Open Questions section at all (M11). |

---

## AD-2 vs PRD §6.4, FR-SES and FR-RNG (focus area)

| PRD rule | Spine AD-2 | Match? |
|---|---|---|
| §6.4 row 1: grace ends normally during payment, alarm resumes at full volume while Play sheet open | `Grace --GraceElapsed--> Loud` has no `paying` guard; note "Grace timer keeps running while `paying` is set" | **Yes** (should state that `paying` is carried into Loud) |
| §6.4 row 2: purchase granted in check → snooze wins, progress discarded, re-ring = fresh check + new grace | `PurchaseGranted` row discards progress; re-ring → Ringing → ImUpTapped → Grace | **Yes**, but guard wording "any active" also matches Snoozed (M3) |
| §6.4 row 3 / FR-SES-7: overlap → merged; during snooze, snooze ends early, re-ring, **no fee, no extra grace window**; absorbed occurrence logged Merged and next occurrence scheduled | `Snoozed --OverlapAlarmFired--> Ringing(n+1)`; nothing prevents ImUpTapped → Grace; no cancel of the pending snooze alarm; no next-occurrence effect | **No** (H3) |
| §6.4 row 4 / FR-SES-8: call pauses sound, grace countdown, 30-min timer | `CallStarted` on "any active" pauses "sound and all timers" | **Over-broad**: includes Snoozed (would pause snooze), backup re-arm (M1) |
| §6.4 row 5 / FR-SES-1: reboot/process death restores; wall + monotonic + boot count; past snooze end rings immediately; restored ring gets a fresh 30-min timer | `ProcessRestored → same; re-run entry effects`. AD-3: timers monotonic only | **No** (H4, H6) |
| §6.4 row 6 / FR-ALM-11: Direct Boot substitution; Snooze unavailable until unlock; substituted check stays for the ring | Not in table | **Missing** (H5) |
| FR-ALM-9: timer reset on any wake-screen tap; new timer per re-ring; snooze never counts | `NoInteractionTimeout` guard "since last interaction" but no interaction event; timer start not in any effect list | **Incomplete** (H10) |
| FR-ALM-8: test alarm, full flow, no payment, outcome Test | Not in table | **Missing** (H11) |
| FR-SES-2: backup alarm ≤ 60 s ahead, re-armed continuously, at snooze end during snooze | "arm backup +60 s" on ring entry only; nothing on PurchaseGranted; no BackupAlarmFired event | **Incomplete** (M2) |
| FR-RNG-4 / §6.3 grant rule: PURCHASED + profileId = current active session **not snoozed**; exactly one grant per token | Guard `token.profileId = sessionId` on "any active with paying" | **Partially**; pending→purchased in-session contradicts (C1); state guard and product match missing (M3) |
| FR-RNG-5: full volume during payment; during grace, mute continues only until countdown ends | SnoozeTapped in Grace keeps state; GraceElapsed unmutes | **Yes** |
| FR-RNG-6: next snooze costs B × (N+1) | `n` incremented by both PurchaseGranted and SnoozeElapsed | **No** (C2) |
| FR-RNG-7: disabled with reason (offline, cap, max, test, before unlock, refunding) | Guard "snooze available (AD-7)"; AD-7 defines nothing | **Missing** (H1) |
| FR-RNG-9: "I'm up" ≤ 1 tap from every ringing state | ImUpTapped only from Ringing; Loud/Grace already in check. OK. Confirm sheet "I'll get up" is UI-only — fine if stated | **Yes** |
| FR-RNG-10: stranded reuse offer, Use it / Not now | No events for OfferReuse outcome | **Missing** (H2) |
| FR-PWK-11: fallback once per session, keeps ringing, no new grace window | `FallbackRequested` "keep timers" | **Ambiguous** on grace mute and on which check a later re-ring uses (M5) |

---

## Findings

### Critical

**C1 — Pending→PURCHASED inside the same active session has three incompatible answers.**
- *Where:* AD-2 rows `PurchaseFailed / Cancelled / Pending → paying=null` and `PurchaseGranted` (requires `paying`); AD-7 reconciler; PRD §6.3 ("When it later becomes PURCHASED, the recovery table decides" → row 1 = **Grant + consume** if current active session, not snoozed); EXPERIENCE Payment outcomes / F4 ("Snooze unavailable: payment pending for this session"; Success: "Your pending payment wasn't used. Google refunds it automatically").
- *Divergence:* The reconciler story (implements PRD) returns `Grant`; the engine story (implements AD-2) has no row because `paying` was cleared; the UX story shows "wasn't used". AD-2 says an unlisted transition is a bug, so the Ralph loop will either crash, drop a paid purchase silently, or grant a snooze the UI says will be refunded. Real money.
- *Fix:* Decide once in AD-7 and AD-2. Recommended (safest, matches UX): a `PENDING` result sets `snoozeBlock = PendingPayment` for the rest of the session and records `pendingIntentId`; a later `PURCHASED` for that intent while the session is still active and not snoozed is **Grant** (charged once, snooze delivered) *only if* the engine is in Ringing/Grace/Loud; otherwise `LeaveForAutoRefund`. Add explicit rows: `Ringing|Grace|Loud, PurchaseGranted, paying=null ∧ intent is this session's pending intent → Snoozed(...)`. Send the chosen rule back to the owner as a PRD/UX conflict note (PRD and EXPERIENCE disagree today).

**C2 — Snooze index is double-incremented; wrong price will be charged.**
- *Where:* AD-2: `Idle→Ringing(n=1)`, `PurchaseGranted … n++`, `Snoozed --SnoozeElapsed--> Ringing(n+1)`, `Snoozed --OverlapAlarmFired--> Ringing(n+1)`. AD-7 `FeeLadder(baseFee, snoozeIndex)`.
- *Divergence:* After one paid snooze, `n` becomes 3 (ring index or snooze index?). If a story feeds `n` into `FeeLadder`, the second snooze costs 3B instead of 2B (FR-RNG-6, §6.2), and max-snooze checks trip early. Two story authors will pick different meanings.
- *Fix:* Replace `n` with two named fields in `SessionState`: `ringIndex` (incremented only on ring entry) and `snoozesPaid` (incremented only on `PurchaseGranted`). Rule: `nextFee = FeeLadder(frozen.baseFee, snoozesPaid + 1)`. Update the table and the mermaid diagram; add a table-driven test "two snoozes charge B then 2B".

### High

**H1 — "Snooze available (AD-7)" points to a rule AD-7 does not contain.**
- FR-RNG-7 lists offline, cap reached, max snoozes, test mode, before first unlock, earlier payment being refunded; UX adds "payment pending (this session)" and "offline until connectivity returns"; also "already paying". None is defined, nor where connectivity/lock state enters the pure reducer.
- *Fix:* Add to AD-7 (or a new AD): `snoozeAvailability(state, env): Available(product, price) | Unavailable(reason)` as a pure core function, where `env` = `{online, userUnlocked, testMode, strandedTokenForProduct, cachedProductDetails}` supplied through ports; reasons are a sealed `SnoozeBlock` enum mapped 1:1 to EXPERIENCE strings. Session state carries sticky blocks (`PendingPayment`, `RefundingEarlierPayment(productId)`); transient ones (offline, locked) come from `env`. `SnoozeTapped` guard = `Available ∧ paying == null`.

**H2 — FR-RNG-10 stranded-purchase reuse and the pre-launch consume step have no transitions.**
- AD-7 returns `OfferReuse`, but AD-2 has no event for the user's answer, no row for "consume granted-unconsumed token for P, then launch", and no row for `ITEM_ALREADY_OWNED` on a granted token ("consume then retry once", §6.3).
- *Fix:* Add events `ReuseOffered(token)`, `ReuseAccepted`, `ReuseDeclined` with rows: `paying ∧ ReuseOffered → same, offer=token`; `ReuseAccepted → Snoozed` (same effects as PurchaseGranted, using the stranded token); `ReuseDeclined → paying=null, snoozeBlock=RefundingEarlierPayment(product)`. State in AD-7 that the pre-launch query runs inside the `SnoozeTapped` effect and can short-circuit to `ReuseOffered`.

**H3 — Overlap during snooze gets a grace window the PRD forbids, and leaves a live snooze alarm.**
- FR-SES-7 / §6.4: "snooze ends early and the session re-rings … with no fee and **no extra grace window**". AD-2 routes to `Ringing(n+1)`, from which `ImUpTapped → Grace` is allowed. Effects omit cancelling the scheduled snooze re-ring (a later `SnoozeElapsed` would arrive in Ringing — unlisted) and scheduling the absorbed alarm's next occurrence.
- *Fix:* Add `graceAvailable: Boolean` to the ring; `Snoozed --OverlapAlarmFired--> Ringing(graceAvailable=false)`; `Ringing --ImUpTapped [¬graceAvailable]--> Loud` (start check, no mute). Effects: `cancel snooze re-ring; move backup +60 s; play at set volume; log Merged(alarmId); schedule next occurrence of alarmId`. Same "schedule next occurrence" effect on every OverlapAlarmFired row. Flag to owner that "no extra grace" and FR-PWK-9 "new window after re-ring" read as a tension; the spine's resolution should be quoted back.

**H4 — Timer persistence across reboot and clock change is not specified; AD-3's monotonic-only rule breaks at reboot.**
- PRD §6.4 defers "wall-clock + monotonic + boot count" details to architecture. `elapsedRealtime` resets at boot; snooze re-ring uses `setAlarmClock` (wall RTC), so a clock change during a snooze shifts it (FR-SES-5). `ProcessRestored → re-run entry effects` does not say: past snooze end rings immediately; restored ring gets a fresh 30-min timer; what happens to a partly elapsed grace window.
- *Fix:* In AD-3 define `Deadline(wallAt: Instant, monoAt: Duration, bootCount: Int)`; evaluation rule: same boot → use mono; different boot → use wall. On `TIME_SET`/`TIMEZONE_CHANGED` during Snoozed, reschedule the snooze alarm from the mono remainder. In AD-2 add restore rules per state: Snoozed with deadline passed → `Ringing` immediately; Ringing/Loud → fresh 30-min timer; Grace → `[ASSUMPTION]` resume with remaining mono time if same boot, else `Loud`. Persist `bootCount` (`Settings.Global.BOOT_COUNT`) via a port.

**H5 — Direct Boot is not modelled, and the runtime is not made Direct-Boot-safe.**
- FR-ALM-11 / §6.4 row 6 / UJ5 / F8: substitution at session start, Snooze disabled until unlock, substituted check stays for the current ring, "Direct Boot" flag on the log. No `UserUnlocked` event; AD-5 does not mark `WakeService`/`WakeActivity` `directBootAware`; `Application.onCreate` (Koin wiring, Firebase init) runs in Direct Boot for direct-boot-aware components, and Firebase and any credential-storage access throw before first unlock — crashing the alarm the feature exists to protect.
- *Fix:* AD-2: `AlarmFired` effect "freeze config" applies substitution when `env.userUnlocked == false` and sets `directBoot=true` on the session; add `UserUnlocked → same` (re-evaluate availability only; check unchanged). AD-5: `WakeService`, `WakeActivity`, alarm/boot receivers are `directBootAware`; `Application` initialises only device-protected dependencies eagerly; Firebase, media repositories and anything credential-protected initialise on `ACTION_USER_UNLOCKED`. Add an instrumented test or a debug check that no credential-protected path is touched before unlock.

**H6 — "A transition not in the table is a bug" plus "re-run entry effects on restore" will produce crashes and double billing prompts.**
- Timer and billing callbacks race with user input (GraceElapsed after CheckCompleted, SnoozeElapsed after overlap, a second PurchaseGranted duplicate). A Ralph loop reading "bug" will `error()` in the reducer. Separately, if `paying` is set when the process dies, "re-run the state's entry effects" can relaunch the Play sheet.
- *Fix:* Replace with: "Unlisted (state, event) pairs are **ignored and logged** (`Logger.warn`), never thrown; a small explicit list of impossible pairs fails tests only." For restore: "`ProcessRestored` never launches billing; if `paying` is set it runs `queryPurchasesAsync` → reconciler, and clears `paying` if nothing is in flight."

**H7 — Grant is split across two databases with no atomicity or replay rule.**
- AD-6 puts purchase records (and implicitly the grant ledger, "unique index on token") in `app.db`, and the session in `runtime.db`. A crash between writing the ledger and persisting `Snoozed` either double-grants on retry or charges without a snooze; the PRD explicitly lists "crash between grant and consume" as a tested case.
- *Fix:* State the ordering and idempotent recovery in AD-7: (1) insert ledger row `{token, sessionId, state=Granting}` in `app.db`; (2) persist `Snoozed` in `runtime.db` (write-ahead); (3) mark ledger `Granted`; (4) consume → `Consumed`. On restore, a `Granting` row whose session is still active and not snoozed is re-applied; if the session ended it becomes stranded. Add these as reconciler table rows.

**H8 — Commitment lock (§6.2), config freeze and in-app session lock (FR-SES-3) are unaddressed.**
- Where "weakening" is computed, how deferred changes are stored and when they apply, which alarm they wait for, and what is frozen at session start are real cross-story divergence points (settings, alarm editor, stats, session engine all touch them). FR-SES-3 (app shows only wake screens during a session, settings and Delete all data unavailable) has no owner.
- *Fix:* New AD: `CommitmentPolicy` in `core` classifies every settings/alarm edit as `Strengthen | Weaken | Neutral`; weakening edits within 8 h of the next enabled occurrence are stored as `PendingChange(effectiveAfterOccurrence)` in `app.db` and applied by the engine on `Logged` of that occurrence; `FrozenConfig` = {B, maxSnoozes, snoozeLength, grace, checks, sound} copied at `AlarmFired`. AD-11: root navigation observes `SessionEngine.state` and renders only `panel-session-in-progress` when not Idle; destructive intents are rejected in the ViewModel when a session is active.

**H9 — Consent gating and "no other network" are not actually enforceable.**
- Firebase Analytics auto-collects (`first_open`, `session_start`, screen views, `app_remove`) as soon as the SDK initialises unless disabled in the manifest; an adapter-level `if (consent)` does not stop it. ML Kit bundled barcode still sends usage/performance metrics to Google, contradicting "the only network calls are Play Billing and Firebase" and the Data safety form. PRD CM-2 relies on the automatic `app_remove` event while NFR-15 says "no other events" — an upstream conflict the spine must settle. "Enforced by review" has no reviewer in an autonomous loop.
- *Fix:* AD-15 rule: manifest sets `firebase_analytics_collection_enabled=false`, `google_analytics_adid_collection_enabled=false`, `google_analytics_default_allow_ad_personalization_signals=false`, automatic screen reporting off; the adapter calls `setAnalyticsCollectionEnabled(consent)`. Decide whether automatic events (`app_remove`) are allowed and note it for the PRD. Disclose/disable ML Kit telemetry (or document it in Data safety). Replace "review" with a Gradle dependency allowlist check on `:androidApp` runtime classpath (same mechanism as AD-1) plus a merged-manifest test.

**H10 — No event resets the no-interaction timer, and no effect starts it.**
- FR-ALM-9: any wake-screen tap restarts the 30-min timer; each re-ring starts a new one. The table has `NoInteractionTimeout` but no `Interaction` event, and `AlarmFired`/`SnoozeElapsed` effects don't start the timer. Stories will variously reset on `CheckStepDone` only (wrong answers wouldn't count) or in the UI.
- *Fix:* Add `UserInteracted` (sent by `WakeActivity` on every tap, debounced) → `same; restart interaction timer`. Add "start interaction timer (30 min, mono)" to every ring-entry effect list; "cancel interaction timer" on Snoozed/Completed/Missed.

**H11 — Test alarm (FR-ALM-8, FR-ONB-4) is not in the state machine.**
- User-facing test alarm runs the full flow with Snooze disabled "Test · no charge", outcome Test excluded from stats; the onboarding checklist needs a "completed with screen locked" signal. AD-14 only mentions a debug receiver. Without a rule, one story forks the engine, another adds `if (test)` in UI.
- *Fix:* `SessionState.mode: Real | Test`; `AlarmFired(mode)`; availability returns `Unavailable(TestMode)` for Test; `CheckCompleted` logs outcome `Test`; `Completed` effect records `lockedAtStart` for FR-ONB-4. Keep the debug "fire in N s" receiver as a separate, release-stripped entry point.

### Medium

**M1 — Call pause over-applies.** `CallStarted` on "any active … pause sound and all timers" includes Snoozed (the snooze would stretch; PRD: snooze time never counts) and the backup-alarm re-arm (if paused, the backup fires mid-call). Audio focus is also lost to non-call apps. *Fix:* restrict to Ringing/Grace/Loud; pause only sound, grace deadline and interaction timer; keep backup re-arming; treat `AUDIOFOCUS_LOSS_TRANSIENT` as pause, ignore `CAN_DUCK`; list PRD Q14 (call-pause cap) as an open question.

**M2 — Backup alarm lifecycle underspecified.** "Re-armed continuously" has no mechanism; there is no `BackupAlarmFired` event (fires while alive vs after kill); during a snooze the backup and snooze re-ring both sit at snooze end (two `setAlarmClock` fires); whether `WakeService` stays in the foreground during Snoozed is unstated (NFR-8, FR-SES-3 notification). *Fix:* `WakeService` ticks every 30 s while ringing and re-arms backup at +60 s; during Snoozed there is **one** alarm (snooze re-ring) and `WakeService` stops; backup firing delivers `ProcessRestored` if the engine was not loaded, otherwise is ignored. Record PRD Q18 (system next-alarm indicator churn) as open.

**M3 — PurchaseGranted row is loose.** Guard "any active with paying" includes Snoozed; no product/intent match; `paying` not cleared; effects omit "move backup to snooze end" and "write purchase record". *Fix:* From = Ringing|Grace|Loud; guard `profileId = sessionId ∧ productId = intent.productId ∧ ledger.notGranted`; To = `Snoozed(until, paying=null)`; effects add record + backup move. Also carry `paying` explicitly through `GraceElapsed`.

**M4 — Fallback semantics ambiguous and PRD/UX conflict unresolved.** Does `FallbackRequested` in Grace end the mute ("the alarm keeps ringing")? After a later re-ring, is the check the configured one or the fallback? PRD FR-PWK-11 (Hard, double count, Math first) vs UX D5 (selectable accessible picker) is not decided or listed. *Fix:* state "fallback does not change mute/timers; applies to the current ring only; next ring uses the configured check" (or the opposite), put `fallbackUsed` in session state, and add D5/Q11/Q12 to Open Questions.

**M5 — Deferred S1 item is not divergence-safe.** The UX unlock step ("Unlock to pay", "Phone still locked. No charge") needs an event (`UnlockFailed`) and a decision on whether the `PurchaseIntent` is written before or after keyguard dismissal (a cancelled unlock must not leave a dangling intent). *Fix:* add `UnlockRequested/UnlockFailed` rows now (intent written after unlock succeeds, immediately before `launchBillingFlow`); defer only the keyguard API mechanics to S1.

**M6 — `rescheduleAll` triggers incomplete; request codes collide.** Force-stop clears all AlarmManager alarms until the next process start; Auto Backup restore brings alarms back without system alarms; granting exact-alarm permission on API 31–32 (`ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED`) needs a reschedule. `alarmId.hash` can collide with other alarms or the fixed codes. *Fix:* also call `rescheduleAll()` on every process start and after restore, and on the permission-state broadcast; store an integer `requestCode` per alarm in `app.db` from a reserved range disjoint from the two fixed codes.

**M7 — Background work scheduler unspecified.** Consumption retry "with backoff" (PRD: app start, resume, WorkManager), the weekly summary (FR-PRG-5, Sunday 19:00 local) and the 3-fallbacks-in-7-days prompt all need a scheduler; WorkManager is not in the Stack and NFR-8 limits background work. *Fix:* decide: WorkManager (add to Stack) behind a `BackgroundJobs` port for billing retry and weekly summary, or `AlarmManager` inexact for the summary; state it in AD-4 or a new AD.

**M8 — Quality gate and lint enforcement gaps.** `qualityGate` omits the FR-SND-1 loudness script the PRD puts in the gate; the detekt bans (`Clock.System`, raw `Color`/`dp`/`sp`) need a custom-rules module not in the Structural Seed, and detekt 2.0 alpha may lag Kotlin 2.4; the manifest test is a denylist (a new dangerous permission passes) and bans `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`, which FR-ONB-2 authors may reach for. *Fix:* add `tools/sound-loudness` and `tools/detekt-rules` (or `build-logic/`) to the seed and gate; make the manifest test an allowlist of exact permissions; state that battery optimisation uses `ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS` only; name a fallback (detekt 1.23 + ktlint) if 2.0 alpha is incompatible.

**M9 — Operations/environment envelope has silent parts.** (a) Play Billing only works for builds with the production `applicationId` and a version uploaded to a Play track — a `.debug` suffix silently breaks billing tests; (b) the Play catalogue is one per app, so `tools/play-catalog` runs against production — which credentials, dry-run, who runs it; (c) versionCode/versionName and tag scheme; (d) staged rollout percentage and hotfix path for "alarm didn't ring"; (e) where the privacy policy, terms and support pages are hosted with no backend; (f) who watches Crashlytics/Android vitals and the CM thresholds. *Fix:* add an "Environments & operations" block: debug keeps the production id (or add a `billingTest` variant signed with the upload key for the internal track), catalogue script has `--dry-run` and runs only from CI with the service account, versionCode = CI run number on tag `vX.Y.Z`, production via staged rollout (e.g., 10 % → 100 %), static pages on GitHub Pages under `docs/site/`, owner monitors vitals weekly; or defer each explicitly.

**M10 — No Open Questions section.** Upstream items that change architecture are not tracked: PRD Q9 (FR-ALM-10 skip), Q11/Q12 (fallback integrity/accessibility), Q14 (call-pause cap), Q15 (outcome of alarm deleted under lock), Q18 (backup indicator), UX D5, and C1's PRD/UX conflict. *Fix:* add `## Open Questions` with each item, its owner and its revisit trigger.

**M11 — Occurrence scheduling effects missing from AD-2.** `AlarmFired` does not schedule the next occurrence of a repeating alarm or disable a one-time alarm; nothing states that the next occurrence is computed from `FrozenConfig` vs live alarm. *Fix:* add "schedule next occurrence (or disable one-time)" to `AlarmFired` and every `OverlapAlarmFired` row.

### Low

**L1 — `now: Instants` is an undefined type.** Define `Now(wall: Instant, mono: Duration, bootCount: Int)` in AD-3 and use it in the reducer signature.

**L2 — Purchase record fields for FR-SET-5.** "Problem with a charge?" pre-fills order id and time; AD-7 lists only intent fields. Add `orderId, purchaseToken, purchaseTime, state` to `PurchaseRecord` (never logged, per Logging convention).

**L3 — Check seed not in session state.** AD-9 generation is deterministic per seed, but the seed's home is unstated; restore (FR-SES-1, "same step as before") needs it. Store `checkSeed` and step progress in `runtime.db` session.

**L4 — Tech versions not verified in this review.** All pins are plausible for 2026-09; the good-spine bar is "verified-current". Add a one-line "verified on {date} against {source}" or let E0's first story confirm them; watch detekt 2.0 alpha, Roborazzi and Firebase BoM increments.

**L5 — PRD links point to `architecture.md`.** PRD §6.3/§6.4 and frontmatter reference `_bmad-output/planning-artifacts/architecture.md`; the spine lives elsewhere under another name. Ask the owner to update the PRD link, or note the alias in the spine's `sources`.

**L6 — Delete all data (FR-SET-4) scope.** Should cancel all system alarms and clear `app.db`, `runtime.db`, DataStore and media, and is refused while a session is active (FR-SES-3). One sentence in AD-6.

**L7 — Backup rules must cover device-protected domains on both API ranges.** Because databases live in device-protected storage, `dataExtractionRules` (API 31+) and `fullBackupContent` (API 26–30) must use `device_database` / `device_file` / `device_sharedpref` domains, and exclude `runtime.db`. Also state that after restore the app runs `rescheduleAll()` and flags House Hunt alarms for re-capture (NFR-14).

---

## What is solid (keep)

- AD-1 dependency graph with a Gradle-enforced `:core` whitelist; `:composeApp` never sees `:data`.
- AD-2's pure reducer + single `SessionEngine` + write-ahead persistence is the right shape; the fixes above are content, not structure.
- AD-5 manifest-permission test and the no-background-activity rule map cleanly to NFR-13.
- AD-6 two-database split (backup vs runtime) matches NFR-14 and FR-ALM-11.
- AD-7 reconciler outcomes map 1:1 to the PRD recovery table rows; catalogue creation by script.
- AD-8 `Money(micros, currency)` with per-currency totals.
- AD-10 generated tokens with CI diff check; AD-14 `qualityGate` and the `human-verify` rule for the Ralph loop.
