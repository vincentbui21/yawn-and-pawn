---
stepsCompleted: [step-01-validate-prerequisites, step-02-design-epics, step-03-create-stories, step-04-final-validation]
inputDocuments:
  - _bmad-output/planning-artifacts/prds/prd-pay-per-snooze-2026-09-26/prd.md
  - _bmad-output/planning-artifacts/architecture/architecture-pay-per-snooze-2026-09-26/ARCHITECTURE-SPINE.md
  - _bmad-output/planning-artifacts/ux-designs/ux-pay-per-snooze-2026-09-26/DESIGN.md
  - _bmad-output/planning-artifacts/ux-designs/ux-pay-per-snooze-2026-09-26/EXPERIENCE.md
---

# Yawn & Pawn - Epic Breakdown

## Overview

This document provides the complete epic and story breakdown for Yawn & Pawn, decomposing the requirements from the PRD, UX Design if it exists, and Architecture requirements into implementable stories.

## Requirements Inventory

### Functional Requirements

PRD IDs are kept exactly as in PRD v0.3 §7 (never renumbered). Priority: [Must] ships at launch · [Should] ships unless the build runs late · [Could] first to drop. Cut order if late (PRD §7): (1) all [Could]; (2) House Hunt FR-PWK-6; (3) FR-PRG-3; (4) FR-PRG-5; (5) FR-SND-6; (6) FR-SND-3/FR-SND-4. Minimum check set at launch: Math, Word Unscramble, Memory Sequence, QR/Barcode.

#### 7.1 Alarms (ALM)

- FR-ALM-1 [Must]: The user can create, edit, delete, and enable/disable multiple alarms.
- FR-ALM-2 [Must]: Each alarm stores time, repeat days (or one-time), label, sound, volume, vibration on/off, snooze length (5/9/10/15 min, default 9), check configuration, grace window length, and an optional motivation recording.
- FR-ALM-3 [Must]: Alarms fire at the exact scheduled time in Doze, silent mode and Do Not Disturb on the alarm audio stream; an automated test asserts `AlarmManager.setAlarmClock` is called with trigger time equal to the scheduled epoch ms (fake scheduler), and on-device timing (≤ 2 s, NFR-1) is covered by the device-verification checklist.
- FR-ALM-4 [Must]: When an alarm rings, a full-screen ringing screen appears over the lock screen whether the phone is locked or the screen is off (unlocked in-use phone behaviour: FR-SES-4).
- FR-ALM-5 [Must]: Scheduled alarms survive reboot, app update, time change, time-zone change and DST transitions.
- FR-ALM-6 [Must]: Per alarm, a "Gradually increase volume" switch (default on): on = ramp from 20% of the set level to the set level over 30 s (fixed, not user-editable; owner decision 2026-09-27); off = start at the set level. Always on the alarm stream, independent of the phone's media/ringer volume.
- FR-ALM-7 [Must]: Home shows the next alarm as a relative countdown (e.g., "Rings in 7 h 12 min").
- FR-ALM-8 [Must]: "Test alarm" runs the full flow (sound, checks, grace window) with no payment possible; the Snooze button shows the price but is disabled with the label "Test · no charge".
- FR-ALM-9 [Must]: If a single ring continues 30 minutes with no interaction (any tap on a wake screen), the alarm stops and the session is logged Missed; each interaction and each re-ring restarts the 30-minute timer, snooze time never counts, the timer pauses during a call, and it uses monotonic time (`elapsedRealtime`).
- FR-ALM-10 [Could]: (Approved by owner, Q9 closed) An optional, default-off pre-alarm notification lets the user skip the next occurrence up to 2 hours before; skipping is free and logged as Skipped.
- FR-ALM-11 [Must]: Alarms ring after an overnight reboot before first unlock (directBootAware `LOCKED_BOOT_COMPLETED` receiver; schedule, frozen session config and active session in device-protected storage); before first unlock, checks/sounds needing normal storage are replaced by Math + default built-in sound, and Snooze is shown disabled with "Unlock your phone to snooze"; after unlock Snooze becomes available while the substituted check stays for the current ring.
- FR-ALM-12 [Must]: On API 31–32 the app requests `SCHEDULE_EXACT_ALARM` (manifest `maxSdkVersion="32"`) with a `canScheduleExactAlarms()` check; API 33+ uses `USE_EXACT_ALARM`.

#### 7.1b Session integrity — no free escapes (SES)

Accepted escapes (never prevented): force-stop, uninstall, the 30-minute no-interaction timeout (Missed, breaks streak), powering off (session resumes on power-on per FR-SES-1).

- FR-SES-1 [Must]: Active-session state (ringing, grace window, in check, paying, snoozed) is persisted immediately and restored after process death, reboot or app update, with the alarm resuming ringing.
- FR-SES-2 [Must]: While a session is active, a backup exact alarm is always scheduled ≤ 60 s ahead (re-armed continuously; at snooze end while snoozed), so the alarm re-rings if the app is killed (Task Manager Stop, OEM kill, swipe from Recents).
- FR-SES-3 [Must]: While a session is active (including snoozed), opening the app shows only the wake screens / "Alarm in progress"; alarm editing, deleting, settings and "Delete all data" are unavailable, with no effect on other apps or the rest of the phone.
- FR-SES-4 [Must]: When the user leaves the ringing screen (Home, app switch, Recents), the app never relaunches its activity from the background; a foreground service keeps the sound playing with an ongoing high-priority notification whose tap (or opening the app) returns to the ringing screen within 1 s; if the notification is dismissed (Android 14+) the sound continues. Pass: sound never stops, return ≤ 1 s, no background activity start.
- FR-SES-5 [Must]: System clock or time-zone changes during a session do not end, skip or shorten it.
- FR-SES-6 [Must]: The alarm plays on the alarm stream at the set volume, (re)applied at the start of each ring and when a grace window ends; volume keys are captured only while the ringing screen is foreground; vibration continues during grace only if "vibrate in grace" is on.
- FR-SES-7 [Must]: An alarm due during an active session is merged: the session keeps its frozen config and fee ladder; if due during a snooze, the snooze ends early and the session re-rings then with no fee and no extra grace window; the absorbed occurrence is logged Merged and its next occurrence is scheduled normally.
- FR-SES-8 [Must]: Calls are detected via audio focus loss (no `READ_PHONE_STATE`); an incoming/ongoing call pauses the alarm sound, grace countdown and 30-minute timer, which resume when the call ends.
- FR-SES-9 [Must]: During a session Home, Recents, other apps, incoming/outgoing calls and the emergency dialer always work; the app never uses `SYSTEM_ALERT_WINDOW`, accessibility services, lock-task/kiosk mode or device admin.
- FR-SES-10 [Must]: Session conflicts follow PRD §6.4 rules: grace ends normally during payment (alarm resumes full volume under the Play sheet); purchase granted during a check wins (ring stops, check progress discarded, re-ring gets fresh check and grace); overlapping alarm merges; call pauses; reboot/process death restores (past snooze end rings immediately, restored ring gets a fresh 30-min timer); before first unlock applies Direct Boot substitution with Snooze unavailable.

#### 7.2 Ringing screen & snooze payment (RNG)

- FR-RNG-1 [Must]: The ringing screen shows time, alarm label, primary "I'm up" (starts the check), secondary "Snooze · {localized price}", today's snooze count and currency-formatted amount paid this session.
- FR-RNG-2 [Must]: Tapping Snooze opens a confirmation step showing the exact Play price, snooze length, next snooze price, a tax note where prices exclude tax, and a supportive line, with buttons "I'll get up" / "Pay {price} and snooze" and no pre-selection (copy/layout per EXPERIENCE.md).
- FR-RNG-3 [Must]: On confirm, the purchase intent is persisted (§6.3) before the Google Play purchase sheet opens; the device is unlocked first if Play requires it (Spike S1).
- FR-RNG-4 [Must]: A snooze is granted only when the purchase is `PURCHASED` and linked to the current session (§6.3); on cancel, error, no connection or pending the user returns to the ringing screen with the message mapped to that `BillingResponseCode`/purchase state (one string resource per reason; mapping table unit-tested).
- FR-RNG-5 [Must]: The alarm keeps ringing at full volume throughout the payment flow; if Snooze is tapped during a muted grace window, the mute lasts only until that window's countdown ends.
- FR-RNG-6 [Must]: When a snooze is granted the alarm stops and re-rings after the snooze length, and the next snooze costs B × (N+1).
- FR-RNG-7 [Must]: When snooze is unavailable (reasons from `snoozeAvailability`, AD-7: test mode, offline, before first unlock, prices not loaded yet, max snoozes reached, price cap reached, payment pending, earlier payment being refunded) the Snooze button is shown disabled with the reason.
- FR-RNG-8 [Must]: After a paid snooze the screen shows exactly "Snoozed. Next ring at {time}." (no guilt, no celebration).
- FR-RNG-9 [Must]: "I'm up" is reachable in ≤ 1 tap from every ringing state (ringing screen, confirm sheet, payment error), online or offline; Snooze is never the only way to stop the sound.
- FR-RNG-10 [Must]: When a snooze purchase hits a stranded token for the same product (`ITEM_ALREADY_OWNED` or pre-launch query), the app offers "You already paid {price} earlier that wasn't used. Use it for this snooze?" with "Use it" (grant + consume, history row becomes a normal paid snooze) / "Not now" (Snooze at that price disabled "An earlier {price} payment is being refunded").

#### 7.3 Proof-of-wake checks (PWK)

- FR-PWK-1 [Must]: Available check types are Memory Sequence, Math, House Hunt, QR/Barcode and Word Unscramble (House Hunt itself is [Should] via FR-PWK-6).
- FR-PWK-2 [Must]: Per alarm the user selects one or more check types and a mode: Random (one random type from the selection) or All (every selected type in the user-arranged order).
- FR-PWK-3 [Must]: Each check type has a per-alarm difficulty (Easy/Medium/Hard) and count.
- FR-PWK-4 [Must]: Memory Sequence lights grid tiles in a sequence of length 4/6/8 by difficulty that the user repeats; a wrong tap restarts that round with a new sequence.
- FR-PWK-5 [Must]: Math generates 1–10 problems: Easy 2-digit add/subtract; Medium 2-digit × 1-digit plus add; Hard multi-step with multiplication.
- FR-PWK-6 [Should]: House Hunt: at setup the user photographs one or more reference spots far from bed; to dismiss they photograph the same spot, accepted when on-device similarity ≥ the Spike S3 threshold.
- FR-PWK-7 [Must]: QR/Barcode: at setup the user registers any barcode/QR; to dismiss they scan it with on-device scanning.
- FR-PWK-8 [Must]: Word Unscramble: unscramble N English words, length 4–5 / 6–7 / 8+ by difficulty.
- FR-PWK-9 [Must]: Tapping "I'm up" mutes the alarm for the grace window (15–30 s per alarm, default 20) with a visible countdown; finishing within it ends the session; expiry returns the alarm at full set volume until the check is done with progress kept; one grace window per ring.
- FR-PWK-10 [Must]: Snooze stays available (subject to RNG rules) during the check; if granted, check progress is discarded.
- FR-PWK-11 [Must]: If a check can't physically be done (CameraX error or no frame within 5 s, QR lost, House Hunt fails 5 matches), the user can switch once per session to a fallback check chosen from non-camera checks (Math listed first) at Hard difficulty with double count; the link appears immediately when camera/permission is unavailable, otherwise after 5 failed attempts; the alarm keeps ringing with no new grace window; fallback use is shown in stats and 3 fallbacks in 7 days prompts re-registration.
- FR-PWK-12 [Must]: Check setup screens let the user try each check before saving.
- FR-PWK-13 [Could]: The app generates a printable QR code for the QR/Barcode check.

#### 7.4 Sounds & motivation (SND)

- FR-SND-1 [Must]: A built-in library of ≥ 10 royalty-free alarm sounds plus system ringtones; every bundled sound peaks ≥ −3 dBFS with integrated loudness ≥ −14 LUFS, enforced by a script in the quality gate.
- FR-SND-2 [Must]: The user can preview any sound before choosing it.
- FR-SND-3 [Should]: The user can record motivation messages in-app (≤ 60 s), re-record, play back and delete them; multiple recordings allowed, one chosen per alarm or "random".
- FR-SND-4 [Should]: Per-alarm motivation playback "After I'm up" plays the recording once the check is completed.
- FR-SND-5 [Must]: If a chosen sound file or recording is missing or broken, the default built-in sound plays; the alarm is never silent.
- FR-SND-6 [Should]: The user can pick an audio file from the device as an alarm sound (subject to FR-SND-5).
- FR-SND-7 [Could]: Motivation playback option "Mix into alarm" alternates the recording with the alarm sound.

#### 7.5 Wake-up progress (PRG)

- FR-PRG-1 [Must]: Every session is logged with scheduled time, first ring, end time, snoozes, amount paid (priceAmountMicros + currency per snooze), check types, time to complete, fallback used, Direct Boot flag and outcome (On time / Snoozed / Missed / Skipped / Test; Skipped and Test excluded from rates and streaks).
- FR-PRG-2 [Must]: The Progress screen shows current and best zero-snooze streak, on-time rate (7/30 days), average minutes from first ring to up, snoozes over the last 30 days (a count; each snoozed morning is marked on the 30-morning ring), and money paid this week/month/all-time computed per currency from micros (one line per currency; zero shown as "Nothing paid", never "$0"); layout and motion follow EXPERIENCE.md (ring of the last 30 mornings, stat tiles, streak card, money and insight; no period tabs).
- FR-PRG-3 [Should]: A calendar view colours each day by outcome.
- FR-PRG-4 [Must]: Purchase history lists date, alarm, snooze number and currency-formatted price; stranded purchases show "Not used, refunded automatically by Google" (or the reused snooze after FR-RNG-10).
- FR-PRG-5 [Should]: An optional weekly summary notification (default on, Sunday 19:00 local [A6]) summarises on-time mornings and amount paid (e.g., "3 on-time mornings, nothing paid. Nice.").
- ~~FR-PRG-6 [Could]: The user can export history as CSV.~~ Removed (owner decision 2026-10-01, PRD v0.3).

#### 7.6 Messaging: "we don't want you to pay" (MSG)

- FR-MSG-1 [Must]: Onboarding explains the mission on one screen: "This app makes money only when you snooze. We hope you never pay us."
- FR-MSG-2 [Must]: Home highlights zero-snooze progress (streak, "Nothing paid this week" or the currency-formatted amount) above everything else.
- FR-MSG-3 [Must]: Zero-snooze mornings get one celebration screen after the check (≤ 3 s animation, copy/motion per EXPERIENCE.md); paid mornings are never shamed.
- FR-MSG-4 [Must]: Copy guidelines in EXPERIENCE.md → Voice and Tone apply to every screen and notification.

#### 7.7 Onboarding & reliability setup (ONB)

- FR-ONB-1 [Must]: First launch runs in order: mission → alarm behaviour disclosure and consent → base fee → first alarm → checks → permissions → analytics choice → test alarm.
- FR-ONB-2 [Must]: A permissions & settings checklist shows each item with a reason and status tick: notifications; full-screen alarm (verify, deep-link); exact alarms (API 31–32 only); DND allows alarms (via interruption filter/notification policy, warn if blocked); battery optimization exemption (deep-link); OEM auto-start guidance (Xiaomi, Samsung, Huawei, Oppo/Realme, Vivo, OnePlus); camera (only if House Hunt or QR selected); microphone (only when recording).
- FR-ONB-3 [Must]: The checklist is available later in Settings and flags anything revoked (e.g., "Alarm may not ring: battery optimization turned back on").
- FR-ONB-4 [Must]: The onboarding test-alarm step asks for a test with the screen locked, skippable only via explicit "Skip for now"; the checklist item stays unticked until a locked-screen test completes.
- FR-ONB-5 [Must]: Before the first alarm is saved, one screen discloses that the alarm rings until the check is done or a snooze is paid, that the phone stays fully usable, and lists accepted escapes; the user must tap "I understand"; also shown under You → "How payments & refunds work".
- FR-ONB-6 [Must]: Onboarding asks once whether to share anonymous usage statistics, default off, changeable in Settings.

#### 7.8 Settings (SET)

FR-SET-3, FR-SET-4 and FR-SET-5, together with Purchase history (FR-PRG-4), are reached from the **You** tab, not Settings. Settings keeps app behaviour only: Snooze, Wake, Appearance, Notifications, Usage stats and the Reliability checklist. There is still no account or sign-in (NFR-4).

- FR-SET-1 [Must]: Settings for base fee ($1–$10 in $1 steps, commitment lock rules), max snoozes per session (default 5, min 1), default grace window (Quiet time) and default snooze length.
- FR-SET-2 [Must]: Settings includes the reliability checklist (FR-ONB-3).
- FR-SET-3 [Must]: Settings includes privacy policy, terms, support contact and "How payments & refunds work" (including the FR-ONB-5 disclosure).
- FR-SET-4 [Must]: Settings includes "Delete all data".
- FR-SET-5 [Must]: "Problem with a charge?" explains Google's 48-hour self-refund, links to Google Play order history, and offers a support email pre-filled with order ID and time from local history.
- FR-SET-6 [Must]: Settings has a toggle for anonymous usage statistics, default off.

#### Core rules referenced by FRs (PRD §6.2, §6.3 — normative for FR-RNG, FR-SET-1, FR-PRG-4)

- Fee ladder: Nth snooze in a session costs B × N (USD tiers); B min $1, $1 steps, max $10; per-snooze cap $50 (snooze above cap not offered); max snoozes per session default 5 (user may lower, min 1).
- B, max snoozes and snooze length are frozen at session start; settings changes apply from the next session.
- Commitment lock: within 8 h of an enabled alarm, weakening changes (lower B, easier checks, longer grace, more snoozes) are saved but apply after that alarm; strengthening applies immediately; disable/delete allowed with confirmation and logged.
- Currency: always display Play's localized price string; never hard-code "$" or any currency symbol; product details cached for offline fee picker, refreshed at each session start when online.
- Billing: purchase intent persisted before `launchBillingFlow` with `obfuscatedProfileId = sessionId`; grant rule, consume-with-retry, pending never grants, recovery table (4 rows), stranded reuse, `ITEM_ALREADY_OWNED` handling for granted-unconsumed tokens (consume then retry once); refunds not detectable client-side.

### NonFunctional Requirements

- NFR-1 Reliability: Alarm starts within 2 s of scheduled time on the device matrix (the owner's Oppo A96 (ColorOS, Android 13) plus Gradle Managed Device emulators (API 26, 31, 34, 36, 37); other makers (Xiaomi, Pixel hardware, budget phones) via optional Firebase Test Lab runs) with screen off, locked, Doze, DND and battery saver. Verified by Spike S2 and the alarm-core device-verification checklist.
- NFR-2 Never silent: Any failure (sound file, audio focus, crash in checks) triggers the default sound fallback (FR-SND-5); a crash or kill in the ringing flow is recovered by FR-SES-1/2.
- NFR-3 Offline: Everything except payment works offline; Snooze is unavailable offline and says so.
- NFR-4 Privacy: No account and no backend; data, photos and recordings stay on device except Auto Backup (media excluded), Crashlytics and opt-in Analytics; House Hunt/QR camera images processed on-device and never uploaded.
- NFR-5 Payments: Google Play Billing only; price always shown before purchase; complies with Play Payments policy; IARC content rating (likely low + "In-app purchases"); Play target audience 18+; onboarding recommends enabling Play purchase authentication.
- NFR-6 Platform: minSdk 26 (Android 8.0), targetSdk 36 (Android 16), Play Billing Library 8+ (architecture pins 9.1.0; compileSdk 37).
- NFR-7 Performance: Ringing screen visible ≤ 1 s after alarm trigger; app cold start ≤ 1.5 s on the reference mid-range device (Pixel 6a [A7]).
- NFR-8 Battery: No background work except scheduled alarms; no persistent service while idle.
- NFR-9 Accessibility: TalkBack labels, 48 dp touch targets, large-font support; each check type has an accessible alternative or the fallback check (known gap Q12: visual Memory Sequence).
- NFR-10 Localization-ready: All strings in resources; currency always formatted from Play data; English only at launch (Q7 closed).
- NFR-11 Maintainability & testability: Business logic (fee engine, session state machine, check generators/validators, stats, billing recovery) in the platform-independent shared module with ≥ 90% line coverage enforced by Kover; all time via injectable `Clock` (wall + monotonic); billing, scheduling, audio, camera behind interfaces with fakes; every feature story has CLI-runnable acceptance checks (unit with fakes, Robolectric, emulator); debug-only "fire now / time warp" hook never in release; human-verify protocol (one device-verification checklist story per epic, tagged `human-verify`, owner records pass/fail with device, Android version, date; Ralph never marks it done; failures become bug stories; ≈ 2–4 h owner time per epic [A4]); instrumented tests on Gradle Managed Devices in CI.
- NFR-12 Shareability with iOS: The shared module must not depend on Android APIs.
- NFR-13 Play policy — no device hostage: Never block Home, Recents, power menu, calls, emergency dialer or other apps; never start activities from background; no `SYSTEM_ALERT_WINDOW`, accessibility services, lock-task or device admin; never reroute audio away from the user's chosen output (alarm stream at set volume); volume keys captured only while ringing screen is foreground; free path ("I'm up" → check) shown first and works offline; App content core-functionality declaration and FGS demo video show the free path and the phone staying usable.
- NFR-14 Backup: Android Auto Backup (`dataExtractionRules`) backs up alarms, settings and history to the user's Google account; excludes House Hunt photos, motivation recordings, custom audio references, the active session and pending purchase intents; after restore, House Hunt alarms ask to re-take reference photos; disclosed in privacy policy and Data safety form.
- NFR-15 Telemetry: Firebase Crashlytics always on (disclosed, no personal content, no free text or media); Firebase Analytics only on opt-in (default off), limited to anonymous events `session_outcome`, `snooze_count`, `check_type`; no advertising ID, no other user properties; disclosed in privacy policy and Data safety form [A1].

### Additional Requirements

#### Starter template / project scaffold — IMPORTANT for Epic 1 Story 1

- **No external starter template is specified by the Architecture.** Epic 1 Story 1 MUST scaffold the project from scratch as an AGP 9 Kotlin Multiplatform project using the JetBrains KMP wizard structure, adapted to the Architecture Spine's Structural Seed: modules `:core` (KMP, commonMain only), `:data` (KMP; `data/schemas/`), `:composeApp` (KMP, Compose Multiplatform), `:androidApp` (Android application), `:testing` (KMP fakes), plus `tools/tokens`, `tools/play-catalog`, `config/` (detekt rules, `dependency-allowlist.txt`, permission allowlist), `docs/`, and `.github/workflows/ci.yml`. Dependency graph exactly: androidApp → composeApp, data, core; composeApp → core; data → core; testing → core; test-only edges androidApp/composeApp/data → testing.
- Stack versions to pin at scaffold: Kotlin 2.4.20, Compose Multiplatform 1.12.1, AGP 9.3.3, Gradle 9.7.1, JDK 17, compileSdk 37 / targetSdk 36 / minSdk 26, KSP 2.3.11, Room KMP (`androidx.room3`) 3.0.3, DataStore 1.2.1, kotlinx-coroutines 1.11.0, kotlinx-datetime 0.8.0, kotlinx-serialization 1.11.0, Navigation 3 (JetBrains CMP) 1.1.2, Koin 4.2.2, Play Billing 9.1.0, CameraX 1.6.2, ML Kit barcode-scanning (bundled) 17.3.0, MediaPipe tasks-vision 1.0.0, WorkManager 2.11.0 (verify at scaffold), Firebase BoM 34.19.0, Kover 0.9.8, detekt 2.0.0-alpha.5, Spotless (ktlint) 8.8.0, Roborazzi 1.74.0, Turbine 1.2.1.
- Scaffold-time confirmations: OQ-2 Room 3.0 vs 2.8 on KMP with AGP 9 (fallback 2.8.5); OQ-3 Roborazzi with AGP 9 host tests inside `:androidApp` (fallback Compose Preview screenshot testing); Geist `tnum` support (fallback Geist Mono for `clock-xl`); WorkManager version.
- Package root `com.yawnandpawn.app` [assumption pending app name Q8], sub-packages `core.session`, `core.billing`, `core.checks.<type>`, `core.alarm`, `core.config`, `core.stats`, `data.db`, `ui.<screen>`, `android.<adapter>`.
- Note: the Architecture quality gate (AD-14: Spotless, `:core`) supersedes the PRD §9 command line (ktlintCheck, `:shared`).

#### Architecture decisions (AD-1 … AD-18) as actionable requirements

- AD-1 Core is platform-free: `:core` has only commonMain sources and depends only on Kotlin stdlib, kotlinx-coroutines, kotlinx-datetime, kotlinx-serialization; no `android.*`, `androidx.*`, Compose, Room or Koin imports; `:composeApp` never depends on `:data`; a Gradle check fails the build if `:core` declares any other dependency.
- AD-2 One owner of the wake session: implement the session as a pure reducer `reduce(state, event, now): Transition(state, oneShotEffects)` plus idempotent `entryEffects(state)`, called only by a single Mutex-serialized `SessionEngine`; each transition committed to `runtime.db` in one transaction (with ledger rows) before effects run; on `ProcessRestored` only entry effects run, `paying` cleared, billing never relaunched, fresh 30-min deadline; `SessionState` holds sessionId, frozen `SessionConfig`, ringIndex, snoozesGranted, `CheckRun` (plan, seeds, step, failed attempts, fallbackUsed), `paying`, `noGraceThisRing`, `paused`, and `Deadline`s; adapters only execute effects and feed events; UI emits only user events (`ImUpTapped`, `CheckAnswerSubmitted`, `SnoozeTapped`, `PayConfirmed`, `ReuseAccepted`, `ReuseDeclined`, `FallbackRequested`, `UserInteracted`); the normative 31-row (architecture v0.3) transition table is encoded with one parameterised test per row plus a table-coverage test; unmatched events are ignored and logged, never thrown; next price always `FeeLadder(config.baseFeeTier, snoozesGranted + 1)`; grace keeps counting while `paying` is set.
- AD-3 Time from ports only: core reads time only via `Clock`, `MonotonicClock` (`elapsedRealtime`) and `BootCounter` (`Settings.Global.BOOT_COUNT`); deadlines stored as `Deadline(wallMillis, elapsedMillis, bootCount)` (same boot → monotonic, different boot → wall); adapter converts to wall time only when arming `setAlarmClock` and re-arms on `TIME_SET`/`TIMEZONE_CHANGED`; `Clock.System` banned outside adapters (detekt rule); occurrence math in core unit-tested across DST and time-zone changes.
- AD-4 One scheduler, one slot per session: `AlarmScheduler` port is the only way to schedule system alarms (adapter uses only `setAlarmClock()`); request codes per occurrence (alarmId-derived) and exactly one session slot (60 s heartbeat while ringing, re-ring while snoozed) whose receiver emits only `SlotFired`; `core.rescheduleAll()` recomputes all occurrences from `app.db` on `BOOT_COMPLETED`, `LOCKED_BOOT_COMPLETED`, `TIME_SET`, `TIMEZONE_CHANGED`, `MY_PACKAGE_REPLACED`, `SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED`, after backup restore and on app start; receivers, `WakeService`, `WakeActivity` are `directBootAware`; no FGS started from boot receivers; `USE_EXACT_ALARM` (33+) and `SCHEDULE_EXACT_ALARM` `maxSdkVersion="32"`.
- AD-5 Wake runtime: occurrence/slot broadcast starts `WakeService` (foreground, type `mediaPlayback` pending Spike S2 vs `systemExempted`, ongoing notification with full-screen intent to `WakeActivity`), which owns the only `AlarmPlayer` (`USAGE_ALARM`); `WakeActivity` uses `setShowWhenLocked`/`setTurnScreenOn` (API 27+) and window flags (API 26), renders wake UI and forwards input as events; volume keys consumed only in foreground; calls detected by `AudioManager.getMode()` in-call/in-communication (on focus loss and mode listener API 31+), other apps taking focus do not pause; no `READ_PHONE_STATE`; no background activity starts, overlays, accessibility service, device admin or kiosk; manifest permission allowlist test (`INTERNET`, `com.android.vending.BILLING`, `POST_NOTIFICATIONS`, `USE_EXACT_ALARM`, `SCHEDULE_EXACT_ALARM` ≤ 32, `USE_FULL_SCREEN_INTENT`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MEDIA_PLAYBACK`, `RECEIVE_BOOT_COMPLETED`, `WAKE_LOCK`, `VIBRATE`, `CAMERA`, `RECORD_AUDIO`, plus reviewed library-merged permissions) fails the build on anything else.
- AD-6 Storage split: two Room KMP databases in device-protected storage (paths from `createDeviceProtectedStorageContext()`): `app.db` (alarms, check configs, session history, purchase records; backed up) and `runtime.db` (active session, purchase intents, grant ledger; not backed up); global settings only in device-protected DataStore (`PreferenceDataStoreFactory.createWithPath`), no settings copy in `app.db`; media (House Hunt photos, recordings, custom sounds) in credential-protected storage, excluded from backup; `dataExtractionRules` (API 31+) and `fullBackupContent` (≤ 30) target the device-protected domain and exclude `runtime.db` and media; only `:data` touches databases; migrations and exported schemas under `data/schemas/`.
- AD-7 Purchases: `FeeLadder` maps (baseFeeTier, snoozeNumber) → `snooze_usd_NN` (01..50); pure `snoozeAvailability(state, config, env)` is the only source of Available(price)/Unavailable(reason) with reasons test mode, offline, before first unlock, catalogue not loaded, max snoozes reached, price cap reached, payment pending, earlier payment being refunded; core persists `PurchaseIntent(intentId, sessionId, productId, snoozeNumber, priceMicros, currency)` before launch; adapter launches with `obfuscatedProfileId = sessionId`, `obfuscatedAccountId = installId` (random, in DataStore); pure `PurchaseReconciler` returns `Grant`/`ConsumeOnly`/`LeaveForAutoRefund`/`OfferReuse`/`Ignore` per PRD §6.3 (Grant needs PURCHASED, profileId = active session, not Snoozed, token not in ledger, product = expected next); grant ledger (token, sessionId, status) in `runtime.db` written in the same transaction as `PurchaseGranted`; `PurchaseLedger` is the only writer of purchase records in `app.db` (idempotent upsert by token; statuses granted, consumed, stranded, reused); order: commit transition + ledger → upsert record → consume (retry via AD-17) → mark consumed → delete ledger row; restore replays from ledger.
- AD-8 Money: `Money(micros: Long, currency: ISO 4217)` is the only money type; totals grouped by currency; formatting only in UI via `MoneyFormatter` port; pre-purchase prices are Play's `formattedPrice` cached with micros and currency.
- AD-9 Checks as plugins: sealed `CheckType` in core, each with deterministic `generate(seed, difficulty, count): Puzzle` and `validate(puzzle, answer): StepResult`; seeds in `CheckRun`; sensor checks: adapter captures and emits `CheckAnswerSubmitted` with capture, image matching runs as an effect via `ImageMatcher` port and returns as an event; thresholds are core config; UI registers one composable per `CheckType` in `CheckRegistry` and never decides correctness.
- AD-10 Generated design tokens: `tools/tokens` parses DESIGN.md YAML frontmatter and generates committed `composeApp/.../theme/PpsTokens.kt` (Light, Dark, Sunrise); CI regenerates and fails on diff; composables use `PpsTheme` only; raw `Color(0x…)`, raw radii and raw `sp` outside the theme package fail detekt; dynamic colour off.
- AD-11 UI state, navigation, strings: one ViewModel per screen (androidx lifecycle KMP) with immutable `StateFlow<UiState>`, single `onIntent(Intent)`, side effects via `Channel<UiEffect>`; Navigation 3 with sealed `@Serializable Route : NavKey` registered via `subclassesOfSealed`; wake flow lives in `WakeActivity`, not the nav graph; while a session is active the main app shows only "Alarm in progress" driven by `SessionEngine.state`; strings from Compose Multiplatform resources only; error copy keyed by `DomainError`.
- AD-12 Errors as values: ports return `Outcome<T, DomainError>`; adapters map platform exceptions; core never throws for expected failures; an uncaught exception in the wake flow is caught at the `WakeService` boundary, reported, and followed by `ProcessRestored` with the default sound.
- AD-13 Koin 4.2 DI: each module exposes one `val xxxModule = module { }`; `:core` has no Koin dependency (constructor params, wired in `:androidApp`); tests build objects directly with `:testing` fakes.
- AD-14 Quality gate: a story is done only when `./gradlew qualityGate` passes: Spotless check, detekt, `:core:allTests`, `:data:allTests`, `:composeApp` host tests, `:androidApp:testDebugUnitTest` (incl. Roborazzi verify), `koverVerify` (core ≥ 90% lines), `:androidApp:lintDebug`, `:androidApp:assembleDebug`, token diff check, manifest allowlist test, dependency allowlist check, sound loudness script; instrumented tests on Gradle Managed Device in CI; every port has a fake in `:testing`; debug-only receiver fires a test alarm in N seconds; `human-verify` stories also need the owner's checklist result and automation never marks them done.
- AD-15 Network and telemetry allowlisted and consent-gated: `Telemetry` port with sealed `TelemetryEvent` (`session_outcome`, `snooze_count`, `check_type` only); Analytics auto-collection disabled in manifest, enabled at runtime only when consent is true; Crashlytics sets no personal custom keys; Firebase and Billing initialise only after first unlock; only network-capable deps are Play Billing, Firebase (Crashlytics, Analytics), ML Kit/MediaPipe (on-device; declare any Google usage logging in Data safety); CI task compares resolved runtime deps to `config/dependency-allowlist.txt` and fails on anything new.
- AD-16 Settings resolved once per session, commitment lock in core: global (DataStore) and alarm (`app.db`) settings edited only through core use cases; weakening change within the lock window stored as `PendingChange(field, value, effectiveAfterOccurrence)`; pure `ConfigResolver` produces effective config; resolved `SessionConfig` frozen into `SessionState` at `AlarmFired`, nothing reads live settings during the session; disable/delete inside the window allowed with confirmation and recorded.
- AD-17 Background work: `BackgroundWork` port (WorkManager adapter) for all non-alarm work (weekly summary, consume retries, catalogue price refresh); nothing on the wake path depends on it.
- AD-18 History and stats single writer: `SessionRecorder` is the only writer of session history (one row per sessionId, upsert, replay-safe); stats (streaks, rates, totals, calendar) are pure functions in `core.stats` over history and purchase records; no stored derived stats.

#### Consistency conventions

- Naming: ports plain names (`AlarmScheduler`), adapters `Android<Port>`/`Room<Port>`, fakes `Fake<Port>`; events past tense (`AlarmFired`), intents imperative (`SaveAlarm`).
- IDs: UUID v4 strings for alarms, sessions, intents, installId; product ids `snooze_usd_NN`.
- Time: instants as epoch millis (Long); wall times as `LocalTime` + `TimeZone` id; deadlines per AD-3; never store formatted dates.
- Coroutines: core exposes `suspend` and `Flow`; injected dispatchers; no `GlobalScope`.
- Logging: `Logger` port; no `println`/`Log.d` in core; never log purchase tokens, photos or recordings.
- Tests: names are backticked sentences; session and reconciler tests table-driven from AD-2 / PRD §6.3 tables.
- Git: conventional commits with story id (`feat(e2-s3): …`); one story per branch or commit series.

#### Data model (entities to implement)

- `app.db`: ALARM, CHECK_CONFIG, PENDING_CHANGE, SESSION_HISTORY, PURCHASE_RECORD, RECORDING/REFERENCE_MEDIA references. `runtime.db`: ACTIVE_SESSION, PURCHASE_INTENT, GRANT_LEDGER. Relationships per the Architecture ER diagram.

#### PRD technical items not captured as FRs

- Play catalogue: 50 consumable one-time products `snooze_usd_01` … `snooze_usd_50` (31 reachable today; rest deliberate headroom), created/updated only via `tools/play-catalog` using the Play Developer API (`onetimeproducts`) in a reviewed commit (pricing templates discontinued); pending purchases must be enabled for one-time products (PBL 8+).
- Unit test: `productFor(fee)` exists for every reachable (B ≤ 10, N ≤ 5) combination.
- Unit tests against a fake BillingClient for every row of the §6.3 recovery table, the reuse offer, and `ITEM_ALREADY_OWNED` cases (pending→purchased during/after session, lost callback, crash between grant and consume, duplicate delivery).
- `BillingResponseCode`/purchase-state → message mapping table unit-tested (one string resource per reason).
- Consume retries with backoff on app start, resume and via WorkManager until success; before launching product P, consume any granted-but-unconsumed token for P.
- Recovery runs on app start, resume and ringing-screen open via `queryPurchasesAsync`.
- Product details cached for offline fee picker; refreshed at every session start when online.
- History stores `priceAmountMicros` + currency per purchase, never only a display string.
- Onboarding recommends turning on Play purchase authentication (NFR-5).
- Deferred review findings to resolve before specific epics: Q10 (check parameter ranges, Math Hard bounds, word list + offensive-word filter) before check-type stories; Q12 (TalkBack fallback path) before the proof-of-wake engine stories; Q15 (calendar colour with several sessions per day; outcome of alarm disabled inside lock window) before progress stories; Q19 (property/mutation tests) at foundation planning.

#### Environments and operations

- Build types: `debug` (no applicationId suffix so license-tester Play Billing works; test-alarm receiver; Firebase debug project) and `release` (R8, Play App Signing, Firebase prod project).
- Versioning: `versionName` semver; `versionCode = major*10000 + minor*100 + patch`.
- CI: GitHub Actions runs the `qualityGate` and managed-device instrumented tests on every push; git tag `vX.Y.Z` → CI builds signed AAB and uploads to the Play internal track; hotfix = patch tag from `main`.
- Play tracks: internal → closed test (≥ 12 testers × 14 days) → production with staged rollout 10% → 50% → 100%; promotion manual in Play Console.
- Secrets (upload key, Play service-account JSON, `google-services.json`) only in GitHub Actions secrets and the owner's machine, never in the repo.
- Privacy policy and support page hosted on GitHub Pages from `docs/`, linked from Play Console.
- Monitoring after launch: Android vitals and Crashlytics checked weekly; any crash in the wake path is a release blocker.

#### Play Console declarations and launch blockers

- `USE_EXACT_ALARM` (alarm clock core use case); `SCHEDULE_EXACT_ALARM` with `maxSdkVersion="32"`.
- Direct Boot receiver (`LOCKED_BOOT_COMPLETED`); re-arm via exact alarm (no media FGS from `BOOT_COMPLETED` on Android 15+).
- `USE_FULL_SCREEN_INTENT` with App content core-functionality declaration.
- Foreground service type declaration with demo video showing the free path and the phone staying usable.
- Data safety form (Auto Backup, Crashlytics, opt-in Analytics, any ML Kit/MediaPipe usage logging), privacy policy URL, IARC content rating, target audience 18+.
- EU DSA trader status with publishable business contact (not home address), or EU excluded at launch.
- Launch blockers: DSA trader status; privacy policy + Data safety; FGS/FSI/exact alarm declarations with demo video; pre-submission policy review against device-hostage risk; all `human-verify` checklist stories passed; closed test complete. Q17 EU/UK right-of-withdrawal consent to be resolved with the privacy/terms author before production.

#### Technical spikes (before feature epics)

- S1 Payment while ringing: can the Play purchase sheet be shown from the lock-screen ringing activity (with `requestDismissKeyguard`); typical purchase duration while ringing; does `obfuscatedProfileId` round-trip on every path incl. pending and promo codes (OQ-1); test with license testers. Decides FR-RNG-3/5 unlock mechanics; may add `UnlockRequested`/`UnlockFailed` events to the AD-2 table.
- S2 Alarm reliability & escapes: prototype alarm + full-screen ringing + backup alarm + FGS notification return path; overnight tests on device matrix (Doze, battery saver, DND, OEM killers, overnight reboot before unlock, Task Manager Stop, swipe from Recents, headphones connected, incoming call); confirm FR-SES-4 without background activity starts; decide FGS type `mediaPlayback` vs `systemExempted`; assess Q18 (backup alarm via `setAlarmClock` changing the system next-alarm indicator).
- S3 House Hunt matching: on-device image similarity (MediaPipe ImageEmbedder) accuracy across lighting/angle vs false accepts (photo of a photo, random room); pick threshold or downgrade/cut FR-PWK-6.

#### Human-verify protocol

- Every epic ends with one device-verification checklist story tagged `human-verify`, collecting behaviour that cannot be automated (lock screen, Doze, real billing, camera, OEM behaviour). The owner runs it on the device matrix (the owner's Oppo A96 (ColorOS, Android 13) plus Gradle Managed Device emulators (API 26, 31, 34, 36, 37); other makers (Xiaomi, Pixel hardware, budget phones) via optional Firebase Test Lab runs) and records pass/fail per item with device, Android version and date in the story file. Automation/Ralph never marks a `human-verify` story done; each failed item becomes a new bug story.

### UX Design Requirements

**Design baseline (sprint-change-proposal-2026-10-01).** From 2026-10-01 the approved design is DESIGN.md / EXPERIENCE.md v0.5 and the design-preview composables in `:composeApp` (`ui.*`). A UI story wires those stateless screens to its ViewModel and real data. It does not redesign them. Where a UX-DR below disagrees with the v0.5 spines, the spines win. A layout change needs the owner (memory: UI review before build).

#### Tokens and theme

- UX-DR1: `tools/tokens` generates `PpsTokens.kt` from DESIGN.md frontmatter with all colour tokens for three sets: Light (no suffix), Dark (`-dark`), Sunrise (`-sunrise`, plus `sunrise-gradient-top`), and typography, rounded and spacing tokens; generated file committed and CI fails on diff (AD-10).
- UX-DR2: `PpsTheme` wraps one Material 3 `MaterialTheme` with three token sets; Material You dynamic colour is off; app screens follow system light/dark with a Settings override System / Light / Dark; Dark is the primary design target; all wake screens (ringing, snooze confirm, check, fallback check picker, success, snoozed) always use Sunrise; Light, Dark and Sunrise are designed and screenshot-tested together.
- UX-DR3: Typography: bundle Geist (OFL) with system sans fallback; implement the 8-style ramp exactly: `clock-xl` 88/92 w300, `display` 48/52 w500, `headline` 28/34 w600, `title` 20/26 w600, `button-wake` 20/24 w500 (wake action labels only), `body` 16/24 w400, `label` 14/20 w500, `caption` 12/16 w400; tabular figures (`tnum`) for clocks, countdowns, prices, stats and list times (fallback Geist Mono for `clock-xl` if tnum missing); sentence case everywhere except day chips (M T W T F S S).
- UX-DR4: Shapes locked to `sm` 8 dp (chips, inputs, letter tiles, snackbars, thumbnails), `md` 16 dp (cards, list items, check tiles, number keys, viewfinder), `lg` 28 dp (sheets, dialogs), `full` (primary/wake buttons, the nav capsule and its "+", shutter, segmented control, badges); no other radii; detekt rejects raw radii, raw `Color(0x…)` and raw `sp` outside the theme package.
- UX-DR5: Spacing on a 4 dp base (`1`–`8` = 4–32 dp), screen margin 20 dp, section gap 24 dp, card padding 16 dp; touch targets `target-min` 48 dp everywhere, `target-wake` 64 dp for every wake action, `target-wake-hero` 72 dp for "I'm up" and the shutter; single-column layouts, one main job per screen.
- UX-DR6: Every screen draws the theme gradient (`PpsBackground`); cards are `glass`; bars and sheets over moving content are `glass-strong` with a hairline `glass-edge`, and are blurred only on API 31+; no glows or decorative shadows; no pure `#000000`/`#FFFFFF` backgrounds.
- UX-DR7: Colour usage rules enforced: one accent (Sunrise orange) for primary actions, selection and countdown ring; accent-coloured text on light backgrounds uses `accent-text`; prices always in `text` colour (never accent, green or red); `success` means on-time only and never money; `error` only for real errors; disabled uses the `disabled-container`/`disabled-content` token pair, not opacity; inverse tokens only for snackbars; accent never placed on `surface-variant-sunrise` (2.89:1) or the sunrise gradient (2.73:1).
- UX-DR8: The DESIGN.md verified contrast table is the gate: every colour pair used ships with a recorded WCAG ratio (text ≥ 4.5, graphics ≥ 3.0); any new pair is added to the table before it ships.
- UX-DR9: The ringing screen follows DESIGN.md v0.5 as built in the preview's `ui/wake` Ringing composable (Sunrise gradient over the top 40%, flat `bg-sunrise` thumb zone); accent never sits on the gradient.
- UX-DR10: "Bright wake screen" setting (default on) raises screen brightness to maximum on wake screens, restores it on "Done", respects the system Extra dim setting, and is explained in onboarding ("Your alarm screen turns bright to help you wake. Change it in Settings."); no blue-light health claims.
- UX-DR11: Icons are Material Symbols Rounded, weight 400, fill 0 (fill 1 for selected nav item and outcome markers).

#### Components — wake screens (Sunrise tokens)

- UX-DR12: `button-wake-primary` ("I'm up"): full width, 72 dp, `rounded.full`, `accent-sunrise` fill, `on-accent-sunrise` label in `button-wake`, largest element on every wake screen, on flat `bg-sunrise`; always enabled; starts check and grace window; never moves between rings.
- UX-DR13: `button-snooze`: one component on all wake screens; full width, 64 dp, `rounded.full`, 1 dp `outline-sunrise` border, `text-sunrise` label "Snooze · {price}"; 16 dp below "I'm up" on ringing, bottom of the check footer on check screens (same height); enabled tap opens `sheet-snooze-confirm`; live-updates when connectivity returns or a stranded token clears.
- UX-DR14: `button-snooze-disabled` variant: no border, `disabled-container-sunrise` fill, `disabled-content-sunrise` label stating the reason (5.63:1), leading `block` icon (or `lock` icon with "Unlock your phone to snooze" before first unlock; becomes "Snooze · {price}" in place after unlock), "Test · no charge" for test alarms, same 64 dp height; not tappable; TalkBack reads "Snooze unavailable, {reason}".
- UX-DR15: `sheet-snooze-confirm`: bottom sheet, `surface-sunrise`, top corners `rounded.lg`, 24 dp padding; order: title (`headline` "Snooze for {minutes} min?"), price (`display`, `text-sunrise`, never accent), next-price line (`body`), nudge (`body`, `text-secondary-sunrise`), tax note (`caption`) where prices exclude tax, then two stacked full-width 64 dp buttons: outlined "Pay {price} and snooze" above, filled "I'll get up" at bottom; three states: *confirm*, *unlocking* (lock icon, "Unlock to pay {price}", single outlined 64 dp "Cancel"), *already paid* ("Use it" outlined above, "Not now" filled bottom); ignores all input for 500 ms after opening and after every state change (including with animations off); neither button pre-selected or focused; swipe down / Back = "I'll get up" path (no charge); alarm keeps ringing throughout; Pay → unlocking (if locked) → keyguard → Play sheet, or Play sheet directly if unlocked; switches to already-paid instead of charging when a stranded token exists.
- UX-DR16: `countdown-ring`: 120 dp circle, 8 dp `accent-sunrise` stroke over `outline-subtle-sunrise` track, seconds centred in `display`; only on `bg-sunrise` or `surface-sunrise`; starts on "I'm up", linear and exact to the second, keeps counting behind the confirm and Play sheets; at 0 replaced by a solid `text-sunrise` 48 dp bell icon with "Alarm's back on", strong haptic, full volume; one window per ring.
- UX-DR17: `number-pad-key` (Math): 64 dp square keys, `rounded.md`, `surface-variant-sunrise` fill, digit in `title`; 3×4 grid, 8 dp gaps, backspace and "Check" keys in the grid; light haptic per tap; wrong answer: shake, error haptic, field cleared, announcement "Not quite. Try again."
- UX-DR18: `memory-tile` (Memory Sequence): ≥ 64 dp squares, `rounded.md`, `surface-sunrise` fill with 1 dp `outline-sunrise` border; lit = `accent-sunrise` fill with number in `on-accent-sunrise`; 3×3 grid (4×4 on Hard); input disabled while the sequence plays; wrong tap restarts the round with a new sequence; accessible variant shows numbers 1–9 on every tile and is announced.
- UX-DR19: `letter-tile` (Word Unscramble): 48 dp, `rounded.sm`, `surface-variant-sunrise` fill, `outline-sunrise` border, letter in `title`; answer slots are empty tiles with dashed border; tap moves a letter to the next empty slot, tapping a slot returns it; "Shuffle" and "Clear" actions.
- UX-DR20: `viewfinder`: full-width camera preview in a `rounded.md` frame; House Hunt adds a 72 dp ghost thumbnail of the reference photo top-left (`rounded.sm`); QR adds a centred square guide; 48 dp torch toggle; starts on screen open; if permission denied or camera fails (CameraX error or no frame within 5 s) shows "Camera isn't available. Pick a fallback check." with `fallback-link` immediately.
- UX-DR21: `shutter` (House Hunt): 72 dp circle, `accent-sunrise` fill, `on-accent-sunrise` camera icon, centred in thumb zone; captures and matches on device; result "Matched" or "Doesn't match yet. Try the same angle."; each non-match counts as a failed attempt.
- UX-DR22: `fallback-link`: text button "Can't do this check?" in `accent-text-sunrise`, `body`, 48 dp target, centred above the snooze button; appears immediately when camera permission is denied/unavailable/fails, otherwise after 5 failed attempts on a camera check; once per session; opens the Fallback check picker.
- UX-DR23: `motivation-player` (Success): `surface-sunrise` card, `rounded.md`, 48 dp play/pause and replay, accent progress bar; auto-plays when set to "After I'm up"; "Done" stops playback and closes.
- UX-DR24: `notification-ringing`: ongoing, alarm category, full-screen intent, monochrome sunrise small icon, accent tint, title = alarm time, text "{time} alarm · Tap to return to your alarm"; tapping anywhere returns to the wake screen; the sound never stops from the notification.

#### Components — app screens (Light/Dark tokens)

- UX-DR25: `button-filled`: app primary action (Save, Done, "Let's set it up"), 48 dp min, `rounded.full`, accent fill, on-accent `label`; one per screen.
- UX-DR26: `button-outlined`: app secondary action, 48 dp, `rounded.full`, `outline` border, `text` label.
- UX-DR27: `button-text`: tertiary actions ("Test alarm", "Try it", "Fix", links), `accent-text` label, 48 dp target.
- UX-DR28: `text-field`: Material outlined field, `rounded.sm`, `outline` border, `error` border and supporting text on error; Math answer field uses `display` digits and is read-only (filled only from the number pad); labels use the system keyboard.
- UX-DR29: `check-type-card`: `surface` card, `rounded.md`, icon + name (`title`) + one line (`body`, `text-secondary`) + "Try it" `button-text`; selected = 2 dp accent border plus check icon; used in onboarding, Check picker and Fallback check picker (Sunrise tokens there); tap toggles selection (picker) or starts that check (fallback picker); camera checks show "Needs the camera. If it can't be used, you'll get a fallback check."; with TalkBack, Memory Sequence shows "Uses numbered tiles with TalkBack."
- UX-DR30: `card-hero` sits inside the collapsing Home header: "Yawn & Pawn" is pinned top-left and gets a glass chip once content scrolls under it; the hero collapses into a compact pinned row as the list scrolls, tied to scroll position; reduced motion makes it an instant switch. The hero shows the streak number in `display`, `accent-text` (light) / `accent-dark` (dark), "days on time" in `body`, and the money-paid-this-week line in `text-secondary` (currency-formatted per currency, "Nothing paid this week" when zero, per FR-PRG-2/FR-MSG-2); not tappable; the streak number never counts up on Home.
- UX-DR31: `card-alarm`: `glass`, `rounded.md`, time in `title`, repeat days and label in `caption` (repeat summaries include "Every day", "Weekdays" (Mon–Fri), "Weekends" (Sat–Sun), "Once" and short day names), 20 dp check icons in `text-secondary`, `switch` on the right; tap → editor; long-press → menu with Duplicate and Delete (also in editor overflow for TalkBack); turning off within 8 h opens `dialog-confirm`.
- UX-DR32: ~~`fab`~~ Removed (owner decision, sprint-change-proposal-2026-10-01): the raised accent "+" in the centre of the nav capsule ("Add alarm", 56 dp, UX-DR44) creates an alarm from any tab, opening the editor with defaults.
- UX-DR33: `banner-warning`: `surface-variant` fill, `rounded.md`, leading `error` icon in `error`, message in `body`, `button-text` "Fix"; shown on Home and Settings when any reliability item fails ("Alarms may not ring. Fix settings"); not dismissible; clears itself when checklist is all OK (re-evaluated on every app foreground); info variant (info icon in `text-secondary`, no error colour, dismissible) for the fallback re-register prompt with "Re-register".
- UX-DR34: `panel-session-in-progress`: replaces all Home content during a session; `surface` card, "Alarm in progress" in `headline`, one `button-filled` "Back to alarm" that opens the wake screen.
- UX-DR35: `note-inline`: leading `info` icon + `caption` in `text-secondary` (Sunrise tokens on wake screens); read-only; used for commitment-lock notes, approximate prices, Direct Boot notice, call pause, one-time alarm "Rings tomorrow at {time}.", missed-session note.
- UX-DR36: `chip-day`: 48 dp, `rounded.sm`, one letter; unselected `surface-variant`, selected accent fill with on-accent bold letter; toggles; no days = one-time alarm at next occurrence; TalkBack reads full day names.
- UX-DR37: `chip-check`: `rounded.sm`, `surface-variant` fill, `outline` border, check icon + name + difficulty in `label`; tap → Check setup.
- UX-DR38: `segmented-control`: M3 segmented button, `rounded.full` ends, selected segment accent fill with check icon; single-select, always one selected; used for difficulty, check mode (Random/All) and theme.
- UX-DR39: `stepper`: value in `display` (tnum for prices) between two 48 dp round −/+ buttons; single steps, long-press repeats; lowering base fee under lock shows the lock note.
- UX-DR40: `slider`: M3 slider, accent active track, `outline` inactive, value label above thumb; value announced on change; steps of 1 s or 5%; used only for Quiet time (the grace window, 15–30 s, default 20) and volume; there is no control for where the ramp starts (it always starts at 20% of the set volume, FR-ALM-6).
- UX-DR41: `switch`: M3 switch, accent checked track, `outline` unchecked border; immediate effect except inside the editor (needs Save).
- UX-DR42: `time-picker`: scrolling wheels (hour, minute, AM/PM on 12 h phones) that snap to each value with a haptic tick and a quiet bundled tick sound per value; digits in `display` with tabular figures; no keyboard entry ever; TalkBack treats each wheel as adjustable and reads it as "Hour, 6".
- UX-DR43: `top-app-bar`: sub-screens have a 48 dp back arrow and a pinned title in `headline`; screens that save use the `SaveCancelPill` in its own bottom area, above the keyboard and never over content; back with unsaved editor changes prompts "Discard changes?".
- UX-DR44: `nav-bar`: a floating `glass-strong` capsule with five slots: Alarms · Progress · (+) · Settings · You; Material Symbols Rounded; the selected tab is in accent with a filled icon; the centre "+" is a raised 56 dp accent button ("Add alarm"); tab changes animate (instant with reduced motion); hidden during the session lock and on sub-screens.
- UX-DR45: `progress-dots` (onboarding): 8 dp dots, 8 dp apart; active accent and 16 dp wide pill, inactive `outline`; back allowed; not tappable.
- UX-DR46: `stat-tile` (Progress): `glass`, `rounded.md`, number in `display` (`text`), label in `caption`; three small tiles in one row ("on time", "to get up", "snoozes"), 2 + 1 at large font; not tappable; the streaks move to the 30-morning ring and `card-streak`.
- UX-DR47: ~~`bar-chart`~~ Removed (owner decision, sprint-change-proposal-2026-10-01): there is no snoozes chart; each snoozed morning shows on the 30-morning ring and the count on the "snoozes" `stat-tile` (UX-DR46).
- UX-DR48: `outcome-marker`: shape plus colour, so it reads without a legend: on time = filled dot (`success`), snoozed = dot with a small clock (`snoozed`), missed = hollow ring (`missed`), skipped/test = small neutral dot (`outline`), today = outlined accent pill; same shapes in the progress ring, calendar and Day detail; fallback used = `alt_route` badge (`text-secondary`) in Day detail; a tap on a day shows a label chip; TalkBack reads "{date}, {outcome}".
- UX-DR49: `calendar-day`: 48 dp cell, date in `caption`, `outcome-marker` below, today in an outlined accent pill; first tap shows the label chip, second tap (or the chip) opens Day detail (owner decision 2026-10-01); TalkBack "Tuesday 14, on time" (+ "fallback check used"); Skipped rendering conditional on Q9.
- UX-DR50: `checklist-row`: 64 dp, leading icon, title (`body`), reason (`caption`), trailing `check_circle` in `success` with "OK" or `button-outlined` "Fix"; Fix deep-links to the system setting and status is re-checked on return; camera and microphone rows appear only when needed.
- UX-DR51: `settings-row`: 56 dp, label left, value (`text-secondary`) or chevron right; tap → detail or toggle; unavailable during a session.
- UX-DR52: `purchase-row`: 64 dp, date and alarm (`body`), "Snooze {n}" (`caption`), localized price right-aligned in `text` (never accent, green or red); read-only; stranded rows read "Not used, refunded automatically by Google".
- UX-DR53: `sound-row`: 56 dp, radio selection, sound name (`body`), source caption, 48 dp preview play button; preview plays at alarm volume and stops on leaving; missing custom file shows "File missing. Default sound will play."
- UX-DR54: `recorder`: 72 dp round accent record button with on-accent mic icon, elapsed/max "0:12 / 1:00" in `display`, level meter in `text-secondary`; mic permission asked on first use; tap to record, tap to stop, auto-stop at 60 s; then Play, Re-record, Save, Delete.
- UX-DR55: `dialog-confirm`: M3 dialog, `rounded.lg`, title `headline`, body `body`, `button-text` actions (confirm in `error` colour only when destructive); two actions with the safe action as default dismiss; logged actions say "This is logged."; used for disable/delete under lock, delete alarm, delete all data, discard changes.
- UX-DR56: `snackbar`: `inverse-surface` container, `inverse-text` message, optional action in `inverse-accent`, `rounded.sm`; app screens: 4 s with optional action; wake screens: no action, stays ≥ 10 s or until next tap, announced politely by TalkBack.
- UX-DR57: `notification-summary`: monochrome small icon, no accent tint, plain copy; weekly Sunday evening (default on, toggle in Settings > Notifications); tap opens Progress.
- UX-DR58: `skeleton`: `surface-variant` blocks, `rounded.sm`, shown only after 300 ms of loading on app screens (text if > 3 s), no shimmer when animations are off, never on wake screens.

#### Information architecture and screens

- UX-DR59: Bottom navigation is the floating glass capsule (UX-DR44) with five slots: Alarms/Home · Progress · (+) · Settings · You; the selected tab is in accent with a filled icon; it animates (instant with reduced motion) and is hidden during the session lock and on sub-screens; all other surfaces are pushed screens, editor sub-screens or bottom sheets; wake screens are one separate full-screen activity outside navigation shown over the lock screen.
- UX-DR60: Surfaces to build (per EXPERIENCE.md IA table): Onboarding (8 steps), Alarms (Home), Alarm editor (grouped cards: time wheel, repeat, name, vibration, and rows that open the sub-screens Sound (volume, ramp switch, sound list), Snooze (length with fee ladder), Wake-up check (checks + mode), Quiet time (slider + "Vibrate during quiet time") and Motivation; "Test alarm" as a text button under the cards; Cancel and Save in the `SaveCancelPill`), Check picker, Check setup (difficulty, count, "Try it"), House Hunt registration (1–3 reference photos, test match), QR registration (scan or printable QR), Sound picker (the editor's Sound sub-screen), Recordings, Progress, Day detail, Purchase history, Settings (Snooze, Wake, Appearance, Notifications, Usage stats, Reliability checklist), You (Purchase history, Payments & refunds, Privacy, Delete all data, Support, Terms, About), Reliability checklist, Payments & refunds, Ringing, Snooze confirm, Check, Fallback check picker, Success, Snoozed.
- UX-DR61: Session lock UI: while a session is active (including snoozed), the app shows only `panel-session-in-progress`; nav bar hidden; editing, deleting, settings and Delete all data unreachable.
- UX-DR62: Design-originated scope requiring PRD acknowledgement is implemented as specified: theme override (System/Light/Dark), "Bright wake screen" setting, selectable accessible fallback check picker, unlock step copy.

#### Accessibility floor

- UX-DR63: Fallback check picker lists only non-camera checks as selectable `check-type-card`s with Math always first and always available; Math's TalkBack-friendly input reads the problem as words ("47 plus 38"), announces each number-pad key and the current answer, and needs no timing or visual matching; Memory Sequence offered in its numbered, announced variant.
- UX-DR64: TalkBack: every control labelled with role and state; icon-only controls have content descriptions; disabled Snooze reads its reason; day chips read full names; `clock-xl` reads the full time; wake-screen initial focus is the clock, then "I'm up"; focus order follows reading order.
- UX-DR65: Grace countdown is announced every 10 s and at 5 s while muted, with a haptic tick every 5 s as the non-audio cue.
- UX-DR66: Font scale 200%: all layouts reflow without clipping; `clock-xl` caps at 1.3× (114 sp); wake actions stay in the thumb zone (bottom 40%) and never scroll off screen at any font scale.
- UX-DR67: Targets ≥ 48 dp everywhere and every wake action ≥ 64 dp in the thumb zone (verified by screenshot/semantic tests).
- UX-DR68: Outcomes never rely on colour alone (glyph + colour + label); `success`/`snoozed` differ in luminance ≥ 1.6:1 in every theme.
- UX-DR69: The system accessibility shortcut (both volume keys held) is never captured.

#### Interaction primitives

- UX-DR70: Haptics: light tick on each digit or tile tap; success pattern on completion; strong buzz when the alarm returns after grace; short tick every 5 s during grace; error haptic on wrong answer.
- UX-DR71: Motion specs: standard transition 250 ms Material emphasized easing; per `pps-design` rule 11, screen and sub-screen slides, expand and collapse, chip and switch states, cards animating in and out, the collapsing header, the "I'm up" pulse, the wheel tick, the Progress entry and the Day detail timeline drawing in; countdown ring linear exact to the second; memory tiles 350 ms highlight + 150 ms gap; on-time Success counts the streak up from n−1 with a bounce, about 1.5 s of confetti and one success haptic; after-snooze Success has no animation; wrong answer 200 ms horizontal shake + error haptic.
- UX-DR72: Reduced motion: when animator duration scale is 0, every motion becomes an instant state change; the countdown still counts as numbers; skeleton has no shimmer.
- UX-DR73: Anti-double-tap guard: the snooze confirm sheet ignores all input for 500 ms after opening and after every state change, including with animations off; "I'll get up" sits under the thumb position of the Snooze tap.
- UX-DR74: Back behaviour: on wake screens Back does nothing (Home and Recents still work); in the confirm sheet Back = "I'll get up" path (closes, no charge).
- UX-DR75: Volume keys captured only while the wake screen is in the foreground; elsewhere they behave normally; set volume re-applied only at start of each ring and when grace ends, never continuously.
- UX-DR76: Tap to act; long-press only on `card-alarm` and always duplicated in a menu.
- UX-DR77: Banned patterns (review checklist): decorative motion, pre-selected payment buttons, disguised or hidden snooze, confirm-shaming copy, carousels, streak-loss threats, badge counts, three-equal-cards rows, decorative eyebrow labels, AI-purple, gradient text, neon glows.

#### State patterns

- UX-DR78: Wake screens never show a loading state and render within 1 s from cached data; implement each wake state: first ring (label, clock, date, I'm up, Snooze · price, volume ramp); ringing after snooze (session line "Snooze {n} of {max} · {paid} paid this morning", next price); snooze unavailable (disabled with reason); test alarm ("Test · no charge", no payment, logged Test only); before first unlock (lock icon label, Math + default sound, `note-inline` "Your phone restarted, so today's check is Math."); locked after first unlock (unlocking state); already paid; grace running (ring counting, muted, vibration only if enabled); grace expired ("Alarm's back on", full volume, progress kept); snooze tapped during grace (mute only until countdown ends); grace expires while sheet open (sheet stays, alarm returns behind it); wrong answer (shake, haptic, cleared, error text in `error-sunrise`); camera denied/failed (fallback link immediately); fallback check (alarm rings, no new grace); phone call (paused, `note-inline` "Paused for your call. Rings again when it ends."); restored after crash/kill/reboot (same step, counts kept, no "restored" message); overlapping alarm (merged silently, Day detail note "{time} alarm merged into this session"); missed (alarm stops, Home note next open); success zero-snooze (streak animation once, success haptic, motivation, brightness restored on Done); success after snooze ("You're up. That's what counts." + "{paid} paid this morning" in `text-secondary-sunrise`, no animation); snoozed ("Snoozed. Next ring at {time}." for 3 s, then screen off).
- UX-DR79: Payment outcomes (snackbar with wake rules, alarm full volume throughout): unlock cancelled/failed "Phone still locked. No charge." → return, snooze still offered; Play cancelled "Payment cancelled. No charge." → offered; billing error "Payment didn't go through. No charge." → offered; no connection "No connection. No charge." → "Snooze unavailable: offline" until connectivity returns; pending "Payment not confirmed yet. If it goes through before you finish, your snooze starts. Otherwise, finish the check to stop the alarm." → "Snooze unavailable: payment pending" for the session; purchased → Snoozed; already paid "Use it" → Snoozed; "Not now" → disabled "An earlier {price} payment is being refunded". Each outcome is a mapping-table unit test.
- UX-DR80: App-screen states: loading (skeleton after 300 ms, text after 3 s); offline (everything but payment works, no banner); prices never loaded (USD tiers with "Approximate. Your local price shows when you're online.", onboarding continues); Home empty ("No alarms yet." + "Add your first alarm"); Home list load failure ("Couldn't load your alarms." + "Try again"); editor load failure (editor closes, snackbar "Couldn't open this alarm."); permission/setting missing (banner-warning); missed session note until dismissed; fallback 3× in 7 days (info banner "Fallback check used 3 times this week. Re-register your {checkName}?"); session active (panel only); weakening under lock ("Saved. Takes effect after tomorrow's {time} alarm."); no check selected (Save blocked, "Pick at least one check."); one-time alarm time passed ("Rings tomorrow at {time}."); custom sound missing; no recordings / mic denied ("Record a message for your morning self." / "Microphone is off. Turn it on in Settings." + Fix); Progress empty ("Your first morning shows up here."); test/skipped day (label only, excluded from rates); Purchase history empty ("No snoozes paid. Keep it that way."); checklist all OK (every row OK, "Ring a test alarm" stays); Settings not reachable during a session.
- UX-DR81: Skipping the onboarding test alarm shows a Home `note-inline` recommending it (FR-ONB-4).

#### Copy and voice

- UX-DR82: Voice rules applied to every string and notification (FR-MSG-4): headlines ≤ 8 words, paragraphs ≤ 25 words; supportive, never shaming; money stated plainly ("Snooze · {price}"), no "Only $3!"; no em dashes in UI strings (periods, commas or "·"); plain verbs (ban Elevate, Seamless, Unleash, Supercharge); real numbers only; at most one emoji and only in the zero-snooze success message; always say "No charge." when a payment did not happen.
- UX-DR83: All user-facing strings live in Compose Multiplatform resources (NFR-10); runtime variables (`{price}`, `{nextPrice}`, `{minutes}`, `{time}`, `{seconds}`, `{streak}`, `{reason}`, …) are string arguments; prices always from Play's localized string, never a hard-coded currency symbol.
- UX-DR84: Implement the EXPERIENCE.md Key strings table verbatim (mission, disclosure + "I understand", analytics choice "Share"/"No thanks", base-fee lock note and offline note, bright wake screen, test alarm "Lock your phone. We'll ring in 10 seconds.", Home next alarm/reliability banner/missed/fallback re-register, session in progress, editor lock/no-check notes, disable/delete dialogs, ringing primary/snooze/session line, snooze-unavailable reasons, stranded, before-first-unlock, test, confirm title/body/nudge/tax/buttons, unlock step, already paid, history stranded, all payment outcome messages, after paid snooze, grace window "Quiet for {seconds}s. Finish before it rings again.", grace ended "Time's up. Alarm's back on until you finish.", Direct Boot notice, camera unavailable, fallback link and picker title, phone call, success variants (on-time Success shows the big number, then "days in a row", then "Up on time.", with no repeated number) incl. "Your pending payment wasn't used. Google refunds it automatically.", weekly summaries, ringing notification, empty states). Zero amounts use "Nothing paid" wording (EXPERIENCE.md updated to match PRD FR-PRG-2/FR-MSG-2).
- UX-DR85: Glossary terms used verbatim in UI and docs: Check, Session, Grace window (the UI says "Quiet time"; specs may say grace window), Fallback check (never "backup"), Commitment lock, Outcomes (On time · Snoozed · Missed · Skipped · Test), Wake screens.

#### Key flows (acceptance sources)

- UX-DR86: F1 First-run setup and test alarm: mission → disclosure "I understand" → base fee stepper with local prices and ladder preview → time + days → checks (Random) with "Try it" → reliability checklist Fix → usage stats off → locked test alarm rings on Sunrise screen with "Test · no charge" → Success; failure paths: offline base fee approximate note; skipped test → Home note.
- UX-DR87: F2 On-time morning with grace window: ramp + brightness → "I'm up" (72 dp) → silent with "Quiet for 20s" → check done in window → on-time Success shows the big number, then "days in a row", then "Up on time." (no repeated number), with the count-up of UX-DR71 → Done restores brightness.
- UX-DR88: F3 Grace expiry: countdown reaches 0 → "Time's up. Alarm's back on until you finish.", strong haptic, full volume, progress kept → completion → "You're up. That's what counts."; snooze during grace keeps mute only until countdown ends.
- UX-DR89: F4 Paid snooze with rising fee and failed payment: Snooze · price → sheet with 500 ms guard and full copy → Pay → unlocking state → keyguard → Play sheet with alarm at full volume → "Snoozed. Next ring at {time}." screen off → re-ring with session line and next price; failure paths: pending (disabled "payment pending", auto-start if confirmed during check, success note about auto-refund otherwise), unlock cancelled/cancelled/error/offline per outcomes table, late-cleared payment opens already-paid state next time.
- UX-DR90: F5 Fallback check with TalkBack and revoked camera: TalkBack reads "6:15. I'm up, button." → QR check camera fails → message + link immediately → picker with Math first → spoken problem, announced number pad → alarm stops with no sighted step; Day detail shows fallback badge; failure: link after 5 failed scans, no second fallback per session, re-register prompt after 3 in 7 days.
- UX-DR91: F6 Commitment lock: lowering base fee under lock shows "Saved. Takes effect after tomorrow's {time} alarm."; toggling alarm off within 8 h shows dialog "Turn off your {time} alarm? It rings in {hours} h. This is logged." with safe default "Keep it on"; confirmed turn-off is logged; strengthening applies immediately.
- UX-DR92: F7 Reliability warning: revoked battery optimization → non-dismissible Home banner → checklist row reason → Fix deep-link → row turns OK on return and banner clears itself → "Ring a test alarm".
- UX-DR93: F8 Overnight reboot before first unlock: rings on lock screen, Direct Boot notice, default sound, Snooze disabled with lock icon; after unlock on the lock screen the wake screen stays on top, button becomes "Snooze · {price}", Math stays for the ring.
- UX-DR94: F9 Review progress: weekly summary notification tap → Progress (30-morning ring with the streak, three stat tiles, `card-streak`, money and Insight) → a ring or calendar day's label chip → Day detail (snoozes, paid, check, timeline) → Purchase history.
- UX-DR95: F10 Record a motivation message: Recordings → mic permission on first use → record/stop → play back, save, select "After I'm up" → next morning Success plays it; failure: mic denied note with Fix, alarm unaffected.

### FR Coverage Map

- FR-ALM-1: Epic 1 — Set an alarm that rings
- FR-ALM-2: Epic 1 — Set an alarm that rings
- FR-ALM-3: Epic 1 — Set an alarm that rings
- FR-ALM-4: Epic 1 — Set an alarm that rings
- FR-ALM-5: Epic 1 — Set an alarm that rings
- FR-ALM-6: Epic 1 — Set an alarm that rings
- FR-ALM-7: Epic 1 — Set an alarm that rings
- FR-ALM-8: Epic 1 — Set an alarm that rings
- FR-ALM-9: Epic 1 — Set an alarm that rings
- FR-ALM-10: Epic 7 — Make it yours
- FR-ALM-11: Epic 2 — An alarm you can't escape
- FR-ALM-12: Epic 1 — Set an alarm that rings
- FR-SES-1: Epic 2 — An alarm you can't escape
- FR-SES-2: Epic 2 — An alarm you can't escape
- FR-SES-3: Epic 2 — An alarm you can't escape
- FR-SES-4: Epic 2 — An alarm you can't escape
- FR-SES-5: Epic 2 — An alarm you can't escape
- FR-SES-6: Epic 2 — An alarm you can't escape
- FR-SES-7: Epic 2 — An alarm you can't escape
- FR-SES-8: Epic 2 — An alarm you can't escape
- FR-SES-9: Epic 2 — An alarm you can't escape
- FR-SES-10: Epic 2 — An alarm you can't escape
- FR-RNG-1: Epic 4 — Pay to snooze
- FR-RNG-2: Epic 4 — Pay to snooze
- FR-RNG-3: Epic 4 — Pay to snooze
- FR-RNG-4: Epic 4 — Pay to snooze
- FR-RNG-5: Epic 4 — Pay to snooze
- FR-RNG-6: Epic 4 — Pay to snooze
- FR-RNG-7: Epic 4 — Pay to snooze
- FR-RNG-8: Epic 4 — Pay to snooze
- FR-RNG-9: Epic 4 — Pay to snooze
- FR-RNG-10: Epic 4 — Pay to snooze
- FR-PWK-1: Epic 3 — Prove you're awake
- FR-PWK-2: Epic 3 — Prove you're awake
- FR-PWK-3: Epic 3 — Prove you're awake
- FR-PWK-4: Epic 3 — Prove you're awake
- FR-PWK-5: Epic 3 — Prove you're awake
- FR-PWK-6: Epic 7 — Make it yours
- FR-PWK-7: Epic 3 — Prove you're awake
- FR-PWK-8: Epic 3 — Prove you're awake
- FR-PWK-9: Epic 3 — Prove you're awake
- FR-PWK-10: Epic 4 — Pay to snooze
- FR-PWK-11: Epic 3 — Prove you're awake
- FR-PWK-12: Epic 3 — Prove you're awake
- FR-PWK-13: Epic 7 — Make it yours
- FR-SND-1: Epic 1 — Set an alarm that rings
- FR-SND-2: Epic 1 — Set an alarm that rings
- FR-SND-3: Epic 7 — Make it yours
- FR-SND-4: Epic 7 — Make it yours
- FR-SND-5: Epic 1 — Set an alarm that rings
- FR-SND-6: Epic 7 — Make it yours
- FR-SND-7: Epic 7 — Make it yours
- FR-PRG-1: Epic 1 (SessionRecorder writes history; views in Epic 6)
- FR-PRG-2: Epic 6 — Wake-up progress
- FR-PRG-3: Epic 6 — Wake-up progress
- FR-PRG-4: Epic 4 — Pay to snooze
- FR-PRG-5: Epic 6 — Wake-up progress
- ~~FR-PRG-6~~: Removed (owner decision 2026-10-01, PRD v0.3); no story
- FR-MSG-1: Epic 5 — First run, settings and trust
- FR-MSG-2: Epic 6 — Wake-up progress
- FR-MSG-3: Epic 6 — Wake-up progress
- FR-MSG-4: Epic 1 (voice rules adopted) — part of the definition of done for every UI story in all epics
- FR-ONB-1: Epic 5 — First run, settings and trust
- FR-ONB-2: Epic 5 — First run, settings and trust
- FR-ONB-3: Epic 5 — First run, settings and trust
- FR-ONB-4: Epic 5 — First run, settings and trust
- FR-ONB-5: Epic 5 — First run, settings and trust
- FR-ONB-6: Epic 5 — First run, settings and trust
- FR-SET-1: Epic 4 (base fee + commitment lock) and Epic 5 (remaining settings)
- FR-SET-2: Epic 5 — First run, settings and trust
- FR-SET-3: Epic 5 — First run, settings and trust
- FR-SET-4: Epic 5 — First run, settings and trust
- FR-SET-5: Epic 4 — Pay to snooze
- FR-SET-6: Epic 5 — First run, settings and trust

## Epic List

### Epic 1: Set an alarm that rings
The user can create alarms that ring on time over the lock screen (Doze, silent, Do Not Disturb, reboot, time and time-zone changes), choose and preview built-in sounds, run a test alarm, and end the alarm with "I'm up". Under the hood this epic also lays the foundation every later epic plugs into.
**FRs covered:** FR-ALM-1, 2, 3, 4, 5, 6, 7, 8, 9, 12 · FR-SND-1, 2, 5 · FR-PRG-1 (history recorder) · FR-MSG-4 (voice rules, then DoD everywhere)
**Implementation notes:** Story 1.1 scaffolds the AGP 9 KMP project per the architecture (modules, pinned stack, `qualityGate`, CI, dependency and permission allowlists). Then: design-token generator + `PpsTheme` (Light/Dark/Sunrise); Google Play Console setup (account verification, app record, payments profile, internal track upload, license testers) [P1]; Spike S1 payment-over-lock-screen [P2]; the **complete** AD-2 session state machine with fake checks and fake billing, table-tested [P3]; `SessionConfig` freeze and `SessionRecorder` [P4]; scheduler + receivers, `WakeService`/`WakeActivity`, sound library; basic runtime permissions (notifications, full-screen intent check, exact alarm on API 31–32) and Crashlytics setup [P5]; Spike S2 reliability. Ends with a human-verify device checklist story.

### Epic 2: An alarm you can't escape
The alarm keeps its promise: it survives process death, app kills and overnight reboots (including before first unlock), can't be dodged by clock changes, handles calls and overlapping alarms, and locks the app to the wake screen during a session while leaving the phone fully usable.
**FRs covered:** FR-ALM-11 · FR-SES-1, 2, 3, 4, 5, 6, 7, 8, 9, 10
**Implementation notes:** Builds on the Epic 1 state machine (no new states). Session slot alarm, deadlines (wall + monotonic + boot count), Direct Boot storage and substitutions, `UserUnlocked`, audio-mode call detection, volume-key capture, app lock screen, manifest allowlist test. Ends with a human-verify escape-attempt checklist on the device matrix.

### Epic 3: Prove you're awake
Dismissing the alarm requires the check(s) the user chose (Math, Word Unscramble, Memory Sequence, QR/Barcode) with difficulty and count, a 15–30 s muted grace window, and an accessible fallback check.
**FRs covered:** FR-PWK-1, 2, 3, 4, 5, 7, 8, 9, 11, 12
**Implementation notes:** Check plugin contract (AD-9) replaces the Epic 1 fake check; one story per check type; QR/Barcode (CameraX + ML Kit) last; check setup with "try it". Human-verify checklist at the end.

### Epic 4: Pay to snooze
Snoozing costs real money through Google Play, rising B × N per snooze with the base fee and commitment lock, honest confirmation, every payment outcome handled (pending, cancel, offline, stranded reuse), and purchase history.
**FRs covered:** FR-RNG-1, 2, 3, 4, 5, 6, 7, 8, 9, 10 · FR-PWK-10 · FR-PRG-4 · FR-SET-5 · FR-SET-1 (base fee + commitment lock, §6.2) [P10]
**Implementation notes:** `tools/play-catalog` (50 products) first; `FeeLadder`, `snoozeAvailability`, `PurchaseReconciler`, grant ledger as pure core with table-driven tests; then the Play Billing 9.1 adapter replacing the Epic 1 fake; UI per EXPERIENCE.md payment outcomes. Human-verify with license testers.

### Epic 5: First run, settings and trust
A new user is guided through mission, alarm-behaviour disclosure and consent, base fee, first alarm, checks, the full reliability checklist (with manufacturer guidance) and a locked-screen test alarm; Settings, privacy, analytics consent and delete-all-data are complete. **The 14-day closed test starts at the end of this epic** [P7].
**FRs covered:** FR-ONB-1, 2, 3, 4, 5, 6 · FR-SET-1 (remaining settings), 2, 3, 4, 6 · FR-MSG-1
**Implementation notes:** Extends the Epic 1 permission prompts into the full checklist; Firebase Analytics consent gating (AD-15); privacy policy page on GitHub Pages; closed-test story (12+ testers as license testers, feedback channel).

### Epic 6: Wake-up progress
The user sees streaks, on-time rate, average time to get up, snoozes over the last 30 days, money paid per currency, a calendar, a weekly summary, and a zero-snooze celebration.
**FRs covered:** FR-PRG-2, 3, 5 · FR-MSG-2, 3
**Implementation notes:** Pure stats functions over Epic 1 history (AD-18); WorkManager for the weekly summary (AD-17); built while the closed test runs.

### Epic 7: Make it yours
Personal touches and flexibility: recorded motivation messages, custom alarm sounds, "Mix into alarm", House Hunt, a printable QR code, and skip-next-alarm.
**FRs covered:** FR-SND-3, 4, 6, 7 · FR-PWK-6 · FR-PWK-13 · FR-ALM-10 [P6]
**Implementation notes:** Mostly Should/Could — the first bucket to cut if time runs short (PRD cut line). House Hunt starts with Spike S3.

### Epic 8: Launch on Google Play
The app is live: store listing and screenshots, Data safety form, Play Console declarations (exact alarm, full-screen intent, foreground service video), EU DSA trader details, closed-test feedback fixes, and staged production rollout.
**FRs covered:** none directly (release and operations requirements from the architecture and PRD §13)
**Implementation notes:** Play Console setup already done in Epic 1; closed test already running from Epic 5.

**Dependency flow:** 1 → 2 → 3 → 4 → 5 → 6 → 7 → 8. Each epic works without the ones after it. NFRs are acceptance criteria inside stories, not separate epics.

## Epic 1: Set an alarm that rings

The user can create alarms that ring on time over the lock screen (Doze, silent, Do Not Disturb, reboot, time and time-zone changes), choose and preview built-in sounds, run a test alarm, and end the alarm with "I'm up". Under the hood this epic lays the foundation every later epic plugs into: the KMP project and quality gate, generated design tokens, the Play Console record, the two spikes, platform-free time and occurrence math, the complete AD-2 session state machine with write-ahead persistence, the single history writer, and the wake runtime.

Every UI story in this epic (and in every later epic) carries two standing acceptance criteria, repeated in the story so the build loop can check them: (1) the `pps-design` Done checklist is copied into the story file with every item ticked; (2) all new user-facing strings live in Compose Multiplatform resources, match `EXPERIENCE.md > Voice and Tone > Key strings` verbatim where a key string exists, and pass the automated copy-rules test from Story 1.3 (FR-MSG-4). A string that EXPERIENCE.md does not define is marked `(EXPERIENCE.md Key strings)` in the story and listed for the owner.

### Story 1.1: Scaffold the KMP project with a quality gate

As the developer (owner or build loop),
I want an AGP 9 Kotlin Multiplatform project with the architecture's modules, pinned versions and one `qualityGate` task,
So that every later story builds on the same structure and has one command that says "done".
**Refs:** NFR-6, NFR-11, NFR-12, AD-1, AD-13, AD-14 · **Priority:** Must · **Verify:** auto

**Acceptance Criteria:**

**Given** an empty repository (planning files only)
**When** the scaffold is created
**Then** `settings.gradle.kts` includes exactly `:core`, `:data`, `:composeApp`, `:androidApp`, `:testing` (plus the build-only `:detekt-rules` module under `config/detekt-rules/`), and the folders `tools/tokens/`, `tools/play-catalog/`, `config/`, `docs/`, `data/schemas/` exist per the Architecture Structural Seed
**And** `:core` and `:testing` are KMP modules with only `commonMain`/`commonTest` sources and a `jvm()` target (iOS targets deferred), `:data` and `:composeApp` are KMP modules with `commonMain` and `androidMain`, and `:androidApp` is the Android application module using the AGP 9 KMP-compatible plugins
**And** the project dependency graph is exactly androidApp → composeApp, data, core; composeApp → core; data → core; testing → core; test-only androidApp/composeApp/data → testing

**Given** `gradle/libs.versions.toml`
**When** it is read
**Then** it pins every Architecture Stack version exactly (Kotlin 2.4.20, Compose Multiplatform 1.12.1, AGP 9.3.3, KSP 2.3.11, Room KMP 3.0.3, DataStore 1.2.1, kotlinx-coroutines 1.11.0, kotlinx-datetime 0.8.0, kotlinx-serialization 1.11.0, Navigation 3 1.1.2, Koin 4.2.2, Play Billing 9.1.0, CameraX 1.6.2, ML Kit barcode 17.3.0, MediaPipe tasks-vision 1.0.0, WorkManager 2.11.0, Firebase BoM 34.19.0, Kover 0.9.8, detekt 2.0.0-alpha.5, Spotless 8.8.0, Roborazzi 1.74.0, Turbine 1.2.1), the Gradle wrapper is 9.7.1 and the JVM toolchain is 17
**And** `:androidApp` has `applicationId` and namespace `com.yawnandpawn.app` (owner decision 2026-09-26), compileSdk 37, targetSdk 36, minSdk 26, no `applicationIdSuffix` on `debug`, R8 on `release`, `versionName` 0.1.0 and `versionCode` computed as major*10000 + minor*100 + patch (a unit test asserts 0.1.0 → 100 and 1.2.3 → 10203)

**Given** the debug APK is installed on an emulator
**When** the app is launched
**Then** a single `MainActivity` shows the `App()` composable from `:composeApp` with the app name from a Compose Multiplatform string resource, and Koin is started in the `Application` with one `val xxxModule = module { }` per module (`:core` has no Koin dependency)
**And** a Robolectric test in `:androidApp` launches `MainActivity` and finds the app-name text, and one Roborazzi screenshot of the empty screen is recorded and verified (confirms OQ-3; if Roborazzi does not work with AGP 9 host tests, the fallback Compose Preview screenshot testing is set up instead and the decision is written to `docs/decisions/oq-3-screenshots.md`)

**Given** the `verifyCoreDependencies` Gradle check (AD-1)
**When** `:core` declares any dependency other than Kotlin stdlib, kotlinx-coroutines, kotlinx-datetime or kotlinx-serialization, or any `:core` source file imports `android.`, `androidx.`, `org.koin.`, Compose or Room packages, or `:composeApp` depends on `:data`
**Then** the build fails with a message naming the offending dependency, import or edge
**And** the check's logic is unit-tested with a passing and a failing fixture

**Given** the `qualityGate` task
**When** `./gradlew qualityGate` runs
**Then** it runs, in one invocation: `spotlessCheck` (ktlint), `detekt` (config in `config/detekt/detekt.yml`, custom rules from `:detekt-rules` with a first rule banning `println` in `:core`), `:core:allTests`, `:data:allTests`, `:composeApp` host tests, `:androidApp:testDebugUnitTest` (incl. Roborazzi verify), `koverVerify` (`:core` ≥ 90% lines), `:androidApp:lintDebug`, `:androidApp:assembleDebug` and `verifyCoreDependencies`
**And** later stories register their checks (token diff, allowlists, loudness script) as dependencies of `qualityGate`, documented in a comment in the root build file
**And** `./gradlew qualityGate` passes

### Story 1.2: CI pipeline and dependency and permission allowlists

As the owner,
I want every push checked in CI and every new dependency or Android permission to fail the build until reviewed,
So that the build loop cannot silently add network SDKs or device-hostage permissions.
**Refs:** NFR-4, NFR-11, NFR-13, AD-5, AD-14, AD-15 · **Priority:** Must · **Verify:** auto

**Acceptance Criteria:**

**Given** `.github/workflows/ci.yml`
**When** a commit is pushed or a pull request is opened
**Then** CI checks out the repo, sets up Temurin JDK 17 and Gradle caching, runs `./gradlew qualityGate`, then runs instrumented tests on a Gradle Managed Device (ATD image, API 34) with one smoke test that launches `MainActivity`
**And** the workflow fails if either step fails, and the job summary names the failing task

**Given** `config/dependency-allowlist.txt` listing every `group:artifact` in the resolved `:androidApp` debug and release runtime classpaths
**When** `./gradlew checkDependencyAllowlist` runs and a resolved runtime dependency is not in the file
**Then** the task fails and prints each unlisted coordinate
**And** removing a line from the allowlist in a test fixture makes the task fail (automated test), and the task is a dependency of `qualityGate`

**Given** `config/permission-allowlist.txt` containing exactly `INTERNET`, `com.android.vending.BILLING`, `POST_NOTIFICATIONS`, `USE_EXACT_ALARM`, `SCHEDULE_EXACT_ALARM` (with `maxSdkVersion="32"`), `USE_FULL_SCREEN_INTENT`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MEDIA_PLAYBACK`, `RECEIVE_BOOT_COMPLETED`, `WAKE_LOCK`, `VIBRATE`, `CAMERA`, `RECORD_AUDIO`, plus reviewed library-merged permissions
**When** `./gradlew checkPermissionAllowlist` parses the merged debug and release manifests
**Then** the task fails on any `uses-permission` not in the list, on `SCHEDULE_EXACT_ALARM` without `maxSdkVersion="32"`, and on any component protected by `BIND_ACCESSIBILITY_SERVICE` or `BIND_DEVICE_ADMIN` or using lock-task mode
**And** unit tests with fixture manifests prove that `READ_PHONE_STATE` and `SYSTEM_ALERT_WINDOW` each fail the check, and the task is a dependency of `qualityGate`

**Given** `.github/workflows/release.yml`
**When** a tag `vX.Y.Z` is pushed and the upload-key and Play service-account secrets exist
**Then** CI builds a signed release AAB with `versionName` X.Y.Z and uploads it to the Play internal track
**And** when the secrets are absent the upload job is skipped with a notice, never failed, and no keystore, `*.jks`, service-account JSON or `google-services.json` is tracked by git (`.gitignore` entries plus a CI step that fails if such files are committed)
**And** `./gradlew qualityGate` passes

### Story 1.3: Generated design tokens and PpsTheme

As a user,
I want every screen to use the same calm colours, type and shapes, with a bright Sunrise look on wake screens,
So that the app is readable at night and unmistakable when the alarm rings.
**Refs:** FR-MSG-4, NFR-9, NFR-10, AD-10, AD-11, UX-DR1, UX-DR2, UX-DR3, UX-DR4, UX-DR5, UX-DR6, UX-DR7, UX-DR8, UX-DR9, UX-DR11, UX-DR82, UX-DR83, UX-DR85 · **Priority:** Must · **Verify:** auto

**Acceptance Criteria:**

**Given** the DESIGN.md YAML frontmatter
**When** `./gradlew generateTokens` (the `tools/tokens` generator) runs
**Then** it writes `composeApp/src/commonMain/kotlin/com/payper/snooze/ui/theme/PpsTokens.kt` with every colour token for Light (no suffix), Dark (`-dark`) and Sunrise (`-sunrise`, plus `sunrise-gradient-top`), the 8-style typography ramp (`clock-xl` 88/92 w300, `display` 48/52 w500, `headline` 28/34 w600, `title` 20/26 w600, `button-wake` 20/24 w500, `body` 16/24 w400, `label` 14/20 w500, `caption` 12/16 w400), rounded tokens `sm` 8 dp / `md` 16 dp / `lg` 28 dp / `full`, and spacing tokens (4 dp base, screen margin 20 dp, section gap 24 dp, card padding 16 dp, `target-min` 48 dp, `target-wake` 64 dp, `target-wake-hero` 72 dp)
**And** the generated file is committed, and `checkTokens` (a `qualityGate` dependency) regenerates into `build/` and fails on any diff
**And** a generator unit test with a fixture frontmatter asserts parsing of each token group and rejects a colour value that is not `#RRGGBB`

**Given** `PpsTheme`
**When** a composable is wrapped in `PpsTheme(mode = System | Light | Dark)` or `PpsTheme(wake = true)`
**Then** one Material 3 `MaterialTheme` is provided with the Light, Dark or Sunrise colour set, dynamic colour is never used, `System` follows `isSystemInDarkTheme()`, and `wake = true` always uses Sunrise regardless of system setting
**And** Geist (OFL, licence file committed) is bundled with system sans fallback; `clock-xl`, prices, countdowns and list times use tabular figures (`fontFeatureSettings = "tnum"`), or Geist Mono for `clock-xl` if Geist lacks `tnum`, with the finding recorded in `docs/decisions/geist-tnum.md`
**And** icons come only from Material Symbols Rounded (weight 400, fill 0; fill 1 for selected nav items and outcome markers) as vector resources

**Given** detekt with the custom `:detekt-rules`
**When** code outside the `ui.theme` package contains `Color(0x…)`, `RoundedCornerShape(<number>.dp)` or a raw `<number>.sp` literal
**Then** detekt fails with a rule message pointing to `PpsTheme`
**And** each rule has a unit test with one violating and one compliant snippet

**Given** the DESIGN.md "Verified contrast" table
**When** the contrast test runs
**Then** it recomputes the WCAG ratio for every listed pair from the generated tokens, asserts each matches the recorded ratio within ±0.02, asserts text pairs ≥ 4.5 and graphic pairs ≥ 3.0, and asserts no background token is `#000000` or `#FFFFFF`

**Given** all Compose Multiplatform string resources (FR-MSG-4 copy rules)
**When** the `CopyRulesTest` runs
**Then** it fails on any string containing an em dash (U+2014), the words Elevate, Seamless, Unleash or Supercharge (any case), a hard-coded currency symbol (`$`, `€`, `£`, `¥`), the word "backup" next to "check", or any emoji outside the key `success_zero_snooze`
**And** it fails when a key ending `_headline` or `_title` has more than 8 words or a key ending `_body` has more than 25 words
**And** a theme showcase screen (debug only) renders every colour role, type style and shape, with Roborazzi screenshots in Light, Dark and Sunrise and at 200% font scale
**And** `./gradlew qualityGate` passes

### Story 1.4: Google Play Console setup (owner task)

As the owner,
I want a verified Play developer account, an app record with the final package id, a payments profile, a signed internal build and license testers,
So that billing spikes, closed testing and releases are not blocked by account setup later.
**Refs:** NFR-5, NFR-6, AD-7, AD-14; PRD §13 release plan · **Priority:** Must · **Verify:** human-verify

**Acceptance Criteria:**

**Given** the owner's Google Play developer account
**When** the owner completes Play Console onboarding
**Then** identity verification (and D-U-N-S if registering as an organization) is complete and the developer contact email is set
**And** the story file records whether the account is a new personal account (which triggers the ≥ 12 testers × 14 days closed-test rule planned in Epic 5)

**Given** the package id is permanent after the first upload
**When** the owner decides PRD Q8 (app name)
**Then** the decision is recorded in `docs/decisions/package-id.md`: either `com.yawnandpawn.app` is accepted or a new id is chosen, in which case a follow-up story renames `applicationId` and namespace before any upload
**And** the app record is created in Play Console with that package id, default language English (United States), type App, Free, with in-app products

**Given** the app record
**When** the owner sets up monetization
**Then** a payments (merchant) profile is created and linked, and Monetize > Products > One-time products is available for the app

**Given** an upload key created on the owner's machine
**When** the owner enrols in Play App Signing and uploads the signed 0.1.0 AAB built from `main`
**Then** the build is rolled out to the internal testing track, the owner's account is on the internal testers list, and installing through the opt-in link on a real phone launches the app
**And** the keystore and passwords are stored only in the owner's password manager and GitHub Actions secrets (`UPLOAD_KEYSTORE_BASE64`, `UPLOAD_KEYSTORE_PASSWORD`, `UPLOAD_KEY_ALIAS`, `UPLOAD_KEY_PASSWORD`), never in the repo

**Given** Setup > License testing
**When** the owner adds license testers
**Then** the owner's account and at least one separate test Google account are license testers with response "RESPOND_NORMALLY"
**And** a Google Cloud service account with Play Developer API access (for `tools/play-catalog` and the release workflow) exists and its JSON key is stored only as the GitHub secret `PLAY_SERVICE_ACCOUNT_JSON`

**Given** the checklist above
**When** the owner finishes
**Then** the story file lists each item with pass/fail, date and notes; automation never marks this story done
**And** `./gradlew qualityGate` still passes on `main` after the decision docs are committed

### Story 1.5: Spike S1: pay for a snooze over the lock screen

As the owner,
I want a throwaway prototype that shows the Google Play purchase sheet from a ringing lock-screen activity,
So that the snooze-payment unlock mechanics are decided before the session state machine is written.
**Refs:** FR-RNG-3, FR-RNG-5, AD-2, AD-7, OQ-1; PRD §10 S1 · **Priority:** Must · **Verify:** human-verify

**Acceptance Criteria:**

**Given** the internal-track app record and license testers from Story 1.4
**When** the spike starts
**Then** the prototype lives only on branch `spike/s1-billing-lockscreen` and is never merged to `main`
**And** one consumable test product `spike_s1_test` at the lowest USD tier is created by hand in Play Console for the spike only (the production catalogue is created by `tools/play-catalog` in Epic 4), with pending purchases enabled, and it is deactivated after the spike

**Given** the prototype: an activity using `setShowWhenLocked`/`setTurnScreenOn` that plays a looping sound on `USAGE_ALARM`, with a "Pay" button that calls `requestDismissKeyguard` when locked and then `launchBillingFlow` with `obfuscatedProfileId` = a random UUID
**When** the owner runs it on at least a Pixel and a Samsung device with a license tester account, locked and unlocked, at least 5 times per device
**Then** `docs/spikes/S1.md` answers, with device, Android version, date and evidence (screen recordings or logs) per run: (1) whether the Play sheet can appear over the lock screen without unlocking, (2) what happens with `requestDismissKeyguard` when the user unlocks, cancels or fails, (3) whether the alarm sound keeps playing at full volume on the alarm stream under the Play sheet, (4) median and max time from "Pay" to purchase result, (5) whether `obfuscatedProfileId` is present on the purchase update, on `queryPurchasesAsync`, on a pending purchase (license-tester slow card) that completes later, and on a promo-code purchase (OQ-1), (6) behaviour with no connection

**Given** the findings
**When** the owner writes the Decision section of `docs/spikes/S1.md`
**Then** it states the unlock mechanics for FR-RNG-3 (for example: confirm sheet → *unlocking* state "Unlock to pay {price}" → keyguard → Play sheet) and whether AD-2 needs `UnlockRequested` / `UnlockFailed` events, listing each new transition-table row as From / Event / Guard / To / One-shot effects
**And** any finding that contradicts the Architecture Spine or PRD (for example, the sheet cannot be shown while locked, or the profile id is missing on some path) is raised through `bmad-correct-course` before Story 1.11 starts

**Given** the spike checklist
**When** it is complete
**Then** the story file records pass/fail per question, device, Android version and date; automation never marks this story done
**And** `./gradlew qualityGate` passes on `main` with `docs/spikes/S1.md` committed

### Story 1.6: Time ports, deadlines and alarm occurrence math in core

As a user,
I want my alarm to ring at the wall-clock time I set, even across daylight-saving changes and time-zone moves,
So that I never wake an hour early or late.
**Refs:** FR-ALM-5, FR-ALM-9, NFR-11, NFR-12, AD-1, AD-3 · **Priority:** Must · **Verify:** auto

**Acceptance Criteria:**

**Given** `:core`
**When** the time ports are added
**Then** `core` exposes `Clock` (wall, `kotlin.time.Clock`), `MonotonicClock` (elapsed millis since boot), `BootCounter` (boot count) and `TimeZoneProvider` (current `TimeZone`), and `:testing` provides `FakeClock`, `FakeMonotonicClock`, `FakeBootCounter` and `FakeTimeZoneProvider` with `advanceBy(duration)`, `set(...)` and `reboot()` helpers
**And** `:androidApp` provides `AndroidMonotonicClock` (`SystemClock.elapsedRealtime`), `AndroidBootCounter` (`Settings.Global.BOOT_COUNT`) and `AndroidTimeZoneProvider`, wired in Koin

**Given** a detekt rule in `:detekt-rules`
**When** `Clock.System`, `System.currentTimeMillis()`, `SystemClock` or `TimeZone.currentSystemDefault()` is used outside `com.yawnandpawn.app.android.*` adapters
**Then** detekt fails (rule unit-tested with a violating and a compliant snippet)

**Given** `Deadline(wallMillis, elapsedMillis, bootCount)`
**When** `isDue(now)` is evaluated with the current wall time, elapsed time and boot count
**Then** on the same boot it compares elapsed time only (a wall-clock jump of ±2 h does not change the result), and on a different boot it compares wall time
**And** `remaining(now)` follows the same rule and never returns a negative duration

**Given** an alarm rule (`LocalTime`, repeat days as a set of `DayOfWeek`, empty = one-time) and `now` in a `TimeZone`
**When** `nextOccurrence(rule, now, zone)` is computed
**Then** it returns the first instant strictly after `now` whose local date's weekday is in the repeat set (any day for one-time) and whose local time equals the rule time, and a one-time alarm whose time has already passed today returns tomorrow
**And** in a DST gap (for example 02:30 on 2027-03-28 in Europe/Berlin) it returns the instant shifted forward by the gap length (03:30 local) (confirmed by owner)
**And** in a DST overlap (for example 02:30 on 2027-10-31 in Europe/Berlin) it returns only the earlier instance, and the next call after that instant returns the following repeat day, never the second 02:30
**And** after a time-zone change (for example Europe/Berlin → America/New_York) the same rule recomputes to the rule time in the new zone
**And** a repeat alarm set for Monday–Friday evaluated on Friday after the time returns Monday
**And** `durationUntil(nextOccurrence, now)` feeds the Home countdown in Story 1.9 (same function, one source)
**And** tests are table-driven, named as backticked sentences, and cover at least 3 zones including one with a 30-minute DST shift (Australia/Lord_Howe)
**And** `./gradlew qualityGate` passes

### Story 1.7: Store alarms in app.db

As a user,
I want my alarms saved on the phone in storage that is readable right after a reboot,
So that they are never lost and can ring before I unlock.
**Refs:** FR-ALM-1, FR-ALM-2, NFR-4, NFR-14, AD-6, AD-12 · **Priority:** Must · **Verify:** auto

**Acceptance Criteria:**

**Given** `:core`
**When** the alarm domain is added
**Then** `Alarm` holds `id` (UUID v4 string), `time` (`LocalTime`), `repeatDays` (set of `DayOfWeek`, empty = one-time), `label` (optional, ≤ 40 characters), `enabled`, `soundRef` (default: the default built-in sound), `volumePercent` (default 80), `gradualVolume` (default true), `rampStartPercent` (default 20), `vibration` (default on), `snoozeLengthMinutes` (one of 5, 9, 10, 15; default 9), `graceSeconds` (15–30, default 20), `requestCode` (stable unique int per alarm), `createdAt`, `updatedAt`
**And** check configuration, pending changes and motivation recording are not stored yet (they arrive with their own tables in Epics 3, 4 and 7)
**And** an `AlarmRepository` port (`observeAll(): Flow`, `get`, `upsert`, `delete`) returns `Outcome<T, DomainError>`, with `FakeAlarmRepository` in `:testing`
**And** use cases `SaveAlarm`, `SetAlarmEnabled`, `DeleteAlarm`, `DuplicateAlarm` validate input (label length, snooze length in {5, 9, 10, 15}, grace in 15–30) and return `DomainError.InvalidAlarm(field)` instead of throwing

**Given** `:data`
**When** `RoomAlarmRepository` is implemented
**Then** `app.db` (Room KMP, schema version 1, table `alarm` only) is created at a path built from `createDeviceProtectedStorageContext()`, never the credential-protected context, and the exported schema is committed under `data/schemas/`
**And** if Room 3.0.3 cannot be used with KMP and AGP 9 (OQ-2), Room 2.8.5 is used and the decision is recorded in `docs/decisions/oq-2-room.md`
**And** `:data` tests (in-memory or temporary file database) cover insert, update, delete, observe ordering by time of day, and a unique `requestCode` constraint

**Given** Android Auto Backup (NFR-14)
**When** backup rules are added
**Then** `dataExtractionRules` (API 31+) and `fullBackupContent` (API ≤ 30) include `app.db` in the device-protected domain and exclude everything else created so far
**And** a Robolectric test parses both XML files and asserts the include and exclude entries
**And** `./gradlew qualityGate` passes

### Story 1.8: Create and edit an alarm

As a user,
I want to set an alarm's time, repeat days, label, snooze length, volume, whether it gradually increases (and from what level) and vibration,
So that each alarm rings the way I need.
**Refs:** FR-ALM-1, FR-ALM-2, FR-MSG-4, NFR-9, NFR-10, AD-11, UX-DR25, UX-DR28, UX-DR35, UX-DR36, UX-DR38, UX-DR40, UX-DR41, UX-DR42, UX-DR43, UX-DR55, UX-DR64, UX-DR66, UX-DR67, UX-DR80 · **Priority:** Must · **Verify:** auto
**Note (2026-10-01):** done in PR #6. The design preview (`spec-design-preview-whole-app.md`) since replaced its editor layout in production: the time wheel, grouped cards with sub-screens, Quiet time, no starting-volume slider and the Cancel | Save pill. The FAB, keyboard time entry and ramp-start slider below are historical.

**Acceptance Criteria:**

**Given** the app root
**When** the app opens with no alarms
**Then** Navigation 3 shows the Alarms route (sealed `@Serializable Route : NavKey` registered with `subclassesOfSealed`) with the empty state "No alarms yet." and a `button-filled` "Add your first alarm", plus the `fab` ("+", content description "Add alarm" (EXPERIENCE.md Key strings)), both opening the Alarm editor with defaults

**Given** the Alarm editor (one ViewModel with `StateFlow<UiState>`, `onIntent(Intent)` and `Channel<UiEffect>`)
**When** it opens for a new alarm
**Then** it shows, in order: `time-picker` (keyboard input first, 12/24 h per system setting, digits in `display` with tabular figures), repeat `chip-day`s M T W T F S S (TalkBack reads full day names, selected chips use accent fill), a label `text-field`, snooze length `segmented-control` 5 / 9 / 10 / 15 min with 9 selected, a Sound row showing the default sound's name, a volume `slider`, a "Gradually increase volume" `switch` (default on) and, only while it is on, a starting-volume `slider` (5% steps, value announced on change), and a vibration `switch`, with a bottom `button-filled` "Save"
**And** the fee ladder next to snooze length, checks, grace window and motivation sections are not shown yet (added in Epics 3, 4 and 7)

**Given** a one-time alarm (no days selected) whose time has already passed today
**When** the time is chosen
**Then** a `note-inline` shows "Rings tomorrow at {time}." with the time formatted per system 12/24 h setting

**Given** valid input
**When** the user taps "Save"
**Then** `SaveAlarm` stores the alarm (enabled) and the editor closes to the Alarms route
**And** an invalid label (over 40 characters) blocks Save with supporting text on the field in `error` colour (EXPERIENCE.md Key strings)

**Given** an existing alarm opened for editing
**When** the user changes a field and presses Back or the `top-app-bar` back arrow
**Then** a `dialog-confirm` "Discard changes?" appears with actions "Discard" and "Keep editing" (EXPERIENCE.md Key strings), "Keep editing" is the default dismiss, and with no changes Back closes immediately

**Given** the editor screens
**When** Roborazzi and semantic tests run
**Then** screenshots exist for new alarm, edit alarm, one-time-tomorrow note and discard dialog in Light and Dark and at 200% font scale with nothing clipped, every touch target is ≥ 48 dp, and every control has a TalkBack label with role and state
**And** ViewModel tests with `FakeAlarmRepository` and `FakeClock` cover save, edit, validation and discard
**And** the `pps-design` Done checklist is ticked in the story file, all strings are resources, key strings match EXPERIENCE.md verbatim and `CopyRulesTest` passes (FR-MSG-4)
**And** `./gradlew qualityGate` passes

### Story 1.9: Alarm list on Home with the next-alarm countdown

As a user,
I want to see all my alarms, turn them on or off, duplicate or delete them, and see when the next one rings,
So that I trust what will happen tomorrow morning.
**Refs:** FR-ALM-1, FR-ALM-7, FR-MSG-4, NFR-9, AD-3, AD-11, UX-DR30, UX-DR31, UX-DR35, UX-DR41, UX-DR55, UX-DR56, UX-DR64, UX-DR66, UX-DR67, UX-DR76, UX-DR80, UX-DR84, UX-DR44, UX-DR59 · **Priority:** Must · **Verify:** auto

**Acceptance Criteria:**

**Given** saved alarms
**When** Home is shown
**Then** each alarm is the preview's `card-alarm` (glass) sorted by time of day, with the time in `title` (tabular figures), repeat days and label in `caption` (repeat summary "Every day", "Weekdays", "Weekends", "Once" or locale short day names like "Mon, Wed, Fri" (EXPERIENCE.md Key strings)) and a `switch` on the right
**And** tapping a card opens the Alarm editor for that alarm
**And** Home is the Alarms tab of `AppShell`, the floating glass nav capsule (Alarms · Progress · + · Settings · You); "+" opens the editor with defaults; the production FAB is removed
**And** the Home header collapses on scroll ("Yawn & Pawn" pinned, glass chip under it) with the next-alarm countdown in it (the streak hero joins at Story 6.3); reduced motion makes it instant
**And** Progress, Settings and You open their existing screens in their empty or default state, with rows whose stories are not done hidden (no dead links)
**And** alarm cards animate in and out on add and delete

**Given** at least one enabled alarm
**When** Home is shown
**Then** the Home header shows "Rings in {hours} h {minutes} min" for the soonest enabled alarm, computed with the Story 1.6 `nextOccurrence`/`durationUntil` functions (the same ones the scheduler uses) and rounded up to the next whole minute
**And** under one hour it shows "Rings in {minutes} min" and at 24 h or more "Rings in {days} d {hours} h" (EXPERIENCE.md Key strings)
**And** the countdown refreshes every minute, on resume, and when the time or time zone changes; with no enabled alarms the line is hidden
**And** unit tests with `FakeClock` cover 7 h 12 min, 59 s → "1 min", exactly 24 h, a DST-gap day and a time-zone change

**Given** an alarm card
**When** the user toggles its `switch`
**Then** `SetAlarmEnabled` applies immediately and the countdown updates (the commitment-lock confirmation for turning off within 8 h is added in Epic 4)

**Given** an alarm card
**When** the user long-presses it, or opens the editor overflow menu (for TalkBack)
**Then** a menu offers "Duplicate" and "Delete"; Duplicate creates a copy with a new id and `requestCode` and opens it in the editor
**And** Delete opens `dialog-confirm` "Delete your {time} alarm? This is logged." with "Delete" (in `error` colour) and "Keep it" as the default dismiss; confirming deletes the alarm and writes an `AlarmDeleted(alarmId, at)` entry through the `Logger` port

**Given** a storage read failure
**When** Home loads the alarm list, or the editor loads an alarm
**Then** Home shows "Couldn't load your alarms." with "Try again" instead of the list, and an editor load failure closes the editor with the snackbar "Couldn't open this alarm."

**Given** the Home screen states (empty, one alarm, many alarms, all disabled, load failure)
**When** Roborazzi and semantic tests run
**Then** screenshots exist for each state in Light and Dark and at 200% font scale, targets are ≥ 48 dp, and long-press actions are also reachable through the editor menu
**And** the `pps-design` Done checklist is ticked in the story file, all strings are resources, key strings match EXPERIENCE.md verbatim and `CopyRulesTest` passes (FR-MSG-4)
**And** `./gradlew qualityGate` passes

### Story 1.10: Schedule alarms exactly and keep them across reboot and clock changes

As a user,
I want every enabled alarm registered with Android as an exact alarm clock and re-registered after reboots, app updates and clock changes,
So that it rings on time in Doze, silent mode and Do Not Disturb.
**Refs:** FR-ALM-3, FR-ALM-5, FR-ALM-12, NFR-1, NFR-8, AD-3, AD-4 · **Priority:** Must · **Verify:** auto

**Acceptance Criteria:**

**Given** the `AlarmScheduler` port in `:core` (`schedule(alarmId, requestCode, triggerAtWallMillis)`, `cancel(requestCode)`, `armSessionSlot(Deadline)`, `cancelSessionSlot()`, `scheduleTest(triggerAtWallMillis)`) with `FakeAlarmScheduler` in `:testing`
**When** `AndroidAlarmScheduler` schedules an alarm
**Then** it calls only `AlarmManager.setAlarmClock()` with an `AlarmClockInfo` whose trigger time equals the scheduled epoch millis and whose show intent opens `MainActivity`, and an operation `PendingIntent` (immutable) to `AlarmFiredReceiver` carrying `alarmId` and the scheduled epoch millis, using the alarm's `requestCode`
**And** the session slot uses one reserved request code and the test alarm another, both distinct from every alarm `requestCode` (unit-tested)
**And** request codes are never reused: a persisted high-water mark in `app.db` gives each new alarm max(ever used) + 1 (test: delete the highest, create, the new code is higher)
**And** a Robolectric test with `ShadowAlarmManager` asserts `setAlarmClock` was called with trigger time == `nextOccurrence(...)` epoch ms (FR-ALM-3) and that no `set`, `setExact` or `setAndAllowWhileIdle` call exists (a detekt or lint rule bans them)

**Given** `core.rescheduleAll()`
**When** it runs
**Then** it reads every alarm from `AlarmRepository`, schedules the next occurrence of each enabled alarm and cancels the request code of each disabled alarm, and is idempotent (running it twice produces the same scheduler calls, verified with `FakeAlarmScheduler`)
**And** `SaveAlarm`, `SetAlarmEnabled`, `DeleteAlarm` and `DuplicateAlarm` sync the affected alarm with the scheduler after the repository write succeeds

**Given** the manifest
**When** the receivers are added
**Then** `SystemEventsReceiver` handles `BOOT_COMPLETED`, `TIME_SET`, `TIMEZONE_CHANGED`, `MY_PACKAGE_REPLACED` and `SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED` by calling `rescheduleAll()` inside `goAsync()`, `rescheduleAll()` also runs on app start, and all receivers are `directBootAware` and never start a foreground service (`LOCKED_BOOT_COMPLETED` is added in Epic 2)
**And** `AlarmFiredReceiver` hands `AlarmFired(alarmId, scheduledAt)` to an `AlarmFiredHandler` whose Epic 1 default schedules the next occurrence of a repeating alarm and disables a one-time alarm (Story 1.14 binds it to the wake runtime)
**And** `USE_EXACT_ALARM`, `SCHEDULE_EXACT_ALARM` with `maxSdkVersion="32"` and `RECEIVE_BOOT_COMPLETED` are declared and pass the permission allowlist
**And** on API 31–32, when `canScheduleExactAlarms()` is false, the adapter returns `DomainError.ExactAlarmNotPermitted` without falling back to an inexact alarm, and the error is logged (the user-facing prompt is Story 1.19)

**Given** Robolectric tests for each broadcast
**When** a boot, time-set, time-zone change or package-replaced broadcast is delivered with `FakeClock` and `FakeTimeZoneProvider` set to the new state
**Then** each enabled alarm is re-armed at the recomputed wall time, including a DST-gap day and a Berlin → New York zone change
**And** after a reboot `rescheduleAll()` uses the wall clock as AD-3 says; the reboot-before-network-time case is recorded in `docs/decisions/reboot-clock.md` and carried to Story 2.2, with no extra logic here
**And** a Robolectric test opens `app.db` with credential storage locked (device-protected context only)
**And** `./gradlew qualityGate` passes

### Story 1.11: The complete wake-session state machine in core

As a user,
I want one set of rules to decide whether my alarm rings, is quiet, is snoozed or is done,
So that no screen or service can disagree about what happens next.
**Refs:** FR-ALM-8, FR-ALM-9, FR-SES-7, FR-SES-8, FR-SES-10, NFR-11, NFR-12, AD-2, AD-3, AD-7, AD-16 · **Priority:** Must · **Verify:** auto

**Acceptance Criteria:**

**Given** `core.session`
**When** the model is added
**Then** `SessionState` is a sealed type with `Idle`, `Ringing`, `Grace`, `Loud`, `Snoozed`, `Completed`, `Missed`, each active state holding `sessionId`, frozen `SessionConfig`, `ringIndex`, `snoozesGranted`, `CheckRun` (plan, seeds, step, failedAttempts, fallbackUsed), `paying: PurchaseIntentId?`, `noGraceThisRing`, `paused`, `beforeFirstUnlock`, `paymentPending` (set by `PurchasePending`, cleared by a later `PurchaseGranted`), `declinedReuseProduct: String?` (set by `ReuseDeclined`), and `Deadline`s for grace end, interaction timeout and snooze end, all `@Serializable` (the display-only `paid` list is added with `Money` in Epic 4)
**And** `SessionConfig` holds alarmId, label, scheduledAt, testMode, baseFeeTier, maxSnoozes, snoozeLengthMinutes, graceSeconds, vibrateInGrace, volumePercent, gradualVolume, rampStartPercent, soundRef, vibration and check plan, and a pure `ConfigResolver.resolve(alarm, globalSettings, testMode)` produces it (pending changes arrive in Epic 4); `GlobalSettings` defaults are base fee tier 1, max snoozes 5, grace 20 s, snooze 9 min
**And** events are the AD-2 set: `AlarmFired`, `TestAlarmFired`, `SlotFired`, `ProcessRestored`, `ImUpTapped`, `GraceElapsed`, `CheckAnswerSubmitted`, `FallbackRequested`, `SnoozeTapped`, `PayConfirmed`, `ReuseOffered`, `ReuseAccepted`, `ReuseDeclined`, `PurchaseGranted`, `PurchaseFailed`, `PurchaseCancelled`, `PurchasePending`, `ImageMatchCompleted`, `ImageMatchFailed`, `NoInteractionTimeout`, `UserInteracted`, `CallStarted`, `CallEnded`, `OverlapAlarmFired`, `UserUnlocked`, `Recorded`; the `UnlockRequested` / `UnlockFailed` events and rows are not added in Epic 1 (if Spike S1, `docs/spikes/S1.md`, finds they are needed, they become a small story at the start of Epic 4, sprint-change-proposal-2026-10-01)

**Given** the pure reducer `reduce(state, event, now): Transition(state, oneShotEffects)` and the idempotent `entryEffects(state)`
**When** each row of the AD-2 transition table in Architecture Spine v0.3 is exercised (31 rows, including `ReuseDeclined` → same state with `declinedReuseProduct` set and the reuse sheet hidden, `PurchasePending` → same state with `paying = null` and `paymentPending = true`, `ImageMatchCompleted`/`ImageMatchFailed` in Grace or Loud driven by `FakeCheck`, and `SlotFired` in Ringing, Grace or Loud → same state, re-arm slot +60 s)
**Then** one parameterised test per row asserts the target state and the exact one-shot effects (for example Idle + `AlarmFired` with alarm enabled → `Ringing(ringIndex = 1)` with effects freeze config, create `CheckRun`, start wake runtime, arm slot +60 s, record session start; `Snoozed` + `OverlapAlarmFired` → `Ringing(ringIndex + 1, noGraceThisRing = true)`, record merged, reschedule that alarm)
**And** a table-coverage test asserts every row has a test, and that every (state, event) pair without a row returns the same state with only a `LogIgnored(event)` effect and never throws
**And** entry effects per state are asserted (Ringing/Loud: sound at set volume, slot armed, wake UI shown; Grace: muted, vibration only if `vibrateInGrace`, slot armed, wake UI shown; Snoozed: sound off, slot armed at snooze end; Completed/Missed: history write requested)

**Given** guards that later epics make real
**When** the reducer evaluates them
**Then** they come from constructor-injected pure policies with fakes in `:testing`: `SnoozeAvailabilityPolicy` (returns `Available(price)` / `Unavailable(reason)`; the Epic 1 production policy returns `Unavailable(TestMode)` for test sessions and `Unavailable(CatalogueNotLoaded)` otherwise), `CheckValidator` (`FakeCheck` programmable valid / invalid / last step; the Epic 1 production plan is one `Placeholder` step), and `FallbackPolicy` (Epic 1 production: not allowed)
**And** the next price is always `FeeLadder(config.baseFeeTier, snoozesGranted + 1)` behind a `FeeLadder` interface (the real ladder is Epic 4), and grace keeps counting while `paying` is set

**Given** the timeout rules (FR-ALM-9)
**When** pure `dueEvents(state, now)` is evaluated
**Then** it emits `GraceElapsed` when the grace deadline is due and `NoInteractionTimeout` when 30 minutes have passed since the last user event in Ringing or Loud, measured with the monotonic clock on the same boot
**And** every user event resets the interaction deadline, each new ring (after snooze or merge) starts a fresh 30-minute deadline, time spent `Snoozed` never counts, time spent `paused` (between `CallStarted` and `CallEnded`) is excluded, and `ProcessRestored` gives the restored ring a fresh 30-minute deadline, clears `paying`, and turns an overdue `Snoozed` into `Ringing(ringIndex + 1)` immediately
**And** tests use `FakeClock`/`FakeMonotonicClock` to cover: 29:59 no timeout; 30:00 → `Missed`; a tap at 20:00 moves the timeout to 50:00; a 10-minute call moves it by 10 minutes; a wall-clock jump of +2 h changes nothing; a snooze of 9 minutes is not counted
**And** Kover shows `core.session` ≥ 90% line coverage
**And** `./gradlew qualityGate` passes

### Story 1.12: SessionEngine with write-ahead persistence

As a user,
I want the alarm's state saved before anything happens,
So that a crash or kill never loses where my morning was.
**Refs:** FR-SES-1 (foundation), NFR-2, AD-2, AD-6, AD-12, AD-13 · **Priority:** Must · **Verify:** auto

**Acceptance Criteria:**

**Given** `SessionEngine` in `:core` (single instance, serialized by a `Mutex`)
**When** `dispatch(event)` is called
**Then** it reads time from the ports, calls `reduce`, commits the new state to the `ActiveSessionStore` port in one transaction, and only after the commit succeeds runs the one-shot effects and then the entry effects through the `EffectRunner` port
**And** if the commit fails, no effect runs, the error is logged, and the previous state is kept (test with a failing fake store)
**And** `state: StateFlow<SessionState>` exposes the committed state, and concurrent dispatches from 100 coroutines produce a strictly serial transition log (test)
**And** `dueEvents` are evaluated on every dispatch and by `tick()` (called by the wake runtime), and resulting events are dispatched in order

**Given** `:data`
**When** `RoomActiveSessionStore` is implemented
**Then** `runtime.db` (Room KMP, version 1, table `active_session` with `session_id`, `state_json`, `updated_at`) is created in device-protected storage, its schema exported to `data/schemas/`, and `state_json` round-trips every `SessionState` variant with kotlinx-serialization (tests)
**And** the backup rules from Story 1.7 are updated so `runtime.db` is explicitly excluded (Robolectric XML test updated)
**And** `purchase_intent` and `grant_ledger` tables are not created yet (Epic 4)

**Given** a persisted active session
**When** `SessionEngine.restore()` runs at process start
**Then** it loads the state, dispatches `ProcessRestored`, and runs only entry effects; no one-shot effect is ever replayed (test: commit `PayConfirmed` then "crash" before effects, restore, assert billing launch is not called and `paying` is null)

**Given** `:testing`
**When** fakes are added
**Then** `FakeActiveSessionStore`, `FakeEffectRunner` (records effects), `FakeBilling` (`launch(intent)` returns programmable `PurchaseGranted` / `Failed` / `Cancelled` / `Pending`) and `FakePurchaseIntentStore` exist, and an engine test runs a full morning with fakes: `AlarmFired` → `SnoozeTapped` → `PayConfirmed` → `PurchaseGranted` → `SlotFired` at snooze end → `ImUpTapped` → valid last answer → `Completed` → `Recorded` → `Idle` with `active_session` cleared
**And** Koin in `:androidApp` wires `SessionEngine` with the Room store and the Epic 1 production policies; the production `Billing` binding is `UnavailableBilling` until Epic 4
**And** Robolectric tests no longer each call `stopKoin()`; a test Application or shared rule starts and stops Koin
**And** `./gradlew qualityGate` passes

### Story 1.13: Record every session in history

As a user,
I want every morning written down once, with when it rang, when I got up and how it ended,
So that my progress can be shown later and is never counted twice.
**Refs:** FR-PRG-1, NFR-14, AD-6, AD-18 · **Priority:** Must · **Verify:** auto

**Acceptance Criteria:**

**Given** `:data`
**When** the history table is added
**Then** `app.db` migrates from version 1 to 2 adding `session_history` (`session_id` primary key, `alarm_id`, `scheduled_at`, `first_ring_at`, `ended_at` nullable, `snooze_count`, `check_types` (list), `time_to_complete_ms` nullable, `fallback_used`, `direct_boot`, `outcome` nullable: OnTime / Snoozed / Missed / Skipped / Test), with the exported v2 schema and a Room migration test from v1 with existing alarms preserved
**And** amounts paid per snooze are not stored in this table; they come from purchase records keyed to `session_id` in Epic 4 (AD-7, AD-8)
**And** `docs/decisions/db-downgrade.md` states the policy for restoring a newer-schema `app.db` on an older install (proposed: the backup agent skips restoring an `app.db` whose version is above the installed schema, and logs it)

**Given** `SessionRecorder` in `:core` (the only writer of session history, via a `SessionHistoryRepository` port with `FakeSessionHistoryRepository`)
**When** the engine executes "record session start" at `AlarmFired` or `TestAlarmFired`
**Then** one row is upserted with `first_ring_at`, `scheduled_at`, `alarm_id`, `direct_boot` and a null outcome

**Given** a session reaching `Completed` or `Missed`
**When** the history-write entry effect runs
**Then** the row is upserted with `ended_at`, `snooze_count`, `check_types`, `time_to_complete_ms` (first ring to completion), `fallback_used` and outcome: OnTime when completed with 0 snoozes, Snoozed when completed with ≥ 1, Missed after the timeout, Test when `config.testMode`
**And** the engine then dispatches `Recorded`, which moves to `Idle` and clears `runtime.db`
**And** replaying the same write (process death between the write and `Recorded`) leaves exactly one row with identical values (test)
**And** no other class writes `session_history` (a unit test scans `:core` and `:data` for writers of the DAO other than `RoomSessionHistoryRepository` used by `SessionRecorder`)
**And** `./gradlew qualityGate` passes

### Story 1.14: Ring the alarm: WakeService, AlarmPlayer and the ongoing notification

As a user,
I want the alarm sound to start on the alarm stream at my chosen volume, ramping up, with a full-screen notification,
So that it wakes me whether the phone is locked, silent or in Do Not Disturb.
**Refs:** FR-ALM-3, FR-ALM-4, FR-ALM-6, NFR-1, NFR-2, NFR-7, NFR-8, AD-2, AD-4, AD-5, AD-12, UX-DR24 · **Priority:** Must · **Verify:** auto (device timing is human-verify in Stories 1.20 and 1.21)

**Acceptance Criteria:**

**Given** an enabled alarm whose occurrence fires
**When** `AlarmFiredReceiver` receives it
**Then** it calls `startForegroundService(WakeService)`; `WakeService` calls `startForeground` within the platform limit with foreground-service type `mediaPlayback` [ASSUMPTION pending Spike S2], and dispatches `AlarmFired` to `SessionEngine` (or `OverlapAlarmFired` when a session is already active)
**And** the `AlarmFiredHandler` still schedules the next occurrence of a repeating alarm and disables a one-time alarm; a disabled or deleted alarm that fires does not ring and is logged
**And** `WakeService`, `WakeActivity` and the receivers are `directBootAware`, and `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MEDIA_PLAYBACK`, `USE_FULL_SCREEN_INTENT`, `WAKE_LOCK` and `VIBRATE` pass the permission allowlist

**Given** the "sound playing" entry effect
**When** `AndroidAlarmPlayer` (the only player, owned by `WakeService`) starts
**Then** it plays the alarm's sound looping with `AudioAttributes` usage `USAGE_ALARM` (independent of media and ringer volume), sets the alarm stream to the alarm's `volumePercent` at the start of the ring and restores the user's previous alarm-stream volume when the session ends (confirmed by owner)
**And** when `gradualVolume` is true, player gain ramps linearly from `rampStartPercent`% of the set volume (fixed 20) to the set volume over 30 s using a pure `rampGain(elapsed, startFraction, duration)` function in core (unit-tested at 0 s, 15 s, 30 s and 45 s); when false, the first audible frame is already at the set volume (unit-tested)
**And** the `AlarmValidation` rule "ramp start must not exceed volume" and the editor's min(20, volume) are removed
**And** vibration runs with alarm usage when `vibration` is on and not at all when off
**And** the default built-in sound is bundled (OGG, licence recorded in `docs/sounds/LICENSES.md`), and when the chosen sound cannot be opened or errors during playback the player switches to it (never silent; full sound-library fallback in Story 1.17)

**Given** the "wake UI shown" entry effect
**When** the notification is posted
**Then** it uses channel "Alarms" (EXPERIENCE.md Key strings) with importance high, category `alarm`, ongoing, monochrome sunrise small icon with accent tint, title = alarm time, text "{time} alarm · Tap to return to your alarm", a full-screen intent and content intent to `WakeActivity`, and no action that stops the sound

**Given** `WakeActivity` (skeleton in this story, full UI in Story 1.15)
**When** it starts
**Then** it calls `setShowWhenLocked(true)`/`setTurnScreenOn(true)` on API 27+ and sets `FLAG_SHOW_WHEN_LOCKED`/`FLAG_TURN_SCREEN_ON` on API 26, Back does nothing, it renders from `SessionEngine.state` without any loading state, and it forwards taps only as session events
**And** the app never starts an activity from the background (only the full-screen intent and notification tap open `WakeActivity`)

**Given** an uncaught exception inside the wake flow
**When** it reaches the `WakeService` boundary
**Then** it is reported through the `CrashReporter` port (fake until Story 1.19), the player switches to the default sound, and `ProcessRestored` is dispatched (AD-12, NFR-2)

**Given** the session reaches `Completed`, `Missed` or `Idle`
**When** the entry effects run
**Then** the sound and vibration stop, the notification is removed, the slot is cancelled and `WakeService` stops itself, leaving no running service (NFR-8)
**And** Robolectric tests cover: receiver → service start → engine `Ringing`; player attributes `USAGE_ALARM`; ramp values; notification fields; stop on completion; missing sound → default
**And** `./gradlew qualityGate` passes

### Story 1.15: Ringing screen over the lock screen with "I'm up"

As a user,
I want a bright, simple ringing screen with the time and one big "I'm up" button,
So that I can stop the alarm half-asleep.
**Refs:** FR-ALM-4, FR-RNG-1 (visual shell), FR-MSG-4, NFR-7, NFR-9, AD-2, AD-5, AD-11, UX-DR2, UX-DR9, UX-DR12, UX-DR13, UX-DR14, UX-DR64, UX-DR66, UX-DR67, UX-DR72, UX-DR74, UX-DR78 · **Priority:** Must · **Verify:** auto, plus (human-verify) timing in Story 1.21

**Acceptance Criteria:**

**Given** a session in `Ringing`
**When** `WakeActivity` renders
**Then** it renders the preview's `ui/wake` Ringing composable from `SessionEngine.state` in `PpsTheme(wake = true)` (Sunrise, layout per DESIGN.md v0.5), with the clock in `clock-xl` (tabular figures, capped at 1.3× font scale), and a gentle pulse on "I'm up" that stops with reduced motion
**And** `button-wake-primary` "I'm up" is full width, 72 dp, `rounded.full`, `accent-sunrise` fill with `on-accent-sunrise` label in `button-wake`, the largest element on screen, always enabled, and in the bottom 40% of the screen
**And** 16 dp below it the snooze control renders the `SnoozeAvailabilityPolicy` result: `button-snooze-disabled` (64 dp, `disabled-container-sunrise` fill, `disabled-content-sunrise` label, leading `block` icon) reading "Snooze unavailable: prices not loaded yet" in a normal Epic 1 session and "Test · no charge" in a test session, with TalkBack "Snooze unavailable, {reason}"; the enabled "Snooze · {price}" variant renders for `Available(price)` in a preview and screenshot (tapping it arrives in Epic 4)
**And** the disabled snooze uses the `disabled-container-sunrise` / `disabled-content-sunrise` pair explicitly (not Material alpha)
**And** the screen has no loading state and renders from the in-memory `SessionEngine.state` (a test asserts no suspend repository call happens before the first frame)

**Given** the ringing screen
**When** the user taps "I'm up"
**Then** `ImUpTapped` is dispatched (plus `UserInteracted`), the session enters Grace (muted), the `Placeholder` check step submits its answer automatically, the session reaches `Completed` → `Recorded` → `Idle`, the sound stops, the notification is removed and `WakeActivity` finishes, with history outcome OnTime (Epic 3 replaces the placeholder with real checks)
**And** any other tap on the screen dispatches `UserInteracted`, and Back does nothing while Home and Recents still work

**Given** accessibility and layout rules
**When** semantic and Roborazzi tests run
**Then** TalkBack initial focus is the clock (read as the full time, for example "6:15 AM"), then "I'm up"; every wake action is ≥ 64 dp ("I'm up" 72 dp); at 200% font scale nothing clips and both actions stay on screen without scrolling; with animator duration scale 0 every transition is instant
**And** screenshots exist for first ring with and without label, snooze unavailable, test alarm, and enabled-snooze preview, in Sunrise at 100% and 200% font scale
**And** an instrumented test on the Gradle Managed Device fires a debug-scheduled alarm and asserts `WakeActivity` is resumed and "I'm up" is displayed within 1,000 ms of the receiver running (NFR-7 on emulator; device timing is checked in Story 1.21 (human-verify))
**And** the `pps-design` Done checklist is ticked in the story file, all strings are resources, key strings match EXPERIENCE.md verbatim and `CopyRulesTest` passes (FR-MSG-4)
**And** `./gradlew qualityGate` passes

### Story 1.16: Stop a forgotten alarm after 30 minutes

As a user (and the people sleeping near me),
I want an alarm nobody touches to stop after 30 minutes and be logged as missed,
So that it doesn't ring all day when I'm away from my phone.
**Refs:** FR-ALM-9, FR-PRG-1, FR-MSG-4, AD-2, AD-3, AD-18, UX-DR35, UX-DR80, UX-DR84 · **Priority:** Must · **Verify:** auto

**Acceptance Criteria:**

**Given** an active ringing session
**When** `WakeService` runs
**Then** it calls `SessionEngine.tick()` when the nearest deadline (interaction timeout or grace end) is due, scheduling the next tick from `Deadline.remaining` with the monotonic clock, and also on every `SlotFired`
**And** when `NoInteractionTimeout` fires the session moves to `Missed`, the sound and vibration stop, the notification is removed, `WakeActivity` finishes, and history records outcome Missed via `SessionRecorder`

**Given** a Missed session in history that the user has not dismissed
**When** the user next opens Home
**Then** a `note-inline` shows "Your {time} alarm stopped after 30 minutes. Logged as missed." until the user dismisses it (dismissal stored per session id in DataStore, device-protected, created here with `PreferenceDataStoreFactory.createWithPath`)

**Given** Robolectric tests with fake clocks driving `WakeService`
**When** 30 minutes pass with no interaction
**Then** the service stops and the Missed row exists; with a `UserInteracted` at minute 20 the stop happens at minute 50; a wall-clock change of −1 h during the ring does not shorten or extend the timeout
**And** Roborazzi screenshots cover Home with the missed note in Light and Dark and at 200% font scale
**And** the `pps-design` Done checklist is ticked in the story file, all strings are resources, key strings match EXPERIENCE.md verbatim and `CopyRulesTest` passes (FR-MSG-4)
**And** `./gradlew qualityGate` passes

### Story 1.17: Built-in sound library with preview and a never-silent fallback

As a user,
I want to pick from at least ten loud alarm sounds or my phone's ringtones and hear them first,
So that I wake to a sound that works for me, and never to silence.
**Refs:** FR-SND-1, FR-SND-2, FR-SND-5, FR-MSG-4, NFR-2, NFR-9, AD-5, AD-14, UX-DR53, UX-DR43, UX-DR64, UX-DR66, UX-DR67 · **Priority:** Must · **Verify:** auto

**Acceptance Criteria:**

**Given** the app bundle
**When** the sound library is added
**Then** at least 10 royalty-free alarm sounds (OGG) are bundled, each listed in `docs/sounds/LICENSES.md` with source URL, author and licence (CC0 or equivalent), and a core `SoundCatalog` lists them with a `SoundRef.BuiltIn(id)` and a resource name; exactly one is marked default
**And** system alarm ringtones are listed through `RingtoneManager` (`TYPE_ALARM`) as `SoundRef.System(uri)`

**Given** the loudness rule (FR-SND-1)
**When** `./gradlew checkSoundLoudness` runs (a `qualityGate` dependency)
**Then** it measures every bundled alarm sound (`res/raw/alarm_*`) with ffmpeg `ebur128` and fails, naming the file, if the peak is below −3 dBFS or the integrated loudness is below −14 LUFS; if ffmpeg is missing it fails with an install hint, and CI installs ffmpeg
**And** UI sounds such as `wheel_tick.wav` are not measured and are listed as exempt in the task
**And** a fixture test proves a quiet file fails the check and a compliant file passes

**Given** the Alarm editor Sound row
**When** the user taps it
**Then** the editor's Sound sub-screen (the preview's `ui/sound`) shows the volume slider and the "Gradually increase volume" switch, then sectioned lists "Built-in" and "System" ("Your files" arrives in Story 7.4), each `sound-row` with radio selection, name in `body` and a 48 dp preview button
**And** selecting a row and returning updates the editor's Sound row; the choice is saved with the alarm on "Save"

**Given** a `sound-row` preview button
**When** the user taps it
**Then** the sound plays once through a preview player with `USAGE_ALARM` at the alarm's volume, a second preview stops the first, and preview stops when the user leaves the screen or the app goes to the background
**And** preview is announced by TalkBack with role and state ("Play preview" / "Stop preview" (EXPERIENCE.md Key strings))

**Given** a chosen sound that is missing or broken (system ringtone URI no longer resolves, file unreadable, decoder error at prepare or during playback)
**When** the alarm rings
**Then** `AlarmPlayer` plays the default built-in sound within the same ring, logs the fallback without file paths, and never leaves the alarm silent (NFR-2)
**And** the Sound sub-screen and editor show "File missing. Default sound will play." for that choice
**And** Robolectric tests cover: missing URI → default; `MediaPlayer` error callback mid-ring → default; default resource always resolves
**And** Roborazzi screenshots cover the Sound sub-screen (list, selected, missing-file row) in Light and Dark and at 200% font scale
**And** the `pps-design` Done checklist is ticked in the story file, all strings are resources, key strings match EXPERIENCE.md verbatim and `CopyRulesTest` passes (FR-MSG-4)
**And** `./gradlew qualityGate` passes

### Story 1.18: Test alarm and debug fire-now hook

As a user,
I want to ring a test of my alarm that can never charge me,
So that I know it works on my phone before tomorrow morning.
**Refs:** FR-ALM-8, FR-MSG-4, NFR-11, AD-2, AD-14, UX-DR14, UX-DR27, UX-DR56, UX-DR78, UX-DR84 · **Priority:** Must · **Verify:** auto

**Acceptance Criteria:**

**Given** the Alarm editor
**When** it is shown
**Then** "Test alarm" is a `button-text` under the editor's cards; Save and Cancel stay in the `SaveCancelPill`

**Given** the user taps "Test alarm"
**When** no session is active
**Then** a snackbar shows "Lock your phone. We'll ring in 10 seconds." and a test ring is scheduled through `AlarmScheduler.scheduleTest` 10 s ahead using the editor's current (even unsaved) values
**And** when it fires, `TestAlarmFired` starts a session with `config.testMode = true`: the full Epic 1 flow runs (sound with ramp, vibration, full-screen ringing screen, "I'm up", placeholder check), the snooze control reads "Test · no charge" and is not tappable, no billing call is possible (test asserts `FakeBilling.launch` is never called in test mode), and history records outcome Test

**Given** a session is already active
**When** a test alarm fires
**Then** the `TestAlarmFired` event is ignored and logged per the AD-2 rule, and the running session is unchanged

**Given** the debug build only
**When** `adb shell am broadcast -a com.yawnandpawn.app.debug.FIRE --ei seconds N [--es alarmId ID] [--ez test true|false]` is sent
**Then** `DebugFireReceiver` schedules that alarm (or a synthetic one with defaults) to fire in N seconds through `AlarmScheduler`, as a real or test session
**And** the receiver lives only in the `debug` source set; a test inspects the merged release manifest and release classes and fails if `DebugFireReceiver` or the `debug.FIRE` action is present
**And** the release-content test also fails if any `debug.preview` or `ThemeShowcase` class or activity, or the "Yawn & Pawn Preview" label, is present
**And** Roborazzi screenshots cover the editor with the Test alarm button and the test snackbar in Light and Dark and at 200% font scale, and the Sunrise ringing screen in test mode
**And** the `pps-design` Done checklist is ticked in the story file, all strings are resources, key strings match EXPERIENCE.md verbatim and `CopyRulesTest` passes (FR-MSG-4)
**And** `./gradlew qualityGate` passes

### Story 1.19: Permissions for reliable alarms and crash reporting

As a user,
I want the app to ask for exactly the permissions an alarm needs and tell me plainly when one is missing,
So that my alarm isn't silently blocked, and crashes get fixed.
**Refs:** FR-ALM-4, FR-ALM-12, FR-MSG-4, NFR-4, NFR-13, NFR-15, AD-4, AD-5, AD-15, UX-DR33, UX-DR50 (partial), UX-DR84 · **Priority:** Must · **Verify:** auto, plus (human-verify) Crashlytics console check

**Acceptance Criteria:**

**Given** a `ReliabilityProbe` port (`notificationsAllowed`, `fullScreenIntentAllowed`, `exactAlarmsAllowed`) with an Android adapter and `FakeReliabilityProbe`
**When** it is evaluated on API 26–36
**Then** notifications use `NotificationManagerCompat.areNotificationsEnabled()`, full-screen intent uses `NotificationManager.canUseFullScreenIntent()` on API 34+ (always true below), and exact alarms use `AlarmManager.canScheduleExactAlarms()` on API 31–32 only (always true on 33+ with `USE_EXACT_ALARM`, and on ≤ 30)
**And** Robolectric tests cover API 26, 31, 32, 33, 34 and 36

**Given** API 33+ and notifications not yet granted
**When** the user saves their first enabled alarm
**Then** the app requests `POST_NOTIFICATIONS` once from that screen (never from the background)

**Given** any probe item is false
**When** Home is in the foreground (re-evaluated on every `ON_START`)
**Then** a non-dismissible `banner-warning` (`surface-variant`, leading `error` icon) shows "Alarms may not ring. Fix settings" with a `button-text` "Fix" that deep-links to the first failing setting: app notification settings, `ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT` (API 34+), or `ACTION_REQUEST_SCHEDULE_EXACT_ALARM` (API 31–32)
**And** the banner clears itself once every item is OK, and granting exact alarms triggers `rescheduleAll()` through the Story 1.10 receiver
**And** the full reliability checklist (DND, battery optimization, OEM guidance) comes in Epic 5

**Given** Firebase Crashlytics (BoM 34.19.0)
**When** it is integrated
**Then** a `CrashReporter` port has `FirebaseCrashReporter` and `FakeCrashReporter`; `FirebaseInitProvider` is removed from the merged manifest and Firebase initialises only when the user is unlocked (`UserManager.isUserUnlocked`, else on `ACTION_USER_UNLOCKED`) (AD-15)
**And** the manifest sets `firebase_analytics_collection_enabled` = false and `google_analytics_adid_collection_enabled` = false (Analytics consent comes in Epic 5), and no Crashlytics custom key or log contains personal data, purchase tokens, labels or file paths (unit test on the reporter wrapper)
**And** the google-services and Crashlytics Gradle plugins apply only when `google-services.json` exists (from CI secrets `GOOGLE_SERVICES_JSON_DEBUG` / `GOOGLE_SERVICES_JSON_RELEASE`); without it the build uses the no-op reporter and `qualityGate` still passes
**And** new Firebase coordinates are added to `config/dependency-allowlist.txt` in the same change, and the permission allowlist still passes

**Given** the owner has created the Firebase debug and prod projects for the final package id and stored the config secrets
**When** a debug build with the file triggers the debug-only test crash (`adb shell am broadcast -a com.yawnandpawn.app.debug.CRASH`)
**Then** the crash appears in the Crashlytics console of the debug project within 10 minutes (human-verify, recorded in the story file with date)
**And** Roborazzi screenshots cover Home with the banner in Light and Dark and at 200% font scale
**And** the `pps-design` Done checklist is ticked in the story file, all strings are resources, key strings match EXPERIENCE.md verbatim and `CopyRulesTest` passes (FR-MSG-4)
**And** `./gradlew qualityGate` passes

### Story 1.20: Spike S2: alarm reliability on the device matrix

As the owner,
I want overnight evidence that alarms ring on real phones in hostile conditions,
So that the foreground-service type and the Epic 2 escape protections are chosen from facts.
**Refs:** FR-ALM-3, FR-ALM-4, FR-ALM-5, FR-SES-2, FR-SES-4, NFR-1, NFR-7, NFR-13, AD-4, AD-5; PRD §10 S2, Q18 · **Priority:** Must · **Verify:** human-verify

**Acceptance Criteria:**

**Given** the Epic 1 debug build from `main` and, for the Epic 2 behaviours, a prototype on branch `spike/s2-reliability` (backup alarm re-armed every 60 s while ringing, notification return path) that is never merged
**When** the owner tests the device matrix (the owner's Oppo A96 (ColorOS, Android 13), plus GMD emulators for the API levels in NFR-1; other makers optional via Firebase Test Lab), recording device, Android version and date per run
**Then** `docs/spikes/S2.md` records for each device: ring latency from scheduled time (target ≤ 2 s, NFR-1) and ringing-screen latency (target ≤ 1 s, NFR-7) measured from screen recordings, with screen off and locked, in Doze (`adb shell dumpsys deviceidle force-idle` and one real overnight run), battery saver on, Do Not Disturb on (default "alarms allowed"), silent mode, and headphones connected
**And** it records what happens after an overnight reboot before first unlock (expected to fail until Epic 2), after "Stop" in the OEM Task Manager, after swiping the app from Recents, and during an incoming call, with and without the prototype backup alarm
**And** it records whether tapping the ongoing notification returns to the ringing screen within 1 s without any background activity start (FR-SES-4), and whether a heartbeat `setAlarmClock` every 60 s changes the system next-alarm indicator visibly (Q18)

**Given** the findings
**When** the owner writes the Decision section
**Then** it states the foreground-service type (`mediaPlayback` or `systemExempted`) with the reason (Android 14–16 start restrictions, Play declaration impact), and any change from `mediaPlayback` becomes a new story before Epic 2 starts
**And** every failed target becomes a bug story or an Epic 2 story note, and contradictions with the Architecture Spine go through `bmad-correct-course`
**And** the story file records pass/fail per scenario and device; automation never marks this story done
**And** `./gradlew qualityGate` passes on `main` with `docs/spikes/S2.md` committed

### Story 1.21: Epic 1 device verification checklist

As the owner,
I want to confirm on real phones everything in Epic 1 that tests can't prove,
So that Epic 2 builds on an alarm I know rings.
**Refs:** FR-ALM-1–9, FR-ALM-12, FR-SND-1, FR-SND-2, FR-SND-5, FR-PRG-1, FR-MSG-4, NFR-1, NFR-2, NFR-7, NFR-9, NFR-11 · **Priority:** Must · **Verify:** human-verify

**Acceptance Criteria:**

**Given** the latest `main` debug build installed on each device of the matrix (the owner's Oppo A96 (ColorOS, Android 13), plus GMD emulators for the API levels in NFR-1; other makers optional via Firebase Test Lab)
**When** the owner runs the checklist
**Then** for each item the story file records pass/fail, device, Android version and date:
1. An alarm set 2 minutes ahead rings within 2 s of the scheduled time with the screen off and locked (NFR-1).
2. The ringing screen is visible within 1 s of the sound starting (NFR-7), measured on a screen recording.
3. It rings in forced Doze, with battery saver on, with Do Not Disturb on, and with the ringer on silent and media volume at 0, on the alarm stream.
4. With "Gradually increase volume" on, the volume ramps from 20% of the set volume to the set volume within 30 s; with it off, the alarm starts at the set volume; vibration on and off are respected.
5. The ringing screen appears over the lock screen when locked and when the screen is off; when the phone is unlocked and in use, the heads-up notification appears and tapping it opens the ringing screen.
6. "I'm up" stops the sound, removes the notification and closes the screen; Home then shows the next "Rings in …" countdown.
7. Create, edit, duplicate, disable, enable and delete alarms; repeat days skip unselected days (checked by setting the date manually); a one-time alarm set for a passed time rings tomorrow and shows "Rings tomorrow at {time}."
8. After a reboot (unlock, then wait) the alarm still rings on time; after installing a newer debug build over the old one (`adb install -r`) it still rings.
9. After changing the clock by +1 h, and after changing the time zone, the alarm rings at the set local time.
10. With the zone set to Europe/Berlin and the date set manually to a DST spring-forward day, a 02:30 alarm rings at 03:30; on the fall-back day a 02:30 alarm rings once.
11. A test alarm rings 10 s after "Test alarm" with the phone locked and shows "Test · no charge" on a disabled snooze control.
12. Leaving an alarm untouched for 30 minutes stops it, and Home shows "Your {time} alarm stopped after 30 minutes. Logged as missed."
13. Every built-in sound previews on the alarm stream and stops when leaving the picker; a sound forced missing via the debug hook plays the default sound.
14. On API 33+, denying notifications shows "Alarms may not ring. Fix settings" and "Fix" opens the right setting; on API 34+ revoking full-screen intent does the same; on an Android 12 device or emulator revoking exact alarms does the same, and the banner clears on return.
15. With TalkBack on, the ringing screen focuses the clock first, then "I'm up", and the disabled snooze reads its reason; at 200% font size both actions stay on screen.
16. All copy seen during the checklist matches EXPERIENCE.md (no em dashes, no filler, "No charge." wording where relevant) (FR-MSG-4).
17. The time wheel ticks (haptic and quiet sound) and is silent when the phone is on silent.
18. Save stays above the keyboard and the pill never covers content (checked with uiautomator bounds).
19. The app draws edge-to-edge with correct status-bar icons in Light, Dark and Sunrise.
20. The nav capsule works, "+" opens the editor from every tab, and the Home header collapses on scroll.
21. Predictive back on editor sub-screens looks right (if not, sub-screens become Navigation 3 routes in a bug story).

**Given** any failed item
**When** the owner records it
**Then** each failure becomes a new bug story referencing this item, and this story stays open until all items pass or are explicitly waived by the owner in the story file
**And** automation never marks this story done

## Epic 2: An alarm you can't escape

The alarm keeps its promise: it survives process death, app kills and overnight reboots (including before the first unlock), clock and time-zone changes can't end or shorten it, it pauses for phone calls, merges overlapping alarms, and locks the app to the wake screen during a session while the rest of the phone stays fully usable. This epic adds no new session states: it builds on the Epic 1 AD-2 state machine, `SessionEngine`, `runtime.db`, `app.db`, `WakeService`, `WakeActivity` and the reserved session-slot request code, and makes them hold up on real phones. It ends with a human-verify escape-attempt checklist on the device matrix.

Every UI story in this epic carries the two standing acceptance criteria from Epic 1, repeated in the story so the build loop can check them: (1) the `pps-design` Done checklist is copied into the story file with every item ticked; (2) all new user-facing strings live in Compose Multiplatform resources, match `EXPERIENCE.md > Voice and Tone > Key strings` verbatim where a key string exists, and pass the automated copy-rules test from Story 1.3 (FR-MSG-4). A string that EXPERIENCE.md does not define is marked `(EXPERIENCE.md Key strings)` in the story and listed for the owner.

### Story 2.1: Keep the backup alarm armed and recover after a kill

As a user,
I want the alarm to come back by itself within a minute if the app is killed while it rings,
So that killing the app, an OEM task killer or a crash never ends my morning for free.
**Refs:** FR-SES-1, FR-SES-2, FR-SES-10, NFR-2, NFR-8, AD-2, AD-3, AD-4, AD-5, AD-12 · **Priority:** Must · **Verify:** auto (device kills are human-verify in Story 2.13)

**Acceptance Criteria:**

**Given** a session in `Ringing`, `Grace` or `Loud`
**When** the entry effects run after any dispatch
**Then** the "slot armed" entry effect calls `AlarmScheduler.armSessionSlot` with a `Deadline` at most 60 s ahead (now + 60 s, re-armed on every `SlotFired` per the AD-2 heartbeat row), and in `Snoozed` it arms the slot at snooze end
**And** `Grace` keeps the heartbeat too: the Story 1.11 entry effects already arm the slot in `Grace`, and a `SlotFired` during `Grace` follows the AD-2 row `Ringing, Grace, Loud | SlotFired` (same state, re-arm slot +60 s); `SessionEngine` re-runs the idempotent `entryEffects(state)` after every dispatch, including an ignored one (no commit is written when the state is unchanged)
**And** `FakeAlarmScheduler` shows exactly one pending slot request code at any time during a session (never two), and none after `Completed`, `Missed` or `Idle`
**And** the reducer's transition table is unchanged (the Story 1.11 table-coverage test still passes without new rows)

**Given** `AndroidAlarmScheduler.armSessionSlot(deadline)`
**When** it arms the slot
**Then** it calls only `setAlarmClock()` with the reserved slot request code, a trigger time of current wall time + `deadline.remaining(now)` (AD-3), a show intent to `MainActivity`, and an immutable operation `PendingIntent` to `SessionSlotReceiver` (`directBootAware`, `exported="false"`)
**And** re-arming replaces the pending slot (same request code, `FLAG_UPDATE_CURRENT`), verified with `ShadowAlarmManager`
**And** the heartbeat mechanism follows the Q18 finding recorded in `docs/spikes/S2.md` (Story 1.20); any change of mechanism needs `bmad-correct-course` first

**Given** the process has been killed during a session
**When** the slot fires and `SessionSlotReceiver` runs
**Then** it calls `startForegroundService(WakeService)` with the slot action; `WakeService` calls `startForeground` first, then `SessionEngine.restore()` (which dispatches `ProcessRestored` when `runtime.db` holds a session), and only then dispatches `SlotFired`
**And** `WakeService` always completes `restore()` before dispatching any broadcast-derived event (`AlarmFired`, `TestAlarmFired`, `OverlapAlarmFired`, `SlotFired`), so an alarm occurrence that fires into a killed process with a persisted session becomes `OverlapAlarmFired`, never a second session (test)
**And** when `runtime.db` holds no session (orphan slot), the service logs it, cancels the slot, removes its notification and stops itself, leaving no running service (NFR-8)

**Given** a restored session
**When** `ProcessRestored` has been handled
**Then** only entry effects run: the sound plays again at the set volume on `USAGE_ALARM` (no ramp on a restored ring), the ongoing notification with its full-screen intent is posted again, `WakeActivity` shows the same step with the same snooze count and check progress, and no "restored" message is shown (UX-DR78)
**And** `paying` is cleared, billing is never relaunched (the Story 1.12 test is extended to go through `SessionSlotReceiver`), and the restored ring has a fresh 30-minute interaction deadline
**And** a restored `Grace` whose grace deadline has passed moves to `Loud` on the same dispatch through `dueEvents`

**Given** a process started only for a non-alarm broadcast (boot, `TIME_SET`, `TIMEZONE_CHANGED`, `MY_PACKAGE_REPLACED`, `SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED`, user unlocked, debug hooks)
**When** `runtime.db` holds an active session
**Then** the process does not run the restore entry effects and never starts a foreground service; it arms the session slot instead: for immediate delivery (wall now + 1 s) in `Ringing`, `Grace` or `Loud`, at snooze end in `Snoozed`, so the alarm broadcast starts `WakeService` (Android 12+ foreground-service start rules; no media foreground service from boot receivers on Android 15+)
**And** `SessionEngine.restore()` runs its entry effects only from `WakeService` start, `MainActivity` creation or `WakeActivity` creation (this narrows Story 1.12's "restore at process start" to these entry points; a unit test fails if `restore()` is called from `Application.onCreate` or any receiver)

**Given** the user swipes the app from Recents during a session
**When** `WakeService.onTaskRemoved` is called
**Then** the session, the player and the foreground notification keep running (`stopWithTask` is not set to true; Robolectric test calls `onTaskRemoved` and asserts the player is still playing)

**Given** Robolectric tests that "kill" the process by discarding the `SessionEngine` instance and rebuilding it from the Room `runtime.db` on a temporary device-protected path
**When** a kill happens in `Ringing`, `Grace`, `Loud`, `Snoozed` and `Ringing` with `paying` set, and the slot is delivered
**Then** each resumes as described above within one slot delivery, `Snoozed` stays silent until snooze end and then rings as `Ringing(ringIndex + 1)`, and history still ends with exactly one row per session
**And** `./gradlew qualityGate` passes

### Story 2.2: Session deadlines survive clock changes and reboots

As a user,
I want changing the clock, the time zone or restarting the phone to neither end nor shorten my alarm,
So that there is no trick with the settings app that stops it for free.
**Refs:** FR-SES-1, FR-SES-5, FR-SES-10, FR-ALM-5, FR-ALM-9, AD-2, AD-3, AD-4 · **Priority:** Must · **Verify:** auto

**Acceptance Criteria:**

**Given** an active session
**When** `TIME_SET` or `TIMEZONE_CHANGED` is received
**Then** `SystemEventsReceiver` runs `rescheduleAll()` (Story 1.10) and also re-arms the session slot from its stored `Deadline`: on the same boot the new trigger is current wall time + the remaining monotonic duration, so the session is not ended, skipped or shortened (FR-SES-5)
**And** the frozen `SessionConfig` is not re-resolved, and the Home countdown and scheduled occurrences use the new time and zone

**Given** tests with `FakeClock`, `FakeMonotonicClock`, `FakeBootCounter` and `FakeTimeZoneProvider`
**When** the wall clock jumps by +2 h, by −2 h, and the zone changes Europe/Berlin → America/New_York
**Then** in `Ringing` and `Loud` the session keeps ringing and the 30-minute timeout still fires 30 monotonic minutes after the last interaction
**And** in `Grace` the grace window ends after the same number of monotonic seconds
**And** in `Snoozed` a 9-minute snooze with a +1 h jump at minute 3 re-rings 6 monotonic minutes later, and with a −1 h jump also 6 minutes later
**And** a scheduled alarm occurrence due during the session is still delivered and dispatched as `OverlapAlarmFired` per the Epic 1 reducer rows (FR-SES-7; the merge record is added in Story 2.9)

**Given** a persisted session and a reboot (the stored `Deadline.bootCount` differs from `BootCounter`)
**When** `BOOT_COMPLETED` or `LOCKED_BOOT_COMPLETED` is received
**Then** the slot is armed from wall time: `Snoozed` with snooze end still ahead → at the snooze end wall time; `Snoozed` with snooze end already past (phone off through it) → wall now + 1 s, and restore turns it into `Ringing(ringIndex + 1)` immediately; `Ringing`, `Grace` or `Loud` → wall now + 1 s, and the restored ring gets a fresh 30-minute deadline (PRD §6.4)
**And** a session restored after the phone was off for 10 hours still rings (powering off is an accepted escape only while the phone is off, FR-SES-1)

**Given** a device where `Settings.Global.BOOT_COUNT` is missing or unreadable
**When** `Deadline` compares boots
**Then** it treats the deadline as the same boot only when the boot count matches and the current elapsed time is not lower than the stored elapsed time; a lower elapsed time always means a reboot and falls back to wall time (unit tests for both cases; this tightens the Story 1.6 rule without changing its results on normal devices)

**Given** an app update during a session
**When** `MY_PACKAGE_REPLACED` is received
**Then** the slot is armed for immediate delivery and the session is restored with the same step and counts (FR-SES-1, Robolectric test)
**And** `./gradlew qualityGate` passes

### Story 2.3: Ring before the first unlock after a reboot

As a user,
I want my alarm to ring after an overnight restart even if I haven't unlocked the phone yet,
So that a system update at 3:00 doesn't make me late.
**Refs:** FR-ALM-11, FR-ALM-5, FR-SES-1, FR-SES-10, FR-RNG-7 (reason only), FR-MSG-4, NFR-2, NFR-9, AD-4, AD-6, AD-7, AD-15, UX-DR14, UX-DR64, UX-DR66, UX-DR78, UX-DR84, UX-DR93 · **Priority:** Must · **Verify:** auto (real reboots are human-verify in Story 2.13)

**Acceptance Criteria:**

**Given** the manifest
**When** `SystemEventsReceiver` (already `directBootAware`) also declares `LOCKED_BOOT_COMPLETED`
**Then** on that broadcast it calls `rescheduleAll()` from the device-protected `app.db` and arms the session slot per Story 2.2 inside `goAsync()`, never starts a foreground service, and `BOOT_COMPLETED` after unlock repeats the same work idempotently (`FakeAlarmScheduler` shows identical calls)

**Given** a Robolectric test with `ShadowUserManager` set to locked and a test context that throws on any access to credential-protected storage (`filesDir`, `getDatabasePath`, `getSharedPreferences`, `dataDir` of the normal context)
**When** it runs `LOCKED_BOOT_COMPLETED` → `rescheduleAll()` → alarm occurrence fires → `WakeService` → `Ringing`
**Then** the alarm rings with sound and the ringing notification, and no credential-protected path is touched (`app.db`, `runtime.db` and the DataStore files all resolve under `createDeviceProtectedStorageContext()`)
**And** Firebase stays uninitialised (Story 1.19 gate) and no `Billing` initialisation call happens while locked (asserted with `FakeBilling`)

**Given** detekt with the custom `:detekt-rules`
**When** code in `:data` or `:androidApp` calls `getDatabasePath`, `filesDir`, `cacheDir`, `getSharedPreferences`, `preferencesDataStore` or `dataStoreFile` on a context that is not the device-protected context
**Then** detekt fails, except inside the `android.media` package reserved for credential-protected media (Epic 7), and the rule has a violating and a compliant snippet test

**Given** a `UserLockState` port in `:core` (`isUserUnlocked(): Boolean`, `observe(): Flow<Boolean>`) with an Android adapter (`UserManager.isUserUnlocked`) and `FakeUserLockState` in `:testing`
**When** `SessionEngine` dispatches `AlarmFired`, `TestAlarmFired` or `ProcessRestored` while the user is locked
**Then** the session gets `beforeFirstUnlock = true` and the pure `DirectBootSubstitution.apply(config)` is applied to the ring's config: a `soundRef` that is not `SoundRef.BuiltIn` becomes the default built-in sound (system ringtones need the media provider, which is not available before unlock), and every check-plan step whose check type is not marked Direct Boot safe is replaced by the Direct Boot check
**And** in this epic the plan is the Epic 1 `Placeholder` step, which is marked Direct Boot safe and stays; Epic 3 sets Math as the Direct Boot check and adds the notice
**And** a chosen built-in sound plays unchanged, and when the user is unlocked `apply` returns the config unchanged (tests)
**And** a session that started unlocked and is restored after a reboot before unlock gets the substitutions for the restored ring only; its fee ladder, max snoozes and snooze length stay frozen

**Given** the Epic 1 production `SnoozeAvailabilityPolicy`
**When** it is extended with the live `UserLockState` value in its environment input
**Then** it returns, in this order of precedence: `Unavailable(TestMode)` for test sessions, `Unavailable(BeforeFirstUnlock)` while the user is locked, otherwise `Unavailable(CatalogueNotLoaded)` (the real `snoozeAvailability` in Epic 4 keeps this precedence)
**And** the ringing screen renders `button-snooze-disabled` with the leading `lock` icon and "Unlock your phone to snooze", and TalkBack reads "Snooze unavailable, Unlock your phone to snooze"; a test session before unlock still shows "Test · no charge"

**Given** a session with any ring before first unlock
**When** `SessionRecorder` writes the history row
**Then** `direct_boot` is true (also when only a restored ring was before unlock), and false otherwise (test)

**Given** the before-first-unlock ringing screen
**When** Roborazzi and semantic tests run
**Then** screenshots exist in Sunrise at 100% and 200% font scale with the lock-icon snooze control, targets are ≥ 64 dp, and TalkBack order is clock, "I'm up", snooze
**And** the `pps-design` Done checklist is ticked in the story file, all strings are resources, key strings match EXPERIENCE.md verbatim and `CopyRulesTest` passes (FR-MSG-4)
**And** `./gradlew qualityGate` passes

### Story 2.4: Unlocking during a ring makes snooze available in place

As a user,
I want the snooze button to switch on as soon as I unlock my phone, without the wake screen going away,
So that a restart never takes away the choices I normally have.
**Refs:** FR-ALM-11, FR-SES-10, FR-MSG-4, AD-2, AD-7, AD-15, UX-DR14, UX-DR78, UX-DR93 · **Priority:** Must · **Verify:** auto (lock-screen behaviour is human-verify in Story 2.13)

**Acceptance Criteria:**

**Given** a session with `beforeFirstUnlock = true`
**When** `WakeService` runs
**Then** it registers a context-registered `UserUnlockedReceiver` for `ACTION_USER_UNLOCKED` (not deliverable to manifest receivers), unregisters it when the session ends, and also treats `BOOT_COMPLETED` and `WakeActivity.onResume` with `UserManager.isUserUnlocked() == true` as unlock signals
**And** each signal dispatches `UserUnlocked` to `SessionEngine`; repeated signals are ignored and logged per AD-2 and never throw

**Given** `Ringing` before first unlock
**When** `UserUnlocked` is dispatched
**Then** the AD-2 row applies: `beforeFirstUnlock` stays recorded for history, the one-shot effect "lift Direct Boot substitutions at next check step" is scheduled so the substituted check and default sound stay for the current ring and the next ring (after a snooze or merge) uses the chosen sound and check, and "init billing" calls `Billing.init()` (a no-op on the Epic 1 `UnavailableBilling`, recorded by `FakeBilling` in tests)
**And** billing and Firebase also initialise on unlock outside the table (AD-15), so an unlock during `Grace` or `Loud`, where AD-2 has no `UserUnlocked` row and the event is ignored and logged, still initialises them

**Given** the wake screen is showing
**When** the user unlocks in `Ringing`, `Grace` or `Loud`
**Then** the snooze control re-renders in place from `SessionEngine`'s combined state-and-environment availability flow, without the screen closing: in this epic it becomes "Snooze unavailable: prices not loaded yet" (the catalogue arrives in Epic 4), and with a fake policy returning `Available(price)` it becomes "Snooze · {price}" (screenshot)
**And** a Robolectric test flips `FakeUserLockState` while `WakeActivity` is resumed and asserts the activity is not finished or recreated and the label changes within one frame

**Given** Roborazzi tests
**When** they record the before/after-unlock pair
**Then** screenshots exist for "Unlock your phone to snooze" → "Snooze unavailable: prices not loaded yet" and → "Snooze · {price}" (fake) in Sunrise at 100% and 200% font scale
**And** the `pps-design` Done checklist is ticked in the story file, all strings are resources, key strings match EXPERIENCE.md verbatim and `CopyRulesTest` passes (FR-MSG-4)
**And** `./gradlew qualityGate` passes

### Story 2.5: Leave the alarm and come back through the notification

As a user,
I want to switch to another app during the alarm and get back to it with one tap,
So that I can check a message without the alarm stopping or taking over my phone.
**Refs:** FR-SES-4, FR-SES-9, FR-MSG-4, NFR-7, NFR-13, AD-5, AD-11, UX-DR24, UX-DR74 · **Priority:** Must · **Verify:** auto, plus (human-verify) heads-up behaviour in Story 2.13

**Acceptance Criteria:**

**Given** a session in `Ringing`, `Grace` or `Loud` with `WakeActivity` in the foreground
**When** the user presses Home, switches to another app or opens Recents
**Then** `WakeActivity` goes to the background, `WakeService` stays in the foreground, the sound keeps playing (Robolectric asserts the player is playing after `onPause`/`onStop`), and nothing starts an activity from the background (Story 2.11 guard)
**And** Back on `WakeActivity` still does nothing and does not finish it

**Given** the ongoing notification (Story 1.14)
**When** the user taps it, or the full-screen intent fires again
**Then** exactly one `WakeActivity` instance exists (`launchMode="singleTask"` with its own task affinity; tapping three times leaves one instance, test), and it renders the current state from `SessionEngine.state`
**And** an instrumented test on the Gradle Managed Device fires a debug alarm, presses Home, opens the notification shade and taps the notification with UiAutomator, and asserts `WakeActivity` is resumed within 1,000 ms (FR-SES-4)

**Given** Android 14+ where the user can swipe the ongoing notification away
**When** it is dismissed
**Then** the sound continues, the notification's `deleteIntent` makes `WakeService` post it again immediately, and the "wake UI shown" entry effect re-posts it on the next heartbeat if it is still missing (Robolectric test delivers the delete intent and asserts the notification is back and the player still plays)
**And** the notification never has an action that stops or lowers the sound

**Given** the user opens the app from the launcher icon during `Ringing`, `Grace` or `Loud`
**When** `MainActivity` resumes
**Then** it starts `WakeActivity` from the foreground within 1,000 ms (allowed: the user opened the app), and in `Snoozed` it does not start `WakeActivity` (the Snoozed app screen is defined in Story 2.6)
**And** the notification text stays "{time} alarm · Tap to return to your alarm" in every ringing state (EXPERIENCE.md Key strings)
**And** `./gradlew qualityGate` passes

### Story 2.6: Lock the app to "Alarm in progress" during a session

As a user,
I want the app to show only the alarm while a session is running,
So that I can't delete, disable or edit my way out of it, while every other app still works.
**Refs:** FR-SES-3, FR-SES-9, FR-MSG-4, NFR-9, AD-11, AD-16, UX-DR34, UX-DR44, UX-DR51, UX-DR61, UX-DR64, UX-DR66, UX-DR67, UX-DR84 · **Priority:** Must · **Verify:** auto

**Acceptance Criteria:**

**Given** `SessionEngine.state` is not `Idle`
**When** `MainActivity` is shown (and in `Ringing`, `Grace` or `Loud` right after it has forwarded to the wake screen per Story 2.5)
**Then** the Navigation 3 back stack is replaced by a single `SessionInProgress` route showing `panel-session-in-progress`: a `surface` card, `rounded.md`, "Alarm in progress" in `headline` and one `button-filled` "Back to alarm" (EXPERIENCE.md Key strings) that opens `WakeActivity`
**And** the lock covers all five tabs: the nav capsule (Story 1.9) is hidden while the panel shows, and no other route is reachable: a `SessionLockTest` iterates every `Route` subclass (so routes added later are covered automatically) and asserts that navigating to it while a session is active leaves the `SessionInProgress` route on screen
**And** in `Snoozed` "Back to alarm" opens `WakeActivity` on the current state (the Snoozed wake screen content arrives in Epic 4)

**Given** the Alarm editor or Sound picker is open when an alarm fires
**When** the session starts
**Then** the editor closes without a dialog and its unsaved draft is discarded, any sound preview stops, and after the session ends the app returns to Home
**And** when the session reaches `Idle`, the panel is replaced by Home within one frame

**Given** the core use cases `SaveAlarm`, `SetAlarmEnabled`, `DeleteAlarm` and `DuplicateAlarm`
**When** they run while a session is active
**Then** they return `DomainError.SessionActive` without writing, through one `SessionLockGuard` reading `SessionEngine.state` (defence in depth behind the UI lock)
**And** the `AlarmFiredHandler` still schedules the next occurrence of a repeating alarm and disables a one-time alarm during a session (it does not use these use cases; test)
**And** later mutating use cases (settings, base fee, Delete all data) are required to use the same guard, stated in the guard's KDoc and checked by a unit test that fails when a `core.alarm` or `core.config` use case writes a repository without calling the guard

**Given** the panel
**When** Roborazzi and semantic tests run
**Then** screenshots exist in Light and Dark and at 200% font scale, "Alarm in progress" is a heading for TalkBack, "Back to alarm" is ≥ 48 dp with role button
**And** the lock affects only this app: no lock-task, launcher or overlay behaviour is used (Story 2.11 guard)
**And** the `pps-design` Done checklist is ticked in the story file, all strings are resources, key strings match EXPERIENCE.md verbatim and `CopyRulesTest` passes (FR-MSG-4)
**And** `./gradlew qualityGate` passes

### Story 2.7: Pause the alarm for phone calls

As a user,
I want the alarm to go quiet while I'm on a call and come back when it ends,
So that I can answer my phone without the alarm blasting in my ear.
**Refs:** FR-SES-8, FR-SES-10, FR-ALM-9, FR-MSG-4, NFR-13, AD-2, AD-5, UX-DR35, UX-DR64, UX-DR78 · **Priority:** Must · **Verify:** auto (real calls are human-verify in Story 2.13)

**Acceptance Criteria:**

**Given** `AndroidAlarmPlayer`
**When** a ring starts
**Then** it requests audio focus with `AUDIOFOCUS_GAIN`, `USAGE_ALARM` attributes and `setWillPauseWhenDucked(false)`, and never lowers its own gain on a focus change

**Given** a `CallDetector` adapter in `:androidApp` using only `AudioManager.getMode()`
**When** audio focus is lost, the mode changes (`addOnModeChangedListener` on API 31+) or a ring begins
**Then** it reports a call when the mode is `MODE_IN_CALL`, `MODE_IN_COMMUNICATION` (VoIP) or `MODE_RINGTONE` (incoming call ringing), and dispatches `CallStarted`; when the mode returns to `MODE_NORMAL` it dispatches `CallEnded`
**And** on API 26–30, while paused, the mode is re-checked once per second and on every focus gain, so call end is detected without a listener
**And** a call already in progress when a ring begins (for example a snooze ending mid-call) pauses the ring before the first audible frame (test)
**And** `READ_PHONE_STATE`, `TelephonyManager.listen` and `registerTelephonyCallback` are never used (Story 2.11 detekt rule plus the Story 1.2 permission fixture)

**Given** another app takes audio focus with the mode `MODE_NORMAL` (video, music, navigation prompt)
**When** the focus loss arrives
**Then** the alarm keeps playing at the set volume on the alarm stream, is not paused or ducked, and no session event is dispatched (Robolectric with `ShadowAudioManager`)

**Given** `Ringing`, `Grace` or `Loud`
**When** `CallStarted` is dispatched
**Then** the sound and vibration pause, the grace countdown and the 30-minute timer are frozen (Epic 1 reducer), and every wake screen shows a Sunrise `note-inline` "Paused for your call. Rings again when it ends." (EXPERIENCE.md Key strings), announced politely by TalkBack
**And** on `CallEnded` the sound resumes at the set volume (no ramp) and vibration resumes if on, the grace window continues with its remaining seconds, and the timeout is extended by the call length (test: a 10-minute call during a ring moves the timeout from 30:00 to 40:00)
**And** in `Snoozed` calls change nothing and the snooze keeps counting
**And** there is no cap on the pause length (PRD Q14, revisit after the closed test)

**Given** a session persisted while paused and restored after a kill or reboot
**When** `ProcessRestored` runs and the call has ended in the meantime
**Then** `CallDetector` checks the mode immediately and dispatches `CallEnded`, so the restored ring is not stuck paused (test)
**And** Roborazzi screenshots cover the ringing screen with the call note in Sunrise at 100% and 200% font scale
**And** the `pps-design` Done checklist is ticked in the story file, all strings are resources, key strings match EXPERIENCE.md verbatim and `CopyRulesTest` passes (FR-MSG-4)
**And** `./gradlew qualityGate` passes

### Story 2.8: Alarm volume and volume keys on the wake screen

As a user,
I want the alarm to stay at the volume I set, with the volume keys doing nothing only while the alarm screen is in front,
So that I can't quietly turn it down from the wake screen, and the rest of my phone behaves normally.
**Refs:** FR-SES-6, NFR-13, AD-5, UX-DR69, UX-DR75 · **Priority:** Must · **Verify:** auto (real keys are human-verify in Story 2.13)

**Acceptance Criteria:**

**Given** a session
**When** a ring starts (first ring, re-ring after a snooze or merge, restored ring) or a grace window ends (`Grace` → `Loud`)
**Then** `AndroidAlarmPlayer` sets the alarm stream to the session's `volumePercent` and the player gain to full (after the ramp, for a first ring with `gradualVolume`)
**And** the volume is never re-applied continuously: a test changes the alarm stream volume with `ShadowAudioManager` during `Loud` and asserts it is not reset until the next ring start or grace end
**And** the user's previous alarm-stream volume is still restored when the session ends (Story 1.14)

**Given** `WakeActivity` is resumed
**When** `KEYCODE_VOLUME_UP`, `KEYCODE_VOLUME_DOWN` or `KEYCODE_VOLUME_MUTE` is pressed
**Then** the key is consumed and does nothing (no stream change, no `UserInteracted`)
**And** when both volume keys are held together (the accessibility shortcut), neither key is consumed from the moment the second key goes down until both are released (UX-DR69; test with synthesized `KeyEvent`s)
**And** after `onPause` the activity consumes nothing, and the app never registers a `MediaSession`, `VolumeProvider` or any global key capture (test inspects the merged manifest and detekt bans the APIs in `com.yawnandpawn.app.android.wake`)

**Given** `Grace`
**When** the entry effects run
**Then** vibration continues during the grace window only if `vibrateInGrace` is true in the frozen config and stops otherwise, and it resumes in `Loud` when `vibration` is on (Robolectric with `ShadowVibrator` for both values; the per-alarm switch arrives in Epic 3)

**Given** wired or Bluetooth headphones are connected
**When** the alarm plays
**Then** the app leaves routing to the system for `USAGE_ALARM` and never calls `setPreferredDevice`, `setSpeakerphoneOn` or `setCommunicationDevice` (NFR-13 "never reroute audio"; enforced by the Story 2.11 detekt rule)
**And** `./gradlew qualityGate` passes

### Story 2.9: Merge an alarm that rings during a session

As a user with more than one alarm,
I want a second alarm that goes off during my morning to join the current one instead of starting over,
So that I never pay twice or get a new free grace window because two alarms overlapped.
**Refs:** FR-SES-7, FR-SES-10, FR-PRG-1, AD-2, AD-4, AD-6, AD-18 · **Priority:** Must · **Verify:** auto

**Acceptance Criteria:**

**Given** `:data`
**When** the merge log is added
**Then** `app.db` migrates from version 2 to 3 adding `session_merge` (`session_id`, `alarm_id`, `scheduled_at`, `merged_at`; primary key `session_id` + `alarm_id` + `scheduled_at`), with the exported v3 schema and a Room migration test from v2 with alarms and history preserved
**And** the "record merged occurrence" effect writes it only through `SessionRecorder` (the Story 1.13 single-writer scan is extended to this table), and replaying the effect after a crash leaves one row (test)

**Given** a session in `Ringing`, `Grace` or `Loud`
**When** another enabled alarm's occurrence fires and `WakeService` dispatches `OverlapAlarmFired`
**Then** the state, sound, volume, frozen config, fee ladder, check progress, grace countdown and 30-minute timeout are unchanged (the merge is not a user interaction), one `session_merge` row is written, and the `AlarmFiredHandler` schedules that alarm's next occurrence (repeating) or disables it (one-time)
**And** the merged alarm's own settings (sound, snooze length, grace, checks) are ignored for this session (test with different values)

**Given** a session in `Snoozed`
**When** `OverlapAlarmFired` is dispatched
**Then** the snooze ends early and the session enters `Ringing(ringIndex + 1, noGraceThisRing = true)` now: no fee (`snoozesGranted` unchanged, `FakeBilling.launch` never called), the heartbeat slot replaces the snooze-end slot, a fresh 30-minute deadline starts, and "I'm up" goes straight to `Loud` without a grace window
**And** one `session_merge` row is written

**Given** two alarms scheduled for the same minute
**When** both receivers deliver within milliseconds
**Then** `SessionEngine`'s mutex yields exactly one session and one merge row, in either delivery order (test with both orders)
**And** an overlap delivered into a killed process is merged after restore (Story 2.1), and a disabled or deleted alarm that fires during a session writes no merge row and is logged (Story 1.14 rule)
**And** a real alarm that fires during a test session is merged like any other and recorded in `session_merge`, and the session's outcome stays Test (documented in the test name)
**And** the Day detail note "{time} alarm merged into this session" is shown in Epic 6 from this table
**And** `./gradlew qualityGate` passes

### Story 2.10: Session conflict rules end to end

As the owner,
I want every PRD §6.4 conflict rule exercised through the real wake runtime with fakes,
So that the pieces from this epic and Epic 1 are proven to agree before payments arrive.
**Refs:** FR-SES-10, FR-SES-1, FR-SES-7, FR-SES-8, FR-ALM-11, FR-RNG-5 (core behaviour), AD-2, AD-3, AD-7 · **Priority:** Must · **Verify:** auto

**Acceptance Criteria:**

**Given** a Robolectric `SessionConflictScenariosTest` that drives `WakeService`, the real `SessionEngine`, Room `runtime.db` and `app.db` on temporary device-protected paths, `FakeBilling`, a fake `SnoozeAvailabilityPolicy` returning `Available(price)`, `FakeUserLockState`, `ShadowAudioManager` and the fake clocks
**When** each PRD §6.4 row runs as one named scenario
**Then** these pass:
1. Payment in progress when the grace window ends: `PayConfirmed` during `Grace` with `FakeBilling` holding the result, grace elapses → `Loud` at full set volume while `paying` is set; then `PurchaseCancelled` → `Loud` continues ringing.
2. Purchase granted during a check: `Loud` at step 2 → `PurchaseGranted` → `Snoozed`, sound off, `CheckRun` progress discarded with new seeds; at snooze end → `Ringing(2)`; "I'm up" → a new `Grace`.
3. Overlapping alarm during `Loud` (merged, nothing changes) and during `Snoozed` (re-rings now, no fee, no grace).
4. Call during `Grace`: countdown frozen for the call and resumes with the same remaining seconds.
5. Reboot or process death: a snooze end already past rings immediately; a restored ring gets a fresh 30-minute timer; `paying` is cleared and billing is not relaunched.
6. Before first unlock: default sound, "Unlock your phone to snooze"; unlock → snooze availability changes in place and the substituted plan stays for the ring.

**Given** combined conflicts
**When** they run
**Then** these also pass: a call during payment (grace and timeout frozen, `paying` kept until the billing result); an overlap during a call (merged, still paused); a reboot while paused with the call over (resumes ringing); a clock change of +2 h during a snooze (re-rings on monotonic time)
**And** every scenario ends by asserting the `session_history` row (outcome, `snooze_count`, `direct_boot`) and `session_merge` rows, and that `runtime.db` is empty after `Recorded`
**And** `./gradlew qualityGate` passes

### Story 2.11: No-hostage guard: the phone stays usable

As a user (and as Google Play reviewers),
I want proof that the alarm never blocks Home, Recents, calls, the emergency dialer or other apps,
So that the app stays within Play policy and my phone is always mine.
**Refs:** FR-SES-9, NFR-13, AD-5, AD-14, UX-DR69, UX-DR74 · **Priority:** Must · **Verify:** auto

**Acceptance Criteria:**

**Given** the Story 1.2 `checkPermissionAllowlist` task
**When** it is extended
**Then** fixture tests prove that each of `SYSTEM_ALERT_WINDOW`, `READ_PHONE_STATE`, `REORDER_TASKS`, `DISABLE_KEYGUARD`, `PACKAGE_USAGE_STATS`, `KILL_BACKGROUND_PROCESSES` and any `MANAGE_DEVICE_POLICY_*` permission fails the check
**And** the merged-manifest check also fails on an intent filter with `CATEGORY_HOME` (the app posing as a launcher), on any `lockTaskMode` other than the default, on `stopWithTask="true"` for `WakeService`, and on any component protected by `BIND_ACCESSIBILITY_SERVICE` or `BIND_DEVICE_ADMIN` (Story 1.2 rules kept), each with a failing fixture

**Given** a `NoHostageApis` detekt rule in `:detekt-rules`
**When** app code uses `startLockTask`, `setLockTaskPackages`, `TYPE_APPLICATION_OVERLAY`, `TYPE_SYSTEM_ALERT`, `WindowManager.addView`, `ActivityManager.moveTaskToFront`, `DevicePolicyManager`, `AccessibilityService`, `KeyguardManager.newKeyguardLock`, `TelephonyManager.listen`, `registerTelephonyCallback`, `setPreferredDevice`, `setSpeakerphoneOn`, `setCommunicationDevice`, or calls `startActivity`/`startActivities` from a `Service`, a `BroadcastReceiver` or any class under `com.yawnandpawn.app.android.receiver` or `com.yawnandpawn.app.android.wake` other than `WakeActivity`
**Then** detekt fails with a message citing NFR-13, and each banned call has a violating and a compliant snippet test
**And** overriding `onKeyDown`/`dispatchKeyEvent` for `KEYCODE_HOME`, `KEYCODE_APP_SWITCH` or `KEYCODE_POWER` is also flagged

**Given** a Robolectric test of a ringing session
**When** `WakeActivity` is paused and stopped and 5 minutes of fake time pass with heartbeats
**Then** `ShadowApplication.getNextStartedActivity()` is null (no activity start from the service or receivers), and the player is still playing

**Given** an instrumented test on the Gradle Managed Device with a debug-fired ringing session
**When** the test presses Home, then launches the system Settings app and the dialer (`Intent.ACTION_DIAL`, no call placed)
**Then** the launcher, Settings and the dialer each come to the foreground and stay there for 10 s without `WakeActivity` resuming, and a debug-only `WakeStatus` query (debug source set, absent from release like `DebugFireReceiver`) reports the player playing throughout
**And** `./gradlew qualityGate` passes

### Story 2.12: Back up alarms and history, never the session or media

As a user,
I want my alarms, settings and history to come back on a new phone, without a half-finished alarm coming with them,
So that switching phones is painless and never restores a stale session.
**Refs:** NFR-4, NFR-14, AD-4, AD-6 · **Priority:** Must · **Verify:** auto, plus (human-verify) a real restore in Story 2.13

**Acceptance Criteria:**

**Given** `dataExtractionRules` (API 31+, both `cloud-backup` and `device-transfer` sections) and `fullBackupContent` (API ≤ 30)
**When** the rules are finalised
**Then** they include, in the device-protected domain only, `app.db` with its `-wal` file and the device-protected DataStore files created so far (settings and the missed-note dismissals from Story 1.16)
**And** they exclude `runtime.db` with its `-wal` and `-shm` files, and the whole credential-protected storage (`root`, `file`, `database`, `sharedpref` domains), which is where media (House Hunt photos, recordings, custom sounds) will live (AD-6)
**And** the Robolectric XML test from Stories 1.7 and 1.12 is updated to assert every include and exclude entry in both files

**Given** a `BackupRulesCoverageTest`
**When** it runs a full simulated morning (alarm saved, session rung, check done, history recorded, missed note dismissed) with real Room and DataStore on a temporary device-protected directory
**Then** every file created under the app's storage is either included or explicitly excluded by the rules, and the test fails naming any new file that is neither, so later stories must add their files to the rules in the same change

**Given** the manifest has `android:allowBackup="true"`, `android:fullBackupOnly="true"` and `android:backupAgent` set to `PpsBackupAgent` (Auto Backup file handling unchanged)
**When** a restore finishes
**Then** `PpsBackupAgent.onRestoreFinished()` runs `rescheduleAll()` so restored enabled alarms are armed without the user opening the app, and `runtime.db` is absent, so the app starts `Idle` (Robolectric test with `FakeAlarmScheduler`)
**And** restored `session_history` and `session_merge` rows are kept unchanged
**And** `./gradlew qualityGate` passes

### Story 2.13: Epic 2 escape-attempts checklist on the device matrix

As the owner,
I want to try every way out of the alarm on real phones,
So that I know the only free exits are the ones we accept, and the phone stays usable.
**Refs:** FR-ALM-11, FR-SES-1–10, NFR-1, NFR-2, NFR-7, NFR-8, NFR-13, NFR-14; PRD §6.4, Spike S2 · **Priority:** Must · **Verify:** human-verify

**Acceptance Criteria:**

**Given** the latest `main` debug build on each device of the matrix (the owner's Oppo A96 plus the NFR-1 emulators; other makers optional via Firebase Test Lab) and `docs/spikes/S2.md` for reference
**When** the owner runs the checklist
**Then** for each item the story file records pass/fail, device, Android version and date:
1. OEM Task Manager "Stop" during a ring (Samsung Device care, Xiaomi Security, others where present): the alarm rings again within 60 s on the same step; if the OEM action is a force-stop (the alarm does not return), record it as the accepted force-stop escape.
2. Swipe the app from Recents while ringing, in the grace window and on the check: the sound never stops, or it returns within 60 s on the same step.
3. `adb shell am kill com.yawnandpawn.app` and `adb shell kill` on the process during a ring: it re-rings within 60 s with the same snooze count and step, and no "restored" message.
4. Reboot before unlock: set an alarm 5 minutes ahead with a system ringtone, reboot and don't unlock. It rings on the lock screen with the default built-in sound and a lock-icon "Unlock your phone to snooze".
5. From item 4, unlock with PIN or fingerprint on the lock screen: the wake screen stays on top and the snooze label changes in place to "Snooze unavailable: prices not loaded yet".
6. Restart the phone while an alarm rings: after boot, before unlock, it rings again; record the seconds from lock screen shown to sound.
7. Power off during a ring for 10 minutes, power on: it rings again.
8. During a ring change the time by +1 h and by −1 h, and change the time zone: it keeps ringing; on one device, time the no-interaction stop with a stopwatch: 30 real minutes.
9. Bluetooth headphones connected, then wired headphones: the alarm is audible and never silent; record where the sound plays (speaker, headphones or both).
10. Incoming call from a second phone during a ring: sound pauses and "Paused for your call. Rings again when it ends." shows; reject it and the alarm resumes; repeat, answer, talk 1 minute, hang up and the alarm resumes. Repeat with a WhatsApp or other VoIP call.
11. Start a video in YouTube (or another video app) during a ring, and have one playing when the alarm fires: the alarm keeps playing at full volume, not paused or ducked.
12. On the wake screen the volume keys do nothing; after pressing Home they change volume normally; with the accessibility shortcut enabled, holding both volume keys for 3 s triggers it.
13. Leave the wake screen (Home, another app, Recents): the sound continues; tapping the notification returns to the wake screen within 1 s (screen recording); on Android 14+ swiping the notification away keeps the sound and the notification comes back; the launcher icon also opens the wake screen.
14. With the phone unlocked and in use when the alarm fires, the heads-up notification appears and tapping it opens the wake screen.
15. Opening the app during a session shows only the wake screen or "Alarm in progress" and "Back to alarm"; no alarm can be edited, disabled or deleted.
16. During a ring: open other apps, place a normal call from the dialer, and open (but do not use) the emergency dialer from the lock screen; all work.
17. Two alarms one minute apart: the second merges silently into one session.
18. `adb shell bmgr backupnow com.yawnandpawn.app`, uninstall, reinstall with restore: alarms and history are back, alarms ring without opening the app, and no session is restored.
19. After each session ends, `adb shell dumpsys activity services com.yawnandpawn.app` shows no running service (NFR-8).
20. Force-stop from App info during a ring stops the alarm (accepted escape) and the next scheduled alarm still rings after the app is next opened.

**Given** any failed item
**When** the owner records it
**Then** each failure becomes a new bug story referencing this item, contradictions with the Architecture Spine go through `bmad-correct-course`, and this story stays open until all items pass or are explicitly waived by the owner in the story file
**And** snoozed-state escape items (kill, reboot and swipe during a paid snooze) are repeated in the Epic 4 checklist, since paid snoozes arrive there
**And** automation never marks this story done

## Epic 3: Prove you're awake

Dismissing the alarm requires the check or checks the user chose for that alarm (Math, Word Unscramble, Memory Sequence, QR/Barcode), each with its own difficulty and count, in Random or All mode, with a 15–30 s muted grace window and a visible countdown. When a camera check can't physically be done, an accessible fallback check (Math first, TalkBack-friendly) keeps waking up free for everyone. Every check can be tried before saving. The AD-9 check plugin contract replaces the Epic 1 `Placeholder` step; the Epic 1 `SessionEngine`, `CheckRun`, `CheckValidator` and `FallbackPolicy` seams, the Epic 2 `DirectBootSubstitution` and the session lock are reused unchanged. A basic Success screen closes the flow (the celebration arrives in Epic 6). The epic ends with a human-verify device checklist.

Every UI story in this epic carries the two standing acceptance criteria from Epic 1, repeated in the story so the build loop can check them: (1) the `pps-design` Done checklist is copied into the story file with every item ticked; (2) all new user-facing strings live in Compose Multiplatform resources, match `EXPERIENCE.md > Voice and Tone > Key strings` verbatim where a key string exists, and pass the automated copy-rules test from Story 1.3 (FR-MSG-4). A string that EXPERIENCE.md does not define is marked `(EXPERIENCE.md Key strings)` in the story and listed for the owner. Check parameters that PRD Q10 leaves open are marked (owner-approved default 2026-09-26) and recorded with their chosen values in `docs/decisions/q10-check-parameters.md` in the story that introduces them.

### Story 3.1: Check plugin contract and the Math generator in core

As a user,
I want every check to follow the same fair rules for difficulty, progress and correctness,
So that no check type can be easier, flakier or judged differently than the others.
**Refs:** FR-PWK-1, FR-PWK-2 (plan resolution), FR-PWK-3, FR-PWK-5, NFR-11, NFR-12, AD-1, AD-2, AD-9 · **Priority:** Must · **Verify:** auto

**Acceptance Criteria:**

**Given** `core.checks`
**When** the contract is added
**Then** `CheckType` is a sealed `@Serializable` hierarchy, each type declaring `id`, `usesCamera`, `directBootSafe`, `hasDifficulty`, `countRange`, `defaultCount`, `generate(seed, difficulty, count): Puzzle` and `validate(puzzle, position, answer): StepResult`, where `StepResult` is `ItemCorrect` (more items remain), `Correct` (puzzle done), `Wrong` or `WrongRestart(newSeed)`
**And** `Puzzle` and `CheckAnswer` are sealed `@Serializable` types, and `generate` is deterministic per seed (a test generates 10,000 seeds twice and compares)
**And** only `Math` is added in this story; each later check story adds its own type, and House Hunt is added in Epic 7

**Given** a per-alarm `CheckPlan(mode: Random | All, entries: List<CheckEntry(type, difficulty, count)>)`
**When** a ring starts (first ring, re-ring after a snooze or merge)
**Then** a pure `PlanResolver` produces the ring's resolved plan: `All` keeps every entry in order, `Random` picks one entry using a seed, so a re-ring can pick a different type
**And** seeds come only from a pure `SeedDeriver.seed(sessionId, ringIndex, entryIndex, attempt)` (no random calls in `:core`; detekt rule bans `kotlin.random.Random` without a seed in `core.checks`), so a restored session regenerates the same puzzle
**And** `CheckRun.step` is a `StepPointer(entry, item)` into the resolved plan, and `failedAttempts` counts invalid answers on the current entry and resets when the entry advances

**Given** the Epic 1 `CheckValidator` seam
**When** the production `PluginCheckValidator` replaces the `Placeholder` validator in core (the Android wiring switches in Story 3.2)
**Then** it maps `StepResult` onto the existing AD-2 rows: `ItemCorrect` and `Correct` on a non-last entry → "valid, not last step" (advance), `Correct` on the last entry → "valid, last step" (`Completed`), `Wrong` and `WrongRestart` → "invalid" (`attempts++`, wrong-answer feedback; `WrongRestart` also stores the new seed for that entry)
**And** the AD-2 table-coverage test still passes with no new rows

**Given** the Math type (FR-PWK-5)
**When** it generates problems
**Then** Easy is `a + b` or `a − b` with a, b in 10–99 and `a ≥ b` for subtraction; Medium is `a × b + c` with a in 10–99, b in 2–9, c in 10–99; Hard is `a × b + c × d` with a, c in 10–99 and b, d in 2–9 (owner-approved default 2026-09-26)
**And** count is 1–10 with default 3, Math is `directBootSafe` and has no camera, each problem exposes a display form ("47 + 38", "23 × 4 + 17") and a spoken form ("47 plus 38", "23 times 4 plus 17") for TalkBack, and validation accepts only the exact non-negative integer (leading zeros ignored, empty rejected)
**And** table-driven tests over 10,000 seeds per difficulty assert every operand is in range, every answer is between 0 and 9,999, and the spoken form matches the display form
**And** Kover shows `core.checks` ≥ 90% line coverage, and production sessions still use the `Placeholder` step until Story 3.2 (no user-visible change)
**And** `./gradlew qualityGate` passes

### Story 3.2: Solve Math to stop the alarm

As a user,
I want to solve a few arithmetic problems on a big number pad after "I'm up",
So that I have to be awake enough to think before the alarm stops.
**Refs:** FR-PWK-1, FR-PWK-5, FR-PWK-9 (behaviour), FR-ALM-11 (Direct Boot check), FR-MSG-4, NFR-2, NFR-7, NFR-9, AD-2, AD-9, AD-11, UX-DR12, UX-DR13, UX-DR17, UX-DR28, UX-DR63, UX-DR64, UX-DR66, UX-DR67, UX-DR70, UX-DR71, UX-DR72, UX-DR78, UX-DR84 · **Priority:** Must · **Verify:** auto

**Acceptance Criteria:**

**Given** `CheckRegistry` in `:composeApp`
**When** a check type is registered
**Then** it maps the `CheckType` to one wake composable and one preview composable, and a test fails if any type that can appear in a production plan has no wake composable
**And** the `Placeholder` step is removed from production code and Koin wiring (it remains only as `FakeCheck` in `:testing`), and `ConfigResolver` gives every alarm the default plan `Random` with one entry Math · Medium · 3 (owner-approved default 2026-09-26) until per-alarm check configs arrive in Story 3.5; the test alarm uses the same plan
**And** Math becomes the Direct Boot check for Epic 2's `DirectBootSubstitution`: any entry whose type is not `directBootSafe` is replaced by the default Math entry (Math · Medium · 3)

**Given** `ImUpTapped` has moved the session to `Grace` (or `Loud` when `noGraceThisRing`)
**When** `WakeActivity` renders the Check screen (Sunrise tokens, no loading state)
**Then** the header shows progress "Problem {n} of {count}" `(EXPERIENCE.md Key strings)` and, in `Grace`, the line "Quiet for {seconds}s. Finish before it rings again." updated every second from the grace `Deadline` (the countdown ring replaces the plain number in Story 3.4); in `Loud` after a grace window it shows "Time's up. Alarm's back on until you finish." (EXPERIENCE.md Key strings)
**And** the body shows the problem's display form in `display` with tabular figures, a read-only answer `text-field` in `display` digits filled only from the pad, and a 3×4 `number-pad-key` grid (1–9, backspace, 0, "Check") of 64 dp keys with 8 dp gaps and a light haptic per tap
**And** the footer shows the same snooze control component as the ringing screen at the bottom, 64 dp, rendering `SnoozeAvailabilityPolicy`
**And** the answer accepts at most 5 digits, and "Check" with an empty field does nothing

**Given** the user taps "Check"
**When** the answer is submitted as `CheckAnswerSubmitted`
**Then** the UI never decides correctness; on an invalid result the field shakes for 200 ms (an instant change with animator duration scale 0), an error haptic plays, the field clears, and "Not quite. Try again." (EXPERIENCE.md Component Patterns) shows in `error-sunrise` and is announced politely
**And** a correct non-last answer shows the next problem with a cleared field, and the last correct answer reaches `Completed`: the sound stops, the notification is removed and `WakeActivity` finishes as in Epic 1 (the Success screen arrives in Story 3.3)
**And** every key tap also dispatches `UserInteracted`

**Given** TalkBack is on
**When** the Check screen appears
**Then** focus moves to the problem, which reads its spoken form ("47 plus 38"); each key announces its digit, backspace reads "Delete digit" `(EXPERIENCE.md Key strings)`, the current answer is announced after each key as "Answer {value}" `(EXPERIENCE.md Key strings)`, and "Check" has role button; nothing depends on timing or visual matching (UX-DR63)

**Given** a process death in the middle of the check
**When** the session is restored (Story 2.1)
**Then** the same problem appears at the same position (deterministic seed), and the digits typed but not submitted are cleared (UI-only state)

**Given** layout rules
**When** semantic and Roborazzi tests run at 100% and 200% font scale
**Then** the "Check" key and the snooze control stay on screen without scrolling at 200% on a 360 × 640 dp configuration (only the problem area may scroll), every key is ≥ 64 dp, and screenshots exist for Math in `Grace`, in `Loud` after grace, a wrong answer and the last problem
**And** an instrumented test on the Gradle Managed Device fires a debug alarm, taps "I'm up", reads the answer through a debug-only hook on `SessionEngine.state` (debug source set only), solves every problem and asserts the alarm stops
**And** the `pps-design` Done checklist is ticked in the story file, all strings are resources, key strings match EXPERIENCE.md verbatim and `CopyRulesTest` passes (FR-MSG-4)
**And** `./gradlew qualityGate` passes

### Story 3.3: Success screen after the check (basic)

As a user,
I want a short, calm confirmation when I finish my check,
So that I know the alarm is done and nothing was charged.
**Refs:** FR-PWK-9 (finishing ends the session), FR-MSG-3 (basic; the celebration is Epic 6), FR-MSG-4, NFR-9, AD-2, AD-11, UX-DR2, UX-DR64, UX-DR66, UX-DR70, UX-DR72, UX-DR78 · **Priority:** Must · **Verify:** auto

**Acceptance Criteria:**

**Given** a session reaches `Completed`
**When** `WakeActivity` observes it
**Then** instead of finishing it shows the Success screen (Sunrise tokens), held as UI-only state keyed by `sessionId`, while the engine continues `Recorded` → `Idle`, clears `runtime.db`, removes the notification and stops `WakeService` in the background (test)
**And** with 0 snoozes in a normal session the headline is "Up on time." `(EXPERIENCE.md Key strings)` (when streaks exist in Epic 6 the on-time layout becomes the streak number, then "days in a row", then "Up on time.", with no repeated number, as UX-DR84)
**And** after one or more snoozes the headline is "You're up. That's what counts." (EXPERIENCE.md Key strings; reachable only with `FakeBilling` until Epic 4, which also adds the "{paid} paid this morning" line)
**And** in a test session the headline is "Test finished. Your alarm works." `(EXPERIENCE.md Key strings)`
**And** one full-width 72 dp `button-wake-primary`-style "Done" (EXPERIENCE.md Component Patterns) sits in the thumb zone, the success haptic pattern plays once, and there is no animation yet (Epic 6 adds the count-up)

**Given** the Success screen
**When** the user taps "Done", or 60 s pass without a tap (owner-approved default 2026-09-26)
**Then** `WakeActivity` finishes; Back does nothing; pressing Home leaves it, and the next app open shows Home, not Success
**And** a new alarm that fires while Success is visible starts a new session and `WakeActivity` (single instance) switches to the ringing screen

**Given** TalkBack and layout rules
**When** semantic and Roborazzi tests run
**Then** initial focus is the headline, then "Done"; screenshots exist for the three variants in Sunrise at 100% and 200% font scale
**And** the `pps-design` Done checklist is ticked in the story file, all strings are resources, key strings match EXPERIENCE.md verbatim and `CopyRulesTest` passes (FR-MSG-4)
**And** `./gradlew qualityGate` passes

### Story 3.4: Quiet time (grace window) with the countdown ring

As a user sharing a bedroom,
I want the alarm to go silent for a few seconds after "I'm up", with a clear countdown,
So that I can do my check without waking anyone, and know exactly when it comes back.
**Refs:** FR-PWK-9, FR-SES-6, FR-ALM-2, FR-MSG-4, NFR-9, AD-2, AD-3, AD-6, UX-DR16, UX-DR40, UX-DR41, UX-DR65, UX-DR66, UX-DR70, UX-DR71, UX-DR72, UX-DR78, UX-DR87, UX-DR88 · **Priority:** Must · **Verify:** auto

**Acceptance Criteria:**

**Given** `:core` and `:data`
**When** the per-alarm grace vibration setting is added
**Then** `Alarm` gains `vibrateInGrace` (default true (owner-approved default 2026-09-26)), `app.db` migrates from version 3 to 4 adding `alarm.vibrate_in_grace` with the exported v4 schema and a migration test with existing rows preserved, and `ConfigResolver` freezes it into `SessionConfig.vibrateInGrace`
**And** commitment-lock handling for a longer grace window is added in Epic 4; here changes apply immediately

**Given** the Alarm editor
**When** it renders
**Then** its "Quiet time" row (the UI name for the grace window) opens the Quiet time sub-screen, which shows a `slider` of 15–30 s in 1 s steps (default 20, the existing `graceSeconds`) with the value announced on change as "{seconds} seconds" `(EXPERIENCE.md Key strings)`, and a `switch` "Vibrate during quiet time"
**And** both are saved with "Save" and covered by the existing "Discard changes?" check

**Given** a session in `Grace`
**When** the Check screen header renders
**Then** the `countdown-ring` (120 dp, 8 dp `accent-sunrise` stroke over `outline-subtle-sunrise` track, seconds centred in `display` with tabular figures) counts down linearly and exactly to the second from the grace `Deadline` read through the engine (never a separate UI timer), with the label "Quiet for {seconds}s. Finish before it rings again." (EXPERIENCE.md Key strings)
**And** it keeps counting behind any sheet, pauses showing the remaining seconds while `paused` for a call (Story 2.7), and after a restore continues from the remaining time or shows the expired state if the deadline passed
**And** a short haptic tick plays every 5 s, and TalkBack politely announces "{seconds} seconds left" `(EXPERIENCE.md Key strings)` every 10 s and at 5 s while muted (UX-DR65)
**And** vibration continues during the window only when `vibrateInGrace` is on (Story 2.8 behaviour, now driven by the alarm's setting)

**Given** the grace deadline passes
**When** `GraceElapsed` moves the session to `Loud`
**Then** the ring is replaced by a solid 48 dp bell icon in `text-sunrise` with "Alarm's back on" (EXPERIENCE.md Component Patterns) and the line "Time's up. Alarm's back on until you finish." (EXPERIENCE.md Key strings), a strong haptic plays, the alarm returns at the full set volume (Story 2.8), and check progress is kept at the same item
**And** there is one grace window per ring: a new window only after a re-ring from a snooze, and none on a ring with `noGraceThisRing` (merged during a snooze), where the header shows neither the ring nor the expired line
**And** with animator duration scale 0 the ring is replaced by the plain seconds number and every change is instant

**Given** the new states
**When** Roborazzi and semantic tests run
**Then** screenshots exist for grace at 20 s and 5 s, paused, expired and no-grace ring in Sunrise at 100% and 200% font scale, and for the Quiet time sub-screen in Light and Dark at 200%
**And** the `pps-design` Done checklist is ticked in the story file, all strings are resources, key strings match EXPERIENCE.md verbatim and `CopyRulesTest` passes (FR-MSG-4)
**And** `./gradlew qualityGate` passes

### Story 3.5: Choose the checks for each alarm

As a user,
I want to pick one or more checks for each alarm, their difficulty and count, and whether I get one at random or all of them in order,
So that each alarm is exactly as hard as I need it to be.
**Refs:** FR-PWK-1, FR-PWK-2, FR-PWK-3, FR-ALM-2, FR-MSG-4, NFR-9, AD-6, AD-9, AD-11, AD-16, UX-DR29, UX-DR31, UX-DR37, UX-DR38, UX-DR39, UX-DR60, UX-DR64, UX-DR66, UX-DR67, UX-DR80, UX-DR84, UX-DR85 · **Priority:** Must · **Verify:** auto

**Acceptance Criteria:**

**Given** `:core` and `:data`
**When** per-alarm check configuration is added
**Then** `app.db` migrates from version 4 to 5 adding `check_config` (`id`, `alarm_id` foreign key with cascade delete, `position`, `type`, `difficulty`, `count`, `created_at`, `updated_at`) and `alarm.check_mode` (Random or All, default Random), with the exported v5 schema and a migration test that gives every existing alarm one Math · Medium · 3 row
**And** a `CheckConfigRepository` port with `FakeCheckConfigRepository` exists; `SaveAlarm` writes the alarm and its configs in one transaction, `DuplicateAlarm` copies them, `DeleteAlarm` removes them, and all three still respect the Story 2.6 session guard
**And** validation returns `DomainError.InvalidAlarm(field)` for zero checks, a count outside the type's range, or the same type twice on one alarm
**And** `ConfigResolver` builds the `CheckPlan` from the alarm's configs (replacing the Story 3.2 default plan), ordered by `position`

**Given** the Alarm editor
**When** it renders
**Then** its "Wake-up check" row opens the Wake-up check sub-screen (the preview round 3 Check picker): the "Checks" card, "Mode" Random / All with two or more checks, and "Your checks" with each selected check's setup as its value (for example "Medium · 2 problems") and a chevron to Check setup (EXPERIENCE.md Information Architecture)
**And** in All mode each "Your checks" row offers "Move up" and "Move down" `(EXPERIENCE.md Key strings)` in its menu and as TalkBack custom actions to set the order
**And** removing the last check blocks "Save" with the inline error "Pick at least one check." (EXPERIENCE.md Key strings)
**And** `card-alarm` on Home now shows the alarm's check icons (20 dp, `text-secondary`)

**Given** the Check picker (the Wake-up check sub-screen, also used in onboarding)
**When** it opens
**Then** it lists one `check-type-card` row per type registered in `CheckRegistry` with both a core plugin and a wake composable (only Math at this point; later stories add theirs), each with icon, name from the glossary, one line of description `(EXPERIENCE.md Key strings)` and "Try it" (wired in Story 3.6); each row has a check box, and a tap toggles selection
**And** camera check cards show "Needs the camera. If it can't be used, you'll get a fallback check." (EXPERIENCE.md Key strings)

**Given** Check setup for one check
**When** it opens
**Then** it shows "Difficulty" Easy / Medium / Hard as radio rows (difficulty lives in Check setup; hidden for types without difficulty) and count as a `stepper` within the type's range, labelled per type (Math: "Problems" `(EXPERIENCE.md Key strings)`)

**Given** the new screens and states
**When** Roborazzi, semantic and ViewModel tests run
**Then** screenshots exist for the Wake-up check sub-screen (one check, several in All mode, none with the error), the picker and Check setup in Light and Dark and at 200% font scale, all targets are ≥ 48 dp, and ViewModel tests cover add, remove, reorder, mode change, validation and discard
**And** the `pps-design` Done checklist is ticked in the story file, all strings are resources, key strings match EXPERIENCE.md verbatim and `CopyRulesTest` passes (FR-MSG-4)
**And** `./gradlew qualityGate` passes

### Story 3.6: "Try it" previews for every check

As a user,
I want to try a check before I save it,
So that I know tomorrow's check is doable half-asleep.
**Refs:** FR-PWK-12, FR-MSG-4, NFR-9, AD-9, AD-11, UX-DR27, UX-DR29, UX-DR60, UX-DR64 · **Priority:** Must · **Verify:** auto

**Acceptance Criteria:**

**Given** a `check-type-card` in the Check picker or the Check setup screen
**When** the user taps "Try it" (EXPERIENCE.md Key strings)
**Then** a pushed full-screen preview opens with Sunrise tokens, rendering the type's preview composable from `CheckRegistry` at the difficulty currently set, with count 1, seeded in the ViewModel (not in `:core`)
**And** correctness still comes only from the core `validate`, and wrong answers show the same feedback as the real check

**Given** a preview is running
**When** anything happens in it
**Then** no `SessionEngine` event is dispatched, no sound plays, there is no grace window and no snooze footer, no history row is written and no scheduler call is made (test with `FakeActiveSessionStore`, `FakeSessionHistoryRepository` and `FakeAlarmScheduler` untouched)
**And** completing it shows "Nice. That's how it works." `(EXPERIENCE.md Key strings)` with a `button-filled` "Done", and Back or "Done" returns to the screen it came from with the unsaved editor state intact

**Given** the preview registry
**When** a test lists every type shown in the Check picker
**Then** each has a preview composable (Math now; each later check story registers its own)
**And** Roborazzi screenshots cover the Math preview (running and completed) at 100% and 200% font scale
**And** the `pps-design` Done checklist is ticked in the story file, all strings are resources, key strings match EXPERIENCE.md verbatim and `CopyRulesTest` passes (FR-MSG-4)
**And** `./gradlew qualityGate` passes

### Story 3.7: Word Unscramble check

As a user,
I want to unscramble words to stop the alarm,
So that I have a check that wakes my brain without numbers.
**Refs:** FR-PWK-1, FR-PWK-3, FR-PWK-8, FR-PWK-12, FR-MSG-4, NFR-9, NFR-10, AD-9, AD-15, UX-DR19, UX-DR64, UX-DR66, UX-DR67, UX-DR70, UX-DR71 · **Priority:** Must · **Verify:** auto

**Acceptance Criteria:**

**Given** an English word list bundled as an APK asset `words_en.txt` (readable before first unlock)
**When** `./gradlew checkWordList` runs (a `qualityGate` dependency)
**Then** it fails unless every entry is lowercase a–z, unique, 4–10 letters, absent from the committed `config/word-blocklist.txt` (offensive and sensitive words, reviewed by the owner), and each length bucket (4–5, 6–7, 8–10) has at least 300 words; fixture tests prove each failure
**And** the list's source and licence (public domain or a permissive licence) are recorded in `docs/checks/WORDS.md`, and resolving PRD Q10's word-list item is recorded in `docs/decisions/q10-check-parameters.md`

**Given** the core `WordUnscramble` type, built with the loaded list (no platform I/O in `:core`)
**When** it generates a puzzle
**Then** it picks `count` distinct words from the difficulty bucket by seed: Easy 4–5 letters, Medium 6–7, Hard 8–10 (owner-approved default 2026-09-26); count is 1–5 with default 2 (owner-approved default 2026-09-26)
**And** each scramble is a deterministic permutation that differs from the word and is not itself a word in the list
**And** validation accepts the target word or any listed word with exactly the same letters, case-insensitive; the type is `directBootSafe` and has no camera

**Given** the Word Unscramble check screen
**When** it renders
**Then** scrambled letters are `letter-tile`s (48 dp, wrapping onto a second row when needed) above empty answer slots with dashed borders; tapping a letter moves it to the next empty slot, tapping a filled slot returns its letter, and "Shuffle" and "Clear" (EXPERIENCE.md Component Patterns) reorder the remaining letters (display only, the puzzle is unchanged) or return all letters
**And** when every slot is filled the answer is submitted automatically; a wrong word shakes, plays the error haptic, clears the slots and shows "Not quite. Try again."; progress shows "Word {n} of {count}" `(EXPERIENCE.md Key strings)`
**And** each tile tap gives a light haptic and dispatches `UserInteracted`

**Given** TalkBack is on
**When** the user explores the screen
**Then** each letter tile reads "Letter {letter}" and each slot reads "Slot {n}, empty" or "Slot {n}, {letter}" `(EXPERIENCE.md Key strings)`, with the current answer announced after each move

**Given** the picker and previews
**When** this story lands
**Then** Word Unscramble appears in the Check picker with count labelled "Words" `(EXPERIENCE.md Key strings)`, and has a "Try it" preview
**And** Roborazzi screenshots cover Easy and Hard (wrapped) layouts, a wrong answer and the preview in Sunrise at 100% and 200% font scale, with "Shuffle", "Clear" and the snooze control on screen without scrolling
**And** the `pps-design` Done checklist is ticked in the story file, all strings are resources, key strings match EXPERIENCE.md verbatim and `CopyRulesTest` passes (FR-MSG-4)
**And** `./gradlew qualityGate` passes

### Story 3.8: Memory Sequence check

As a user,
I want to repeat a sequence of lit tiles to stop the alarm,
So that I have a quick check that needs focus rather than typing.
**Refs:** FR-PWK-1, FR-PWK-3, FR-PWK-4, FR-PWK-12, FR-MSG-4, NFR-9, AD-9, UX-DR18, UX-DR29, UX-DR63, UX-DR64, UX-DR67, UX-DR70, UX-DR71, UX-DR72 · **Priority:** Must · **Verify:** auto

**Acceptance Criteria:**

**Given** the core `MemorySequence` type
**When** it generates a puzzle
**Then** the grid is 3×3 on Easy and Medium and 4×4 on Hard, the sequence length is 4, 6 or 8 by difficulty (FR-PWK-4), count is the number of rounds, 1–5 with default 2 (owner-approved default 2026-09-26), and no tile repeats twice in a row
**And** each tile tap is submitted as its own `CheckAnswerSubmitted`; a correct tap returns `ItemCorrect` (or `Correct` at the end of the last round), and a wrong tap returns `WrongRestart(newSeed)`, so the round restarts with a new sequence (FR-PWK-4)
**And** an accessible variant, chosen when the plan is resolved and TalkBack is on (from an `AccessibilityState` port with `FakeAccessibilityState`), always uses a 3×3 grid with the same length; the type is `directBootSafe` and has no camera

**Given** the Memory Sequence check screen
**When** a round starts
**Then** it shows "Watch the sequence" `(EXPERIENCE.md Key strings)` and plays the sequence with 350 ms highlights and 150 ms gaps (`accent-sunrise` fill with the tile number in `on-accent-sunrise`); input is disabled while it plays; then it shows "Your turn" `(EXPERIENCE.md Key strings)` and progress "Round {n} of {count}" `(EXPERIENCE.md Key strings)`
**And** each tap gives brief lit feedback, a light haptic and a `UserInteracted`; a wrong tap shakes, plays the error haptic, shows "Not quite. Try again." and replays a new sequence
**And** with animator duration scale 0 highlights are instant on and off state changes that keep the same timing
**And** a restore in the middle of a round replays that round's sequence from the start

**Given** the accessible variant
**When** it renders
**Then** every tile shows its number 1–9, each tile reads "Tile {number}" `(EXPERIENCE.md Key strings)`, and the sequence is announced as numbers ("3, 7, 1, 9") before input
**And** with TalkBack on, the Check picker card shows "Uses numbered tiles with TalkBack." (EXPERIENCE.md Key strings)

**Given** the picker and previews
**When** this story lands
**Then** Memory Sequence appears in the Check picker with count labelled "Rounds" `(EXPERIENCE.md Key strings)`, and has a "Try it" preview
**And** Roborazzi screenshots cover 3×3, 4×4, the accessible variant, playback and a wrong tap in Sunrise at 100% and 200% font scale, with tiles ≥ 64 dp
**And** the `pps-design` Done checklist is ticked in the story file, all strings are resources, key strings match EXPERIENCE.md verbatim and `CopyRulesTest` passes (FR-MSG-4)
**And** `./gradlew qualityGate` passes

### Story 3.9: Fallback check picker

As a user whose check can't be done (camera broken, permission gone, code lost),
I want to switch once to a check I can do without the camera,
So that waking up is always free, and the alarm never traps me.
**Refs:** FR-PWK-11, FR-PRG-1, FR-MSG-4, NFR-2, NFR-9, AD-2, AD-9, AD-18, UX-DR13, UX-DR22, UX-DR29, UX-DR63, UX-DR64, UX-DR78, UX-DR84, UX-DR85, UX-DR90 · **Priority:** Must · **Verify:** auto

**Acceptance Criteria:**

**Given** the production `FallbackPolicy` replacing the Epic 1 "not allowed" policy
**When** `FallbackRequested(type, reason)` arrives in `Grace` or `Loud`
**Then** it is allowed only when the current entry's type `usesCamera`, `fallbackUsed` is false, the chosen type has no camera, and either the reason is `CameraUnavailable` or `failedAttempts ≥ 5`
**And** the allowed effect follows the AD-2 row: the rest of the plan is replaced by one entry of the chosen type at Hard with count = 2 × that type's default count (Math 6, Word Unscramble 4, Memory Sequence 4) (owner-approved default 2026-09-26), `fallbackUsed = true`, timers unchanged: the grace window keeps counting and no new one starts
**And** the fallback plan stays for the rest of the session: a re-ring after a snooze gets new seeds for the fallback entry, never the camera check again
**And** a camera test type `FakeCameraCheck` in `:testing` drives table tests for allowed, denied (non-camera step, fewer than 5 failures, already used, camera type chosen) and the once-per-session rule

**Given** a camera check is showing
**When** the policy would allow a fallback for it
**Then** `fallback-link` "Can't do this check?" (EXPERIENCE.md Key strings) appears centred above the snooze control, `body` in `accent-text-sunrise`, 48 dp target, and it never appears after the fallback was used this session

**Given** the user taps the link
**When** the Fallback check picker opens (wake screen, Sunrise tokens, snooze control in the footer)
**Then** the title is "Pick a fallback check" (EXPERIENCE.md Key strings) and it lists only non-camera types as Sunrise `check-type-card`s with Math always first and always available, then Word Unscramble, then Memory Sequence in its numbered accessible variant
**And** tapping a card dispatches `FallbackRequested(type, reason)` and shows that check; a close icon (content description "Back to check" `(EXPERIENCE.md Key strings)`) returns to the current check without using the fallback; Back does nothing
**And** the alarm keeps ringing in `Loud` and stays muted until the countdown ends in `Grace`

**Given** a session that used the fallback
**When** `SessionRecorder` writes history
**Then** `fallback_used` is true and `app.db` migrates from version 5 to 6 adding the nullable `session_history.fallback_from` (the replaced check type id), with the exported v6 schema and a migration test, written only by `SessionRecorder`
**And** Roborazzi screenshots cover the link and the picker in Sunrise at 100% and 200% font scale, using `FakeCameraCheck` for the camera screen
**And** the `pps-design` Done checklist is ticked in the story file, all strings are resources, key strings match EXPERIENCE.md verbatim and `CopyRulesTest` passes (FR-MSG-4)
**And** `./gradlew qualityGate` passes

### Story 3.10: QR/Barcode: register a code and scan it to stop the alarm

As a user,
I want to register a barcode far from my bed and have to scan it to stop the alarm,
So that I have to get up and walk to it.
**Refs:** FR-PWK-1, FR-PWK-3, FR-PWK-7, FR-PWK-12, FR-ONB-2 (camera asked only when needed), FR-MSG-4, NFR-4, NFR-9, AD-5, AD-6, AD-9, AD-15, UX-DR20, UX-DR29, UX-DR60, UX-DR64 · **Priority:** Must · **Verify:** auto (real scanning is human-verify in Story 3.14)

**Acceptance Criteria:**

**Given** the build
**When** CameraX 1.6.2 (`camera-core`, `camera-camera2`, `camera-lifecycle`, `camera-view`) and bundled ML Kit barcode-scanning 17.3.0 are added
**Then** their coordinates are added to `config/dependency-allowlist.txt` in the same change, merged permissions still pass the permission allowlist (`CAMERA` is already listed), and the bundled model is used (no model download through Play services)
**And** any Google usage-logging dependency pulled in by ML Kit is listed in the story file for the Epic 8 Data safety form (AD-15)
**And** frames are analysed on device only; the analyser never writes an image to storage (test on the analyser wrapper)

**Given** a `CodeScanner` abstraction in `:composeApp` `androidMain` (camera preview lives there per the Architecture) with a `FakeCodeScanner` for tests
**When** a code is detected
**Then** it emits `ScanResult(format, rawValue)` only after the same value is seen in 3 consecutive frames

**Given** the core `QrBarcode` type
**When** it is added
**Then** `usesCamera = true`, `directBootSafe = false`, no difficulty, count fixed at 1 (owner-approved default 2026-09-26); the puzzle is the registered code, and validation accepts only the same format and the same trimmed raw value; any other code is `Wrong` (a failed attempt)
**And** `app.db` migrates from version 6 to 7 adding nullable `check_config.code_format` and `check_config.code_value`, with the exported v7 schema and a migration test; `SaveAlarm` rejects a QR/Barcode config without a registered code, and the editor shows "Scan a code to use this check." `(EXPERIENCE.md Key strings)`

**Given** the user selects QR/Barcode in the Check picker
**When** camera permission is not granted
**Then** the app asks for `CAMERA` at that moment only (never at app start, never for other checks), and if denied the card stays unselected with a `note-inline` "Camera isn't available." `(EXPERIENCE.md Key strings)` and a `button-text` "Fix" opening the app's system settings; "don't ask again" is handled the same way

**Given** QR registration (pushed screen, reached from Check setup)
**When** it opens with permission granted
**Then** the `viewfinder` starts immediately with a centred square guide and a 48 dp torch toggle (content description "Torch" `(EXPERIENCE.md Key strings)`); on a detection it pauses and offers "Use this code" and "Scan again" `(EXPERIENCE.md Key strings)`, and "Use this code" stores format and value in the setup draft (saved with the alarm)

**Given** a ring whose current entry is QR/Barcode
**When** the Check screen opens
**Then** the `viewfinder` starts on screen open with the square guide, the header shows "Scan your code" `(EXPERIENCE.md Key strings)`, and a matching scan submits a correct answer (completing the entry)
**And** a different code submits a wrong answer: error haptic and "That's a different code. Scan your registered one." `(EXPERIENCE.md Key strings)`; the same wrong code seen again within 2 s is not submitted twice
**And** when permission is missing or CameraX fails to bind, "Camera isn't available. Pick a fallback check." (EXPERIENCE.md Key strings) and the fallback link (Story 3.9) show immediately (the no-frame watchdog, mid-scan failures and Direct Boot are in Story 3.11)

**Given** the picker and previews
**When** this story lands
**Then** QR/Barcode appears in the Check picker with the camera note, and "Try it" opens a preview that scans the registered code without a session
**And** ViewModel tests with `FakeCodeScanner` cover registration, match, wrong code, duplicate suppression and permission denied; Roborazzi screenshots (camera preview replaced by a placeholder surface) cover registration, the wake QR check and the camera-unavailable state in their themes at 100% and 200% font scale
**And** the `pps-design` Done checklist is ticked in the story file, all strings are resources, key strings match EXPERIENCE.md verbatim and `CopyRulesTest` passes (FR-MSG-4)
**And** `./gradlew qualityGate` passes

### Story 3.11: QR/Barcode when the camera fails, and before first unlock

As a user,
I want the app to notice quickly when the camera can't work and offer me another way, including after an overnight restart,
So that a broken camera or a locked phone never leaves the alarm ringing with no way out.
**Refs:** FR-PWK-11, FR-ALM-11, FR-SES-1, FR-MSG-4, NFR-2, NFR-9, AD-3, AD-9, UX-DR20, UX-DR22, UX-DR35, UX-DR64, UX-DR78, UX-DR90, UX-DR93 · **Priority:** Must · **Verify:** auto (real camera failures are human-verify in Story 3.14)

**Acceptance Criteria:**

**Given** the QR check has bound the camera
**When** no frame arrives within 5 s (measured with the monotonic clock), CameraX reports an error, or the camera is disconnected (for example another app takes it)
**Then** the check shows "Camera isn't available. Pick a fallback check." and the fallback link immediately (`FallbackRequested` reason `CameraUnavailable`)
**And** if the camera later recovers the scan resumes and the link stays available
**And** tests with `FakeCodeScanner` and `FakeMonotonicClock` cover 4.9 s (no message), 5.0 s (message), an error callback mid-scan and a disconnect

**Given** the user scans wrong codes
**When** the fifth failed attempt is recorded on the entry
**Then** the fallback link appears (reason `FailedAttempts`), which is also the path for a lost code (FR-PWK-11)

**Given** `WakeActivity` is paused (screen off, Home, notification shade)
**When** it resumes
**Then** the camera is released while paused and rebinds on resume with the 5 s watchdog restarted; a restored session after a kill rebinds the camera on the same step

**Given** a ring before first unlock whose plan contains QR/Barcode
**When** Epic 2's `DirectBootSubstitution` applies
**Then** the entry is replaced by the default Math entry (Math · Medium · 3), and the ringing and check screens show a Sunrise `note-inline` "Your phone restarted, so today's check is Math." (EXPERIENCE.md Key strings)
**And** the note and Math stay for that ring even after unlock, and the next ring after unlock uses QR/Barcode again (test with `FakeUserLockState`)

**Given** TalkBack is on
**When** the QR check is shown
**Then** the viewfinder reads "Camera viewfinder. Point at your code." `(EXPERIENCE.md Key strings)`, the torch reads its state, results are announced politely, and the fallback link is focusable as soon as it appears
**And** Roborazzi screenshots cover the watchdog message, the link after 5 failures and the Direct Boot note on ringing and check screens in Sunrise at 100% and 200% font scale
**And** the `pps-design` Done checklist is ticked in the story file, all strings are resources, key strings match EXPERIENCE.md verbatim and `CopyRulesTest` passes (FR-MSG-4)
**And** `./gradlew qualityGate` passes

### Story 3.12: Checks with TalkBack, end to end

As a user who relies on TalkBack,
I want to stop my alarm without needing to see the screen,
So that waking up is free for me too.
**Refs:** NFR-9, FR-PWK-11, FR-MSG-4, UX-DR8, UX-DR63, UX-DR64, UX-DR65, UX-DR66, UX-DR67, UX-DR69, UX-DR90 · **Priority:** Must · **Verify:** auto, plus (human-verify) the flow on device in Story 3.14

**Acceptance Criteria:**

**Given** an instrumented test on the Gradle Managed Device with accessibility checks enabled for Compose
**When** it runs flow F5: a debug alarm with a QR/Barcode check, camera permission revoked (`pm revoke`) (Story 3.12 default: the camera reports no permission, as on a fresh install that never granted it; `pm revoke` would kill the test process)
**Then** initial focus is the clock, then "I'm up"; after "I'm up" the camera message and the fallback link are focusable immediately; the Fallback check picker lists Math first; the Math problem reads its spoken form; the pad announces keys and the answer; "Check" completes the session and the alarm stops
**And** no step needs sight, a timed gesture or anything other than a tap (every actionable node has a label and a role)

**Given** every check screen, the Fallback check picker and the Success screen
**When** semantic tests run
**Then** every control has a label, role and state; the problem or instruction is a heading; wrong-answer feedback and the grace countdown are polite live regions; focus order follows reading order and returns to the first input after a wrong answer
**And** the Memory Sequence accessible variant is chosen automatically when TalkBack is on (`FakeAccessibilityState`)

**Given** a 360 × 640 dp configuration at 200% font scale
**When** Roborazzi records every check screen, the picker and Success
**Then** the primary input, the fallback link when shown and the snooze control are all on screen without scrolling, and the clock stays capped at 1.3×

**Given** the DESIGN.md contrast table
**When** the Story 1.3 contrast test runs
**Then** it also checks the list of colour pairs used by the check screens (including `error-sunrise` feedback, lit memory tiles and the countdown ring), and fails on any pair missing from the table
**And** PRD Q12 is closed in `docs/decisions/q12-accessible-fallback.md`, describing the TalkBack path (Math first with spoken input, numbered Memory Sequence)
**And** the `pps-design` Done checklist is ticked in the story file, all strings are resources, key strings match EXPERIENCE.md verbatim and `CopyRulesTest` passes (FR-MSG-4)
**And** `./gradlew qualityGate` passes

### Story 3.13: Suggest re-registering after 3 fallbacks in 7 days

As a user,
I want to hear when my code keeps failing,
So that I fix the check instead of relying on the fallback every morning.
**Refs:** FR-PWK-11, FR-MSG-4, AD-18, UX-DR33, UX-DR80, UX-DR84 · **Priority:** Must · **Verify:** auto

**Acceptance Criteria:**

**Given** a pure `reRegisterSuggestion(history, alarms, checkConfigs, now)` in `core.stats`
**When** it runs
**Then** it returns the alarm and check type when at least 3 sessions of that alarm in the last 7 × 24 hours (by `first_ring_at`) have `fallback_used` with `fallback_from` equal to a camera type the alarm still has configured, counting only sessions after that check's last registration (`updated_at`), and excluding Test sessions
**And** table-driven tests cover 2 fallbacks (none), 3 within 7 days (suggested), one of 3 older than 7 days (none), Test sessions (excluded), re-registered after the fallbacks (none), and two alarms each with 2 (none)

**Given** a suggestion
**When** Home is shown and no session is active
**Then** the info variant of `banner-warning` (info icon in `text-secondary`, no error colour) shows "Fallback check used 3 times this week. Re-register your {checkName}?" with a `button-text` "Re-register" (EXPERIENCE.md Key strings), where `{checkName}` is the glossary name ("QR/Barcode")
**And** "Re-register" opens QR registration for that alarm, and saving a new code clears the banner
**And** the banner is dismissible (close icon, content description "Dismiss" `(EXPERIENCE.md Key strings)`); dismissal is stored in the device-protected DataStore per alarm and type, and the banner returns only after 3 new fallbacks; the reliability `banner-warning` (Story 1.19), when present, stays above it
**And** the new DataStore key is covered by the Story 2.12 backup coverage test

**Given** the banner states
**When** Roborazzi and semantic tests run
**Then** screenshots exist with and without the reliability banner in Light and Dark and at 200% font scale, and targets are ≥ 48 dp
**And** the `pps-design` Done checklist is ticked in the story file, all strings are resources, key strings match EXPERIENCE.md verbatim and `CopyRulesTest` passes (FR-MSG-4)
**And** `./gradlew qualityGate` passes

### Story 3.14: Epic 3 device verification checklist

As the owner,
I want to confirm on real phones everything about checks that tests can't prove,
So that Epic 4 builds payments on checks I know work at 6 a.m.
**Refs:** FR-PWK-1–5, FR-PWK-7–9, FR-PWK-11, FR-PWK-12, FR-ALM-11, FR-MSG-4, NFR-2, NFR-9 · **Priority:** Must · **Verify:** human-verify

**Acceptance Criteria:**

**Given** the latest `main` debug build on each device of the matrix (the owner's Oppo A96 plus the NFR-1 emulators; other makers optional via Firebase Test Lab)
**When** the owner runs the checklist
**Then** for each item the story file records pass/fail, device, Android version and date:
1. Math at Easy, Medium and Hard with counts 1 and 5: problems match the difficulty, the pad is easy to hit half-asleep, and wrong answers shake with a haptic.
2. Grace window: "I'm up" mutes the alarm, the ring counts 20 s exactly (stopwatch), at 0 a strong haptic and full volume return with progress kept; with "Vibrate during quiet time" on the phone vibrates during the window, with it off it is still.
3. A 30 s grace window and a 15 s one both work, and after the window expires no second window starts in the same ring (the new window after a paid snooze is checked in Epic 4).
4. Word Unscramble at each difficulty: word lengths match; "Shuffle" and "Clear" work; across 30 samples no offensive or obscure words appear (list any to add to the blocklist).
5. Memory Sequence: sequences of 4, 6 and 8, a 4×4 grid on Hard, highlight timing readable, a wrong tap restarts the round.
6. All mode with Memory Sequence then Word Unscramble runs in order; Random mode varies across 5 test alarms.
7. QR/Barcode: the camera permission prompt appears only when QR/Barcode is selected; register a product barcode and a printed QR; both scan at wake time, including in a dim room with the torch.
8. A different barcode shows the wrong-code message; after 5 wrong codes the fallback link appears.
9. Revoke the camera permission in system settings: the message and link appear immediately. Open a video call app that holds the camera, then fire an alarm: the message appears within 5 s. Close the call app: the viewfinder comes back and the link stays. With the camera privacy toggle on (Android 12+, on API 31–32 and on the newest version): the message and link appear at once if the phone refuses the camera, or, if it shows black frames, the link appears after 60 s with the viewfinder kept (Story 3.11). During the QR check, turn the screen off and on with the torch lit: the camera and the torch come back.
10. The Fallback check picker lists Math first; the chosen check is Hard with double count; the alarm keeps ringing (or stays muted until the countdown ends); the link does not come back in the same session.
11. After 3 fallbacks in 7 days on real (non-test) debug alarms, Home shows the re-register banner; "Re-register" opens registration and a new code clears it.
12. Reboot before unlock with a QR/Barcode alarm: Math and "Your phone restarted, so today's check is Math." appear; after unlock Math stays for that ring; the next alarm uses QR/Barcode.
13. "Try it" on every check: no sound, nothing logged, the editor keeps unsaved changes.
14. With TalkBack on, flow F5 end to end without looking at the screen, on at least two devices. Also (Story 3.12): after a wrong Math answer or Word, TalkBack moves to "Not quite. Try again." and reads it whole, and the next swipe is "1" or the first letter; after a wrong Memory tap focus stays put and "Not quite. Try again.", the phase and the whole replayed sequence ("3, 7, 1, 9") are heard before "Your turn"; the torch reads "on" or "off"; "Answer {value}" is heard after each key.
15. At 200% font size on the smallest device, every check keeps its main input and the snooze control on screen (Story 3.12 pins the Memory grid, the Word letters and the QR viewfinder above the footer and opens the scrolling area at its end, next to them; after 5 different codes the QR viewfinder scrolls with the rest).
16. Kill the process mid-check (`adb shell am kill com.yawnandpawn.app`): within 60 s the same problem or step returns.
17. The Success screen shows the right variant (on time, test); "Done" closes it; untouched it closes after 60 s.
18. All copy seen matches EXPERIENCE.md, and every `(EXPERIENCE.md Key strings)` string from this epic has been accepted or reworded by the owner in EXPERIENCE.md (FR-MSG-4).
19. Reboot before unlock with a Word Unscramble alarm: when it rings before the first unlock, "I'm up" shows a Word puzzle (the word list is read from the APK, which Direct Boot allows; Story 3.7 review, as Robolectric cannot prove it).

**Given** any failed item
**When** the owner records it
**Then** each failure becomes a new bug story referencing this item, and this story stays open until all items pass or are explicitly waived by the owner in the story file
**And** automation never marks this story done

## Epic 4: Pay to snooze

Snoozing costs real money through Google Play. The Nth snooze in a session costs B × N (USD tiers shown as Play's local price), with a $50 per-snooze cap, a max-snoozes limit and a commitment lock that delays weakening changes made within 8 h of an alarm. The confirm sheet is honest and slow to mis-tap, every payment outcome ends in plain copy ("No charge." whenever nothing was charged), a purchase is granted only when it is `PURCHASED` and linked to the current session, stranded payments are never consumed (Google refunds them) or are reused with consent, and every charge appears in purchase history. Under the hood: `tools/play-catalog` creates the 50 products first; `FeeLadder`, `snoozeAvailability`, `PurchaseReconciler` and the grant ledger are pure core with table-driven tests; the Play Billing 9.1 adapter then replaces the Epic 1 `FakeBilling`/`UnavailableBilling` binding; the wake UI follows the EXPERIENCE.md payment outcomes table.

This epic builds on Epic 1 (`SessionState`, the AD-2 events, `SessionEngine`, `SnoozeAvailabilityPolicy`, `FeeLadder` interface, `FakeBilling`, `FakePurchaseIntentStore`, `SessionConfig`/`ConfigResolver`/`GlobalSettings`, the Play Console record and license testers from Story 1.4, and the unlock decision in `docs/spikes/S1.md` from Story 1.5), Epic 2 (session slot, `UserUnlocked`, `beforeFirstUnlock`, session lock UI) and Epic 3 (real checks via the AD-9 plugin contract, `CheckRun`, the check footer with `button-snooze`, grace window, fallback check, the basic Success screen). It does not redefine them.

Every UI story carries the two standing acceptance criteria from Epic 1: (1) the `pps-design` Done checklist is copied into the story file with every item ticked; (2) all new user-facing strings live in Compose Multiplatform resources, match `EXPERIENCE.md > Voice and Tone > Key strings` verbatim where a key string exists, and pass `CopyRulesTest` (FR-MSG-4). A string that EXPERIENCE.md does not define is marked `(EXPERIENCE.md Key strings)` in the story and listed for the owner.

### Story 4.1: Create the 50 snooze products with tools/play-catalog

As the owner,
I want one reviewed script that creates and updates the 50 consumable snooze products in Play Console,
So that prices are never typed by hand and the catalogue always matches the fee ladder.
**Refs:** PRD §6.3 (products), NFR-5, AD-7, AD-14 · **Priority:** Must · **Verify:** auto, plus (human-verify) first live run

**Acceptance Criteria:**

**Given** `tools/play-catalog`, a JVM tool wired the same way as `tools/tokens` (Story 1.3)
**When** `./gradlew playCatalog -Pmode=dry-run` runs
**Then** it builds the desired catalogue in memory: exactly 50 one-time products `snooze_usd_01` … `snooze_usd_50`, product `NN` priced at NN.00 USD as the base price, regional prices produced by the Play Developer API `convertRegionPrices` for every region Play offers, status active, one default buy purchase option usable by Play Billing Library 9 (legacy-compatible), and pending purchases allowed as PBL 8+ requires for one-time products
**And** it lists the app's existing one-time products through the Play Developer API (`monetization.onetimeproducts`, package name from `docs/decisions/package-id.md`), prints a plan of create / update / unchanged per product id, and makes no write call (asserted against a fake API client)
**And** listing title "Snooze" and description "One snooze for your alarm." are set per product (EXPERIENCE.md Key strings)

**Given** `./gradlew playCatalog -Pmode=apply`
**When** it runs against the fake API client with a partially existing catalogue (10 products, 2 with a wrong price, 1 inactive)
**Then** it creates the 40 missing products, patches the 2 wrong prices and reactivates the inactive one, and a second run immediately after reports "0 changes" and issues no write call (idempotent)
**And** products outside the `snooze_usd_NN` pattern (for example `spike_s1_test` from Story 1.5) are reported as "unmanaged" and never changed or deleted
**And** the tool never deletes a product, and exits non-zero with the API error message on any failed call without retrying writes blindly

**Given** credentials
**When** the tool starts
**Then** it reads the service-account JSON only from the `PLAY_SERVICE_ACCOUNT_JSON` environment variable or a path given with `-Pcredentials=`, never from the repo, and fails with a clear message when neither is present (unit-tested)
**And** unit tests cover: id list and USD prices for all 50, plan diff (create, price change, reactivate, unchanged, unmanaged), dry-run makes no writes, apply is idempotent, missing credentials

**Given** the owner's Play Console app record (Story 1.4)
**When** the owner runs `apply` once for real (human-verify)
**Then** Monetize > Products > One-time products shows 50 active products `snooze_usd_01` … `snooze_usd_50` with local prices, the dry-run output and apply output are pasted into `docs/decisions/play-catalog-run.md` with date, and a second dry run shows "0 changes"
**And** any catalogue change after this goes only through this tool in a reviewed commit (documented in `tools/play-catalog/README.md`)
**And** `./gradlew qualityGate` passes

### Story 4.2: Money, MoneyFormatter and the FeeLadder in core

As a user,
I want each snooze to cost exactly base fee × snooze number, never more than $50, always shown in my currency,
So that the price is predictable and rises fairly.
**Refs:** FR-RNG-6, FR-RNG-7, NFR-10, NFR-11, AD-7, AD-8; PRD §6.2 · **Priority:** Must · **Verify:** auto

**Acceptance Criteria:**

**Given** `core.billing`
**When** `Money` is added
**Then** `Money(micros: Long, currency: String)` (ISO 4217 code, validated as 3 upper-case letters) is the only money type, `plus` throws nothing and returns `DomainError.CurrencyMismatch` for different currencies, and `totalsByCurrency(List<Money>)` returns one `Money` per currency in first-seen order
**And** a detekt rule in `:detekt-rules` fails on `Double` or `Float` properties or parameters whose name contains `price`, `amount`, `fee` or `paid` in `:core` and `:data` (rule unit-tested with a violating and a compliant snippet)

**Given** the `MoneyFormatter` port (format a `Money` for display) with `FakeMoneyFormatter` in `:testing`
**When** `AndroidMoneyFormatter` formats with the device locale
**Then** it uses `NumberFormat.getCurrencyInstance(locale)` with the currency's own fraction digits, and Robolectric tests assert USD 1_000_000 micros in en-US → "$1.00", EUR 1_000_000 in de-DE → "1,00 €", JPY 150_000_000 in ja-JP → "￥150", VND 25_000_000_000 in vi-VN → "25.000 ₫", and a mixed list renders one string per currency joined with " + " (EXPERIENCE.md Key strings)
**And** UI code never formats money in any other way (the Epic 1 `CopyRulesTest` still rejects hard-coded currency symbols in resources)

**Given** the real `FeeLadder` replacing the Epic 1 placeholder behind the same interface
**When** `FeeLadder.productFor(baseFeeTier, snoozeNumber)` is called
**Then** it returns `snooze_usd_NN` with NN = baseFeeTier × snoozeNumber zero-padded to 2 digits when 1 ≤ NN ≤ 50, and `PriceCapReached` when NN > 50 (the $50 cap)
**And** it rejects baseFeeTier outside 1–10 or snoozeNumber < 1 with `DomainError.InvalidFee`
**And** a table test asserts `productFor` exists for every reachable combination (B 1–10 × N 1–5, 31 distinct products) and that every returned id is in the 50-id list used by `tools/play-catalog` (the list lives in one shared file, `config/snooze-products.txt`, read by both tests)
**And** examples are asserted: B = 1 → 01, 02, 03, 04, 05; B = 3 → 03, 06, 09, 12, 15; B = 10, N = 5 → 50; B = 10, N = 6 → `PriceCapReached`
**And** Kover shows `core.billing` ≥ 90% line coverage
**And** `./gradlew qualityGate` passes

### Story 4.3: Cache Play prices for offline display

As a user,
I want to see the snooze price in my currency even when I'm offline,
So that the fee picker and ringing screen never show a blank or wrong price.
**Refs:** FR-RNG-1, FR-RNG-7, NFR-3, NFR-8, AD-7, AD-8, AD-15, AD-17; PRD §6.2 currency rule · **Priority:** Must · **Verify:** auto

**Acceptance Criteria:**

**Given** a `PriceCatalog` port in core (`observe(): Flow<PriceCatalogSnapshot>`, `refresh(): Outcome<Unit, DomainError>`) and `FakePriceCatalog` in `:testing`
**When** a snapshot is read
**Then** each entry holds `productId`, Play's `formattedPrice` string, `Money(priceAmountMicros, priceCurrencyCode)` and `fetchedAt`, and `snapshot.priceFor(productId)` returns the entry or `null`
**And** `refresh()` delegates to a `ProductDetailsSource` port (`FakeProductDetailsSource` programmable success / partial / failure; the Play implementation arrives in Story 4.12), stores the full result atomically, keeps the previous snapshot on failure, and ignores products Play reports as unfetched

**Given** `:data`
**When** the cache store is implemented
**Then** it is a separate device-protected DataStore file `price_cache` (created with `PreferenceDataStoreFactory.createWithPath`) holding the serialized snapshot, and the backup rules exclude it (a device restored in another country must not show old currency) with the Robolectric backup-XML test updated
**And** tests cover round-trip, partial refresh keeps missing entries from the previous snapshot, and a corrupt file resets to empty without crashing

**Given** the `BackgroundWork` port (AD-17) with `FakeBackgroundWork`, added here as its first user
**When** `AndroidBackgroundWork` (WorkManager 2.11) is added
**Then** it enqueues unique work by name, WorkManager is initialised on demand only after user unlock (default initializer removed from the merged manifest, custom `Configuration.Provider`), and WorkManager coordinates are added to `config/dependency-allowlist.txt` in the same change
**And** a unique "price-refresh" job with a network constraint runs `PriceCatalog.refresh()` on app start and once a day; nothing on the wake path waits for it

**Given** a session starts (`AlarmFired`) while the device is online and unlocked
**When** the wake runtime starts
**Then** it triggers one non-blocking `PriceCatalog.refresh()` (PRD: refreshed at each session start), and the wake UI renders from the cached snapshot without waiting (test asserts first frame before refresh completes)
**And** `./gradlew qualityGate` passes

### Story 4.4: Commitment lock and pending changes in core

As a user,
I want weakening changes I make late at night to wait until after my next alarm, while making things harder applies at once,
So that my sleepy self can't quietly undo the plan my daytime self made.
**Refs:** FR-SET-1, FR-ALM-2, NFR-11, AD-6, AD-16; PRD §6.2 commitment lock, Q4, Q15, Q16 · **Priority:** Must · **Verify:** auto

**Acceptance Criteria:**

**Given** `core.config`
**When** `LockWindow` is evaluated
**Then** an enabled alarm is "in the lock window" when 0 < (its next occurrence − now) ≤ 8 h, computed with the Story 1.6 `nextOccurrence` on instants (not local times), and tests cover 7 h 59 min 59 s (inside), exactly 8 h 00 min 00 s (inside), 8 h 00 min 01 s (outside), a DST-gap night where the local-time difference and the instant difference disagree (instant wins), and a disabled alarm (never locks)
**And** for a global setting the window applies when any enabled alarm is in its window, and `effectiveAfterOccurrence` is the latest such occurrence at save time (`alarmId`, `scheduledAt`) (owner-approved default 2026-09-26); for an alarm setting only that alarm counts

**Given** the weakening rules
**When** `classify(field, oldEffective, new)` runs
**Then** lower base fee, higher max snoozes and longer grace window are Weakening; the opposite directions are Strengthening; equal is NoChange
**And** a check-plan change is Strengthening only when the new plan uses mode All, contains every old check type, and each type's difficulty and count are ≥ the old ones; any other change (fewer types, Random mode, lower difficulty or count, a type swapped) is Weakening (owner-approved default 2026-09-26)
**And** snooze length, sound, volume, label and repeat days are not locked fields (PRD lists only fee, checks, grace and snoozes)

**Given** a `PendingChange(field, value, effectiveAfterOccurrence)` store behind a `PendingChangeRepository` port (`FakePendingChangeRepository` in `:testing`)
**When** use cases `SetBaseFee`, `SetMaxSnoozes`, and the alarm-field path of `SaveAlarm` save a change
**Then** a Strengthening change or any change outside the lock window applies immediately and clears a pending change for that field; a Weakening change inside the window is stored as a `PendingChange` and the use case returns `Saved(pendingUntil = occurrence)`
**And** a second change on a field with a pending change is classified against the current effective value (for example effective $3, pending $1, new $2 → still Weakening, pending replaced by $2; new $5 → Strengthening, applied now, pending cleared) (tests)
**And** `SetMaxSnoozes` accepts 1–5 and `SetBaseFee` accepts tiers 1–10, returning `DomainError.InvalidSetting` otherwise

**Given** `ConfigResolver.resolve(alarm, globalSettings, pendingChanges, occurrence, testMode)` (extending the Epic 1 signature)
**When** a session is resolved at `AlarmFired`
**Then** a pending change is ignored for the occurrence it waits for and for any earlier one, and applied for every later occurrence, so the frozen `SessionConfig` never changes mid-session
**And** `PromotePendingChanges` writes a pending value into the live setting and deletes it once now > `effectiveAfterOccurrence.scheduledAt` and no active session exists for that occurrence; it runs on app start, on `Recorded` and in `rescheduleAll()`, so a pending change whose alarm was turned off still takes effect after that time (tests)

**Given** `:data`
**When** storage is added
**Then** alarm pending changes live in a new `app.db` table `pending_change` (`alarm_id`, `field`, `value_json`, `effective_after_alarm_id`, `effective_after_scheduled_at`), added by the `app.db` migration from version 7 to 8 with the exported v8 schema and a migration test preserving existing rows, and global pending changes live in the settings DataStore (AD-6: no settings copy in `app.db`)
**And** a new `app.db` table `commitment_event` (`id`, `alarm_id`, `occurrence_at`, `action` Disabled/Deleted, `at`), added in the same v8 migration, is written by use case `RecordCommitmentEvent` (read later by Day detail in Epic 6; Q15 stays open for how it shows)
**And** Kover shows `core.config` ≥ 90% line coverage
**And** `./gradlew qualityGate` passes

### Story 4.5: Snooze settings: base fee and max snoozes

As a user,
I want to set my base fee from $1 to $10 in my local currency and how many snoozes a morning allows,
So that snoozing costs what I decided it should.
**Refs:** FR-SET-1, FR-MSG-4, NFR-3, NFR-9, NFR-10, AD-8, AD-11, AD-16, UX-DR35, UX-DR39, UX-DR44, UX-DR51, UX-DR59, UX-DR61, UX-DR64, UX-DR66, UX-DR67, UX-DR80, UX-DR84, UX-DR91 · **Priority:** Must · **Verify:** auto

**Acceptance Criteria:**

**Given** the app root
**When** this story lands
**Then** the Settings tab (from the Story 1.9 nav capsule) shows its "Snooze" card (EXPERIENCE.md Key strings), whose "Base fee" and "Max snoozes per session" rows open Settings sub-screens with the `stepper`
**And** Settings rows are `settings-row`s (56 dp) and are unreachable while a session is active (existing Epic 2 lock, re-asserted by a test)

**Given** the Base fee sub-screen
**When** it renders with a loaded price cache
**Then** the "Base fee" `stepper` (EXPERIENCE.md Key strings) shows the Play `formattedPrice` of `snooze_usd_0B` for the current tier B in `display` with tabular figures, the − button is disabled at tier 1 and + at tier 10, long-press repeats, and a ladder preview line shows "Snooze 1: {price1} · 2: {price2} · 3: {price3}" for the first three snoozes (or fewer if max snoozes < 3) (EXPERIENCE.md Key strings)
**And** the `note-inline` "You can raise it anytime. Lowering it waits until after your next alarm." is always shown under the stepper
**And** the "Max snoozes per session" sub-screen's `stepper` (EXPERIENCE.md Key strings) runs 1–5 (default 5)

**Given** no cached price for a tier (never online)
**When** the section renders
**Then** the stepper and ladder show USD amounts formatted by `MoneyFormatter` from `Money(B × 1_000_000, "USD")` with the `note-inline` "Approximate. Your local price shows when you're online.", and the screen stays fully usable offline

**Given** an enabled alarm at 7:30 tomorrow and now 23:40
**When** the user lowers the base fee or raises max snoozes
**Then** the new value is shown as saved, the `note-inline` "Saved. Takes effect after tomorrow's {time} alarm." appears with {time} formatted per the system 12/24 h setting, and when the waited-for occurrence is later today the note reads "Saved. Takes effect after today's {time} alarm." (EXPERIENCE.md Key strings)
**And** raising the fee or lowering max snoozes applies at once with no note, and a change with no alarm inside 8 h applies at once
**And** (UX note) the F6 flow's times (23:10 → 7:30 is 8 h 20 min, outside the window) are not used in tests; tests use 23:40

**Given** the Snooze section screens
**When** Roborazzi and semantic tests run
**Then** screenshots exist for loaded prices, approximate prices, lock note after lowering, and max snoozes at 1 and 5 in Light and Dark and at 200% font scale, targets are ≥ 48 dp, and the stepper announces its value with the localized price on change
**And** ViewModel tests with `FakePriceCatalog`, `FakePendingChangeRepository` and `FakeClock` cover immediate, pending, offline and bounds
**And** the `pps-design` Done checklist is ticked in the story file, all strings are resources, key strings match EXPERIENCE.md verbatim and `CopyRulesTest` passes (FR-MSG-4)
**And** `./gradlew qualityGate` passes

### Story 4.6: Fee ladder, lock notes and turn-off confirmation in the editor and on Home

As a user,
I want to see what each snooze will cost next to the snooze length, and to be asked before I turn off or weaken an alarm late at night,
So that I know the price before the morning and don't weaken my plan by accident.
**Refs:** FR-SET-1, FR-ALM-1, FR-ALM-2, FR-MSG-4, NFR-9, AD-16, UX-DR31, UX-DR35, UX-DR55, UX-DR56, UX-DR64, UX-DR66, UX-DR67, UX-DR80, UX-DR84, UX-DR91 · **Priority:** Must · **Verify:** auto

**Acceptance Criteria:**

**Given** the editor's Snooze sub-screen (snooze length 5 / 9 / 10 / 15 min; the same ladder shows on the Settings Base fee sub-screen, Story 4.5)
**When** it renders
**Then** under the snooze length a fee ladder line shows "Snooze 1: {price1} · 2: {price2} · 3: {price3}" using the effective base fee (pending changes applied for the alarm's next-but-one occurrence are not shown; the ladder shows what the next morning will charge), the cached Play prices, and up to max snoozes entries, or USD approximations with "Approximate. Your local price shows when you're online." when the cache is empty

**Given** an alarm whose next occurrence is inside the lock window
**When** the user saves a longer grace window or a weaker check plan (Story 4.4 rules)
**Then** `SaveAlarm` stores the other fields immediately and the weakened fields as `PendingChange`s, the editor closes, Home shows a `snackbar` "Saved. Takes effect after tomorrow's {time} alarm." (or the "today's" variant), and when the editor is reopened each pending field shows the same `note-inline` under it with the pending value selected
**And** strengthening changes (shorter grace, harder checks) save with no note and apply to the next occurrence

**Given** an enabled alarm inside the lock window on Home
**When** the user toggles its `switch` off
**Then** a `dialog-confirm` "Turn off your {time} alarm? It rings in {hours} h. This is logged." opens with "Turn off" and "Keep it on" (the default dismiss), {hours} being whole hours rounded down, and under 1 h the body reads "Turn off your {time} alarm? It rings in {minutes} min. This is logged." (EXPERIENCE.md Key strings)
**And** "Turn off" disables the alarm (cancelling its schedule) and calls `RecordCommitmentEvent(Disabled)`; "Keep it on", Back or tapping outside leaves it enabled; outside the lock window the switch turns off with no dialog (Story 1.9 behaviour)

**Given** a delete inside the lock window
**When** the user confirms the existing "Delete your {time} alarm? This is logged." dialog (Story 1.9)
**Then** `RecordCommitmentEvent(Deleted)` is written in addition to the Story 1.9 log entry, and outside the window no commitment event is written

**Given** the editor and Home states
**When** Roborazzi and semantic tests run
**Then** screenshots exist for the fee ladder (loaded, approximate, max snoozes 2), pending-field note, and both turn-off dialog variants in Light and Dark and at 200% font scale, with targets ≥ 48 dp
**And** ViewModel tests with `FakeClock` cover dialog at 7 h 59 min, no dialog at 8 h 01 min, "Keep it on" default, commitment events written only inside the window
**And** the `pps-design` Done checklist is ticked in the story file, all strings are resources, key strings match EXPERIENCE.md verbatim and `CopyRulesTest` passes (FR-MSG-4)
**And** `./gradlew qualityGate` passes

### Story 4.7: snoozeAvailability with every reason

As a user,
I want the Snooze button to say exactly why it can't be used right now,
So that I'm never confused and always know "I'm up" is the way out.
**Refs:** FR-RNG-1, FR-RNG-7, FR-RNG-9, FR-ALM-8, FR-ALM-11, NFR-3, NFR-9, NFR-11, AD-2, AD-7, UX-DR13, UX-DR14, UX-DR64, UX-DR78, UX-DR84 · **Priority:** Must · **Verify:** auto

**Acceptance Criteria:**

**Given** the AD-2 session model
**When** this story extends it
**Then** `SessionState` gains one `@Serializable` field with a default, `paid: List<Money> = emptyList()` (appended by `PurchaseGranted`/`ReuseAccepted` for wake-screen display only; history totals always come from purchase records), and a test decodes a `state_json` written before this story into the new model
**And** no AD-2 row is added or changed: `paymentPending`, `declinedReuseProduct` and the v0.3 `ReuseOffered`, `ReuseDeclined` and `PurchasePending` rows already exist from Story 1.11, and the Story 1.11 table-coverage test still passes

**Given** a `Connectivity` port (`observeOnline(): Flow<Boolean>`) with `AndroidConnectivity` (`ConnectivityManager` default network callback with `NET_CAPABILITY_VALIDATED`) and `FakeConnectivity`
**When** the real production `SnoozeAvailabilityPolicy` replaces the Epic 1 one
**Then** the pure function `snoozeAvailability(state, config, env)` with `env = (online, priceSnapshot, strandedProducts, userUnlocked)` returns `Available(productId, formattedPrice, money)` or `Unavailable(reason)` checked in this order: `TestMode`, `BeforeFirstUnlock`, `MaxSnoozesReached` (snoozesGranted ≥ config.maxSnoozes), `PriceCapReached` (`FeeLadder` says so), `PaymentPending`, `EarlierPaymentRefunding(price)` (declinedReuseProduct = expected product and that product is still in `strandedProducts`), `Offline`, `CatalogueNotLoaded` (no cached price for the expected product)
**And** a table test has one row per reason plus overlaps (for example test mode and offline → TestMode; max snoozes and offline → MaxSnoozesReached; offline with no cache → Offline) and the Available case, and `PriceCapReached` is tested with a directly built config (B = 10, max 6) because production limits cannot reach it
**And** a stranded token for the expected product that the user has not declined keeps Snooze Available (the reuse offer happens on "Pay", Story 4.11)

**Given** the ringing screen and the Epic 3 check footer
**When** they render `button-snooze` from the policy
**Then** reasons map to exactly: "Test · no charge"; "Unlock your phone to snooze" (lock icon); "Snooze unavailable: max snoozes reached"; "Snooze unavailable: price cap reached"; "Snooze unavailable: payment pending"; "An earlier {price} payment is being refunded"; "Snooze unavailable: offline"; "Snooze unavailable: prices not loaded yet"; and Available renders "Snooze · {price}" with Play's `formattedPrice`
**And** TalkBack reads "Snooze unavailable, {reason}" for every disabled variant, and "I'm up" stays enabled and visible in every variant (FR-RNG-9)
**And** the wake UI combines `SessionEngine.state` with the env flows, so the button changes in place without leaving the screen when connectivity returns, the cache loads, the phone is unlocked (Epic 2 `UserUnlocked`) or a stranded token clears (test with `FakeConnectivity` toggling)
**And** Roborazzi screenshots cover every variant on Ringing and on the check footer in Sunrise at 100% and 200% font scale
**And** the `pps-design` Done checklist is ticked in the story file, all strings are resources, key strings match EXPERIENCE.md verbatim and `CopyRulesTest` passes (FR-MSG-4)
**And** `./gradlew qualityGate` passes

### Story 4.8: Purchase intents, install id and the runtime.db intent table

As a user,
I want every payment attempt saved with its session and price before Google Play opens,
So that a crash in the middle of paying can never lose or double my snooze.
**Refs:** FR-RNG-3, FR-SES-1, NFR-4, NFR-14, AD-2, AD-6, AD-7, AD-8, AD-12; PRD §6.3 linking · **Priority:** Must · **Verify:** auto

**Acceptance Criteria:**

**Given** `core.billing`
**When** `PurchaseIntent` is added
**Then** it holds `intentId` (UUID v4), `sessionId`, `productId`, `snoozeNumber` (= snoozesGranted + 1), `price: Money`, `formattedPrice` and `createdAt`, taken from the `Available` result the confirm sheet showed
**And** the `PurchaseIntentStore` port (Epic 1 `FakePurchaseIntentStore` extended) supports `get(intentId)`, `forSession(sessionId)`, `forProduct(sessionId, productId)` and `purgeOlderThan(instant)`

**Given** `SessionEngine` handling `PayConfirmed` while Available
**When** the transition is committed
**Then** the new state (`paying = intentId`) and the `purchase_intent` row are written in one `runtime.db` transaction through `ActiveSessionStore.commit(state, writes)`, and only after the commit does the "launch billing" one-shot effect run (test: failing commit → no intent row, no launch, previous state kept)
**And** a crash after commit and before launch, then `ProcessRestored`, leaves the intent row, clears `paying`, and never launches billing (extends the Story 1.12 test)
**And** `PayConfirmed` while Unavailable is ignored and logged, and a second `PayConfirmed` while `paying` is set is ignored (no second intent, no second launch)

**Given** `:data`
**When** `runtime.db` migrates from version 1 to 2
**Then** it adds `purchase_intent` (`intent_id` PK, `session_id`, `product_id`, `snooze_number`, `price_micros`, `currency`, `formatted_price`, `created_at`) with the exported schema and a migration test keeping an existing `active_session` row
**And** intents older than 7 days are purged on app start (long enough to price a pending purchase that completes after the session) (owner-approved default 2026-09-26)
**And** `runtime.db` stays excluded from backup (NFR-14: pending purchase intents never restored; Robolectric XML test still passes)

**Given** the install id
**When** it is first needed
**Then** `InstallIdProvider` returns a random UUID v4 stored in the device-protected settings DataStore, stable across restarts, never derived from any device or account identifier, and never logged (test on the `Logger` fake)
**And** `./gradlew qualityGate` passes

### Story 4.9: PurchaseReconciler for every recovery case

As a user,
I want every Google Play purchase checked against one set of rules,
So that I get exactly one snooze per charge, and a charge that gave me nothing is refunded instead of kept.
**Refs:** FR-RNG-4, FR-RNG-10, FR-PRG-4, NFR-11, AD-7; PRD §6.3 grant rule and recovery table · **Priority:** Must · **Verify:** auto

**Acceptance Criteria:**

**Given** `core.billing`
**When** the pure `PurchaseReconciler.decide(input)` is added
**Then** `input` holds a `PurchaseSnapshot` (token, productId, state Purchased/Pending, profileId nullable, orderId nullable, purchaseTime), the active session summary (sessionId, state kind, expected next productId, testMode) or none, the grant-ledger lookup for the token, the purchase-record lookup by token hash, and the context (`Update`, `Recovery`, `PreLaunch(productId)`, `AlreadyOwned(productId)`)
**And** it returns exactly one of `Grant`, `ConsumeOnly(retryLaunch: Boolean)`, `LeaveForAutoRefund`, `OfferReuse`, `Ignore(reason)`

**Given** a table-driven test named from PRD §6.3
**When** each case runs
**Then** these rows pass, one test each:
1. `PURCHASED`, profileId = active session, session in Ringing, Grace or Loud, not in ledger, product = expected next → `Grant`
2. `PURCHASED`, profileId = active session, product ≠ expected next → `LeaveForAutoRefund`
3. `PURCHASED`, profileId = an ended session → `LeaveForAutoRefund`
4. `PURCHASED`, profileId = another session id → `LeaveForAutoRefund`
5. `PURCHASED`, profileId = active session that is currently `Snoozed` (purchase while snoozed) → `LeaveForAutoRefund`
6. `PURCHASED`, profileId missing (promo code, OQ-1) → `LeaveForAutoRefund`
7. `PURCHASED`, any profileId, ledger status granted (not consumed) → `ConsumeOnly(retryLaunch = false)` (crash between grant and consume)
8. `PENDING`, any → `Ignore(Pending)`
9. duplicate delivery: token already in the ledger as granted, delivered again with profileId = active session → `ConsumeOnly`, never a second `Grant`
10. duplicate delivery after consume: token's purchase record status consumed or reused → `Ignore(AlreadyHandled)`
11. `PreLaunch(P)` or `AlreadyOwned(P)`: owned `PURCHASED` token for P, not in ledger, record absent or stranded, active session not Snoozed and not test mode, P = expected next → `OfferReuse`
12. `AlreadyOwned(P)`: token for P in ledger as granted → `ConsumeOnly(retryLaunch = true)`
13. active session in test mode, any `PURCHASED` token for it → `LeaveForAutoRefund` (never grant in test mode)
14. no active session, `PURCHASED` not in ledger → `LeaveForAutoRefund`
**And** sequence tests pass: pending then `PURCHASED` for the active session in Grace or Loud (mid-check) → `Grant`; pending then `PURCHASED` after the session ended → `LeaveForAutoRefund`; lost callback found by a `Recovery` query for the active session → `Grant`; the same token decided twice across a simulated restart → one `Grant` total
**And** the reconciler is the only place these rules live (a unit test scans `:core` and `:androidApp` for other readers of `Purchase.purchaseState` outside the adapter mapping)
**And** Kover shows `core.billing` ≥ 90% line coverage
**And** `./gradlew qualityGate` passes

### Story 4.10: Grant ledger, purchase records and consume with retry

As a user,
I want a granted snooze to be recorded and my payment consumed exactly once, even if the app dies in between,
So that I'm never charged twice and never lose a snooze I paid for.
**Refs:** FR-RNG-4, FR-RNG-6, FR-PRG-1, FR-PRG-4, FR-SES-1, NFR-2, NFR-4, NFR-14, AD-2, AD-6, AD-7, AD-8, AD-17, AD-18 · **Priority:** Must · **Verify:** auto

**Acceptance Criteria:**

**Given** `:data`
**When** storage is added
**Then** `runtime.db` migrates from version 2 to 3 adding `grant_ledger` (`token` PK, `session_id`, `product_id`, `status` granted/consumed, `created_at`), and `app.db` migrates from version 8 to 9 adding `purchase_record` (`token_hash` PK = SHA-256 hex of the token, `order_id` nullable, `product_id`, `session_id` nullable, `alarm_id` nullable, `snooze_number` nullable, `price_micros`, `currency`, `purchased_at`, `status` granted/consumed/stranded/reused, `updated_at`), both with exported schemas and migration tests
**And** raw purchase tokens are stored only in `runtime.db` (not backed up); `app.db` (backed up) holds only the token hash (owner-approved default 2026-09-26)

**Given** `SessionEngine` committing `PurchaseGranted(token, productId, money)` (reconciler said `Grant`)
**When** the transition commits
**Then** the Snoozed state and a `grant_ledger` row (status granted) are written in the same `runtime.db` transaction, and only then the effects run in this order: `PurchaseLedger.upsert(record, status granted)` → `Billing.consume(token)` → on success mark record consumed → delete the ledger row
**And** `PurchaseLedger` (core, via a `PurchaseRecordRepository` port with fake) is the only writer of `purchase_record` (a unit test scans `:core` and `:data` for other writers of the DAO), and upserts are idempotent by token hash
**And** the record's `session_id`, `alarm_id`, `snooze_number` and price come from the matching `PurchaseIntent`, or from the price snapshot when no intent exists

**Given** crash tests with fakes that throw at each step
**When** the process "dies" after the commit, after the record upsert, or after consume but before marking consumed, and restores
**Then** restore replays from the ledger: the record is upserted (no duplicate), consume is called again (Play treats a repeat consume of a consumed token as already consumed, mapped to success), the record ends consumed, the ledger row is gone, `snoozesGranted` was incremented exactly once and the session stays Snoozed
**And** `ConsumeOnly` decisions from the reconciler go through the same consume path

**Given** consume fails (offline, service error)
**When** the failure is returned
**Then** the ledger row stays granted, the record stays granted, and a unique "consume-retry" job is enqueued through `BackgroundWork` with a network constraint and exponential backoff (30 s initial), and retry also runs on app start and on resume, until success (test with `FakeBilling` failing twice then succeeding)
**And** the grant never waits for consume: the snooze starts at commit (test asserts Snoozed before consume completes)

**Given** a `LeaveForAutoRefund` decision
**When** it is handled
**Then** `PurchaseLedger` upserts a record with status stranded (never consumed, no ledger row)
**And** `PurchaseLedger.markReused(tokenHash, sessionId, alarmId, snoozeNumber)` changes a stranded record to status reused with the given session, alarm and snooze number, idempotently, and refuses any other starting status (unit-tested here; the reuse flow calls it in Story 4.11)
**And** `./gradlew qualityGate` passes

### Story 4.11: Billing orchestration: launch, recovery, ITEM_ALREADY_OWNED and stranded reuse

As a user,
I want the app to recover lost payment results and offer an earlier unused payment instead of charging me again,
So that a bad connection or a crash never costs me an extra charge.
**Refs:** FR-RNG-3, FR-RNG-4, FR-RNG-10, FR-PRG-4, NFR-11, AD-2, AD-7, AD-12; PRD §6.3 recovery, stranded reuse, `ITEM_ALREADY_OWNED` · **Priority:** Must · **Verify:** auto

**Acceptance Criteria:**

**Given** the `Billing` port (Epic 1) extended to `launch(intent, installId): Outcome<LaunchResult>`, `queryPurchases(): Outcome<List<PurchaseSnapshot>>`, `consume(token)`, `purchaseUpdates: Flow<PurchaseUpdate>` and `FakeBilling` programmable for each (including `ItemAlreadyOwned`, `Pending`, lost callback, duplicate delivery)
**When** `PurchaseCoordinator` (core) executes the "launch billing" effect for intent I with product P
**Then** it first consumes any granted-but-unconsumed ledger token for P, then queries owned purchases; an owned token for P that the reconciler marks `OfferReuse` dispatches `ReuseOffered(token)` instead of launching; otherwise it launches with `obfuscatedProfileId = sessionId` and `obfuscatedAccountId = installId`
**And** a launch result of `ItemAlreadyOwned` re-queries and applies the reconciler with context `AlreadyOwned(P)`: granted token → consume then retry the launch once (a second `ItemAlreadyOwned` becomes `PurchaseFailed(Error)`); stranded token → `ReuseOffered(token)`

**Given** purchase updates and launch results
**When** they arrive
**Then** each purchase goes through `PurchaseReconciler` and the coordinator dispatches: `Grant` → `PurchaseGranted`; `ConsumeOnly` → consume path; `LeaveForAutoRefund` → stranded record; a pending purchase for the active session → `PurchasePending`; user cancel → `PurchaseCancelled`; errors → `PurchaseFailed(kind)` with kind Offline, UnlockFailed or Error
**And** a `PurchaseGranted` that arrives while the session is in Grace or Loud (pending cleared mid-check) moves to Snoozed and discards check progress per AD-2 (engine test)

**Given** recovery triggers
**When** the app starts, `MainActivity` resumes, or `WakeActivity` opens
**Then** the coordinator runs `queryPurchases()` and reconciles every result with context `Recovery`, never before first unlock (AD-15), and the set of stranded product ids feeds `snoozeAvailability`'s env
**And** tests cover: lost callback → granted on `WakeActivity` open; stranded for an ended session → record stranded, not consumed; crash between grant and consume → consumed on start; duplicate delivery → one grant

**Given** the reuse flow
**When** `ReuseAccepted(token)` is dispatched
**Then** it commits Snoozed and a ledger row for the stranded token in one transaction exactly like `PurchaseGranted`, the record changes from stranded to reused with the current session, and the token is consumed through the Story 4.10 path
**And** `ReuseDeclined` sets `declinedReuseProduct` so Snooze shows "An earlier {price} payment is being refunded" until a later recovery no longer finds that token
**And** an engine test runs PRD UJ4 end to end with fakes: payment error → session completes → token becomes `PURCHASED` later → recovery marks it stranded → next morning `Pay` → `ReuseOffered` → `ReuseAccepted` → Snoozed, with exactly one record (status reused) and one consume call
**And** `./gradlew qualityGate` passes

### Story 4.12: Play Billing 9.1 adapter replacing the fake

As a user,
I want Snooze to use real Google Play payments, unlocking my phone first when Play needs it,
So that I can actually pay for a snooze from the ringing screen.
**Refs:** FR-RNG-3, FR-RNG-4, FR-RNG-5, NFR-5, NFR-6, AD-5, AD-7, AD-12, AD-13, AD-15; PRD §10 S1 · **Priority:** Must · **Verify:** auto, plus (human-verify) in Story 4.18

**Acceptance Criteria:**

**Given** Play Billing Library 9.1.0
**When** `AndroidBilling` is added behind a thin `BillingClientFacade` (so Robolectric tests use a fake facade)
**Then** the client is built with the purchases-updated listener, `enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())` and automatic service reconnection, it connects only after user unlock (before unlock every call returns `DomainError.BeforeFirstUnlock`), and Billing coordinates are added to `config/dependency-allowlist.txt` in the same change
**And** Koin binds `AndroidBilling` as the production `Billing` and `AndroidProductDetailsSource` as the production `ProductDetailsSource`, replacing `UnavailableBilling`; the debug build keeps a hidden developer toggle to bind `FakeBilling` for emulator tests only (absent from release, checked by the Story 1.18 release-manifest test pattern)

**Given** product details
**When** `AndroidProductDetailsSource` runs
**Then** it queries all 50 ids with `queryProductDetailsAsync` (type INAPP), maps each product's one-time purchase offer to `formattedPrice`, `priceAmountMicros` and `priceCurrencyCode`, and reports unfetched products without failing the whole refresh

**Given** a launch request from `PurchaseCoordinator`
**When** it runs
**Then** it calls `launchBillingFlow` from the resumed `WakeActivity` with the product's `ProductDetails`, `setObfuscatedAccountId(installId)` and `setObfuscatedProfileId(sessionId)`, and returns `PurchaseFailed(Error)` without launching if `WakeActivity` is not resumed
**And** response codes map to domain results in one table (unit-tested row by row): OK → purchases to the coordinator; USER_CANCELED → Cancelled; ITEM_ALREADY_OWNED → ItemAlreadyOwned; NETWORK_ERROR, SERVICE_UNAVAILABLE, SERVICE_DISCONNECTED → Offline; BILLING_UNAVAILABLE, ERROR, DEVELOPER_ERROR, ITEM_UNAVAILABLE, FEATURE_NOT_SUPPORTED, ITEM_NOT_OWNED → Error; purchase state PENDING → Pending
**And** `queryPurchasesAsync(INAPP)` and `consumeAsync` map to the port, a consume of an already consumed token (ITEM_NOT_OWNED) maps to success, and no purchase token appears in any log or Crashlytics key (test on the `Logger` and `CrashReporter` fakes)

**Given** the unlock mechanics decided in `docs/spikes/S1.md` (Story 1.5)
**When** "Pay" is confirmed while the keyguard is locked
**Then** a `DeviceUnlocker` port (`isLocked()`, `requestUnlock(): Outcome<Unit, UnlockError>`) with `FakeDeviceUnlocker` is implemented by `AndroidDeviceUnlocker` using `KeyguardManager.requestDismissKeyguard(WakeActivity, callback)`; success continues to the launch; cancel or error dispatches `UnlockFailed` (or `PurchaseFailed(UnlockFailed)` if S1 added no event), and the Play sheet is launched only after `onDismissSucceeded`
**And** if S1 found the Play sheet works over the lock screen without unlocking, the adapter skips the unlock step, and the story file records which branch was built with a link to the S1 Decision section
**And** the alarm sound is not paused, lowered or muted by any billing or unlock call (test asserts no `AlarmPlayer` effect is emitted by the adapter)
**And** `./gradlew qualityGate` passes

### Story 4.13: Snooze confirm sheet

As a user,
I want a clear confirmation before I pay, with the easy "I'll get up" under my thumb and taps ignored for a moment,
So that I never pay by accident while half asleep.
**Refs:** FR-RNG-2, FR-RNG-3, FR-RNG-5, FR-RNG-9, FR-RNG-10, FR-PWK-10, FR-MSG-4, NFR-5, NFR-9, NFR-13, AD-2, AD-11, UX-DR15, UX-DR16, UX-DR64, UX-DR66, UX-DR67, UX-DR72, UX-DR73, UX-DR74, UX-DR77, UX-DR78, UX-DR84, UX-DR89 · **Priority:** Must · **Verify:** auto, plus (human-verify) in Story 4.18

**Acceptance Criteria:**

**Given** a session in Ringing, Grace or Loud with Snooze Available
**When** the user taps `button-snooze` on the ringing screen or the check footer
**Then** `SnoozeTapped` is dispatched and `sheet-snooze-confirm` opens over the current wake screen (`surface-sunrise`, top corners `rounded.lg`, 24 dp padding) showing in order: "Snooze for {minutes} min?" (`headline`, {minutes} = frozen config snooze length), the price in `display` and `text-sunrise`, "This one costs {price}. The next one costs {nextPrice}." (`body`), "Is {minutes} more minutes worth {price}? You've got this." (`body`, `text-secondary-sunrise`), then the tax note when applicable, then two stacked full-width 64 dp buttons: outlined "Pay {price} and snooze" above and filled "I'll get up" at the bottom
**And** when the next snooze would exceed max snoozes or the cap, the body shows only "This one costs {price}." (EXPERIENCE.md Key strings)
**And** the tax note "Google Play shows the final total, including any tax." is shown when the Play billing country (from `getBillingConfigAsync`, cached) is in `config/tax-exclusive-countries.txt` (initially US and CA) (owner-approved default 2026-09-26)

**Given** the anti-double-tap guard
**When** the sheet opens, or its state or displayed price changes
**Then** all input is ignored for 500 ms measured with the monotonic clock (not animation end), including with animator duration scale 0, and neither button is pre-selected or focused (TalkBack focus starts on the title)
**And** a test taps "Pay" at 499 ms (ignored) and 500 ms (accepted), and repeats after a state change

**Given** the sheet
**When** the user taps "I'll get up", swipes down or presses Back
**Then** the sheet closes with no charge and no intent written, the screen underneath shows "I'm up" (or the current check) again, and no payment message is shown
**And** the alarm keeps ringing while the sheet is open; if the sheet was opened during a muted grace window, the mute ends when the countdown ends and the sheet stays open with the alarm at full volume behind it (AD-2 grace keeps counting)

**Given** "Pay {price} and snooze" is tapped
**When** the phone is locked and the S1 branch requires unlocking
**Then** the sheet switches to the *unlocking* state: lock icon, "Unlock to pay {price}", one outlined 64 dp "Cancel"; "Cancel" returns to the ringing (or check) screen with "Phone still locked. No charge."; a successful unlock opens the Play sheet
**And** when unlocked, `PayConfirmed` opens the Play sheet directly

**Given** the coordinator dispatches `ReuseOffered`
**When** the sheet is showing
**Then** it switches to the *already paid* state: "You already paid {price} earlier that wasn't used. Use it for this snooze?" with outlined "Use it" above and filled "Not now" at the bottom, the 500 ms guard applies again, "Use it" dispatches `ReuseAccepted` and "Not now", swipe or Back dispatches `ReuseDeclined`

**Given** the sheet states
**When** Roborazzi and semantic tests run
**Then** screenshots exist for confirm (with and without tax note, last-snooze body), unlocking and already paid, over Ringing and over a check, in Sunrise at 100% and 200% font scale, with every button ≥ 64 dp and both buttons on screen without scrolling at 200%
**And** the `pps-design` Done checklist is ticked in the story file, all strings are resources, key strings match EXPERIENCE.md verbatim and `CopyRulesTest` passes (FR-MSG-4)
**And** `./gradlew qualityGate` passes

### Story 4.14: Payment outcome messages

As a user,
I want a short plain message after every failed payment that tells me I wasn't charged,
So that I never wonder whether I paid.
**Refs:** FR-RNG-4, FR-RNG-5, FR-RNG-7, FR-RNG-9, FR-MSG-4, NFR-3, NFR-9, AD-11, UX-DR56, UX-DR64, UX-DR79, UX-DR84, UX-DR89 · **Priority:** Must · **Verify:** auto

**Acceptance Criteria:**

**Given** a `PaymentOutcome` mapping table in `:composeApp` keyed by the domain result (from Story 4.12's response-code table)
**When** the mapping test runs
**Then** each row maps to exactly one string resource, return surface and availability, matching the EXPERIENCE.md Payment outcomes table:
- UnlockFailed → "Phone still locked. No charge." → back to Ringing or Check → Snooze still offered
- Cancelled → "Payment cancelled. No charge." → Snooze still offered
- Error → "Payment didn't go through. No charge." → Snooze still offered
- Offline → "No connection. No charge." → "Snooze unavailable: offline" until connectivity returns
- Pending → "Payment not confirmed yet. If it goes through before you finish, your snooze starts. Otherwise, finish the check to stop the alarm." → "Snooze unavailable: payment pending" for this session
- Purchased, Already paid "Use it" → no message → Snoozed
- Already paid "Not now" → no message → "An earlier {price} payment is being refunded"
**And** a test asserts every `LaunchResult`/`PurchaseFailed` kind has a row (no unmapped result compiles, via an exhaustive `when`)

**Given** an outcome with a message
**When** it is shown on a wake screen
**Then** it is a `snackbar` with wake rules: no action, visible at least 10 s or until the next tap, announced politely by TalkBack, never covering "I'm up" or the check input, and the alarm is at full set volume (or muted only while a grace countdown still runs)
**And** "I'm up" (on Ringing) or the check (on Check) is usable immediately while the snackbar shows, so the free path stays one tap away (FR-RNG-9)
**And** an Offline outcome while `Connectivity` later reports online re-enables Snooze in place without a new message

**Given** outcome states
**When** Roborazzi tests run
**Then** screenshots exist for each message on Ringing and on a check in Sunrise at 100% and 200% font scale
**And** the `pps-design` Done checklist is ticked in the story file, all strings are resources, key strings match EXPERIENCE.md verbatim and `CopyRulesTest` passes (FR-MSG-4)
**And** `./gradlew qualityGate` passes

### Story 4.15: Snoozed screen, session line and snoozing from a check

As a user,
I want a calm "Snoozed" note after paying, and on the next ring to see how many snoozes I've used and what I've paid,
So that I know exactly where my morning stands without feeling judged.
**Refs:** FR-RNG-1, FR-RNG-6, FR-RNG-8, FR-PWK-10, FR-SES-10, FR-MSG-3, FR-MSG-4, NFR-9, AD-2, AD-8, UX-DR13, UX-DR64, UX-DR66, UX-DR72, UX-DR78, UX-DR84, UX-DR88, UX-DR89 · **Priority:** Must · **Verify:** auto

**Acceptance Criteria:**

**Given** a session enters Snoozed after `PurchaseGranted` or `ReuseAccepted`
**When** `WakeActivity` renders
**Then** the Snoozed surface (Sunrise) shows exactly "Snoozed. Next ring at {time}." with {time} = snooze end formatted per the system 12/24 h setting, for 3 s, then `WakeActivity` finishes and clears keep-screen-on so the system turns the screen off (the app never forces the screen off)
**And** the sound and vibration have stopped, the session slot is armed at snooze end (Epic 2), and no celebration, animation or payment wording appears

**Given** the re-ring after a snooze
**When** the ringing screen renders
**Then** under the date it shows "Snooze {n} of {max} · {paid} paid this morning" with n = snoozesGranted, max = frozen config max snoozes and {paid} = `SessionState.paid` formatted per currency by `MoneyFormatter`, and `button-snooze` shows the next price B × (n + 1) or the matching unavailable reason
**And** the first ring shows no session line
**And** a merged overlapping alarm during a snooze (Epic 2) re-rings early with the same session line and no fee

**Given** a session in Grace or Loud on a check (Epic 3)
**When** the user snoozes from the check footer and the purchase is granted
**Then** the check progress is discarded, the Snoozed surface shows, and the re-ring starts a fresh `CheckRun` (new seeds) and a new grace window (FR-PWK-10, PRD §6.4 "snooze wins")
**And** when a pending payment clears to `PURCHASED` while the user is mid-check, the same happens without any tap

**Given** the session completes
**When** the Epic 3 Success screen renders
**Then** after at least one paid snooze it shows "You're up. That's what counts." plus "{paid} paid this morning" in `text-secondary-sunrise`, with no animation
**And** when the session had a pending payment that was never granted it adds "Your pending payment wasn't used. Google refunds it automatically."

**Given** these states
**When** Roborazzi and semantic tests run
**Then** screenshots exist for Snoozed, re-ring with session line (USD and EUR, n = 1 and 4 of 5), check footer during a paid session, Success after snooze and Success with pending not used, in Sunrise at 100% and 200% font scale; TalkBack reads the session line after the clock and before "I'm up"
**And** the `pps-design` Done checklist is ticked in the story file, all strings are resources, key strings match EXPERIENCE.md verbatim and `CopyRulesTest` passes (FR-MSG-4)
**And** `./gradlew qualityGate` passes

### Story 4.16: Purchase history

As a user,
I want a list of every snooze I paid for, with the date, alarm, snooze number and price,
So that I can check any charge, and see when a payment wasn't used and was refunded.
**Refs:** FR-PRG-4, FR-RNG-10, FR-MSG-4, NFR-9, NFR-10, AD-8, AD-11, AD-18, UX-DR52, UX-DR58, UX-DR64, UX-DR66, UX-DR67, UX-DR80, UX-DR84 · **Priority:** Must · **Verify:** auto

**Acceptance Criteria:**

**Given** the You tab (Story 1.9)
**When** its "Money" card renders
**Then** it shows a `settings-row` "Purchase history" (EXPERIENCE.md Key strings) opening the Purchase history route (`top-app-bar` titled "Purchase history"); Epic 6 also links to it from the Progress "Money paid" card

**Given** purchase records
**When** Purchase history renders
**Then** each record is a `purchase-row` (64 dp) newest first: date and alarm (label, or alarm time if no label) in `body`, "Snooze {n}" in `caption`, and the price formatted by `MoneyFormatter` from micros and currency, right-aligned in `text` (never accent, green or red), tabular figures
**And** consumed and reused records read as normal paid snoozes; stranded records read "Not used, refunded automatically by Google" instead of the snooze number; granted records not yet consumed show as normal paid snoozes
**And** a record with no alarm or snooze number (stranded purchase with missing profileId) shows the date and "Not used, refunded automatically by Google" only
**And** rows are read-only; TalkBack reads each row as one item (date, alarm, snooze number or status, price)

**Given** no records
**When** the screen renders
**Then** it shows "No snoozes paid. Keep it that way."
**And** loading shows a `skeleton` only after 300 ms

**Given** records in two currencies (a trip abroad)
**When** the screen renders
**Then** each row keeps its own currency and nothing sums across currencies on this screen
**And** Roborazzi screenshots cover empty, mixed statuses and two currencies in Light and Dark and at 200% font scale, and a ViewModel test with `FakePurchaseRecordRepository` covers ordering and status mapping
**And** the `pps-design` Done checklist is ticked in the story file, all strings are resources, key strings match EXPERIENCE.md verbatim and `CopyRulesTest` passes (FR-MSG-4)
**And** `./gradlew qualityGate` passes

### Story 4.17: "Problem with a charge?"

As a user,
I want a clear way to ask for a refund or contact support about a charge,
So that I'm never stuck with a charge I don't understand.
**Refs:** FR-SET-5, FR-PRG-4, FR-MSG-4, NFR-4, NFR-5, NFR-9, AD-11, UX-DR26, UX-DR27, UX-DR51, UX-DR64, UX-DR66, UX-DR67 · **Priority:** Must · **Verify:** auto, plus (human-verify) links in Story 4.18

**Acceptance Criteria:**

**Given** Purchase history and the You tab's "Money" card
**When** they render
**Then** each has a `button-text` / `settings-row` "Problem with a charge?" opening a pushed screen of the same title

**Given** the "Problem with a charge?" screen
**When** it renders
**Then** it explains in ≤ 25-word paragraphs that Google lets you request a refund yourself within 48 hours of a charge, that payments that were not used are refunded automatically by Google, and that a pending payment only charges if it goes through (EXPERIENCE.md Key strings)
**And** a `button-filled` "Open Google Play order history" (EXPERIENCE.md Key strings) opens `https://play.google.com/store/account/orderhistory` with `ACTION_VIEW`, and if no app can handle it a snackbar says "No browser found." (EXPERIENCE.md Key strings)

**Given** at least one purchase record
**When** the user taps `button-outlined` "Email support" (EXPERIENCE.md Key strings)
**Then** a picker lists the 10 most recent records (date, price, status) with the newest selected, and confirming opens `ACTION_SENDTO` `mailto:` to the support address from `config/app-links.properties` (placeholder `vincentbui2108@gmail.com` until a dedicated support address exists; changing it only edits `config/app-links.properties`) with subject "Charge question" and a body containing the order ID, purchase date and time (ISO 8601 with offset), price and status (EXPERIENCE.md Key strings)
**And** the email body never contains the purchase token, install id, session id or alarm label (unit test on the body builder), and with no records the button opens the same email without order details
**And** "Email support" is the first use of `button-outlined`, built here as the shared app secondary-action component: at least 48 dp tall, `rounded.full`, 1 dp `outline` border, label in `text` colour, Light and Dark tokens only (wake screens keep `button-snooze`), with its own Roborazzi screenshots (enabled, pressed, 200% font scale) in Light and Dark; later stories reuse it rather than styling their own

**Given** the screen
**When** Roborazzi and semantic tests run
**Then** screenshots exist with and without records in Light and Dark and at 200% font scale, targets ≥ 48 dp, and a Robolectric test asserts both intents' action, data and extras
**And** the `pps-design` Done checklist is ticked in the story file, all strings are resources, key strings match EXPERIENCE.md verbatim and `CopyRulesTest` passes (FR-MSG-4)
**And** `./gradlew qualityGate` passes

### Story 4.18: Epic 4 payment verification with license testers

As the owner,
I want to confirm real Google Play payments from a ringing phone with license testers,
So that nobody is ever charged without a snooze or snoozes without being charged.
**Refs:** FR-RNG-1–10, FR-PWK-10, FR-PRG-4, FR-SET-1, FR-SET-5, NFR-3, NFR-5, NFR-13 · **Priority:** Must · **Verify:** human-verify

**Acceptance Criteria:**

**Given** the latest `main` debug build installed from the internal track on a Pixel and a Samsung (plus the Xiaomi and budget device for items 1, 3 and 12), signed in with a license tester account (Story 1.4), with the 50 products live (Story 4.1)
**When** the owner runs the checklist
**Then** for each item the story file records pass/fail, device, Android version and date:
1. With base fee $1, the ringing screen shows "Snooze · {local price}" matching Play Console's price for `snooze_usd_01`; with the phone offline for the whole ring (after one earlier online launch), Snooze reads "Snooze unavailable: offline" and turns into "Snooze · {local price}" in place when connectivity returns; after clearing app data and staying offline it reads "Snooze unavailable: offline".
2. The confirm sheet shows title, price, next price, nudge and (US account) the tax note; taps in the first 500 ms do nothing; "I'll get up", swipe down and Back close it with no charge.
3. Locked phone: "Pay" shows "Unlock to pay {price}"; unlocking opens the Play sheet over the ringing flow; cancelling the unlock shows "Phone still locked. No charge."
4. The alarm keeps ringing at full volume on the alarm stream under the Play sheet; snoozing during a grace window keeps the mute only until the countdown ends.
5. A successful test purchase shows "Snoozed. Next ring at {time}.", the screen turns off, and it re-rings at that time with "Snooze 1 of 5 · {price} paid this morning" and the next price at 2× B.
6. Cancelling the Play sheet shows "Payment cancelled. No charge."; airplane mode during "Pay" shows "No connection. No charge." and Snooze becomes "Snooze unavailable: offline" until connectivity returns.
7. "Slow test card, approves after a few minutes": "Payment not confirmed yet…" appears, Snooze reads "Snooze unavailable: payment pending"; if it approves during the check the snooze starts by itself; if the check is finished first, Success shows "Your pending payment wasn't used. Google refunds it automatically." and history later shows "Not used, refunded automatically by Google".
8. A stranded purchase (from item 7, not yet refunded) makes the next "Pay" at that price show the already-paid state; "Use it" snoozes with no new charge in Play order history; "Not now" shows "An earlier {price} payment is being refunded".
9. Force-stopping the app right after a purchase completes (before the Snoozed screen) and reopening: the snooze is granted once and the purchase is consumed (buying the same product again works).
10. Snooze from the check footer grants the snooze, discards check progress, and the re-ring starts a fresh check and grace window.
11. After max snoozes (set to 2), Snooze reads "Snooze unavailable: max snoozes reached"; a test alarm shows "Test · no charge" and can't open the sheet.
12. Lowering the base fee at 23:40 before a 7:30 alarm shows "Saved. Takes effect after tomorrow's 7:30 alarm." and the 7:30 session still charges the old fee; the next day's session charges the new fee; turning the alarm off within 8 h asks "Turn off your … alarm?" with "Keep it on" as default.
13. Purchase history lists every test purchase with date, alarm, snooze number and local price; stranded purchases read "Not used, refunded automatically by Google".
14. "Problem with a charge?" opens Google Play order history and a pre-filled support email with the order ID and time.
15. With a second license tester on a device set to a non-USD country (for example Germany), prices, session line and history use that currency's Play formatting.
16. TalkBack reads the sheet title first, neither button is pre-focused, and disabled Snooze reads its reason; at 200% font size both sheet buttons stay on screen.
17. All copy seen matches EXPERIENCE.md and every failed payment says "No charge." (FR-MSG-4).

**Given** any failed item
**When** the owner records it
**Then** each failure becomes a new bug story referencing this item, and this story stays open until all items pass or are explicitly waived by the owner in the story file
**And** automation never marks this story done

## Epic 5: First run, settings and trust

A new user is guided through the mission, the alarm behaviour disclosure and consent, the base fee, a first alarm, checks, the full reliability checklist (with Do Not Disturb detection and manufacturer guidance), an anonymous-stats choice and a test alarm on a locked screen. Settings is complete: wake and snooze defaults, "Bright wake screen", theme override, usage stats, reliability checklist, "How payments & refunds work", privacy policy, terms, support and "Delete all data". Firebase Analytics stays off until the user consents (AD-15). The privacy policy, terms and support pages are published on GitHub Pages from `docs/`. **The 14-day closed test starts at the end of this epic.**

This epic builds on Epic 1 (`ReliabilityProbe`, the Story 1.19 `banner-warning`, Crashlytics and the Firebase init-after-unlock rule, `GlobalSettings`/`ConfigResolver`, `PpsTheme(mode)`, the Story 1.18 test alarm, the Story 1.4 Play Console record and license testers), Epic 2 (session lock: Settings and "Delete all data" are unreachable during a session), Epic 3 (check picker, `check-type-card` with "Try it", QR registration, Success screen basic) and Epic 4 (base fee `stepper`, ladder preview, commitment lock, the Settings Snooze sub-screens, `PriceCatalog`, "Problem with a charge?", Purchase history, `BackgroundWork`). It does not redefine them.

Every UI story carries the two standing acceptance criteria from Epic 1: (1) the `pps-design` Done checklist is copied into the story file with every item ticked; (2) all new user-facing strings live in Compose Multiplatform resources, match `EXPERIENCE.md > Voice and Tone > Key strings` verbatim where a key string exists, and pass `CopyRulesTest` (FR-MSG-4). A string that EXPERIENCE.md does not define is marked `(EXPERIENCE.md Key strings)` in the story and listed for the owner.

### Story 5.1: Wake and snooze defaults and theme override in Settings

As a user,
I want to set the default grace window, vibrate-in-grace and snooze length for new alarms, and choose System, Light or Dark for the app,
So that new alarms start the way I like and the app is comfortable to use at night.
**Refs:** FR-SET-1 (remaining), FR-MSG-4, NFR-9, AD-6, AD-10, AD-11, AD-16, UX-DR2, UX-DR38, UX-DR40, UX-DR41, UX-DR51, UX-DR62, UX-DR64, UX-DR66, UX-DR67 · **Priority:** Must · **Verify:** auto

**Acceptance Criteria:**

**Given** `GlobalSettings` in the device-protected settings DataStore
**When** this story extends it
**Then** it holds `defaultGraceSeconds` (15–30, default 20), `defaultVibrateInGrace` (default on, matching Story 3.4), `defaultSnoozeLengthMinutes` (5, 9, 10 or 15, default 9) and `themeMode` (System, Light, Dark; default System), edited only through core use cases `SetDefaultGrace`, `SetDefaultVibrateInGrace`, `SetDefaultSnoozeLength`, `SetThemeMode` that reject out-of-range values with `DomainError.InvalidSetting`
**And** these defaults apply only to alarms created after the change (the Alarm editor reads them for a new alarm), never alter existing alarms, and are therefore not commitment-locked fields (owner-approved default 2026-09-26)

**Given** Settings (grouped cards; rows open sub-screens)
**When** it renders
**Then** the Snooze card gains a "Default snooze length" row that opens its sub-screen (5 / 9 / 10 / 15 min); a "Wake" card shows "Default quiet time", which opens a sub-screen with a `slider` 15–30 s in 1 s steps with the value announced, and "Vibrate during quiet time" as a `switch`; an "Appearance" card shows a `segmented-control` "System" / "Light" / "Dark" (EXPERIENCE.md Key strings)
**And** switches and segmented controls apply immediately (no Save)

**Given** the theme override
**When** the user picks Light or Dark
**Then** every app screen re-renders at once with `PpsTheme(mode = Light | Dark)`, System follows `isSystemInDarkTheme()` and updates when the system setting changes, the choice survives restart, and wake screens always stay Sunrise regardless of the choice (test)

**Given** the new Settings sections
**When** Roborazzi and semantic tests run
**Then** screenshots exist for the Wake, Snooze and Appearance cards and their sub-screens in Light and Dark and at 200% font scale, and for Home rendered under each theme mode, with targets ≥ 48 dp
**And** ViewModel tests cover bounds, immediate apply and that a new alarm opened in the editor uses the new defaults while an existing alarm keeps its values
**And** the `pps-design` Done checklist is ticked in the story file, all strings are resources, key strings match EXPERIENCE.md verbatim and `CopyRulesTest` passes (FR-MSG-4)
**And** `./gradlew qualityGate` passes

### Story 5.2: Bright wake screen

As a user,
I want the alarm screen to turn fully bright when it rings, and go back to normal when I'm done,
So that the light helps me wake up.
**Refs:** UX-DR10, UX-DR2, UX-DR62, UX-DR78, UX-DR87, FR-MSG-4, AD-5, AD-16, NFR-13 · **Priority:** Should · **Verify:** auto, plus (human-verify) in Story 5.13

**Acceptance Criteria:**

**Given** `GlobalSettings.brightWakeScreen` (default on)
**When** a session starts
**Then** `ConfigResolver` copies it into the frozen `SessionConfig` (`brightWakeScreen`), so changing the setting during a session has no effect until the next session (AD-16) (test)
**And** the Settings "Wake" section has a `switch` "Bright wake screen" (default on) with the caption "Raises screen brightness on alarm screens." (EXPERIENCE.md Key strings); the onboarding key string "Your alarm screen turns bright to help you wake. Change it in Settings." is used in Story 5.12

**Given** `config.brightWakeScreen` is true
**When** `WakeActivity` shows any wake screen (Ringing, Snooze confirm, Check, Fallback check picker, Success)
**Then** it sets its window `screenBrightness` to `BRIGHTNESS_OVERRIDE_FULL` (window-only, no `WRITE_SETTINGS` permission, permission allowlist unchanged)
**And** brightness returns to the system level when the user taps "Done" on Success, on the Snoozed surface, and whenever `WakeActivity` stops or finishes (Home, Recents, crash), because the override is window-scoped (Robolectric test on window attributes for each case)
**And** when the system Extra dim setting is active (`Settings.Secure` `reduce_bright_colors_activated` = 1, API 31+) the override is not applied (owner-approved default 2026-09-26)
**And** with the setting off, the window brightness is never touched (test)
**And** no copy anywhere makes a blue-light or health claim (reviewed in the `pps-design` checklist)
**And** the `pps-design` Done checklist is ticked in the story file, all strings are resources, key strings match EXPERIENCE.md verbatim and `CopyRulesTest` passes (FR-MSG-4)
**And** `./gradlew qualityGate` passes

### Story 5.3: Full reliability probe: Do Not Disturb, battery and the locked-screen test

As a user,
I want the app to detect every phone setting that can stop my alarm,
So that I find out tonight, not tomorrow morning.
**Refs:** FR-ONB-2, FR-ONB-3, FR-ONB-4, FR-SET-2, NFR-1, NFR-11, NFR-13, AD-4, AD-5, AD-12 · **Priority:** Must · **Verify:** auto

**Acceptance Criteria:**

**Given** the Story 1.19 `ReliabilityProbe` port
**When** it is extended
**Then** it adds `dndAllowsAlarms`, `batteryOptimizationIgnored`, `backgroundRestricted`, `standbyBucketRestricted` and `cameraGranted`, implemented in `AndroidReliabilityProbe` and `FakeReliabilityProbe`
**And** `dndAllowsAlarms` is false when `NotificationManager.getCurrentInterruptionFilter()` is `INTERRUPTION_FILTER_NONE`, or when (API 28+) the notification policy's `priorityCategories` lacks `PRIORITY_CATEGORY_ALARMS`, so a scheduled night-time DND that would block alarms is caught even while DND is off; OEM "total silence" modes the API doesn't expose are not detected (covered by manufacturer guidance, Story 5.5)
**And** `batteryOptimizationIgnored` uses `PowerManager.isIgnoringBatteryOptimizations`, `backgroundRestricted` uses `ActivityManager.isBackgroundRestricted()` (API 28+), `standbyBucketRestricted` is `UsageStatsManager.getAppStandbyBucket() == STANDBY_BUCKET_RESTRICTED` (API 30+), and no `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` or `ACCESS_NOTIFICATION_POLICY` permission is added (permission allowlist unchanged)
**And** Robolectric tests cover each value on API 26, 28, 30, 31, 33, 34 and 36

**Given** the locked-screen test result
**When** a test session reaches `Completed`
**Then** if the keyguard was locked when `TestAlarmFired` was handled (recorded in `SessionConfig.testStartedLocked` from `KeyguardManager.isKeyguardLocked`), `lockedTestPassedAt` is stored in the settings DataStore; a test completed unlocked does not set it (test)

**Given** pure `ReliabilityChecklist.evaluate(probe, alarms, device, stored)` in core
**When** it runs
**Then** it returns rows in this order with status OK, NeedsFix, Revoked or Hidden: Notifications; Full-screen alarm; Exact alarms (Hidden except API 31–32); Do Not Disturb; Battery (fails when optimization is not ignored, background is restricted or the bucket is restricted); Manufacturer settings (Hidden unless `device.makerCovered` is true; then OK when a confirmation for the current maker and `Build.FINGERPRINT` is stored, otherwise NeedsFix; production passes `makerCovered = false` until Story 5.5 adds the maker mapping); Camera (Hidden unless an enabled alarm uses QR/Barcode, or House Hunt later); Microphone (Hidden unless an alarm uses a recording, none until Epic 7); Locked-screen test
**And** a row that was OK at the last evaluation (stored set in DataStore) and now fails is Revoked, otherwise NeedsFix
**And** `alarmBlocking(rows)` is true when any of Notifications, Full-screen alarm, Exact alarms, Do Not Disturb or Battery is not OK; Manufacturer, Camera, Microphone and Locked-screen test never raise the Home banner (owner-approved default 2026-09-26)
**And** table tests cover every row state and the Revoked transition, and Kover keeps core ≥ 90%
**And** `./gradlew qualityGate` passes

### Story 5.4: Reliability checklist screen and revoked-setting warnings

As a user,
I want one screen that shows each setting my alarm needs, why, and a Fix button that takes me there,
So that I can make my alarm reliable in a minute and notice when a phone update breaks it.
**Refs:** FR-ONB-2, FR-ONB-3, FR-SET-2, FR-ALM-12, FR-MSG-4, NFR-9, AD-5, AD-11, UX-DR33, UX-DR50, UX-DR51, UX-DR64, UX-DR66, UX-DR67, UX-DR80, UX-DR84, UX-DR92 · **Priority:** Must · **Verify:** auto, plus (human-verify) in Story 5.13

**Acceptance Criteria:**

**Given** Settings
**When** the user taps "Reliability checklist" (EXPERIENCE.md Key strings)
**Then** a pushed screen shows each non-hidden row from Story 5.3 as a `checklist-row` (64 dp): leading icon, title (`body`), reason (`caption`), and trailing `check_circle` in `success` with "OK" or a `button-outlined` "Fix"
**And** row titles and reasons are (EXPERIENCE.md Key strings): "Notifications" · "Needed to show your alarm."; "Full-screen alarm" · "Shows the alarm over the lock screen."; "Exact alarms" · "Lets the alarm ring on time."; "Do Not Disturb" · "Do Not Disturb must allow alarms."; "Battery" · "Stops Android from pausing your alarm."; "Manufacturer settings" · "Your phone may stop apps in the background."; "Camera" · "Needed for your QR/Barcode check."; "Microphone" · "Needed to record messages."; "Test alarm" · "Ring a test with your phone locked."
**And** a Revoked row's reason reads "Alarm may not ring: {setting} turned back on" or "Alarm may not ring: {setting} turned off" as fits, with exactly "Alarm may not ring: battery optimization turned back on" for Battery (PRD FR-ONB-3) (EXPERIENCE.md Key strings)

**Given** a row with "Fix"
**When** it is tapped
**Then** it deep-links to: app notification settings; `ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT` (API 34+); `ACTION_REQUEST_SCHEDULE_EXACT_ALARM` (API 31–32); `Settings.ACTION_ZEN_MODE_PRIORITY_SETTINGS`; `Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS` (or app details when background is restricted); app details for Manufacturer settings (the row is Hidden in production until Story 5.5, which replaces this target with its guidance screen); the camera runtime permission request (app details if permanently denied); the Story 1.18 test alarm for "Test alarm"
**And** every intent is checked with `resolveActivity` first and falls back to `ACTION_APPLICATION_DETAILS_SETTINGS`, then `ACTION_SETTINGS` (Robolectric test per row with and without a resolver)
**And** statuses are re-evaluated on every `ON_START`/`ON_RESUME`, so a fixed row turns "OK" on return without a tap

**Given** all rows are OK
**When** the screen renders
**Then** every row shows "OK" and the `button-outlined` "Ring a test alarm" stays at the bottom, scheduling a locked-screen test through Story 1.18 with the soonest enabled alarm's settings (or defaults)

**Given** the Story 1.19 `banner-warning` on Home
**When** `alarmBlocking` is true
**Then** it shows "Alarms may not ring. Fix settings" on Home and on Settings, its "Fix" now opens the Reliability checklist (F7), it is not dismissible, and it clears itself once `alarmBlocking` is false (re-evaluated on every app foreground)
**And** granting exact alarms still triggers `rescheduleAll()` (Story 1.10)

**Given** the checklist states
**When** Roborazzi and semantic tests run
**Then** screenshots exist for all OK, several NeedsFix, a Revoked battery row, camera row visible, and API 31 with the exact-alarm row, in Light and Dark and at 200% font scale, and TalkBack reads each row as title, reason and status
**And** the `pps-design` Done checklist is ticked in the story file, all strings are resources, key strings match EXPERIENCE.md verbatim and `CopyRulesTest` passes (FR-MSG-4)
**And** `./gradlew qualityGate` passes

### Story 5.5: Manufacturer background guidance

As a user with a Xiaomi, Samsung, Huawei, Oppo/Realme, Vivo or OnePlus phone,
I want short steps for my phone's own background settings, with a button that opens them,
So that my phone's battery tools don't kill my alarm.
**Refs:** FR-ONB-2, FR-ONB-3, FR-MSG-4, NFR-1, NFR-9, AD-5, UX-DR50, UX-DR64, UX-DR66 · **Priority:** Must · **Verify:** auto, plus (human-verify) in Story 5.13

**Acceptance Criteria:**

**Given** `Build.MANUFACTURER` and `Build.BRAND` (case-insensitive)
**When** `DeviceMaker` is resolved
**Then** it maps xiaomi, redmi, poco → Xiaomi; samsung → Samsung; huawei, honor → Huawei; oppo, realme → OppoRealme; vivo, iqoo → Vivo; oneplus → OnePlus; anything else → Other (Manufacturer row Hidden) (unit-tested per value)

**Given** a covered maker
**When** the user taps "Fix" on the Manufacturer settings row
**Then** a pushed guidance screen shows 2 to 4 numbered steps for that maker (for example Xiaomi: allow Autostart, set Battery saver to "No restrictions", lock the app in Recents), each step ≤ 25 words [OPEN: Samsung, Huawei, Oppo/Realme, Vivo and OnePlus steps still to write in EXPERIENCE.md Long-form copy; Xiaomi done], sourced from `docs/oem-guidance.md`, which lists per maker the steps, the source (dontkillmyapp.com page and OEM help pages), the Android/skin versions checked and a review date
**And** Oppo / ColorOS steps are written (EXPERIENCE.md has none yet), since the owner's Oppo A96 is the test phone
**And** an "Open settings" `button-filled` (EXPERIENCE.md Key strings) tries that maker's known settings components from `OemIntents` in order, launching the first one that `resolveActivity` finds, falling back to app details; it never uses a component that is not resolvable (Robolectric test per maker with and without resolvable components)
**And** a `button-outlined` "I've done this" (EXPERIENCE.md Key strings) marks the row OK, storing the maker and `Build.FINGERPRINT`

**Given** a stored confirmation
**When** `Build.FINGERPRINT` changes (system update)
**Then** the Manufacturer row becomes Revoked with "Alarm may not ring: check your phone's background settings again" (EXPERIENCE.md Key strings) (test)
**And** Roborazzi screenshots cover each maker's guidance screen in Light and Dark and at 200% font scale
**And** the `pps-design` Done checklist is ticked in the story file, all strings are resources, key strings match EXPERIENCE.md verbatim and `CopyRulesTest` passes (FR-MSG-4)
**And** `./gradlew qualityGate` passes

### Story 5.6: Anonymous usage stats, off until the user says yes

As a user,
I want usage statistics sent only if I turn them on, and only three anonymous numbers,
So that my mornings stay private unless I choose to help.
**Refs:** FR-ONB-6, FR-SET-6, FR-MSG-4, NFR-4, NFR-15, AD-13, AD-15, UX-DR41, UX-DR51, UX-DR84 · **Priority:** Must · **Verify:** auto, plus (human-verify) DebugView check

**Acceptance Criteria:**

**Given** `core`
**When** telemetry is added
**Then** a `Telemetry` port takes a sealed `TelemetryEvent` with exactly `SessionOutcome(outcome)`, `SnoozeCount(count)` and `CheckTypeUsed(type)`, whose parameters are enums or integers only (a unit test asserts no `String` field other than enum names), and `FakeTelemetry` records events
**And** a `ConsentStore` (settings DataStore) holds `analyticsConsent` (default false) and `analyticsAsked` (default false), and `ConsentGatedTelemetry` drops every event while consent is false, never queueing it for later (test: 3 events before consent, consent on, 1 event → only 1 delivered)
**And** after `Recorded` for a non-test session, one `SessionOutcome`, one `SnoozeCount` and one `CheckTypeUsed` per check type used are logged; test sessions log nothing (owner-approved default 2026-09-26)

**Given** Firebase Analytics (BoM 34.19.0)
**When** `FirebaseTelemetry` is integrated
**Then** the manifest keeps `firebase_analytics_collection_enabled` = false (Story 1.19) and adds `google_analytics_adid_collection_enabled` = false, `google_analytics_ssaid_collection_enabled` = false, `google_analytics_default_allow_ad_storage` = false, `google_analytics_default_allow_ad_user_data` = false, `google_analytics_default_allow_ad_personalization_signals` = false and `google_analytics_automatic_screen_reporting_enabled` = false
**And** the merged `com.google.android.gms.permission.AD_ID` permission is removed with `tools:node="remove"` so the permission allowlist still passes, and the Analytics coordinates are added to `config/dependency-allowlist.txt` in the same change
**And** `setAnalyticsCollectionEnabled(true)` and analytics-storage consent granted are called only when consent is true and the user is unlocked; turning consent off calls `setAnalyticsCollectionEnabled(false)` and `resetAnalyticsData()`; no user id or user property is ever set (Robolectric test on a facade)
**And** Firebase automatic events (`first_open`, `session_start`, `app_remove`) are left on while consented because PRD CM-2 uses `app_remove` (owner-approved default 2026-09-26)

**Given** Settings
**When** the "Usage stats" section renders
**Then** it has a `switch` "Share anonymous usage stats" (EXPERIENCE.md Key strings), default off, applying immediately
**And** Roborazzi screenshots cover the section in Light and Dark and at 200% font scale

**Given** the owner's Firebase debug project
**When** a debug build with consent on completes a session
**Then** the three events appear in Firebase DebugView with no other custom events, and with consent off nothing appears (human-verify, recorded with date)
**And** the `pps-design` Done checklist is ticked in the story file, all strings are resources, key strings match EXPERIENCE.md verbatim and `CopyRulesTest` passes (FR-MSG-4)
**And** `./gradlew qualityGate` passes

### Story 5.7: Privacy policy, terms and support pages on GitHub Pages

As the owner,
I want the privacy policy, terms and support page published from the repo,
So that Play Console, the app and testers link to one honest, up-to-date source.
**Refs:** FR-SET-3, NFR-4, NFR-5, NFR-14, NFR-15, AD-15; PRD §13 launch blockers, Q17 · **Priority:** Must · **Verify:** auto, plus (human-verify) owner review and live URLs

**Acceptance Criteria:**

**Given** `docs/site/`
**When** the pages are written
**Then** it contains `index.md`, `privacy.md`, `terms.md`, `support.md` and `payments.md` in plain Markdown with a minimal Jekyll config, and only `docs/site/` is published (spike notes and decisions in `docs/` stay unpublished) by `.github/workflows/pages.yml` using the official Pages actions on pushes to `main` that touch `docs/site/`
**And** `privacy.md` covers: no account and no backend; what stays on the phone (alarms, history, purchase records, photos, recordings); Android Auto Backup of alarms, settings, history and purchase records to the user's Google account and what is excluded (photos, recordings, custom sounds, the active session, pending purchase intents); Firebase Crashlytics always on with no personal content; Firebase Analytics only if the user opts in, limited to three anonymous events, no advertising ID; Google Play Billing processes payments, the app keeps order ID, price and time locally and sends only random ids; camera images processed on the device and never uploaded; 18+ audience; how to delete data ("Delete all data" or uninstall); contact; effective date
**And** `terms.md` covers fees (B × N, cap, max snoozes, commitment lock), that Google handles payments and refunds, pending and unused payments, no guarantee that an alarm rings on every device, and a clearly marked placeholder for EU/UK right-of-withdrawal wording (Q17) [ASSUMPTION: owner or a legal reviewer finalises terms and Q17]
**And** `support.md` gives the support email and short answers for refunds, pending payments, unused payments and alarm reliability; `payments.md` mirrors the in-app "How payments & refunds work" text

**Given** `./gradlew checkSitePages` (a `qualityGate` dependency)
**When** it runs
**Then** it fails if a page is missing, if `privacy.md` lacks any required heading (Data on your phone, Backup, Crash reports, Usage stats, Payments, Camera and microphone, Deleting your data, Contact), if any page contains "TODO" outside the marked Q17 placeholder, or if the copy rules (no em dash, banned words) are broken
**And** the published URLs live in `config/app-links.properties` (`privacyUrl`, `termsUrl`, `supportUrl`, `supportEmail`) and are exposed to the app as `AppLinks` (supportEmail placeholder `vincentbui2108@gmail.com`; GitHub Pages base URL `https://vincentbui21.github.io/yawn-and-pawn/` (repo https://github.com/vincentbui21/yawn-and-pawn))

**Given** the owner
**When** the pages are live
**Then** the owner reads every page, confirms it matches the app's behaviour, and records the live URLs and date in the story file (human-verify); automation never marks this story done
**And** `./gradlew qualityGate` passes

### Story 5.8: "How payments & refunds work", privacy, terms and support in the You tab

As a user,
I want to read how fees, pending payments and refunds work, and reach the privacy policy, terms and support from the You tab,
So that I trust the app before I ever pay.
**Refs:** FR-SET-3, FR-ONB-5, FR-SET-5, FR-MSG-4, NFR-5, NFR-9, AD-11, UX-DR51, UX-DR60, UX-DR64, UX-DR66, UX-DR67, UX-DR84 · **Priority:** Must · **Verify:** auto

**Acceptance Criteria:**

**Given** the You tab
**When** it renders
**Then** its cards have the rows "How payments & refunds work", "Privacy policy", "Terms" and "Support" (EXPERIENCE.md Key strings); Privacy policy, Terms and Support open `AppLinks` URLs with `ACTION_VIEW` (snackbar "No browser found." when nothing handles it, reusing Story 4.17's string), and Support also offers the support email

**Given** the Payments & refunds screen
**When** it renders
**Then** it shows, in ≤ 25-word paragraphs: how the fee rises (base fee × snooze number, local price from Google Play, max snoozes, $50 cap), that the fee is locked for the morning and lowering it waits until after the next alarm, that a pending payment only starts a snooze if it clears during the session, that unused payments are refunded automatically by Google, and that Google lets you request a refund within 48 hours (EXPERIENCE.md Key strings)
**And** it shows the alarm behaviour disclosure verbatim: "Your alarm keeps ringing until you finish your check or pay to snooze. Your phone stays usable: calls, other apps and emergency calls all work. Other ways to stop it: force-stop or uninstall the app, turn off the phone, or leave it 30 minutes." (FR-ONB-5)
**And** it recommends Play purchase authentication: "Turn on purchase authentication in Google Play so every snooze needs your fingerprint or password." (EXPERIENCE.md Key strings) (NFR-5)
**And** it links to "Problem with a charge?" (Story 4.17) and "Purchase history" (Story 4.16)

**Given** these screens
**When** Roborazzi and semantic tests run
**Then** screenshots exist for You (full list) and Payments & refunds in Light and Dark and at 200% font scale, headings are TalkBack headings, targets are ≥ 48 dp, and a Robolectric test asserts each link intent
**And** the `pps-design` Done checklist is ticked in the story file, all strings are resources, key strings match EXPERIENCE.md verbatim and `CopyRulesTest` passes (FR-MSG-4)
**And** `./gradlew qualityGate` passes

### Story 5.9: Delete all data

As a user,
I want to delete everything the app stores about me in one step,
So that I can start over or leave without a trace on the phone.
**Refs:** FR-SET-4, FR-SES-3, FR-MSG-4, NFR-4, NFR-14, NFR-15, AD-4, AD-6, AD-7, AD-12, UX-DR55, UX-DR61 · **Priority:** Must · **Verify:** auto

**Acceptance Criteria:**

**Given** the You tab
**When** the user taps "Delete all data" (EXPERIENCE.md Key strings)
**Then** the preview's `dialog-confirm` opens with "Delete all data?" and "Alarms, history, purchase records and settings are removed from this phone. This can't be undone." with "Delete" in `error` colour and "Keep it" as the default dismiss (EXPERIENCE.md Key strings)

**Given** core use case `DeleteAllData`
**When** it runs while a session is active (including Snoozed)
**Then** it returns `DomainError.SessionActive` and changes nothing (the You row is also unreachable under the Epic 2 session lock; this guard is defence in depth, unit-tested)

**Given** no active session and the user confirms
**When** `DeleteAllData` runs
**Then** it writes a `deleteInProgress` marker, cancels every scheduled alarm request code and the test slot through `AlarmScheduler`, cancels all `BackgroundWork` jobs except consume-retry, deletes all rows in `app.db` (alarms, check configs, pending changes, commitment events, session history, purchase records), deletes `purchase_intent` rows and the active session in `runtime.db`, deletes media folders in credential-protected storage (if present), clears the settings DataStore, price cache and missed-note dismissals, regenerates the install id, sets analytics consent false and calls `resetAnalyticsData()`, calls Crashlytics `deleteUnsentReports()`, then removes the marker
**And** `grant_ledger` rows with status granted are kept until consumed, so a paid and granted snooze is never left unconsumed and refunded as if unused (owner-approved default 2026-09-26)
**And** if the process dies midway, the next app start sees the marker and finishes the deletion before showing any screen (test with a fake that throws after each step)
**And** afterwards the app opens onboarding (Story 5.10 onward; until then, the empty Home)

**Given** tests
**When** they run
**Then** a `:data` integration test fills both databases and the DataStores, runs the deletion and asserts every table and file is empty except granted ledger rows, and a Robolectric test asserts no alarm remains in `ShadowAlarmManager`
**And** Roborazzi screenshots cover the dialog in Light and Dark and at 200% font scale
**And** the `pps-design` Done checklist is ticked in the story file, all strings are resources, key strings match EXPERIENCE.md verbatim and `CopyRulesTest` passes (FR-MSG-4)
**And** `./gradlew qualityGate` passes

### Story 5.10: Onboarding, part 1: mission, alarm behaviour disclosure and base fee

As a new user,
I want to understand on the first screens that waking up is free, how the alarm behaves, and what a snooze will cost me,
So that I agree to the rules before I set my first alarm.
**Refs:** FR-ONB-1, FR-ONB-5, FR-MSG-1, FR-SET-1, FR-MSG-4, NFR-3, NFR-5, NFR-9, NFR-13, AD-11, AD-16, UX-DR25, UX-DR35, UX-DR39, UX-DR45, UX-DR60, UX-DR64, UX-DR66, UX-DR67, UX-DR80, UX-DR84, UX-DR86 · **Priority:** Must · **Verify:** auto

**Acceptance Criteria:**

**Given** `onboardingCompleted` false in the settings DataStore (first launch, or after "Delete all data")
**When** the app opens
**Then** the Onboarding route shows instead of Home, with `progress-dots` for 8 steps (mission, disclosure, base fee, first alarm, checks, reliability checklist, usage stats, test alarm), Back going to the previous step (and leaving the app from step 1), dots not tappable
**And** the steps use the design preview round 3 screens (`progress-dots` on a small glass capsule, one job per step, the step's actions in their own bottom area)
**And** the current step is saved, so a process death resumes at the same step
**And** a user restored from backup with `onboardingCompleted` true goes straight to Home (the reliability banner covers settings that differ on the new phone)

**Given** step 1, mission
**When** it renders
**Then** it shows "This app makes money only when you snooze. We hope you never pay us." as the headline content, "Snooze costs money. Waking up is free." below it, and one `button-filled` "Let's set it up" (FR-MSG-1)

**Given** step 2, disclosure
**When** it renders
**Then** it shows exactly "Your alarm keeps ringing until you finish your check or pay to snooze. Your phone stays usable: calls, other apps and emergency calls all work. Other ways to stop it: force-stop or uninstall the app, turn off the phone, or leave it 30 minutes." and one `button-filled` "I understand"; there is no skip
**And** "I understand" stores `disclosureAcceptedAt`, and `SaveAlarm` returns `DomainError.DisclosureRequired` while it is unset, so no alarm can be saved before consent (unit test; FR-ONB-5)

**Given** step 3, base fee
**When** it renders
**Then** it reuses the Epic 4 base fee `stepper` and ladder preview "Snooze 1: {price1} · 2: {price2} · 3: {price3}" with local Play prices, the note "You can raise it anytime. Lowering it waits until after your next alarm.", and the purchase-authentication tip from Story 5.8, with `button-filled` "Continue" (EXPERIENCE.md Key strings)
**And** offline or with no cached prices it shows USD amounts with "Approximate. Your local price shows when you're online." and "Continue" still works; `PriceCatalog.refresh()` is attempted when the step opens
**And** the fee is saved through `SetBaseFee` (no alarm exists yet, so no commitment lock applies)

**Given** steps 1 to 3
**When** Roborazzi and semantic tests run
**Then** screenshots exist for each step (base fee loaded and approximate) in Light and Dark and at 200% font scale, TalkBack focus starts on each step's headline, and targets are ≥ 48 dp
**And** the `pps-design` Done checklist is ticked in the story file, all strings are resources, key strings match EXPERIENCE.md verbatim and `CopyRulesTest` passes (FR-MSG-4)
**And** `./gradlew qualityGate` passes

### Story 5.11: Onboarding, part 2: first alarm, checks, checklist and usage stats

As a new user,
I want to set my first alarm and its check, fix my phone's settings and decide about usage stats in a few taps,
So that I finish setup with an alarm I can trust.
**Refs:** FR-ONB-1, FR-ONB-2, FR-ONB-6, FR-ALM-1, FR-PWK-2, FR-PWK-12, FR-MSG-4, NFR-9, NFR-15, AD-11, AD-15, UX-DR29, UX-DR36, UX-DR42, UX-DR45, UX-DR50, UX-DR64, UX-DR66, UX-DR67, UX-DR80, UX-DR84, UX-DR86 · **Priority:** Must · **Verify:** auto

**Acceptance Criteria:**

**Given** step 4, first alarm
**When** it renders
**Then** it shows the editor's shared time wheel (`time-picker`) and the repeat cards Once · Weekdays · Custom (Custom expands to the `chip-day`s) with a headline "When should it ring?" (EXPERIENCE.md Key strings); other alarm fields use the Story 5.1 defaults; a passed one-time time shows "Rings tomorrow at {time}."

**Given** step 5, checks
**When** it renders
**Then** it reuses the Epic 3 `check-type-card`s with "Try it", mode Random selected by default, headline "How will you prove you're up?" (EXPERIENCE.md Key strings); choosing QR/Barcode runs the Epic 3 registration (camera permission asked there, with its reason)
**And** "Continue" with no check selected is blocked with "Pick at least one check."
**And** on "Continue" the alarm is saved with `SaveAlarm` (enabled), the Story 1.19 notification permission request runs once on API 33+, and going back to step 4 or 5 edits the same alarm instead of creating a second one (test)

**Given** step 6, reliability checklist
**When** it renders
**Then** it embeds the Story 5.4 checklist as the `ChecklistCard` (rows, Fix deep-links, re-check on return) with headline "Make sure it rings" (EXPERIENCE.md Key strings); the "Test alarm" row is left for step 8 and "Continue" is never blocked by failing rows

**Given** step 7, usage stats
**When** it renders
**Then** it shows "Share anonymous usage stats? Off unless you turn it on." with two equal `button-outlined` "Share" and "No thanks", neither pre-selected or focused
**And** either tap sets `analyticsAsked` true and consent accordingly (Story 5.6), the question is never asked again, and the Settings toggle reflects the choice

**Given** steps 4 to 7
**When** Roborazzi and semantic tests run
**Then** screenshots exist for each step (checks with QR selected, checklist with two Fix rows) in Light and Dark and at 200% font scale; a ViewModel test drives the whole flow with fakes and asserts exactly one alarm saved and consent false after "No thanks"
**And** the `pps-design` Done checklist is ticked in the story file, all strings are resources, key strings match EXPERIENCE.md verbatim and `CopyRulesTest` passes (FR-MSG-4)
**And** `./gradlew qualityGate` passes

### Story 5.12: Onboarding test alarm on a locked screen

As a new user,
I want to ring a test alarm with my phone locked before tonight,
So that I see with my own eyes it rings over the lock screen, for free.
**Refs:** FR-ONB-1, FR-ONB-4, FR-ALM-8, FR-MSG-4, UX-DR10, UX-DR14, UX-DR35, UX-DR45, UX-DR64, UX-DR81, UX-DR84, UX-DR86 · **Priority:** Must · **Verify:** auto, plus (human-verify) in Story 5.13

**Acceptance Criteria:**

**Given** step 8, test alarm (the design preview round 3 screen)
**When** it renders
**Then** it shows "Lock your phone. We'll ring in 10 seconds." after the user taps `button-filled` "Ring a test alarm", the note "Your alarm screen turns bright to help you wake. Change it in Settings." (when Bright wake screen is on), and a `button-text` "Skip for now"
**And** "Ring a test alarm" schedules a test through Story 1.18 using the first alarm's settings (check, sound, grace), and the test ring shows "Test · no charge" and can never charge

**Given** the test completes while the phone was locked when it fired
**When** the user returns to the app
**Then** `lockedTestPassedAt` is set (Story 5.3), the checklist "Test alarm" row is OK, `onboardingCompleted` is set and Home opens

**Given** the test completes while the phone was not locked
**When** the user returns
**Then** step 8 stays with "Your phone wasn't locked. Try again with it locked." (EXPERIENCE.md Key strings) and the row stays unticked

**Given** the user taps "Skip for now"
**When** onboarding ends
**Then** `onboardingCompleted` is set, Home shows a `note-inline` "Ring a test alarm with your phone locked to check it works." (EXPERIENCE.md Key strings) until a locked-screen test passes (UX-DR81), and the checklist row stays unticked (FR-ONB-4)
**And** skipping is only possible through "Skip for now" (Back returns to step 7)

**Given** the test step
**When** Roborazzi and instrumented tests run
**Then** screenshots cover step 8 (before tap, waiting, not-locked retry) and Home with the skip note in Light and Dark and at 200% font scale, and a Gradle Managed Device test with the debug keyguard locked completes the test session and asserts `lockedTestPassedAt` is set
**And** the `pps-design` Done checklist is ticked in the story file, all strings are resources, key strings match EXPERIENCE.md verbatim and `CopyRulesTest` passes (FR-MSG-4)
**And** `./gradlew qualityGate` passes

### Story 5.13: Epic 5 device verification checklist

As the owner,
I want to confirm first run, settings and the reliability checklist on real phones,
So that closed testers get an app that sets itself up correctly on their devices.
**Refs:** FR-ONB-1–6, FR-SET-1–4, FR-SET-6, FR-MSG-1, FR-MSG-4, UX-DR10, NFR-4, NFR-9, NFR-15 · **Priority:** Must · **Verify:** human-verify

**Acceptance Criteria:**

**Given** the latest `main` debug build freshly installed on each device of the matrix (the owner's Oppo A96 plus the NFR-1 emulators; other makers optional via Firebase Test Lab)
**When** the owner runs the checklist
**Then** for each item the story file records pass/fail, device, Android version and date:
1. First launch runs mission → disclosure → base fee → first alarm → checks → checklist → usage stats → test alarm, in that order, and resumes at the same step after swiping the app away.
2. The disclosure has no skip and "I understand" is required; the base fee shows local prices online and "Approximate…" in airplane mode.
3. Every checklist "Fix" opens the right system screen and the row turns "OK" on return: notifications, full-screen alarm (API 34+), exact alarms (Android 12 device or emulator), Do Not Disturb set to block alarms, battery optimization.
4. With a DND schedule that blocks alarms (DND currently off), the Do Not Disturb row shows Fix and the Home banner appears.
5. Turning battery optimization back on after it was OK shows "Alarms may not ring. Fix settings" on Home and "Alarm may not ring: battery optimization turned back on" in the checklist; fixing clears both.
6. On the Xiaomi and Samsung, the Manufacturer settings row appears, "Open settings" opens the right OEM screen, and "I've done this" marks it OK; on the Pixel the row is hidden.
7. The onboarding test alarm rings over the locked screen with "Test · no charge", the screen goes fully bright, brightness returns to normal on "Done", and the Test alarm row turns OK; an unlocked test shows the retry message; "Skip for now" leaves the Home note.
8. With Extra dim on (API 31+), the wake screen does not force full brightness; with "Bright wake screen" off, brightness is untouched.
9. Theme System / Light / Dark switches every app screen immediately; wake screens stay Sunrise.
10. New default grace window and snooze length apply to a new alarm but not to existing ones.
11. Usage stats "No thanks" sends nothing (Firebase DebugView empty after a session); "Share" sends only the three events.
12. Privacy policy, Terms and Support open the live GitHub Pages; Payments & refunds shows the disclosure text and links to "Problem with a charge?".
13. "Delete all data" is unreachable during a session; when idle it removes all alarms (none ring afterwards), history and settings, and the app restarts onboarding.
14. TalkBack reads each onboarding step headline first and every checklist row as title, reason and status; at 200% font size nothing clips.
15. All copy seen matches EXPERIENCE.md (FR-MSG-4).

**Given** any failed item
**When** the owner records it
**Then** each failure becomes a new bug story referencing this item, and this story stays open until all items pass or are explicitly waived by the owner in the story file
**And** automation never marks this story done

### Story 5.14: Start the 14-day closed test (owner task)

As the owner,
I want at least 12 testers running the app on the closed track for 14 days, able to test payments for free and send feedback,
So that the production-access requirement is met and real-world bugs surface before launch.
**Refs:** NFR-1, NFR-5, NFR-11, AD-7, AD-14; PRD §11 SM-1, §13 release plan and launch blockers · **Priority:** Must · **Verify:** human-verify

**Acceptance Criteria:**

**Given** Stories 4.18 and 5.13 have passed (or failures are explicitly waived)
**When** the owner prepares the closed test
**Then** a Google Group (or email list) of at least 12, ideally 15 or more, testers exists, every tester's Google account is also added under Setup > License testing with "RESPOND_NORMALLY" so snooze purchases are test purchases that charge nothing, and testers are told this in writing
**And** `docs/closed-test.md` (committed) gives testers: the opt-in link, what to test each morning (real alarms, snooze payments, reliability checklist, a locked test alarm), the device matrix gaps to fill, how to report (feedback form link and email), and a note that alarms may fail in testing so they keep a second alarm

**Given** Play Console requirements for a closed track
**When** the owner sets up the track
**Then** the App content items Play requires before publishing to closed testing are complete (privacy policy URL from Story 5.7, ads declaration, content rating questionnaire, target audience 18+, Data safety draft, and any exact alarm, full-screen intent or foreground-service declarations Play asks for); anything pulled forward from Epic 8 is noted in the story file
**And** a release tag `vX.Y.Z` from `main` builds the signed AAB through the Story 1.2 release workflow, the build is promoted from internal to the closed track, and the tester group is attached

**Given** the feedback channel
**When** testers report issues
**Then** a feedback form (for example a Google Form: device, Android version, what happened, time) and the support email are live, Play's private tester feedback is enabled, and the owner triages reports at least twice a week into bug stories referencing this story

**Given** the test starts
**When** at least 12 testers have opted in
**Then** the story file records the start date, the planned end date (start + 14 days), the opted-in count on day 1, and a weekly count until the end; the continuous 14 days with ≥ 12 opted-in testers are tracked into Epic 8 (production access)
**And** this story is done when the test has started with ≥ 12 opted-in testers; automation never marks it done

## Epic 6: Wake-up progress

The user sees how their mornings are going: the current and best zero-snooze streak and this week's money on Home above everything else, a Progress screen with the ring of the last 30 mornings, on-time rate (7 and 30 days), average time from first ring to up, snoozes over the last 30 days and money paid per currency, a calendar that marks each day's outcome with shape, colour and label, a Day detail for each morning, a Sunday-evening weekly summary, and a one-screen zero-snooze celebration. Every number comes from pure functions in `core.stats` over the Story 1.13 `session_history` table (written only by `SessionRecorder`) and the Epic 4 purchase records (written only by `PurchaseLedger`, `Money` micros plus currency); nothing stores derived stats (AD-18, AD-8). Test and Skipped sessions, and sessions still in progress (null outcome), never count toward rates, streaks or averages. This epic is built while the closed test from Epic 5 runs.

Every UI story in this epic carries the two standing acceptance criteria from Epic 1, repeated in the story so the build loop can check them: (1) the `pps-design` Done checklist is copied into the story file with every item ticked; (2) all new user-facing strings live in Compose Multiplatform resources, match `EXPERIENCE.md > Voice and Tone > Key strings` verbatim where a key string exists, and pass `CopyRulesTest` (FR-MSG-4). A string that EXPERIENCE.md does not define is marked `(EXPERIENCE.md Key strings)` and listed for the owner.

Stats definitions shared by every story in this epic (owner to confirm; these resolve PRD Q15 and the open definitions in FR-PRG-2) (owner-approved default 2026-09-26):
- **Day of a session** = local date of `scheduled_at` in the zone from `TimeZoneProvider` at the time of computation (a 23:50 alarm finished at 00:10 belongs to the earlier day).
- **Counted session** = outcome OnTime, Snoozed or Missed. Test, Skipped and null outcome are never counted.
- **Good day** = a day with at least one counted session where every counted session is OnTime. A day with any Snoozed or Missed session breaks the streak. Days with no counted session (no alarm, or only Test/Skipped) neither extend nor break a streak.
- **Week** = ISO week, Monday 00:00 to Sunday 24:00 local, so the Sunday 19:00 summary covers the whole week so far. **Month** = local calendar month.
- **Money paid** = purchase records with status granted, consumed or reused, summed in micros per currency; stranded records are never counted (they are refunded by Google). Self-requested refunds are not detectable (PRD §6.3), so totals show what was charged.
- **Calendar day outcome** when a day has several sessions = the worst counted outcome (Missed, then Snoozed, then On time); a day with only Test or Skipped sessions shows the small neutral dot labelled Skipped if any session was skipped, otherwise Test.

### Story 6.1: Streaks, on-time rate and time to up in core.stats

As a user,
I want my streak, on-time rate and average time to get up calculated the same way everywhere,
So that Home, Progress, the celebration and the weekly summary never disagree.
**Refs:** FR-PRG-1, FR-PRG-2, NFR-11, NFR-12, AD-1, AD-3, AD-18 · **Priority:** Must · **Verify:** auto

**Acceptance Criteria:**

**Given** `core.stats`
**When** the day model is added
**Then** a pure `sessionDay(entry, zone): LocalDate` returns the local date of `scheduled_at`, and `isCounted(entry)` is true only for outcome OnTime, Snoozed or Missed (false for Test, Skipped and a null outcome)
**And** every function in this story takes the history as `List<SessionHistoryEntry>` plus `today: LocalDate` and `zone: TimeZone`, reads no clock itself (time comes from the Story 1.6 ports at the call site), and lives in commonMain with no platform import (AD-1)

**Given** a history
**When** `currentStreak(history, today, zone)` and `bestStreak(history, zone)` are computed
**Then** days with a counted session are ordered by date; a good day extends the run, a day with any Snoozed or Missed session ends it, and days without a counted session are skipped without breaking it
**And** the current streak walks back from the latest day ≤ `today` that has a counted session; days after `today` (clock set back) are ignored
**And** tests cover: empty history → 0 and 0; five on-time weekdays with no weekend alarms → 5; on time Monday, Missed Tuesday, on time Wednesday → current 1, best 1; two sessions on one day, one OnTime and one Snoozed → that day breaks the streak; a day with only a Test session between two good days → streak 2; a Skipped day between two good days → streak 2; today Snoozed after a 9-day run → current 0, best 9; a session still in progress (null outcome) today → ignored, current streak unchanged
**And** a seeded property test over 1,000 random histories asserts `bestStreak ≥ currentStreak ≥ 0` and that adding a Test or Skipped row never changes either value

**Given** a history
**When** `onTimeRate(history, today, zone, days)` is computed for `days` = 7 and 30
**Then** the window is `today − (days − 1)` through `today`, the result is OnTime count ÷ counted count in the window rounded half-up to a whole percent, and it is `null` (not 0) when the window has no counted session
**And** tests cover 6 of 7 → 86%, 1 of 3 → 33%, 2 of 3 → 67%, a Missed-only window → 0%, only Test/Skipped in the window → null, and a session exactly on the window's first day is included while the day before is not

**Given** a history
**When** `averageTimeToUp(history, today, zone)` is computed
**Then** it is the mean of `time_to_complete_ms` over OnTime and Snoozed sessions in the last 30 days (for Snoozed sessions this includes snooze time, as defined in FR-PRG-2 "from first ring to up"), returned as a `Duration`, and `null` when there are none
**And** rows with a null `time_to_complete_ms` are skipped, Missed sessions never contribute, and a helper `wholeMinutes(duration)` rounds half-up (2 min 29 s → 2, 2 min 30 s → 3) with durations under 30 s marked as "under a minute" for the UI
**And** tests use builders from `:testing` (`aSession(...)`) and cover Europe/Berlin across the DST change, where 2027-03-28 still counts as one day, and a time-zone change from Asia/Ho_Chi_Minh to Europe/London that moves a 06:30 session to the previous local date
**And** Kover shows `core.stats` ≥ 90% line coverage
**And** `./gradlew qualityGate` passes

### Story 6.2: Snoozes per week, money per currency and day outcomes in core.stats

As a user,
I want my snoozes and payments added up honestly, per currency, from what was really charged,
So that the numbers I see match my Google Play receipts.
**Refs:** FR-PRG-1, FR-PRG-2, FR-PRG-3, FR-MSG-2, NFR-10, NFR-11, AD-8, AD-18 · **Priority:** Must · **Verify:** auto

**Acceptance Criteria:**

**Given** a history
**When** `snoozesLast30Days(history, today, zone)` is computed
**Then** it returns the sum of `snooze_count` over sessions whose day falls in the last 30 days up to and including `today`, including Snoozed and Missed sessions (paid snoozes before a Missed timeout still happened) and excluding Test, Skipped and null-outcome rows
**And** no weekly series for a chart is built (the weekly totals for the Sunday summary stay in Story 6.8), and tests cover a session on the 30th day back (counted) and one on the 31st (not counted)

**Given** a history
**When** `insight(history, today, zone)` is computed
**Then** it returns one short line based on the user's data for the Progress Insight card (for example "You get up fastest on weekdays." (EXPERIENCE.md Key strings)), or nothing when the data is too thin, in which case the card is hidden

**Given** purchase records from the Epic 4 read port (each with `Money(micros, currency)`, status, `session_id` and purchase time)
**When** `moneyPaid(records, period, today, zone)` is computed for `ThisWeek`, `ThisMonth` and `AllTime`
**Then** it returns a list of `Money`, one per currency, summing micros as `Long` only over statuses granted, consumed and reused, sorted by ISO currency code, and an empty list when nothing was paid
**And** stranded records are never included, a record that moved from stranded to reused is counted once, and amounts in different currencies are never added together (test with EUR 2.99 + USD 1.00 + EUR 5.99 → EUR 8.98 and USD 1.00 as two entries)
**And** a purchase at 23:59 on the last day of a month counts in that month and a purchase at 00:00 on Monday counts in the new week (tests)

**Given** a history and purchase records
**When** `dayOutcome(history, records, date, zone)` is computed
**Then** it returns `DaySummary(date, outcome, sessionCount, fallbackUsed)` where `outcome` is `None` when the day has no session, otherwise the worst counted outcome (Missed, then Snoozed, then OnTime), or `Skipped` / `Test` for days with only those sessions (Skipped wins over Test), and `fallbackUsed` is true if any session that day used the fallback check
**And** `monthSummary(history, records, yearMonth, zone)` returns one `DaySummary` per day of that month, and `sessionsOn(history, records, date, zone)` returns that day's sessions ordered by `scheduled_at`, each with its paid `Money` list (granted, consumed, reused) and its stranded records listed separately

**Given** `SessionHistoryRepository` from Story 1.13 and the Epic 4 purchase-record port
**When** the `ObserveProgress` use case is added
**Then** it exposes `Flow<ProgressSnapshot>` holding current and best streak, on-time rate 7 d and 30 d, average time to up, snoozes over the last 30 days, the insight line, money this week / month / all time and a `hasHistory` flag, recomputed whenever either table changes and whenever the local date or time zone changes (a `DayTicker` input driven from `Clock` and `TimeZoneProvider`)
**And** a read-only `observeAll(): Flow<List<SessionHistoryEntry>>` is added to `SessionHistoryRepository` (Room implementation plus the `:testing` fake) if Story 1.13 did not provide one; `SessionRecorder` stays the only writer
**And** Turbine tests with `FakeSessionHistoryRepository`, the Epic 4 purchase-record fake and `FakeClock` assert a new emission after a session row is upserted, after a purchase record is upserted, and when the fake clock crosses local midnight
**And** a performance test builds 3 years of daily sessions (about 1,100 rows) and asserts a full `ProgressSnapshot` computes in under 50 ms on the JVM
**And** Kover shows `core.stats` ≥ 90% line coverage
**And** `./gradlew qualityGate` passes

### Story 6.3: Streak and money hero card on Home

As a user,
I want Home to show my streak and what I paid this week before anything else,
So that the thing I'm working toward is the first thing I see.
**Refs:** FR-MSG-2, FR-PRG-2, FR-MSG-4, NFR-9, NFR-10, AD-8, AD-11, UX-DR30, UX-DR61, UX-DR64, UX-DR66, UX-DR67, UX-DR80, UX-DR84 · **Priority:** Must · **Verify:** auto

**Acceptance Criteria:**

**Given** Home with at least one alarm or at least one history row
**When** it renders
**Then** `card-hero` fills the collapsing Home header from Story 1.9, above the reliability `banner-warning`, notes and the alarm list, and shows the current streak from `ObserveProgress` in `display` with tabular figures (`accent-text` in Light, `accent-dark` in Dark), the label "days on time" in `body` (EXPERIENCE.md Key strings), and the money line in `text-secondary`
**And** the card is `glass`, `rounded.md`, 16 dp padding, not tappable, collapses into the compact pinned row on scroll (UX-DR30), and the streak number never counts up on Home

**Given** nothing was paid this ISO week
**When** the money line renders
**Then** it reads exactly "Nothing paid this week. Keep it that way." (EXPERIENCE.md Key strings), never a zero amount

**Given** purchases this week in one or more currencies
**When** the money line renders
**Then** it reads "{paid} paid this week" (EXPERIENCE.md Key strings) where `{paid}` is each currency's total formatted by the Epic 4 `MoneyFormatter` port, joined with " · " in currency-code order (for example "€8.98 · $1.00 paid this week"), with no currency symbol in any string resource
**And** the price text uses `text` colour, never accent, green or red (UX-DR7)

**Given** the Home empty state (no alarms and no history) or an active session
**When** Home renders
**Then** the hero card is hidden: the empty state shows "No alarms yet." with "Add your first alarm", and during a session `panel-session-in-progress` replaces all Home content (UX-DR61)

**Given** the hero card
**When** a session is recorded, a purchase record changes, the local date rolls over, or the app returns to the foreground
**Then** the streak and money line update without a restart (ViewModel test with fakes and Turbine)
**And** TalkBack reads the card as one element, "{streak} days on time. {money line}", with no button role

**Given** Roborazzi and semantic tests
**When** they run
**Then** screenshots cover streak 0 with nothing paid, streak 1, streak 12 with one currency paid, two currencies paid, and the hero above the reliability banner, in Light and Dark and at 200% font scale with nothing clipped
**And** the `pps-design` Done checklist is ticked in the story file, all strings are resources, key strings match EXPERIENCE.md verbatim and `CopyRulesTest` passes (FR-MSG-4)
**And** `./gradlew qualityGate` passes

### Story 6.4: Zero-snooze celebration on the Success screen

As a user who got up without snoozing,
I want one short, warm celebration that shows my streak growing,
So that waking on time feels like a win, and a paid morning never feels like a failure.
**Refs:** FR-MSG-3, FR-MSG-4, FR-PRG-2, NFR-7, NFR-9, AD-2, AD-11, AD-18, UX-DR2, UX-DR64, UX-DR66, UX-DR70, UX-DR71, UX-DR72, UX-DR77, UX-DR78, UX-DR84, UX-DR87 · **Priority:** Must · **Verify:** auto, plus (human-verify) haptic and brightness in Story 6.10

**Acceptance Criteria:**

**Given** a session that starts (`AlarmFired`, `TestAlarmFired` or `ProcessRestored`)
**When** `WakeService` handles it
**Then** it loads a history snapshot for the celebration off the wake critical path (after the ringing screen is shown) and keeps it in memory for the session; `streakBefore = currentStreak(snapshot, today, zone)`
**And** the ringing screen's first frame never waits for this load (test asserts no repository call before the first frame, as in Story 1.15)

**Given** the Success wake screen shown after `Completed` (Story 3.3, with the "after snooze" variant from Story 4.15; extended here)
**When** the session completed with 0 snoozes and is not a test
**Then** `streakAfter = currentStreak(snapshot + this session as OnTime, today, zone)` and the screen shows `streakAfter` as the big number in tabular figures, then "days in a row", then the headline "Up on time." (EXPERIENCE.md Key strings), with no repeated number
**And** when `streakAfter > streakBefore` the number counts up from `streakBefore` with a bounce, about 1.5 s of confetti plays and one success haptic fires (UX-DR71); when the streak did not grow (a second on-time session on the same day) the same text shows with no animation and no extra haptic
**And** the whole animation finishes within 3 s of the screen appearing (FR-MSG-3), is not repeated on recomposition, rotation or return from Recents, and never plays on Home

**Given** the animator duration scale is 0
**When** the celebration would play
**Then** the final state appears instantly with no count-up, bounce or confetti, and the success haptic still fires (UX-DR72)

**Given** a session completed after one or more paid snoozes
**When** Success renders
**Then** it shows "You're up. That's what counts." and "{paid} paid this morning" in `text-secondary-sunrise` (EXPERIENCE.md Key strings), with no streak number, no animation and no celebration haptic beyond the standard success pattern (paid mornings are never shamed or celebrated)

**Given** a test session that completes
**When** Success renders
**Then** it shows "Test finished. Your alarm works." (EXPERIENCE.md Key strings) with no streak, no animation, and history outcome Test unchanged

**Given** the in-memory snapshot could not be loaded (read error or process restored without it)
**When** a zero-snooze session completes
**Then** Success shows "You're up. That's what counts." without a number or animation and the failure is logged through `Logger` (never a loading state, never a wrong streak)

**Given** the Success screen
**When** the user taps "Done" or the activity stops
**Then** the screen closes, the screen brightness set by the Epic 5 "Bright wake screen" setting is restored, and `FLAG_KEEP_SCREEN_ON` is cleared so the system screen timeout applies while Success is visible
**And** Back still does nothing on the wake screen (UX-DR74), and TalkBack initial focus is the headline, then "Done"

**Given** Roborazzi, semantic and ViewModel tests with `FakeClock` and fake history
**When** they run
**Then** they cover streak growing 11 → 12 (animation state start and end), no growth on a second session the same day, first-ever on-time morning, after snooze with one currency, test session, and snapshot missing, in Sunrise at 100% and 200% font scale
**And** a unit test asserts the success-screen string keys contain no emoji other than the one allowed key and no word from the banned list (`CopyRulesTest`)
**And** the `pps-design` Done checklist is ticked in the story file, all strings are resources, key strings match EXPERIENCE.md verbatim and `CopyRulesTest` passes (FR-MSG-4)
**And** `./gradlew qualityGate` passes

### Story 6.5: Progress screen with the 30-morning ring, stat tiles and money

As a user,
I want one screen that shows my streaks, on-time rate, time to get up, snoozes and money paid,
So that I can see whether I'm snoozing less over time.
**Refs:** FR-PRG-2, FR-PRG-4, FR-MSG-4, NFR-3, NFR-9, NFR-10, AD-8, AD-11, AD-18, UX-DR44, UX-DR46, UX-DR48, UX-DR58, UX-DR59, UX-DR64, UX-DR66, UX-DR67, UX-DR68, UX-DR80, UX-DR84, UX-DR94 · **Priority:** Must · **Verify:** auto

**Acceptance Criteria:**

**Given** the nav capsule
**When** the user taps "Progress" (the Story 1.9 tab, filled here)
**Then** the Progress route shows, top to bottom, with no heading, no period tabs and no chart (EXPERIENCE.md Information Architecture): the `progress-ring` of the last 30 mornings with the streak, "/ 30" and "day streak" in the centre; three small `stat-tile`s in one row (2 + 1 at large font); `card-streak` ("Current streak", "Best streak", "Keep it going."); the calendar (Story 6.6); and the "Money paid" card with the Purchase history link (the Epic 4 screen) beside the Insight card (Story 6.2, hidden when the data is thin)
**And** tapping a ring dot shows its label chip ("{weekday} {day} · {outcome}"), and tapping again or tapping the chip opens Day detail (Story 6.7)
**And** on entry the cards fade and rise in sequence, the ring dots sweep in and the streak counts up; with reduced motion the final state shows at once
**And** the tile labels are "on time", "to get up" and "snoozes" (EXPERIENCE.md Key strings) for the 30-day on-time rate, the average time to up and the snooze count over the last 30 days; numbers are in `display` with tabular figures in `text` colour, percentages are locale-formatted (86% in en-US), average time reads "{minutes} min" or "Under 1 min" (EXPERIENCE.md Key strings)
**And** a tile whose value is `null` shows "No mornings yet" (EXPERIENCE.md Key strings) in `body` instead of a number, never 0% or 0 min

**Given** the money section titled "Money paid" (EXPERIENCE.md Key strings)
**When** it renders `moneyPaid` for this month
**Then** it is labelled "This month" (EXPERIENCE.md Key strings) and shows one line per currency formatted by `MoneyFormatter`, in `text` colour, never accent, green or red, with the "Purchase history" link
**And** nothing paid reads "Nothing paid" (FR-PRG-2), never a zero amount or a currency symbol

**Given** no history at all
**When** Progress opens
**Then** it shows the empty ring with "Your first morning shows up here." (EXPERIENCE.md Key strings), the calendar and the Purchase history link, with no empty tiles
**And** while the first snapshot loads, `skeleton` blocks appear only after 300 ms, without shimmer when animations are off, and text replaces them after 3 s (UX-DR58)

**Given** a history with only Test and Skipped sessions
**When** Progress opens
**Then** the streaks show 0, rate and average tiles show "No mornings yet", and the snoozes tile shows 0 (Test and Skipped never count)

**Given** an active session
**When** the user opens the app
**Then** Progress is unreachable because `panel-session-in-progress` replaces the app and the nav capsule is hidden (UX-DR61)

**Given** Roborazzi, semantic and ViewModel tests with fake history and purchase records
**When** they run
**Then** screenshots cover empty, Test-only, a typical month (F9: streak 12, best 12, 86% 30 d, 3 min, a ring with varied outcomes), two currencies, and a tapped ring dot with its label chip, in Light and Dark and at 200% font scale with nothing clipped, all targets ≥ 48 dp and every tile read by TalkBack as "{label}, {value}"
**And** the `pps-design` Done checklist is ticked in the story file, all strings are resources, key strings match EXPERIENCE.md verbatim and `CopyRulesTest` passes (FR-MSG-4)
**And** `./gradlew qualityGate` passes

### Story 6.6: Calendar of morning outcomes

As a user,
I want a month calendar that marks each morning as on time, snoozed, missed, skipped or test,
So that I can spot patterns, like which weekdays I struggle on.
**Refs:** FR-PRG-3, FR-PRG-1, FR-MSG-4, NFR-9, AD-18, UX-DR11, UX-DR48, UX-DR49, UX-DR64, UX-DR66, UX-DR67, UX-DR68, UX-DR85, UX-DR94 · **Priority:** Should · **Verify:** auto

**Acceptance Criteria:**

**Given** the Progress screen with at least one history row
**When** it renders
**Then** a compact calendar card below `card-streak` shows the current month as a 7-column grid of 48 dp `calendar-day` cells starting on Monday (matching the ISO week used by the stats), with the month and year as a locale-formatted title and weekday initials above the columns
**And** each cell shows the date in `caption` and, below it, the `outcome-marker` shape for `dayOutcome` (UX-DR48): filled dot in `success` (On time), dot with a small clock in `snoozed` (Snoozed), hollow ring in `missed` (Missed), small neutral dot in `outline` (Skipped or Test); a fallback check is said to TalkBack (the `alt_route` badge is shown in Day detail only); days with no session show only the date
**And** today's date sits in an outlined accent pill, and future days show only the date and are not tappable

**Given** a day cell with sessions
**When** the user taps it
**Then** its label chip ("{weekday} {day} · {outcome}", glossary outcome words) pops in under the grid (instant with reduced motion), and a second tap on the day or a tap on the chip opens Day detail; there is no legend, because shape plus colour plus the chip label carry the meaning (UX-DR68)

**Given** the calendar
**When** the user taps the 48 dp "Previous month" or "Next month" icon buttons (EXPERIENCE.md Key strings)
**Then** the grid moves one month, "Next month" is disabled on the current month, and "Previous month" is disabled on the month of the oldest history row
**And** the grid recomputes from `monthSummary` when a session is recorded or the local date changes

**Given** TalkBack
**When** focus moves over a day cell
**Then** it reads "{weekday} {day}, {outcome}" in the EXPERIENCE.md pattern (for example "Tuesday 14, on time"), adding ", fallback check used" when relevant, ", {n} sessions" when the day has more than one session (EXPERIENCE.md Key strings), or "{weekday} {day}, no alarm" for an empty day (EXPERIENCE.md Key strings), with a double-tap hint to open Day detail on days with sessions

**Given** a day with several sessions (for example a Snoozed 06:00 and an On time 06:30)
**When** its cell renders
**Then** it shows the worst counted outcome (Snoozed here) per the shared stats definitions, and Day detail lists both sessions (PRD Q15 resolution, owner to confirm)

**Given** an alarm turned off or deleted inside the commitment-lock window
**When** the calendar renders that day
**Then** no marker is shown for the occurrence that did not ring (no session row exists, so it is neither Missed nor counted), per the PRD Q15 resolution (owner to confirm)

**Given** Roborazzi and semantic tests
**When** they run
**Then** screenshots cover a month with every outcome, a fallback badge, a multi-session day, today, an empty previous month, and a label chip, in Light and Dark and at 200% font scale with no clipped cell, and a test asserts every glyph/colour pair used is in the DESIGN.md verified contrast table
**And** the `pps-design` Done checklist is ticked in the story file, all strings are resources, key strings match EXPERIENCE.md verbatim and `CopyRulesTest` passes (FR-MSG-4)
**And** `./gradlew qualityGate` passes

### Story 6.7: Day detail for each morning

As a user,
I want to tap a day and see what happened: when it rang, when I got up, snoozes, what I paid, which check I did,
So that I understand each morning, not just the totals.
**Refs:** FR-PRG-1, FR-PRG-3, FR-PRG-4, FR-SES-7, FR-PWK-11, FR-MSG-4, NFR-9, AD-8, AD-16, AD-18, UX-DR48, UX-DR52, UX-DR64, UX-DR66, UX-DR78, UX-DR80, UX-DR91, UX-DR94 · **Priority:** Should · **Verify:** auto

**Acceptance Criteria:**

**Given** a ring or calendar day with sessions
**When** the user opens it from its label chip
**Then** the Day detail route opens with a one-line title (short weekday, day and month, for example "Wed, Sep 23" in the phone's locale) and, per session from `sessionsOn` ordered by scheduled time, a `day-hero`, three small tiles ("rings" · "snoozes" · "paid", "No charge" when nothing was paid) and a `morning-timeline` of the events in order, which draws in on open (instant with reduced motion)

**Given** a counted session (On time, Snoozed or Missed)
**When** its hero, tiles and timeline render
**Then** they show the `outcome-marker` with its label, the alarm time and label, first ring time, time up (`ended_at`; not shown for Missed), time to up as "{minutes} min" or "Under 1 min" (Story 6.5 strings), snooze count as "{n} snoozes" (Story 6.5 string), the amount paid per currency via `MoneyFormatter` or "Nothing paid", and the check types by their glossary names
**And** when `fallback_used` is true it adds the `alt_route` badge and "Fallback check used"; when `direct_boot` is true it adds "Rang before your first unlock" (EXPERIENCE.md Key strings)
**And** each occurrence merged into this session (recorded by the Epic 2 merge handling) adds a `note-inline` "{time} alarm merged into this session" (EXPERIENCE.md State Patterns)
**And** each stranded purchase linked to the session is listed as a `purchase-row`-style line reading "Not used, refunded automatically by Google" (EXPERIENCE.md Key strings), not included in the paid amount

**Given** a Test or Skipped session
**When** it renders
**Then** it shows the hero only: the outcome marker, the label "Test" or "Skipped" and the alarm time (EXPERIENCE.md State Patterns: outcome label only)

**Given** an alarm the user turned off or deleted inside the commitment-lock window on that day (F6)
**When** Day detail renders
**Then** it lists "Your {time} alarm was turned off. Logged." or "Your {time} alarm was deleted. Logged." (EXPERIENCE.md Key strings) from the `commitment_event` rows (Story 4.4 table, written by the Story 4.6 turn-off and delete confirmations), with `{time}` taken from `occurrence_at` and the day from its local date; no new table or migration is added

**Given** Day detail with TalkBack
**When** focus moves through a session
**Then** the outcome is read as a word, never as a colour or icon name, and each fact is read as label and value

**Given** a session whose `session_history` row has a null `ended_at` or `time_to_complete_ms` (for example an older row)
**When** it renders
**Then** the missing facts are omitted, never shown as 0 or "null" (test)

**Given** Roborazzi, semantic and ViewModel tests
**When** they run
**Then** screenshots cover an On time session, a Snoozed session with two currencies and a stranded purchase, a Missed session, a fallback plus Direct Boot session, a merged occurrence, a Test session, a Skipped session and a turned-off note, in Light and Dark and at 200% font scale
**And** the `pps-design` Done checklist is ticked in the story file, all strings are resources, key strings match EXPERIENCE.md verbatim and `CopyRulesTest` passes (FR-MSG-4)
**And** `./gradlew qualityGate` passes

### Story 6.8: Weekly summary notification on Sunday evening

As a user,
I want a short Sunday-evening note with my on-time mornings and what I paid,
So that I notice my progress without opening the app.
**Refs:** FR-PRG-5, FR-MSG-4, NFR-8, NFR-10, AD-3, AD-11, AD-17, AD-18, UX-DR51, UX-DR57, UX-DR84, UX-DR94 · **Priority:** Should · **Verify:** auto, plus (human-verify) real delivery in Story 6.10

**Acceptance Criteria:**

**Given** `core.stats`
**When** `nextWeeklySummaryAt(now, zone)` is computed
**Then** it returns the first Sunday 19:00 local strictly after `now` [A6], tested for Saturday 23:00 → next day, Sunday 18:59 → same day, Sunday 19:00 exactly → a week later, the Europe/Berlin DST change weekend, and a zone change from Asia/Ho_Chi_Minh to America/New_York
**And** `weeklySummary(history, records, weekOf, zone)` returns the on-time count and `moneyPaid` for that ISO week, plus `shouldSend = false` when the week has no counted session

**Given** the `BackgroundWork` port (AD-17) and its WorkManager adapter (from Epic 4 consume retries; the port gains a `scheduleWeeklySummary(at)` / `cancelWeeklySummary()` pair here) with `FakeBackgroundWork` updated
**When** the weekly summary is enabled
**Then** one unique one-time work request named `weekly-summary` is enqueued (policy REPLACE) with its initial delay computed from `nextWeeklySummaryAt`, and it is re-enqueued at the end of every run, on app start, on toggle-on, and from the Story 1.10 `SystemEventsReceiver` on boot, `TIME_SET` and `TIMEZONE_CHANGED`
**And** nothing on the wake path depends on it, no periodic or foreground work is added, and no alarm is scheduled through `AlarmScheduler` for it (NFR-8, AD-17)

**Given** the worker runs
**When** it evaluates the summary
**Then** if the run is within 24 h after the target Sunday 19:00 it summarises that ISO week; if later (for example the phone stayed locked since boot until Tuesday) it skips that week without posting; if `shouldSend` is false it posts nothing
**And** the ISO week id of the last posted summary is stored in device-protected DataStore, so a duplicate run for the same week never posts twice (test)
**And** if `POST_NOTIFICATIONS` is not granted it posts nothing and never requests the permission from the background

**Given** a week to report
**When** the notification is posted
**Then** it uses channel "Weekly summary" (EXPERIENCE.md Key strings) with default importance, a monochrome small icon with no accent tint, the app name as title, and text "{onTime} on-time mornings, nothing paid. Nice." when nothing was paid and at least one morning was on time, or "{onTime} on-time mornings, {paid} paid this week." when something was paid (EXPERIENCE.md Key strings), with `{paid}` formatted per currency by `MoneyFormatter` and joined with " · "
**And** when nothing was paid and `{onTime}` is 0 (only Missed mornings) it reads "0 on-time mornings this week. Next week's a fresh start." (EXPERIENCE.md Key strings), never "Nice."
**And** `{onTime}` = 1 uses a singular form ("1 on-time morning, nothing paid. Nice.") (EXPERIENCE.md Key strings)
**And** tapping it opens the app on the Progress route (or `panel-session-in-progress` if a session is active), and it auto-cancels on tap

**Given** Settings
**When** the Notifications section renders (created here if Epic 5 did not add it)
**Then** it has a `settings-row` `switch` "Weekly summary" (EXPERIENCE.md Key strings), default on, stored in device-protected DataStore through a core use case; turning it off cancels the unique work immediately, turning it on enqueues it
**And** the row is unreachable during an active session (session lock)

**Given** Robolectric tests with WorkManager's test driver, `FakeClock` and fake repositories
**When** they run
**Then** they cover: enqueue on first launch with default on; delay equals the computed instant; run on Sunday 19:00 posts the expected text for nothing-paid, paid in two currencies, zero on-time, and singular cases; a week with only Test sessions posts nothing; a late run on Tuesday skips; a second run for the same week does nothing; toggle off cancels; notification denied posts nothing; tap intent targets Progress
**And** `WorkManager` stays in `config/dependency-allowlist.txt` and the permission allowlist still passes
**And** the `pps-design` Done checklist is ticked in the story file, all strings are resources, key strings match EXPERIENCE.md verbatim and `CopyRulesTest` passes (FR-MSG-4)
**And** `./gradlew qualityGate` passes

### ~~Story 6.9: Export history as CSV~~ (dropped 2026-10-01, FR-PRG-6 removed)

Dropped by sprint-change-proposal-2026-10-01: the owner decided against an in-app CSV export (design preview feedback item 23), so FR-PRG-6 is removed from PRD v0.3 and this story is not built.

### Story 6.10: Epic 6 device verification checklist

As the owner,
I want to confirm on real phones the progress features that tests can't prove,
So that closed testers and launch users see numbers and notifications they can trust.
**Refs:** FR-PRG-2, FR-PRG-3, FR-PRG-5, FR-MSG-2, FR-MSG-3, FR-MSG-4, NFR-9, NFR-10, NFR-11 · **Priority:** Must · **Verify:** human-verify

**Acceptance Criteria:**

**Given** the latest `main` debug build installed on each device of the matrix (the owner's Oppo A96 plus the NFR-1 emulators; other makers optional via Firebase Test Lab), using the debug fire-now hook and manual date changes to build a week of history
**When** the owner runs the checklist
**Then** for each item the story file records pass/fail, device, Android version and date:
1. After three on-time test-free mornings (real, non-test alarms via the debug hook), Home's hero shows "3 days on time" and "Nothing paid this week. Keep it that way." above the reliability banner and the countdown.
2. A zero-snooze morning shows the streak number, "days in a row" and "Up on time." with the count-up, bounce, about 1.5 s of confetti and one success haptic, finishing within 3 s; a second on-time alarm the same day shows the text with no animation.
3. With "Remove animations" on, the celebration appears instantly and the haptic still fires.
4. A morning with one paid snooze (license tester) shows "You're up. That's what counts." with the amount paid, no animation; Home then shows "{paid} paid this week" with the Play-localized price, and Progress shows it under "Money paid" for this month.
5. "Done" on Success restores the screen brightness set before the alarm.
6. Progress matches a hand count from Day detail for current streak, best streak, on-time 7 d and 30 d, average time to up and the 30-day snoozes count; a Test alarm and a skipped occurrence (if Epic 7 skip ships) change none of them.
7. The calendar shows the correct shape and colour for On time, Snoozed, Missed and Test days, a first tap shows the label chip and a second opens Day detail, Day detail shows the fallback badge after a fallback check, and TalkBack reads "{weekday} {day}, {outcome}".
8. Day detail for a snoozed morning lists rings, snoozes, paid amount, check and time to up, and its morning timeline draws in with the events in order; a Missed morning shows no time up.
9. With the weekly summary on and the device clock set to Sunday 18:58, the notification arrives by 19:15 with the EXPERIENCE.md text, and tapping it opens Progress; with the toggle off, nothing arrives.
10. After changing the time zone, Home, Progress and the calendar recompute without restarting the app.
11. The Progress ring shows the last 30 mornings with today in the accent pill; tapping a dot shows its label chip, and tapping again or tapping the chip opens Day detail; the entry animation plays once and is instant with "Remove animations" on.
12. At 200% font size and with TalkBack on, Home hero, Progress tiles, ring dots and calendar cells are readable and reachable.
13. All copy seen matches EXPERIENCE.md and the owner-approved assumption strings (no em dashes, no currency symbols in resources, "Nothing paid" for zero) (FR-MSG-4).

**Given** any failed item
**When** the owner records it
**Then** each failure becomes a new bug story referencing this item, and this story stays open until all items pass or are explicitly waived by the owner in the story file
**And** automation never marks this story done

## Epic 7: Make it yours

Personal touches and flexibility: the user can record motivation messages and hear one after getting up (or mixed into the alarm), use their own audio file as an alarm sound, dismiss the alarm by photographing a spot far from bed (House Hunt, after Spike S3 decides it is accurate enough), print a QR code for the QR/Barcode check, and skip the next alarm from a pre-alarm notification. All media (recordings, custom sounds, House Hunt photos and embeddings) lives in credential-protected storage, is excluded from backup, and is replaced by the default built-in sound or Math before the first unlock after a reboot (AD-6, FR-ALM-11, Direct Boot substitution). Every chosen sound or recording that cannot play falls back to the default built-in sound during a ring; the alarm is never silent (FR-SND-5, NFR-2).

This epic is mostly Should and Could and is the first bucket to cut if time runs short. PRD cut order within it: all [Could] first (Stories 7.5, 7.10, 7.11), then House Hunt (Stories 7.6 to 7.9), then custom audio file (Story 7.4), then recordings (Stories 7.1 to 7.3). Each story works without the later ones. The `app.db` versions below (v10 to v15, continuing from v9 in Epic 4) assume every earlier Epic 7 story shipped; if a story is cut, each later schema story takes the next free version in story order, so versions never clash or skip.

Every UI story in this epic carries the two standing acceptance criteria from Epic 1, repeated in the story so the build loop can check them: (1) the `pps-design` Done checklist is copied into the story file with every item ticked; (2) all new user-facing strings live in Compose Multiplatform resources, match `EXPERIENCE.md > Voice and Tone > Key strings` verbatim where a key string exists, and pass `CopyRulesTest` (FR-MSG-4). A string that EXPERIENCE.md does not define is marked `(EXPERIENCE.md Key strings)` and listed for the owner.

### Story 7.1: Record and store motivation messages

As a user,
I want the app to record a short message in my own voice and keep it safely on my phone,
So that my evening self can talk to my morning self.
**Refs:** FR-SND-3, NFR-4, NFR-14, AD-1, AD-6, AD-12, AD-13 · **Priority:** Should · **Verify:** auto

**Acceptance Criteria:**

**Given** `:core`
**When** the recording domain is added
**Then** `Recording` holds `id` (UUID v4), `name`, `durationMs`, `fileName` and `createdAt`, and a `RecordingRepository` port (`observeAll(): Flow`, `get`, `save`, `delete`) and an `AudioRecorder` port (`start(): Outcome<RecordingSession, DomainError>`, `stop()`, `cancel()`, `level(): Flow<Float>`, `elapsed(): Flow<Duration>`) return `Outcome<T, DomainError>`, with `FakeRecordingRepository` and `FakeAudioRecorder` in `:testing`
**And** use cases `SaveRecording` (auto-name "Message {n}" (EXPERIENCE.md Key strings), n = highest existing number + 1), `DeleteRecording` and `ReRecord` (replaces the file of an existing recording, keeps its id so alarms that use it keep working) validate input and never throw

**Given** `:data`
**When** the table is added
**Then** `app.db` migrates from version 9 to 10 adding `recording` (`id` primary key, `name`, `duration_ms`, `file_name`, `created_at`) with exported schema and a migration test that preserves existing alarms and history
**And** the audio files live only in the credential-protected `filesDir/recordings/{id}.m4a` (never the device-protected context), written to a temporary file first and renamed on success so a crash never leaves a half-written file in place

**Given** `AndroidAudioRecorder` (`MediaRecorder`, AAC in MPEG-4, mono, 44.1 kHz, 64 kbit/s)
**When** a recording runs
**Then** it stops automatically at exactly 60 s (FR-SND-3), stops and keeps what was captured when the app goes to the background, audio focus is lost to a call, or the screen turns off, and discards captures shorter than 1 s with `DomainError.RecordingTooShort`
**And** a `MediaRecorder` error, missing microphone or storage-full condition maps to a `DomainError` and deletes the temporary file (Robolectric tests with a shadowed recorder)
**And** recordings are never logged, uploaded or sent to Crashlytics; only a duration and an error code may be logged

**Given** Android Auto Backup (NFR-14)
**When** the backup rules are updated
**Then** `dataExtractionRules` and `fullBackupContent` explicitly exclude the credential-protected `recordings/` folder (as well as `sounds/` and `househunt/`, created in later stories) while the `recording` table in `app.db` is still backed up, and the Robolectric XML test asserts these entries
**And** a restored `recording` row whose file does not exist is reported by `RecordingRepository` as `missing = true` (test), so later stories can show it and fall back
**And** `RECORD_AUDIO` is already in the permission allowlist and no new permission is added
**And** `./gradlew qualityGate` passes

### Story 7.2: Recordings screen and choosing a message per alarm

As a user,
I want to record, play back, re-record and delete messages, and choose one (or a random one) for each alarm,
So that the right voice greets me each morning.
**Refs:** FR-SND-3, FR-ALM-2, FR-ONB-2, FR-MSG-4, NFR-9, AD-11, AD-16, UX-DR54, UX-DR55, UX-DR56, UX-DR60, UX-DR64, UX-DR66, UX-DR67, UX-DR80, UX-DR84, UX-DR95 · **Priority:** Should · **Verify:** auto

**Acceptance Criteria:**

**Given** the Alarm editor
**When** it renders
**Then** its "Motivation" row (EXPERIENCE.md Key strings) opens the Motivation sub-screen with a row "Message" (EXPERIENCE.md Key strings) whose value is "None", the recording's name, or "Random" (EXPERIENCE.md Key strings), and tapping it opens the Recordings screen in selection mode
**And** the alarm stores `motivation` = `None` | `Recording(id)` | `Random` (default `None`) in the `alarm` table through the `app.db` migration from version 10 to 11 with exported schema and test, saved only on "Save" like every editor field, and `ConfigResolver` copies it into `SessionConfig`

**Given** the Recordings screen (the preview's round 3 `ui/recordings`, title "Recordings" per the EXPERIENCE.md IA)
**When** there are no recordings
**Then** it shows "Record a message for your morning self." (EXPERIENCE.md Key strings) above the `recorder`

**Given** the `recorder` (72 dp round accent record button with on-accent mic icon, "0:12 / 1:00" in `display` with tabular figures, level meter in `text-secondary`)
**When** the user taps record for the first time
**Then** `RECORD_AUDIO` is requested from this screen only (never at app start or in onboarding), and recording starts only after it is granted
**And** tapping again stops; at 60 s it stops by itself; afterwards "Play", "Re-record", "Save" and "Delete" (EXPERIENCE.md Component Patterns) are offered; "Save" adds the recording to the list; a capture under 1 s shows the snackbar "Too short. Try again." (EXPERIENCE.md Key strings)
**And** the record button's TalkBack label is "Start recording" / "Stop recording" (EXPERIENCE.md Key strings) and elapsed time is announced every 10 s while recording

**Given** the microphone permission is denied (once or permanently)
**When** the screen renders
**Then** it shows "Microphone is off. Turn it on in Settings." with a `button-text` "Fix" (EXPERIENCE.md Key strings) that opens the app's system settings page, the state is re-checked on return, and alarms, playback and existing recordings keep working
**And** the Epic 5 reliability checklist shows the microphone row only after the user has tapped record at least once (FR-ONB-2 "only when recording"), with status from the permission

**Given** the recordings list
**When** it renders
**Then** each row (56 dp) shows the name, duration in tabular figures, a 48 dp play/stop preview button (media usage, stops on leaving the screen or when another preview starts) and, in selection mode, a radio; a "Random" row sits at the top when there are at least two recordings
**And** a restored recording whose file is missing shows "File missing. Default sound will play." (EXPERIENCE.md State Patterns) and cannot be previewed

**Given** a recording used by one or more alarms
**When** the user deletes it
**Then** `dialog-confirm` asks "Delete this message? Alarms using it will play no message." with "Delete" (in `error` colour) and "Keep it" as the default dismiss (EXPERIENCE.md Key strings); confirming deletes the file and row and sets those alarms' motivation to `None` (or leaves `Random`, which falls back to `None` when fewer than one recording remains)
**And** editing motivation is not a weakening change under the commitment lock (it applies immediately) and the screen is unreachable during an active session (session lock)

**Given** Roborazzi, semantic and ViewModel tests with the Story 7.1 fakes
**When** they run
**Then** screenshots cover empty, recording in progress, after stop, list with selection and Random, missing file and microphone denied, in Light and Dark and at 200% font scale, with every target ≥ 48 dp
**And** the `pps-design` Done checklist is ticked in the story file, all strings are resources, key strings match EXPERIENCE.md verbatim and `CopyRulesTest` passes (FR-MSG-4)
**And** `./gradlew qualityGate` passes

### Story 7.3: Play my message "After I'm up"

As a user,
I want my chosen message to play on the Success screen once I finish the check,
So that the first thing I hear after the alarm is my own encouragement.
**Refs:** FR-SND-4, FR-SND-5, FR-ALM-11, FR-MSG-4, NFR-2, NFR-9, AD-2, AD-5, AD-6, AD-16, UX-DR23, UX-DR64, UX-DR78, UX-DR95 · **Priority:** Should · **Verify:** auto, plus (human-verify) audio on device in Story 7.12

**Acceptance Criteria:**

**Given** an alarm with motivation `Recording(id)` or `Random` and playback mode "After I'm up" (EXPERIENCE.md IA; the default mode, and the only one until Story 7.5)
**When** `ConfigResolver` freezes `SessionConfig` at `AlarmFired`
**Then** `Random` is resolved once to a concrete recording id using the session seed, so a restored session plays the same message (test); test sessions resolve and play the message too, so the user can hear it before a real morning
**And** before the first unlock (Direct Boot) the resolved motivation is `None` because credential-protected storage is unavailable (Direct Boot substitution), and it stays `None` for this session even after unlock

**Given** the AD-2 `Completed` one-shot effect "play motivation"
**When** `EffectRunner` executes it with a recording
**Then** the Success screen shows a `motivation-player` (`surface-sunrise` card, `rounded.md`, 48 dp play/pause and replay, accent progress bar) that starts playing automatically through a `MotivationPlayer` port (fake in `:testing`) with `USAGE_ALARM` at the user's alarm-stream volume, once, with no loop
**And** play/pause/replay have TalkBack labels "Play message", "Pause message" and "Replay message" (EXPERIENCE.md Key strings)
**And** "Done", Back-less exit via Home, or the activity stopping stops playback immediately; the zero-snooze celebration (Story 6.4) runs at the same time, unchanged

**Given** the recording file is missing, unreadable or errors during playback
**When** the effect runs
**Then** no player is shown or the player hides, nothing plays, the failure is logged without file paths, and the Success screen is otherwise unchanged (the alarm has already stopped, so the default-sound fallback does not apply after the check; it applies to "Mix into alarm" in Story 7.5)

**Given** a session that ended Missed or by a paid snooze
**When** it ends
**Then** no message plays (only `Completed` plays it)

**Given** Robolectric, Roborazzi and engine tests with fakes
**When** they run
**Then** they cover: recording plays on `Completed`; `Random` resolves to the same id after `ProcessRestored`; Direct Boot session plays nothing; missing file hides the player; Done stops playback; screenshots of Success with the player (zero-snooze and after-snooze) in Sunrise at 100% and 200% font scale
**And** the `pps-design` Done checklist is ticked in the story file, all strings are resources, key strings match EXPERIENCE.md verbatim and `CopyRulesTest` passes (FR-MSG-4)
**And** `./gradlew qualityGate` passes

### Story 7.4: Use my own audio file as an alarm sound

As a user,
I want to pick an audio file from my phone as my alarm sound,
So that I wake to something I chose, with the default sound as a safety net.
**Refs:** FR-SND-6, FR-SND-5, FR-SND-2, FR-ALM-11, FR-MSG-4, NFR-2, NFR-4, NFR-14, AD-5, AD-6, AD-12, UX-DR53, UX-DR64, UX-DR66, UX-DR67, UX-DR80 · **Priority:** Should · **Verify:** auto

**Acceptance Criteria:**

**Given** the editor's Sound sub-screen from Story 1.17
**When** it renders
**Then** after the built-in and system sections it shows a "Your files" section of imported sounds with source caption "Your file" and a `button-outlined` "Pick a file" (EXPERIENCE.md Key strings)

**Given** the user taps "Pick a file"
**When** the system document picker (`ACTION_OPEN_DOCUMENT`, `audio/*`) returns a URI
**Then** the app takes the persistable read permission, validates that the file is decodable audio with a duration of at least 1 s and a size of at most 20 MB (owner-approved default 2026-09-26), and copies it into credential-protected `filesDir/sounds/{id}.{ext}` so the alarm never depends on the original file (AD-6: custom sounds live in credential-protected storage); the persisted permission is then released
**And** the import records `custom_sound` (`id`, `display_name`, `file_name`, `duration_ms`, `created_at`) in `app.db` via the migration from version 11 to 12 with exported schema and test, and the new sound is selected as `SoundRef.File(id)`
**And** a non-audio, corrupt or DRM file shows the snackbar "This file can't be played. Pick another." and an oversized file "This file is too big. Pick one under 20 MB." (EXPERIENCE.md Key strings); nothing is copied or saved
**And** no storage or media permission is requested and the permission allowlist still passes (the picker needs none)

**Given** a `sound-row` for an imported file
**When** the user previews it
**Then** it plays with `USAGE_ALARM` at the alarm's volume and stops on leaving, exactly like built-in sounds (Story 1.17)

**Given** an alarm whose sound is `SoundRef.File(id)`
**When** it rings
**Then** `AlarmPlayer` loops the copied file; if the file is missing (for example after a backup restore, since `sounds/` is excluded from backup), unreadable, or errors at prepare or mid-ring, it switches to the default built-in sound within the same ring and logs the fallback without paths (FR-SND-5, NFR-2)
**And** before the first unlock the Direct Boot substitution plays the default built-in sound instead (credential storage unavailable), and the ring keeps the default sound after unlock
**And** the Sound sub-screen and editor show "File missing. Default sound will play." (EXPERIENCE.md State Patterns) for a missing file

**Given** loudness
**When** a user file is imported
**Then** the FR-SND-1 loudness script does not apply (it checks bundled files only), and gradual volume and the set volume still apply to it

**Given** Robolectric and Roborazzi tests
**When** they run
**Then** they cover: successful import copies bytes and saves the row; non-audio rejected; oversized rejected; missing file at ring → default sound; `MediaPlayer` error mid-ring → default sound; Direct Boot → default sound; picker screenshots with the "Your files" section, a missing file row and an error snackbar, in Light and Dark and at 200% font scale
**And** the `pps-design` Done checklist is ticked in the story file, all strings are resources, key strings match EXPERIENCE.md verbatim and `CopyRulesTest` passes (FR-MSG-4)
**And** `./gradlew qualityGate` passes

### Story 7.5: "Mix into alarm" motivation playback

As a user,
I want my message to alternate with the alarm sound while it rings,
So that my own voice pushes me out of bed.
**Refs:** FR-SND-7, FR-SND-5, FR-ALM-6, FR-ALM-11, FR-MSG-4, NFR-2, AD-2, AD-5, UX-DR38, UX-DR64 · **Priority:** Could · **Verify:** auto, plus (human-verify) audio on device in Story 7.12

**Acceptance Criteria:**

**Given** the editor "Motivation" section with a message selected
**When** it renders
**Then** a `segmented-control` offers "After I'm up" / "Mix into alarm" (EXPERIENCE.md IA labels), default "After I'm up", stored with the alarm (`alarm.motivation_mode`, added by the `app.db` migration from version 12 to 13 with exported schema and test) and frozen into `SessionConfig`

**Given** a pure `mixSchedule(elapsedInRing, soundBlock = 30 s, recordingDuration)` in core
**When** it is evaluated
**Then** it returns which source plays: the alarm sound for the first 30 s of each ring (so the volume ramp from FR-ALM-6 completes), then the recording once, then 30 s of alarm sound, then the recording, repeating until the ring ends (unit tests at 0 s, 29 s, 30 s, 30 s + duration, and after a grace window ends)

**Given** a ring with mode "Mix into alarm"
**When** `AlarmPlayer` runs it
**Then** it switches between the alarm sound and the recording per `mixSchedule`, both with `USAGE_ALARM` at the set volume, never leaving a silent gap longer than 250 ms between sources
**And** during a grace window everything is muted as usual; when grace ends, the schedule resumes with the alarm sound at full set volume (FR-SES-6)
**And** if the recording is missing, unreadable or errors, the player plays only the alarm sound for the rest of the session (never silent, FR-SND-5), and before the first unlock the Direct Boot substitution plays only the default sound
**And** "After I'm up" playback on Success does not also play when the mode is "Mix into alarm"

**Given** Robolectric tests with fake players and `FakeMonotonicClock`
**When** they run
**Then** they cover the alternation order, mute in grace, resume after grace, a recording error mid-ring switching to alarm sound only, and a Direct Boot session with no recording
**And** Roborazzi screenshots cover the editor with the mode control in Light and Dark and at 200% font scale
**And** the `pps-design` Done checklist is ticked in the story file, all strings are resources, key strings match EXPERIENCE.md verbatim and `CopyRulesTest` passes (FR-MSG-4)
**And** `./gradlew qualityGate` passes

### Story 7.6: Spike S3: House Hunt image matching

As the owner,
I want measured evidence of how well on-device image matching recognises the same spot and rejects others,
So that House Hunt ships only with a threshold that wakes people reliably, or is cut.
**Refs:** FR-PWK-6, FR-PWK-11, NFR-4, AD-9, AD-15; PRD §10 S3, §12 (House Hunt false rejects), Q10, Q11 · **Priority:** Should · **Verify:** human-verify

**Acceptance Criteria:**

**Given** MediaPipe tasks-vision 1.0.0 `ImageEmbedder` with a bundled model (MobileNet-V3 small and large embedder `.tflite` compared, loaded from assets, no network)
**When** the spike starts
**Then** the prototype and evaluation harness live only on branch `spike/s3-house-hunt` and are never merged to `main`
**And** the test photo set stays on the owner's machine and is never committed; only aggregate numbers go into the report

**Given** a photo set of at least 10 spots in at least 3 homes, each with 3 reference photos and test photos under 3 lighting conditions (daylight, lamp, dim morning), 3 angles (same, ±15°, ±30°) and with small changes (a towel moved, a person's hand in frame)
**And** impostor photos: other spots in the same room, other rooms, random internet-style photos, and a photo of the reference photo shown on a second phone and printed on paper
**When** the harness computes cosine similarity between each test photo and the best of the 3 references for each model
**Then** `docs/spikes/S3.md` records per model: the false-reject rate (genuine photos below threshold) and false-accept rate (impostors at or above threshold) across thresholds 0.50 to 0.95 in 0.05 steps, embedding time and model size on the budget device and a Pixel, and memory use
**And** it reports separately the false-accept rate for photo-of-a-photo attempts (a known limitation; PRD Q11)

**Given** the targets (owner-approved default 2026-09-26): false-reject rate per attempt ≤ 10% (so 5 attempts before the fallback link almost always succeed), false-accept rate for other rooms and random photos ≤ 1%, match time ≤ 500 ms on the budget device
**When** the owner writes the Decision section
**Then** it states Go or No-go, the chosen model, one threshold per difficulty (Easy, Medium, Hard) as core config values, the number of reference photos (1 to 3 of one spot) and a count of 1 spot per check (resolving the House Hunt part of PRD Q10)
**And** on No-go, FR-PWK-6 is cut or downgraded through `bmad-correct-course`, Stories 7.7 to 7.9 are dropped, and House Hunt stays hidden from check pickers
**And** any MediaPipe usage logging or network behaviour found is recorded for the Data safety form (AD-15)

**Given** the spike checklist
**When** it is complete
**Then** the story file records pass/fail per question, device, Android version and date; automation never marks this story done
**And** `./gradlew qualityGate` passes on `main` with `docs/spikes/S3.md` committed

### Story 7.7: House Hunt check plugin and on-device matcher

As a user,
I want the app to decide on the phone whether my photo shows my registered spot,
So that House Hunt works offline and my photos never leave my phone.
**Refs:** FR-PWK-6, FR-PWK-1, FR-PWK-3, FR-PWK-11, NFR-4, NFR-11, NFR-12, AD-2, AD-6, AD-9, AD-12, AD-15 · **Priority:** Should · **Verify:** auto

**Acceptance Criteria:**

**Given** Spike S3 decided Go
**When** `CheckType.HouseHunt` is added to the sealed `CheckType` in core with the Epic 3 AD-9 contract
**Then** `generate(seed, difficulty, count)` returns a `Puzzle` naming the alarm's reference set and the threshold for the difficulty from core config (values from `docs/spikes/S3.md`), count fixed at 1, and `validate(puzzle, position, answer)` returns `Correct` when the answer's best similarity ≥ the threshold and `Wrong` otherwise (table tests at threshold − 0.01, exactly threshold, + 0.01)
**And** the thresholds and model name are constants in `core.checks.househunt` with a test that they match the S3 Decision values

**Given** the AD-9 sensor-check path
**When** the House Hunt check screen submits a capture
**Then** `CheckAnswerSubmitted(PhotoCapture(tempRef))` produces the effect `MatchImage(tempRef, referenceSetId)`; the `ImageMatcher` port returns `ImageMatchCompleted(similarity)` or `ImageMatchFailed(reason)` as events, and the engine validates the similarity through `validate`
**And** both events and their AD-2 v0.3 rows already exist from Story 1.11 (tested there with `FakeCheck`); this story adds no row, and the Story 1.11 table-coverage test still passes with the real House Hunt validator
**And** each non-match counts as a failed attempt for the Epic 3 `FallbackPolicy` (link after 5 failed attempts); `ImageMatchFailed` because the model cannot load counts as "camera or check unavailable" and allows the fallback immediately (FR-PWK-11)

**Given** `:data` and `:androidApp`
**When** storage and the adapter are added
**Then** `app.db` migrates from version 13 to 14 adding `reference_media` (`id`, `check_config_id`, `file_name`, `embedding_file_name`, `created_at`, `needs_retake`) linked to the alarm's House Hunt `CHECK_CONFIG` row, with exported schema and migration test
**And** photos (JPEG, longest side 1024 px) and their embeddings live only in credential-protected `filesDir/househunt/`, excluded from backup (rules from Story 7.1), and wake-time captures are written to the cache, deleted right after matching, and never logged, uploaded or sent to Crashlytics (test asserts the capture file is gone after `ImageMatchCompleted`)
**And** `MediaPipeImageMatcher` embeds with the S3 model bundled in assets, computes cosine similarity against stored reference embeddings (best of 1 to 3), runs off the main thread, and maps every MediaPipe exception to `ImageMatchFailed` (AD-12); `FakeImageMatcher` returns programmable similarities
**And** the MediaPipe coordinates are already in `config/dependency-allowlist.txt` (or added in this change after review), and a test asserts the model is loaded from assets with no network call

**Given** Direct Boot (before first unlock)
**When** `ConfigResolver` freezes a plan containing House Hunt
**Then** House Hunt is replaced by Math (Direct Boot substitution from Epic 2) because the photos are in credential-protected storage (test)

**Given** engine tests with `FakeImageMatcher`
**When** they run
**Then** they cover: match on first try completes the step; 5 non-matches unlock the fallback link; a matcher failure allows the fallback immediately; a purchase granted while matching discards the pending match result (it arrives in `Snoozed` and is ignored and logged)
**And** Kover shows `core.checks` ≥ 90% line coverage
**And** `./gradlew qualityGate` passes

### Story 7.8: House Hunt registration and re-take after restore

As a user,
I want to photograph one to three reference shots of a spot far from my bed and test a match before saving,
So that I know the check will accept me tomorrow morning.
**Refs:** FR-PWK-6, FR-PWK-2, FR-PWK-12, FR-ONB-2, NFR-4, NFR-14, FR-MSG-4, AD-6, AD-9, AD-16, UX-DR20, UX-DR21, UX-DR29, UX-DR33, UX-DR60, UX-DR64, UX-DR66, UX-DR67 · **Priority:** Should · **Verify:** auto, plus (human-verify) camera on device in Story 7.12

**Acceptance Criteria:**

**Given** the Epic 3 Check picker
**When** Spike S3 decided Go
**Then** a House Hunt `check-type-card` appears with the name "House Hunt", the line "Photograph a spot far from your bed." (EXPERIENCE.md Key strings) and the camera warning "Needs the camera. If it can't be used, you'll get a fallback check." (EXPERIENCE.md Component Patterns)
**And** selecting it requests `CAMERA` only if not granted (the reliability checklist camera row from Epic 5 then appears), and its Check setup offers difficulty Easy / Medium / Hard

**Given** the House Hunt registration screen (from Check setup, `top-app-bar` "House Hunt photos" (EXPERIENCE.md Key strings))
**When** it opens
**Then** it shows the instruction "Take 1 to 3 photos of one spot far from your bed." (EXPERIENCE.md Key strings), the `viewfinder` (starts on open), the 72 dp `shutter` with TalkBack label "Take photo" (EXPERIENCE.md Key strings), and thumbnails of taken photos (`rounded.sm`) each with a 48 dp "Remove" action (EXPERIENCE.md Key strings)
**And** after 3 photos the shutter is disabled; each photo is saved and embedded through the Story 7.7 adapter; an embedding failure shows "Couldn't use that photo. Try again." (EXPERIENCE.md Key strings) and keeps the photo out of the set

**Given** at least one reference photo
**When** the user taps "Test match" (EXPERIENCE.md Key strings) and takes a new photo
**Then** it is matched with the Medium threshold (or the chosen difficulty) and shows "Matched" or "Doesn't match yet. Try the same angle." (EXPERIENCE.md Component Patterns); the test photo is deleted after matching (FR-PWK-12)

**Given** no reference photo
**When** the user tries to save the check
**Then** Save is blocked with "Take at least one photo." (EXPERIENCE.md Key strings)

**Given** camera permission is denied or the camera fails to start
**When** the registration screen opens
**Then** it shows "Camera isn't available." with a "Fix" `button-text` to the app's settings (EXPERIENCE.md Key strings), and House Hunt cannot be saved until photos exist

**Given** a backup restore on a new phone (the `reference_media` rows come back, the photos do not, NFR-14)
**When** the app starts or `rescheduleAll()` runs after the restore
**Then** a `ReferenceMediaIntegrity` check sets `needs_retake` on every row whose photo or embedding file is missing (test)
**And** Home shows the info variant of `banner-warning` "House Hunt photos weren't restored. Retake them." with "Retake" (EXPERIENCE.md Key strings), dismissible, which opens the registration screen for the first affected alarm and clears once every House Hunt check has photos
**And** until photos are retaken, `ConfigResolver` replaces House Hunt with Math at session start and the wake screen shows `note-inline` "Your House Hunt photos are missing, so today's check is Math." (EXPERIENCE.md Key strings)

**Given** commitment lock (AD-16)
**When** the user removes House Hunt or lowers its difficulty within 8 h of the alarm
**Then** it is stored as a weakening `PendingChange` per Epic 4; adding photos or raising difficulty applies immediately; the screen is unreachable during a session

**Given** Roborazzi, semantic and ViewModel tests with `FakeImageMatcher` and a fake camera
**When** they run
**Then** screenshots cover the picker card, registration with 0, 1 and 3 photos, a test match success and failure, camera unavailable, and the Home restore banner, in Light and Dark and at 200% font scale, with every target ≥ 48 dp
**And** the `pps-design` Done checklist is ticked in the story file, all strings are resources, key strings match EXPERIENCE.md verbatim and `CopyRulesTest` passes (FR-MSG-4)
**And** `./gradlew qualityGate` passes

### Story 7.9: House Hunt check on the wake screen

As a user,
I want to dismiss my alarm by walking to my spot and taking its photo,
So that I'm out of bed before the alarm stops.
**Refs:** FR-PWK-6, FR-PWK-9, FR-PWK-10, FR-PWK-11, FR-MSG-4, NFR-2, NFR-4, NFR-9, AD-2, AD-5, AD-9, UX-DR16, UX-DR20, UX-DR21, UX-DR22, UX-DR64, UX-DR66, UX-DR67, UX-DR78, UX-DR84 · **Priority:** Should · **Verify:** auto, plus (human-verify) in Story 7.12

**Acceptance Criteria:**

**Given** a session whose current check step is House Hunt
**When** the check screen renders (Sunrise tokens)
**Then** it shows the `countdown-ring` while grace runs, the `viewfinder` with the 72 dp ghost thumbnail of the first reference photo top-left (`rounded.sm`), a 48 dp torch toggle, the 72 dp `shutter` centred in the thumb zone, and the check footer with `button-snooze` (Epic 4 rules) at the bottom
**And** the camera starts on screen open, and the screen never shows a loading state before the first frame (the viewfinder area shows its frame colour until the preview arrives)

**Given** the user taps the shutter
**When** the capture is matched
**Then** input is ignored until the result arrives, the shutter shows a progress state announced as "Checking" (EXPERIENCE.md Key strings), and the result reads "Matched" (step completes) or "Doesn't match yet. Try the same angle." (EXPERIENCE.md Component Patterns) with an error haptic, counting a failed attempt
**And** a match result that arrives after the step already ended (snooze granted, grace expired into Loud, or a restore) is handled by the engine rules, never by the UI

**Given** 5 failed matches in this session, or the camera permission is denied, the camera fails (CameraX error or no frame within 5 s) or the matcher cannot load
**When** the condition occurs
**Then** the `fallback-link` "Can't do this check?" appears (after 5 failures), or immediately with "Camera isn't available. Pick a fallback check." (EXPERIENCE.md Key strings), opening the Epic 3 Fallback check picker; the alarm keeps ringing with no new grace window, and the fallback is offered once per session (FR-PWK-11)

**Given** grace ends while the user is framing the photo
**When** `GraceElapsed` fires
**Then** the alarm returns at full set volume, "Time's up. Alarm's back on until you finish." shows, and the viewfinder keeps running with progress kept (FR-PWK-9)

**Given** a snooze is granted during the check
**When** `PurchaseGranted` arrives
**Then** the camera stops, pending captures are deleted, and the re-ring starts a fresh House Hunt step (FR-PWK-10)

**Given** TalkBack
**When** the House Hunt screen is focused
**Then** initial focus is the instruction, the shutter reads "Take photo", results are announced politely, and the fallback link is reachable in one swipe from the shutter

**Given** Robolectric, Roborazzi and instrumented tests with a fake camera provider and `FakeImageMatcher`
**When** they run
**Then** they cover grace running, grace expired, matching, matched, not matched, fallback after 5, camera unavailable immediately, and snooze granted mid-check, with Sunrise screenshots at 100% and 200% font scale showing the shutter and snooze in the thumb zone
**And** an instrumented Gradle Managed Device test with the emulator camera completes a House Hunt step using `FakeImageMatcher` returning a match
**And** the `pps-design` Done checklist is ticked in the story file, all strings are resources, key strings match EXPERIENCE.md verbatim and `CopyRulesTest` passes (FR-MSG-4)
**And** `./gradlew qualityGate` passes

### Story 7.10: Printable QR code for the QR/Barcode check

As a user without a handy barcode,
I want the app to make a QR code I can print and stick far from my bed,
So that I can use the QR check without hunting for a product.
**Refs:** FR-PWK-13, FR-PWK-7, FR-MSG-4, NFR-4, AD-9, AD-15, UX-DR60, UX-DR64, UX-DR66, UX-DR67 · **Priority:** Could · **Verify:** auto, plus (human-verify) print and scan in Story 7.12

**Acceptance Criteria:**

**Given** the Epic 3 QR registration screen
**When** it renders
**Then** besides scanning, it offers a `button-outlined` "Make a printable QR" (EXPERIENCE.md Key strings)

**Given** the user taps it
**When** the code is generated
**Then** core creates a payload `pps:{random UUID v4}` (no personal data, no alarm details), it is registered as that alarm's QR/Barcode code exactly as a scanned code would be, and a QR image (error correction M, at least 4-module quiet zone) is rendered with ZXing core (ZXing core 3.5.3 is in the Architecture Stack v0.3; add it to `config/dependency-allowlist.txt` in this story)
**And** a preview shows the code with the hint "Stick it somewhere far from your bed." (EXPERIENCE.md Key strings)

**Given** the preview
**When** the user taps "Print or save as PDF" (EXPERIENCE.md Key strings)
**Then** an A4 / Letter PDF built with `PdfDocument` (QR 6 cm square, centred, with the line "Scan this to stop your alarm." (EXPERIENCE.md Key strings)) is sent to Android `PrintManager`, where the user can print or save as PDF; no file is kept by the app afterwards

**Given** a generated code
**When** it is scanned by the Epic 3 QR check (ML Kit) on screen and on a 6 cm print
**Then** it validates as the registered code (instrumented test renders the bitmap and decodes it with ML Kit; unit test round-trips the payload with ZXing)
**And** generating a new code replaces the old one after a `dialog-confirm` "Replace your QR code? The old one stops working." / "Replace" / "Keep it" (EXPERIENCE.md Key strings); replacing a code is not a weakening change

**Given** Roborazzi and semantic tests
**When** they run
**Then** screenshots cover the QR registration with the new button, the preview and the replace dialog in Light and Dark and at 200% font scale, and the QR image has a content description "QR code for your alarm" (EXPERIENCE.md Key strings)
**And** the `pps-design` Done checklist is ticked in the story file, all strings are resources, key strings match EXPERIENCE.md verbatim and `CopyRulesTest` passes (FR-MSG-4)
**And** `./gradlew qualityGate` passes

### Story 7.11: Skip the next alarm from a pre-alarm notification

As a user whose plans changed,
I want an optional notification before my alarm that lets me skip that one morning,
So that I don't have to turn the alarm off and remember to turn it back on.
**Refs:** FR-ALM-10, FR-PRG-1, FR-SES-3, FR-MSG-4, NFR-8, AD-3, AD-4, AD-16, AD-17, AD-18, UX-DR31, UX-DR55, UX-DR84 · **Priority:** Could · **Verify:** auto, plus (human-verify) in Story 7.12

**Acceptance Criteria:**

**Given** the Alarm editor
**When** it renders
**Then** it has a `switch` "Offer to skip 2 h before" (EXPERIENCE.md Key strings), default off, stored per alarm (`alarm.offer_skip`, plus `alarm.skipped_date` used below, both added by the `app.db` migration from version 14 to 15 with exported schema and test)
**And** turning it on is a weakening change under the commitment lock: within 8 h of that alarm it is saved as a `PendingChange` effective after the next occurrence, with the Epic 4 note "Saved. Takes effect after tomorrow's {time} alarm." (EXPERIENCE.md Key strings); turning it off applies immediately

**Given** an enabled alarm with the switch on (effective)
**When** its next occurrence is computed by `rescheduleAll()` or a save
**Then** a `BackgroundWork` one-time request `pre-alarm-{alarmId}` is enqueued for occurrence − 2 h (REPLACE); if that time has already passed but the occurrence has not, the notification is posted at once
**And** the notification (channel "Upcoming alarms" (EXPERIENCE.md Key strings), default importance, monochrome icon) has title "{time} alarm" and text "Rings in {hours} h {minutes} min" (EXPERIENCE.md Key strings), with one action "Skip this one" (EXPERIENCE.md Key strings)
**And** it is cancelled when the occurrence starts, the alarm is edited, disabled or deleted, or the switch is turned off; a late worker run after the occurrence posts nothing

**Given** the user taps "Skip this one"
**When** no session is active
**Then** the app opens a `dialog-confirm` "Skip your {time} alarm? This is logged." with "Skip" and "Keep it" as the default dismiss (EXPERIENCE.md Key strings) (skipping is allowed inside the lock window like disabling, confirmed and logged, PRD §6.2)
**And** confirming stores the skipped local date on the alarm, cancels that occurrence's system alarm, schedules the following occurrence (a one-time alarm is disabled instead), and `SessionRecorder` writes one history row with a new session id, `scheduled_at` = the skipped occurrence, `first_ring_at` null and outcome Skipped (the same v15 migration makes `session_history.first_ring_at` nullable, which Story 1.13 did not, with a test preserving existing rows); the snackbar reads "Skipped. No charge." (EXPERIENCE.md Key strings)
**And** skipping is free and the Skipped row never counts toward rates or streaks (Story 6.1)

**Given** a skipped occurrence that has not happened yet
**When** Home renders the alarm
**Then** its `card-alarm` caption reads "Next ring skipped" (EXPERIENCE.md Key strings), the countdown uses the following occurrence, and the card menu offers "Don't skip" (EXPERIENCE.md Key strings), which re-arms the occurrence and asks `SessionRecorder` to discard that Skipped row (strengthening, always allowed)

**Given** a session is active when the action is tapped
**When** the app opens
**Then** only `panel-session-in-progress` shows (FR-SES-3) and nothing is skipped; the notification stays until the occurrence

**Given** edge cases
**When** tests run with `FakeClock`, `FakeTimeZoneProvider`, `FakeAlarmScheduler` and `FakeBackgroundWork`
**Then** they cover: notification time at occurrence − 2 h; enqueue when the window already started; a time-zone change after skipping still skips the occurrence on the same local date; a DST day; skipping a one-time alarm disables it; the same occurrence cannot be skipped twice (idempotent); "Don't skip" restores the system alarm and removes the row; skip during a session is ignored
**And** Roborazzi screenshots cover the editor switch, the confirm dialog and a card with "Next ring skipped" in Light and Dark and at 200% font scale
**And** the `pps-design` Done checklist is ticked in the story file, all strings are resources, key strings match EXPERIENCE.md verbatim and `CopyRulesTest` passes (FR-MSG-4)
**And** `./gradlew qualityGate` passes

### Story 7.12: Epic 7 device verification checklist

As the owner,
I want to confirm on real phones the recordings, custom sounds, House Hunt, printed QR and skip features,
So that the personal touches work and never make an alarm silent.
**Refs:** FR-SND-3, FR-SND-4, FR-SND-5, FR-SND-6, FR-SND-7, FR-PWK-6, FR-PWK-11, FR-PWK-13, FR-ALM-10, FR-ALM-11, FR-MSG-4, NFR-2, NFR-4, NFR-9, NFR-14 · **Priority:** Must · **Verify:** human-verify

**Acceptance Criteria:**

**Given** the latest `main` debug build installed on each device of the matrix (the owner's Oppo A96 plus the NFR-1 emulators; other makers optional via Firebase Test Lab); items for stories that were cut are marked "cut" instead of pass/fail
**When** the owner runs the checklist
**Then** for each item the story file records pass/fail, device, Android version and date:
1. The microphone permission is asked only on the first record tap; a 10 s message records, plays back, re-records and saves; recording stops by itself at 1:00; denying shows "Microphone is off. Turn it on in Settings." and "Fix" opens the right page.
2. An alarm with "After I'm up" plays the message on Success after the check, audible with media volume at 0, and "Done" stops it; "Random" picks among saved messages.
3. "Mix into alarm" alternates 30 s of alarm sound with the message, is muted during grace, and returns at full volume after grace.
4. An imported MP3 from Downloads rings as the alarm; after deleting the original from Downloads it still rings; a corrupted file is rejected at import.
5. After an overnight reboot before first unlock, an alarm set to a custom file and a message plays the default sound, shows the Direct Boot notice, and plays no message.
6. After a backup restore to a second phone (or `adb shell bmgr restore`), the custom sound and messages show "File missing. Default sound will play.", the alarm rings with the default sound, and House Hunt shows the retake banner and uses Math until photos are retaken.
7. House Hunt registration takes 3 photos of a bathroom sink; "Test match" says "Matched" from the same spot in lamp light and "Doesn't match yet. Try the same angle." from the bedroom.
8. A real House Hunt morning completes by photographing the spot within 3 tries on each device; covering the camera for 5 attempts shows "Can't do this check?", and revoking camera permission shows the fallback immediately.
9. Photographing the reference photo shown on another phone: result recorded (known limitation, PRD Q11).
10. A printed QR from "Make a printable QR" scans on the QR check from 30 cm in normal room light.
11. With "Offer to skip 2 h before" on, the notification arrives within 15 minutes of occurrence − 2 h (Doze allowed), "Skip this one" → "Skip" prevents the ring, Day detail and the calendar show Skipped, and streak and rates are unchanged; "Don't skip" restores it.
12. No recording, photo or file path appears in Crashlytics logs from these tests.
13. At 200% font size and with TalkBack on, the recorder, Recordings list, House Hunt registration and check screens are usable.
14. All copy seen matches EXPERIENCE.md and the owner-approved assumption strings (FR-MSG-4).

**Given** any failed item
**When** the owner records it
**Then** each failure becomes a new bug story referencing this item, and this story stays open until all items pass or are explicitly waived by the owner in the story file
**And** automation never marks this story done

## Epic 8: Launch on Google Play

The app is live on Google Play: the store listing, screenshots and graphics are made from real screens, every App content declaration (Data safety, content rating, target audience 18+, exact alarm, full-screen intent, foreground service with demo video) is complete and matches what the app does, the EU DSA trader launch blocker is resolved, releases are signed through Play App Signing and uploaded from a git tag, closed-test feedback is triaged to exit criteria, and production rolls out 10% → 50% → 100% behind Android vitals and Crashlytics gates, followed by a weekly monitoring routine. Play Console setup was done in Story 1.4 and the closed test has been running since the end of Epic 5; this epic finalises the preliminary answers entered then for the full feature set (including whichever Epic 7 features shipped).

Most stories here are owner tasks in Play Console and are tagged `human-verify`: each lists objective completion criteria, the owner records pass/fail, date and evidence in the story file, and automation never marks them done. Where a story commits text or assets to the repo, automated checks verify them and `./gradlew qualityGate` must pass. PRD launch blockers (§13) are Stories 8.3, 8.5, 8.6, 8.8, 8.9 and 8.10; production submission (Story 8.12) is not allowed until all are done.

### Story 8.1: Store listing text

As the owner,
I want an honest, policy-safe store listing that explains pay-to-snooze and the free way to wake up,
So that users know exactly what they are installing and reviewers see nothing misleading.
**Refs:** FR-MSG-1, FR-MSG-4, NFR-5, NFR-13, AD-14; PRD §5 principles, §12 policy risks, §13 · **Priority:** Must · **Verify:** auto, plus (human-verify) Play Console entry

**Acceptance Criteria:**

**Given** `docs/store/en-US/`
**When** the listing is written
**Then** it contains `title.txt` (≤ 30 characters, the app name decided in PRD Q8 / `docs/decisions/package-id.md`), `short-description.txt` (≤ 80 characters) and `full-description.txt` (≤ 4,000 characters), all owner-approved [ASSUMPTION: listing copy is owner-approved, not an EXPERIENCE.md key string]
**And** the full description states plainly: snoozing costs real money through Google Play, the first snooze costs the base fee the user sets and each further snooze in the same morning costs more; waking up with a check is always free and works offline; the alarm keeps ringing until the check is done or a snooze is paid, the phone stays usable (calls, other apps, emergency calls), and the other ways to stop it (the FR-ONB-5 disclosure); no account, data stays on the phone; the app is for adults (18+)
**And** it lists the check types that actually ship (House Hunt only if Epic 7 Stories 7.6 to 7.9 shipped) and never claims features that were cut

**Given** a `StoreListingTest` in the `qualityGate`
**When** it runs over `docs/store/**`
**Then** it fails on a length limit breach, on an em dash, on the banned words from `CopyRulesTest`, on emoji, on a hard-coded price or currency symbol, on superlatives or ranking claims ("best", "#1", "top", "free alarm" without the snooze-cost statement), on invented statistics (any percentage or "x times" claim), and on keyword lists (the same word more than 5 times), per the Play metadata policy
**And** the test has one failing and one passing fixture per rule

**Given** Play Console > Grow > Store presence > Main store listing
**When** the owner enters the committed text and sets the app category (Tools or Lifestyle, recorded in the story), contact email, and privacy policy URL (GitHub Pages from Epic 5, returning HTTP 200)
**Then** the Console shows no listing errors and the text matches the committed files exactly (owner pastes a diff-free check result in the story file)
**And** the story file lists each item with pass/fail, date and notes; automation never marks the Console part done
**And** `./gradlew qualityGate` passes

### Story 8.2: Screenshots, feature graphic and icon from real screens

As the owner,
I want store graphics generated from the real app with realistic demo data,
So that the listing shows exactly what users get and can be regenerated after any UI change.
**Refs:** NFR-5, NFR-9, NFR-10, AD-10, AD-14, UX-DR2, UX-DR77 · **Priority:** Must · **Verify:** auto, plus (human-verify) Play Console upload

**Acceptance Criteria:**

**Given** a debug-only `DemoDataSeeder` (never in release; the release-manifest test from Story 1.18 extended to its class) with three alarms, 8 weeks of history matching F9, and cached USD prices
**When** `./gradlew captureStoreScreenshots` runs Roborazzi (or the OQ-3 fallback) at 1080 × 1920 px
**Then** it writes to `docs/store/screenshots/phone/` at least 4 and at most 8 PNGs, in this order: Ringing screen (Sunrise) with "I'm up" and "Snooze · $1"; Snooze confirm sheet; a check screen with the grace countdown; Home with the hero card and alarm list (Dark); Progress (Light); the Reliability checklist
**And** the screens may be captured from the design-preview deep links with demo data or from the real app, and the store name shown is "Yawn & Pawn"
**And** the images are the real composables with the committed tokens and strings, with no device frames, no added marketing text over the UI, no fake system notifications, and prices shown exactly as Play formats them in en-US

**Given** the feature graphic and icon
**When** `captureStoreScreenshots` runs
**Then** it renders `docs/store/feature-graphic.png` at 1024 × 500 px from a debug-only composable using the Sunrise tokens, the app name and a crop of the real ringing screen (no gradient text, no glow, UX-DR77), and exports `docs/store/icon-512.png` at 512 × 512 px from the adaptive launcher icon (which is added in this story if earlier epics left the default icon; icon concept per EXPERIENCE.md D1, owner-approved)

**Given** a `StoreAssetsTest` in the `qualityGate`
**When** it inspects `docs/store/`
**Then** it fails unless every screenshot is PNG or JPEG, 24-bit with no alpha, each side between 320 and 3,840 px, the long side ≤ 2 × the short side, at least 4 are ≥ 1080 px on the short side; the feature graphic is exactly 1024 × 500 with no alpha; the icon is exactly 512 × 512, 32-bit PNG, ≤ 1 MB
**And** the images are regenerated and the test re-run whenever a UI story changes a shown screen (documented in `docs/store/README.md`)

**Given** Play Console > Main store listing > Graphics
**When** the owner uploads the icon, feature graphic and screenshots
**Then** the Console accepts them with no warnings and the listing preview shows them in order
**And** the story file lists each item with pass/fail, date and notes; automation never marks the Console part done
**And** `./gradlew qualityGate` passes

### Story 8.3: Data safety form (launch blocker)

As the owner,
I want the Data safety form to match exactly what the app and its libraries collect and share,
So that users are told the truth and Play does not reject or pull the app.
**Refs:** NFR-4, NFR-14, NFR-15, FR-ONB-6, FR-SET-6, AD-6, AD-15; PRD §9 Play Console declarations, §13 launch blockers · **Priority:** Must · **Verify:** human-verify

**Acceptance Criteria:**

**Given** the release build's resolved runtime dependencies (`config/dependency-allowlist.txt`), merged release manifest and the privacy policy from Epic 5
**When** the owner prepares `docs/store/data-safety.md`
**Then** it records every Play Data safety question with the answer and its source (requirement, dependency, or official Google documentation URL with the date read), covering at least:
1. Crashlytics (always on): crash logs and diagnostics collected, app instance / Crashlytics installation ID as "Device or other IDs", purpose App functionality / Analytics, not shared, encrypted in transit, not optional.
2. Firebase Analytics (opt-in, default off): app interactions (`session_outcome`, `snooze_count`, `check_type` only) and app instance ID, purpose Analytics, collection optional, not shared, no advertising ID.
3. Purchases: handled by Google Play Billing; purchase history stays on the device (not collected by the developer).
4. Photos (House Hunt), audio (recordings, custom sounds), alarms, settings and history: processed and stored only on the device, not collected, not shared.
5. ML Kit barcode scanning and MediaPipe: any usage or diagnostics logging these libraries send, as found in their documentation and the Spike S3 notes, declared accordingly.
6. Android Auto Backup of alarms, settings and history to the user's own Google account: answered per Google's current Data safety guidance for platform backup, with the citation recorded (NFR-14).
7. Data deletion: in-app "Delete all data" (Epic 5) for on-device data; the support email for Crashlytics / Analytics deletion requests.
**And** the document states the privacy policy sections that disclose each item, and any mismatch found between the policy, the app and the form becomes a fix to the policy page or a bug story before this story closes

**Given** Play Console > Policy > App content > Data safety
**When** the owner submits the form from `docs/store/data-safety.md`
**Then** the Console shows the Data safety section as complete with no warnings, and the store listing preview shows the expected "Data safety" summary (screenshot attached in the story file)
**And** the story file lists each item with pass/fail, date and notes; automation never marks this story done
**And** `./gradlew qualityGate` passes on `main` with `docs/store/data-safety.md` committed

### Story 8.4: Content rating, target audience 18+ and other App content declarations

As the owner,
I want the content rating, target audience and remaining App content declarations answered correctly,
So that the app is rated honestly, kept away from children's surfaces, and not blocked in review.
**Refs:** NFR-5; PRD §9, §12 (minors pay, policy rejection), Q6 · **Priority:** Must · **Verify:** human-verify

**Acceptance Criteria:**

**Given** Play Console > Policy > App content > Content rating
**When** the owner completes the IARC questionnaire (category "Utility, Productivity, Communication, or Other")
**Then** the answers are recorded in `docs/store/app-content.md`: no violence, sexual content, profanity, drugs, or user-to-user communication; no gambling or simulated gambling (a snooze is a fixed-price digital purchase with no chance element); digital purchases: yes; location sharing: no
**And** the issued rating certificate (expected low age rating with "In-App Purchases") is recorded with its IARC id and date; any unexpected rating is escalated to the owner before continuing

**Given** Target audience and content
**When** the owner sets it
**Then** the only selected age group is 18 and over, "appeals to children" is No, and the app is not opted into Designed for Families (NFR-5, Q6)

**Given** the other App content declarations
**When** the owner completes them
**Then** `docs/store/app-content.md` records: Ads = No; App access = all functionality available without login, with reviewer instructions (how to create an alarm, run "Test alarm" to see the ringing screen without payment, where the snooze purchase appears and that it is a real consumable at the base fee); Government app = No; Financial features = none; Health = none; News = No; privacy policy URL set
**And** the Console "App content" page shows every section complete (screenshot in the story file)
**And** the story file lists each item with pass/fail, date and notes; automation never marks this story done
**And** `./gradlew qualityGate` passes on `main` with `docs/store/app-content.md` committed

### Story 8.5: Exact alarm, full-screen intent and foreground service declarations with demo video (launch blocker)

As the owner,
I want the sensitive-permission declarations filed with a demo video that shows the free path and a phone that stays usable,
So that Play approves the alarm permissions and does not see the app as holding the phone hostage.
**Refs:** FR-ALM-3, FR-ALM-4, FR-ALM-12, FR-SES-4, FR-SES-9, FR-RNG-9, NFR-13, AD-4, AD-5; PRD §9 Play Console declarations, §12, §13 · **Priority:** Must · **Verify:** human-verify

**Acceptance Criteria:**

**Given** the release build's merged manifest (checked by the Story 1.2 permission allowlist)
**When** the owner prepares `docs/store/declarations.md`
**Then** it lists each declaration with its text: `USE_EXACT_ALARM` (API 33+) for an alarm clock as core functionality and `SCHEDULE_EXACT_ALARM` limited to API ≤ 32; `USE_FULL_SCREEN_INTENT` for the alarm use case; the foreground service type chosen in Spike S2 (`docs/spikes/S2.md`: `mediaPlayback` or `systemExempted`) with the task ("plays the user's alarm on the alarm stream until they finish their check or pay to snooze"), what the user sees (ongoing notification), and the impact if deferred (the alarm would stop)
**And** it confirms the merged manifest has no `SYSTEM_ALERT_WINDOW`, accessibility service, device admin, lock-task, `READ_PHONE_STATE`, `READ_MEDIA_*` or storage permission (quoting the allowlist check output)

**Given** a demo video (screen recording of a release-signed build from the internal track, 60 to 180 s, uploaded as an unlisted YouTube video)
**When** the owner records it on a real phone
**Then** it shows in order: the alarm ringing over the lock screen; "I'm up" and the check shown first and completed for free; a second ring where the user presses Home, opens another app and the sound continues with the ongoing notification; tapping the notification returns to the ringing screen; an incoming call works and pauses the alarm; the lock-screen emergency call is reachable; the snooze price and confirm sheet (without necessarily paying); and the FR-ONB-5 disclosure screen with "I understand"
**And** the video URL, recording date, device and build version are recorded in `docs/store/declarations.md`

**Given** a pre-submission policy review against the device-hostage risk (PRD §12, §13)
**When** the owner completes the checklist in `docs/store/declarations.md`
**Then** each NFR-13 and FR-SES-9 item (Home, Recents, power menu, calls, emergency dialer and other apps always work; no background activity starts; no overlays, accessibility service, lock-task or device admin; alarm audio on the alarm stream at the set volume without rerouting; volume keys captured only in the foreground; free path shown first and working offline) has evidence (test name, device checklist item from an earlier epic, or video timestamp) and pass/fail

**Given** Play Console > App content (Sensitive app permissions, Full-screen intent, Foreground service permissions)
**When** the owner submits the declarations with the video link
**Then** each declaration shows as submitted, and approval (or the reviewer's question and the owner's response) is recorded with dates; a rejection becomes a bug story or a `bmad-correct-course` proposal
**And** the story file lists each item with pass/fail, date and notes; automation never marks this story done
**And** `./gradlew qualityGate` passes on `main` with `docs/store/declarations.md` committed

### Story 8.6: EU DSA trader status and EU consumer rules (launch blocker)

As the owner,
I want the EU Digital Services Act trader status settled without publishing my home address,
So that the app can launch legally in the EU, or deliberately without the EU.
**Refs:** NFR-5; PRD §9, §12 (DSA exposes personal address), §13 launch blockers, Q17 · **Priority:** Must · **Verify:** human-verify

**Acceptance Criteria:**

**Given** the app sells in-app products (so the developer is expected to declare as a trader under the DSA)
**When** the owner decides
**Then** `docs/decisions/eu-dsa-trader.md` records one option with reasons and date: (a) declare trader status with a publishable business address, phone and email that are not the owner's home (for example a registered business address or a mail-handling service that accepts legal mail), or (b) launch without the EU/EEA and revisit later

**Given** option (a)
**When** the owner completes Play Console > Settings > Developer account > Trader status
**Then** the address, phone and email pass Google's verification, the Console shows trader status as verified, and the store listing preview for an EU country shows the trader contact details (screenshot in the story file)

**Given** option (b)
**When** the owner configures production country availability
**Then** all 27 EU member states plus Iceland, Liechtenstein and Norway are excluded from production and closed tracks that would reach EU users, and the country list is recorded in the decision file

**Given** PRD Q17 (EU/UK right of withdrawal for instantly delivered consumables)
**When** the owner resolves it with the privacy/terms author
**Then** the decision file records whether Google Play's checkout covers the consent and waiver or whether the app must show its own consent text before the first purchase; if the app must, a bug story is created in the backlog with the approved text before Story 8.10 can pass
**And** the story file lists each item with pass/fail, date and notes; automation never marks this story done
**And** `./gradlew qualityGate` passes on `main` with the decision file committed

### Story 8.7: Release signing and tag-driven upload to the internal track

As the owner,
I want every `vX.Y.Z` tag on `main` to produce one signed, R8-checked, correctly numbered release uploaded to the internal track,
So that releases are repeatable and a hotfix is one tag away.
**Refs:** NFR-6, NFR-11, NFR-15, AD-14, AD-15; Architecture Environments and operations (versioning, Play App Signing, hotfix) · **Priority:** Must · **Verify:** auto, plus (human-verify) first real upload

**Acceptance Criteria:**

**Given** the versioning scheme `versionCode = major*10000 + minor*100 + patch`
**When** the build reads the version from the tag (`-PreleaseVersion=X.Y.Z`, defaulting to the committed version locally)
**Then** it fails unless the version is plain semver with minor ≤ 99 and patch ≤ 99, and unit tests cover 1.0.0 → 10000, 1.2.3 → 10203, 1.99.99 → 19999 and rejections of 1.100.0, 1.0.100 and 1.0.0-rc1

**Given** the `release.yml` workflow from Story 1.2
**When** a tag `vX.Y.Z` is pushed
**Then** it fails unless the tagged commit is on `main` (`git merge-base --is-ancestor`), `docs/release-notes/X.Y.Z/en-US.txt` exists and is ≤ 500 characters and passes the `StoreListingTest` copy rules, and `./gradlew qualityGate` passes on the tag
**And** it builds `bundleRelease` signed with the upload key from the GitHub secrets (`UPLOAD_KEYSTORE_BASE64`, `UPLOAD_KEYSTORE_PASSWORD`, `UPLOAD_KEY_ALIAS`, `UPLOAD_KEY_PASSWORD`), verifies the AAB signature with `jarsigner -verify`, and uploads it with the R8 `mapping.txt` and release notes to the internal track through the Play Developer API using `PLAY_SERVICE_ACCOUNT_JSON` (Play App Signing re-signs for distribution)
**And** the Crashlytics Gradle plugin uploads the release mapping file so stack traces are deobfuscated, using `GOOGLE_SERVICES_JSON_RELEASE`
**And** a versionCode already used on any track fails the job with a message naming it, before upload
**And** without secrets the upload is skipped with a notice, as in Story 1.2

**Given** R8 can break reflection-based code in release only
**When** the `qa` build type is added (`initWith(release)`, debug signing, minify on, no debug receivers)
**Then** CI runs a Gradle Managed Device smoke test against the minified `qa` APK that launches the app, creates and saves an alarm, kills and restarts the process, finds the alarm again, and round-trips every `SessionState` variant through kotlinx-serialization, Room and Koin wiring; keep rules live in `androidApp/proguard-rules.pro` with a comment per rule
**And** a hotfix procedure (patch tag `vX.Y.(Z+1)` from `main`) is written in `docs/release/README.md`

**Given** the first real tag after this story
**When** the workflow completes
**Then** the owner confirms in Play Console that the build is on the internal track with the right versionName, versionCode and release notes, installs it from the internal opt-in link on a real phone, and sees a test crash from that build deobfuscated in the Crashlytics prod project (human-verify, recorded with date)
**And** `./gradlew qualityGate` passes

### Story 8.8: Release-build device verification checklist (launch blocker)

As the owner,
I want to run the most important morning paths on the Play-signed, R8-minified build on the device matrix,
So that nothing that only breaks in release reaches testers or production.
**Refs:** FR-ALM-3, FR-ALM-4, FR-ALM-11, FR-SES-1, FR-SES-2, FR-SES-4, FR-RNG-3, FR-RNG-4, FR-RNG-6, FR-PWK-7, FR-PRG-4, NFR-1, NFR-2, NFR-7, NFR-11, NFR-15 · **Priority:** Must · **Verify:** human-verify

**Acceptance Criteria:**

**Given** the release build from Story 8.7 installed from the internal track (not a debug build) on each device of the matrix (the owner's Oppo A96 plus the NFR-1 emulators; other makers optional via Firebase Test Lab), with a license tester account
**When** the owner runs the checklist
**Then** for each item the story file records pass/fail, device, Android version, versionName and date:
1. First launch runs onboarding to a locked-screen test alarm that rings with "Test · no charge".
2. A real alarm 2 minutes ahead rings within 2 s with the screen off and locked, and the ringing screen shows within 1 s (NFR-1, NFR-7).
3. Each shipped check type completes, including QR/Barcode with the camera (and House Hunt if shipped).
4. A paid snooze with a license tester completes, shows "Snoozed. Next ring at {time}.", re-rings with the next price, and appears in Purchase history with the localized price.
5. Pending (license-tester slow card) and cancelled payments show their EXPERIENCE.md messages and "No charge."
6. Swiping the app from Recents and "Stop" in the OEM task manager during a ring: the alarm re-rings within 60 s (FR-SES-2).
7. An overnight reboot before first unlock rings with Math and the default sound (FR-ALM-11).
8. Home, Progress, calendar and the weekly summary work with real history; "Delete all data" clears everything.
9. Analytics stays off until consent; after opting in, the three events appear in Firebase DebugView and nothing else; Crashlytics receives a forced test crash, deobfuscated.
10. Installing an update from the internal track over the previous release keeps alarms, history and settings, and the alarm still rings.

**Given** any failed item
**When** the owner records it
**Then** each failure becomes a new bug story referencing this item, and this story stays open until all items pass or are explicitly waived by the owner in the story file
**And** automation never marks this story done

### Story 8.9: Closed-test feedback triage and exit (launch blocker)

As the owner,
I want every closed-test report triaged and the Play closed-test requirement met with evidence,
So that production launches only when testers found no alarm, payment or policy blockers.
**Refs:** NFR-1, NFR-2, NFR-11; PRD §11 SM-1, SM-2, CM-1, CM-4, §13 release plan, Q11, Q13, Q14 · **Priority:** Must · **Verify:** human-verify

**Acceptance Criteria:**

**Given** feedback sources: Play Console testers' private feedback, the support email and feedback channel set up in Epic 5, Crashlytics and Android vitals for the closed track, and the Play pre-launch report
**When** the owner triages
**Then** `docs/closed-test/feedback.md` lists every item with id, date, source, device and Android version, summary, severity (S1 blocker: alarm didn't ring or rang late, charged without a snooze or double-charged, crash or ANR in the wake path, anything resembling device-hostage behaviour; S2 major; S3 minor) and decision (bug story id, won't fix with reason, or duplicate of id)
**And** every pre-launch report crash, accessibility warning and security warning is listed and decided the same way

**Given** the exit criteria
**When** the owner checks them
**Then** the story file records, with evidence: at least 12 testers opted in continuously for at least 14 days (Play Console closed-testing dashboard screenshot, required for new personal accounts per Story 1.4); zero open S1 items and zero open S2 items, each fix verified by the reporter or owner on a newer closed-track build; SM-1 = 0 confirmed "alarm didn't ring" reports; Crashlytics crash-free sessions ≥ 99.5% on the last closed build (SM-2)
**And** the revisit conditions are checked and recorded: fallback check share of sessions > 10% (CM-4) reopens PRD Q11; refund rate > 5% (CM-1) reopens Q13; tester reports of the call pause as an escape reopen Q14; each reopened question goes to `bmad-correct-course` before production

**Given** Play Console > Production > "Apply for production" (required for new personal developer accounts)
**When** the exit criteria pass
**Then** the owner submits the application with answers drawn from `docs/closed-test/feedback.md` (how testers were recruited, what feedback was received, what changed), and records the submission date and Google's decision
**And** the story file lists each item with pass/fail, date and notes; automation never marks this story done

### Story 8.10: Launch readiness review

As the owner,
I want one checklist that proves every launch blocker is done before I submit to production,
So that nothing is forgotten on launch day.
**Refs:** NFR-5, NFR-6, NFR-11, NFR-13; PRD §13 launch blockers, Q17 · **Priority:** Must · **Verify:** human-verify

**Acceptance Criteria:**

**Given** `docs/release/launch-readiness.md`
**When** the owner completes it
**Then** each row has pass/fail, evidence link and date:
1. EU DSA trader status or EU exclusion (Story 8.6), and Q17 resolved with any required in-app consent shipped.
2. Privacy policy live on GitHub Pages (HTTP 200) and consistent with the Data safety form (Story 8.3).
3. Exact alarm, full-screen intent and foreground service declarations approved, with the demo video and pre-submission policy review (Story 8.5).
4. Content rating, target audience 18+ and App content complete (Story 8.4); store listing and graphics published (Stories 8.1, 8.2).
5. Every `human-verify` checklist story passed or explicitly waived: 1.21, the Epic 2 to Epic 5 checklists, 6.10, 7.12 and 8.8 (story ids listed).
6. Closed test complete and production access granted (Story 8.9).
7. targetSdk 36 and Play Billing Library ≥ 8 (9.1.0) in the release build, meeting Play's current deadlines.
8. `tools/play-catalog` in dry-run mode reports no difference between the repo and the 50 live products, and all 31 reachable products are active.
9. The production candidate AAB is the same versionCode that passed Stories 8.8 and 8.9 (no rebuild).
10. `docs/runbooks/post-launch-monitoring.md` exists (Story 8.11).
**And** any failing row blocks Story 8.12, and the story file lists each item with pass/fail, date and notes; automation never marks this story done
**And** `./gradlew qualityGate` passes on `main` with the readiness file committed

### Story 8.11: Post-launch monitoring runbook

As the owner,
I want a written weekly routine and incident playbooks, with a reminder that creates the checklist for me,
So that crashes, missed alarms and payment problems are caught fast after launch.
**Refs:** NFR-1, NFR-2, NFR-15, AD-14; PRD §11 SM-1 to SM-6, CM-1 to CM-4, §12; Architecture Environments and operations (monitoring) · **Priority:** Must · **Verify:** auto, plus (human-verify) first weekly check

**Acceptance Criteria:**

**Given** `docs/runbooks/post-launch-monitoring.md`
**When** it is written
**Then** it defines the weekly check with thresholds and sources: Android vitals user-perceived crash rate < 1.09% and ANR rate < 0.47% (Play bad-behaviour thresholds); Crashlytics crash-free sessions ≥ 99.5% (SM-2); any crash or ANR in `WakeService`, `WakeActivity`, alarm receivers, `SessionEngine` or billing is a release blocker; Play rating ≥ 4.3 and new reviews answered within 3 days (SM-5) (owner-approved default 2026-09-26); refund rate ≤ 5% (CM-1, Play order management); opt-in Analytics SM-3 and CM-2 to CM-4; D30 retention (SM-4)
**And** it contains incident playbooks with first steps and owner decisions for: "my alarm didn't ring" (collect device, Android version, OEM settings, reliability checklist state; reproduce with the device matrix; S1 bug story); "I was charged but didn't get a snooze" (FR-SET-5 support flow, Google 48-hour refund, check the stranded-purchase path); wake-path crash spike (halt rollout, patch tag per `docs/release/README.md`); a Play policy notice or rejection (respond within the stated deadline, `bmad-correct-course` if a product change is needed)
**And** it lists recurring deadlines: Play target API level each August, Play Billing Library deprecation dates, Firebase BoM and dependency updates each quarter through the dependency allowlist review, and the deferred items from the Architecture Spine (targetSdk 37, detekt stable)

**Given** `.github/workflows/weekly-monitoring.yml`
**When** it runs every Monday on schedule (and on manual dispatch)
**Then** it opens a GitHub issue "Weekly monitoring {date}" whose body is the runbook's weekly checklist as task items, and it never needs secrets beyond the default token
**And** a unit test (or workflow lint) asserts the issue template stays in sync with the runbook's checklist section

**Given** the first week after the runbook is merged
**When** the owner completes the first generated issue
**Then** every item is ticked or noted with numbers, and the issue link is recorded in the story file (human-verify); automation never marks that part done
**And** `./gradlew qualityGate` passes

### Story 8.12: Staged production rollout

As the owner,
I want production to roll out 10%, then 50%, then 100%, moving on only when the health gates pass,
So that a problem reaches as few people as possible and can be halted.
**Refs:** NFR-1, NFR-2, NFR-15; PRD §11 SM-1, SM-2, CM-1, §13; Architecture Environments and operations (staged rollout, hotfix) · **Priority:** Must · **Verify:** human-verify

**Acceptance Criteria:**

**Given** Story 8.10 passed and production access is granted
**When** the owner creates the production release
**Then** it promotes the exact AAB (same versionCode) that passed Stories 8.8 and 8.9, with the committed release notes, country availability per Story 8.6, and staged rollout at 10%
**And** `docs/release/rollout-vX.Y.Z.md` records each stage's start date, percentage and the gate numbers below

**Given** each stage (10% held at least 3 days, then 50% held at least 3 days) (owner-approved default 2026-09-26)
**When** the owner checks the gates before increasing
**Then** all must pass: zero crashes or ANRs in the wake path in Crashlytics; Crashlytics crash-free sessions ≥ 99.5%; Android vitals crash and ANR rates below the bad-behaviour thresholds (or "not enough data" recorded, in which case Crashlytics decides); zero confirmed "alarm didn't ring" reports (SM-1); refund rate ≤ 5% (CM-1); no Play policy notice
**And** only then the owner raises the rollout to 50%, then to 100%, recording date and numbers for each step

**Given** any gate fails
**When** the owner acts
**Then** the rollout is halted in Play Console the same day, an S1 bug story is created, the fix ships as a patch tag `vX.Y.(Z+1)` through the internal track (and Story 8.8 items relevant to the fix are re-run), and the rollout resumes from the halted percentage with the gates re-checked; every step is recorded in the rollout file

**Given** 100% rollout reached
**When** 7 days pass with the gates still green
**Then** the owner records "launch complete" with date in the rollout file, and weekly monitoring continues per Story 8.11
**And** the story file lists each item with pass/fail, date and notes; automation never marks this story done
