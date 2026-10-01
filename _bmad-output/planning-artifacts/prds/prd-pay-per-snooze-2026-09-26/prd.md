---
title: Yawn & Pawn — Product Requirements Document
version: 0.3 (draft)
status: Draft — review findings applied, awaiting owner confirmation of [ASSUMPTION] items
owner: Kiet Bui
date: 2026-09-26
platform: Android first (iOS later)
workflow: BMAD Method (PRD → UX design → Architecture → Epics/Stories) → Ralph loop implementation
sources:
  - review-rubric.md (this folder)
  - review-adversarial-general.md (this folder)
related:
  - _bmad-output/planning-artifacts/ux-designs/ux-pay-per-snooze-2026-09-26/DESIGN.md (visual design)
  - _bmad-output/planning-artifacts/ux-designs/ux-pay-per-snooze-2026-09-26/EXPERIENCE.md (flows, voice and tone, screen copy)
  - _bmad-output/planning-artifacts/architecture/architecture-pay-per-snooze-2026-09-26/ARCHITECTURE-SPINE.md (session state machine transition table, billing module, storage)
---

# Yawn & Pawn — PRD

## 1. Summary

Yawn & Pawn is an Android alarm clock where **every snooze costs real money**, and the cost **rises with each snooze** in the same session. Waking up is always free: the user turns the alarm off by completing a **check they chose themselves** (Memory Sequence, Math, House Hunt, QR/Barcode, Word Unscramble).

The app's message is clear and consistent: **we don't want your money — we want you up.** Paying is available, honest and never hidden, but the whole experience is designed to make not paying the obvious, rewarding choice.

**Business model:** revenue comes **only** from snooze payments. No subscriptions, no ads, no paid features, no data selling. This is a fixed product principle.

## 2. Background & market

- Several pay-to-snooze apps exist (Unsnooze, Paid Alarm Clock, Slooze/Looze, Nuj, WAKEorDONATE). Most are new (2026), iOS-only, low traction, and each ties the user to a single wake-up mechanism. [ASSUMPTION — A5: based on owner's store survey, not a cited source]
- Alarmy dominates "hard to dismiss" alarms with missions, but has no money stake.
- **Our differentiation:**
  1. Snooze fee that rises with every snooze, with a user-controlled base fee (minimum $1).
  2. The user picks from a menu of checks instead of being locked into one.
  3. A **muted grace window** (15–30 s) while doing the check, so others aren't disturbed; if the check isn't done in time, the alarm comes back and stays on until it is.
  4. Wake-up progress tracking, recorded motivation messages, and custom alarm sounds (table stakes, not unique).
  5. Android first (the less crowded store; a go-to-market choice).

## 3. Goals & non-goals

### Goals
- G1. Help users stop snoozing: users who keep the app should snooze less over time.
- G2. Be the most **reliable** alarm on the phone — an alarm that fails to ring destroys trust immediately.
- G3. Make every payment **honest and explicit**: the user always sees the exact price, as returned by Google Play, before paying.
- G4. Ship an Android MVP to Google Play in roughly 9–10 weeks including the mandatory closed test (see §13 timeline). [ASSUMPTION — A3]
- G5. Keep business logic shareable with a future iOS app.

### Non-goals (MVP)
- iOS app (planned later).
- Accounts, login, cloud sync (Android Auto Backup to the user's own Google account is not "cloud sync"; see NFR-14).
- Our own backend or server of any kind.
- Social features, friend pots, leaderboards.
- Charity donations.
- Subscriptions, ads, paid unlocks — **never**, not only in MVP.
- Wear OS, widgets, smart-home integration.

## 4. Target users

- **Primary — "The chronic snoozer"** (students, young professionals, 18–35): hits snooze 3+ times most mornings, has tried "hard alarm" apps and learned to game them. Wants a real consequence.
- **Secondary — "The routine builder"**: already gets up okay, wants accountability and a streak to protect.
- **Affected bystanders**: partners/roommates. The grace window exists for them.

The app targets adults (Play target audience 18+, NFR-5). This **reduces but does not prevent** use by minors; Google Play parental controls and purchase authentication are the real guard.

## 5. Product principles

1. **Waking up is always free.** There is always a way to stop the alarm without paying: complete the check.
2. **We don't want you to pay.** Copy, visuals and stats celebrate zero-snooze mornings. Money paid is shown as a cost, never as an achievement.
3. **No dark patterns.** Price shown before every payment, one clear confirm, no pre-selected upsells, no confusing buttons, no shaming language. Snooze is a normal button — not disguised, not labelled "don't pay".
4. **Reliability over features.** If a feature risks the alarm not ringing, it waits.
5. **Private by default.** No account needed; there is no backend. Data lives on the device. The only things that leave it are: Android Auto Backup to the user's own Google account (photos and recordings excluded, NFR-14), crash reports without personal content (NFR-15) and **opt-in** anonymous analytics, off by default (NFR-15).
6. **The phone is never held hostage.** The alarm is loud and persistent, but the phone always stays usable: Home, other apps, calls and the emergency dialer always work (FR-SES-9, NFR-13).

## 5a. Glossary

These terms are used with exactly these meanings throughout this PRD and all downstream documents and stories.

| Term | Meaning |
|---|---|
| **Session** (alarm session) | Starts when an alarm first rings; ends when the check is completed (outcome On time or Snoozed) or the 30-minute no-interaction timeout fires (outcome Missed). Snooze count and fees reset per session. One session can contain several rings. |
| **Ring** | One continuous period during which the alarm is sounding or muted by the grace window, from the moment it starts (first ring or re-ring after a snooze) until a snooze is granted or the session ends. |
| **Re-ring** | A ring that starts when a snooze ends. |
| **Snooze** | A paid pause of the current ring for the alarm's snooze length. Granted only by a `PURCHASED` Google Play purchase (§6.3). |
| **Base fee (B)** | The user-set price of the first snooze in a session. Global setting (FR-SET-1), minimum $1. |
| **Fee ladder** | The price sequence within a session: the Nth snooze costs B × N (USD tier), displayed as Google Play's localized price. |
| **Check** | The proof-of-wake task that ends a session for free. **Check type** = Memory Sequence, Math, House Hunt, QR/Barcode or Word Unscramble. |
| **Wake screens** | The ringing screen, the snooze confirm sheet and the check screens. |
| **Interaction** | Any tap on a wake screen. Resets the 30-minute no-interaction timer (FR-ALM-9). |
| **Grace window** | The 15–30 s muted period that starts when the user taps "I'm up" (FR-PWK-9). One per ring. Shown to users as **Quiet time** (editor row, sub-screen, 'Vibrate during quiet time', Settings 'Default quiet time'). 'Grace window' remains the internal and spec term. |
| **Fallback check** | The replacement check offered when the configured check can't physically be done (FR-PWK-11). Never called "backup". |
| **Direct Boot substitution** | Before the first unlock after a reboot, checks and sounds that need normal storage are replaced by Math and the default built-in sound (FR-ALM-11). |
| **Default sound fallback** | Playing the default built-in sound whenever the chosen sound or recording can't play (FR-SND-5, NFR-2). |
| **Backup alarm** | The exact system alarm kept scheduled while a session is active so the alarm re-rings if the app is killed (FR-SES-2). Unrelated to the fallback check. |
| **Purchase intent** | A local record written **before** `launchBillingFlow`: sessionId, productId, snoozeIndex, priceAmountMicros, currency code (§6.3). |
| **Stranded purchase** | A `PURCHASED` token that never granted a snooze and belongs to an ended or different session (or to a session that is currently snoozed). It is never consumed by recovery and Google refunds it automatically after 3 days, unless the user reuses it (FR-RNG-10). |
| **Commitment lock** | The rule that "weakening" changes made within 8 h of an enabled alarm take effect only after that alarm (§6.2). |
| **Outcome values** | **On time** (check completed, 0 snoozes) · **Snoozed** (check completed after ≥ 1 paid snooze) · **Missed** (session ended by the 30-minute no-interaction timeout) · **Test** (test alarm) · **Skipped** (FR-ALM-10). Test and Skipped are excluded from rates and streaks. **Merged** is a log label for an alarm occurrence absorbed into an active session (FR-SES-7), not a session outcome. |

## 6. Core concepts & rules

### 6.1 Alarm session
See Glossary: **Session**, **Ring**. A session starts when an alarm first rings and ends when the user completes the check or the session times out (FR-ALM-9).

### 6.2 Snooze fee formula
- **Base fee (B):** set by the user, global. Minimum **$1**, adjustable in **$1 steps**, maximum **$10** (see open question Q1).
- **Fee ladder:** the **Nth snooze** in a session costs **B × N**.
  - B = $1 → $1, $2, $3, $4, $5 …
  - B = $3 → $3, $6, $9, $12, $15 …
- **Per-snooze cap:** $50. A snooze that would cost more is not offered; only the check remains. With today's defaults (B ≤ $10, ≤ 5 snoozes) the max is exactly $50, so the cap only matters if Q1/Q3 change. (Q2)
- **Fee locked per session:** B, max snoozes and snooze length are frozen when the session starts; settings changes apply from the next session.
- **Max snoozes per session:** 5 by default, user may lower it (min 1). After that, snooze is not offered. (Q3)
- **Snooze length:** set per alarm (5 / 9 / 10 / 15 min; default 9).
- **Commitment lock (a nudge, not a wall):** raising B / making checks harder is always allowed. Within **8 hours** of an enabled alarm, "weakening" changes — lowering B, easier checks, longer grace window, more snoozes — are saved but only take effect **after** that alarm. Disabling or deleting an alarm stays allowed (plans change) but asks for confirmation and is logged. (Q4)
- **Currency:** the fee ladder is defined in **USD tiers**; Google Play shows the user's **local price**. The app always displays the localized price string returned by Google Play and never hard-codes "$" or any currency symbol in UI copy. Local prices are not perfectly linear (the $2 product isn't always exactly 2× the $1 in local currency) — acceptable. Product details are cached so the fee picker works offline after first load, and refreshed at every session start when online.
- **Tax:** in regions where Play prices exclude tax, the confirm sheet says "Google Play shows the final total, including any tax." (FR-RNG-2).

### 6.3 How the fee maps to Google Play Billing
(Research summary — details confirmed in ARCHITECTURE-SPINE.md and Spike S1.)

**Products**
- Each price step is a **consumable one-time product** with a fixed USD price: `snooze_usd_01` … `snooze_usd_50` (50 products). Only 31 prices are reachable with today's limits (B ≤ 10, N ≤ 5); the full 1–50 catalogue is **deliberate headroom** for Q1/Q3. A unit test asserts that `productFor(fee)` exists for every reachable (B, N). Google Play allows up to 1,000 products per app. Pricing templates were discontinued in Oct 2025, so prices are set via the Play Developer API (`onetimeproducts`) from a script in the repo, not by hand.

**Linking a purchase to a session**
- Every session has a random, non-personal `sessionId` (UUID).
- **Before** `launchBillingFlow`, the app persists a **purchase intent** (sessionId, productId, snoozeIndex, priceAmountMicros, currency code) and launches the flow with `obfuscatedProfileId = sessionId`. The returned Purchase carries that profileId, so every token can be matched to its session.
- History stores `priceAmountMicros` + currency code per purchase (from the intent / ProductDetails), never only a display string.

**Grant and consume**
- **Grant rule:** a snooze is granted as soon as the purchase state is `PURCHASED` and its profileId is the current active session, which is not currently snoozed (not after consumption). Each purchase token grants **exactly one** snooze; granted tokens are recorded in a local grant ledger to prevent double-grant.
- **Consume:** a granted token is consumed right away (consumption also acknowledges it). If consumption fails it retries with backoff on app start, resume and via WorkManager until it succeeds. Before launching the flow for product P, any **granted but unconsumed** token for P is consumed first.
- **Pending purchases** (e.g., cash payment methods; PBL 8+ requires enabling them for one-time products): a pending purchase does **not** grant a snooze. The alarm keeps ringing and the user is told the payment hasn't cleared. When it later becomes `PURCHASED`, the recovery table below decides; there is no separate pending rule.

**Recovery** (on app start, resume and ringing-screen open, via `queryPurchasesAsync`)

| Token | profileId | Grant ledger | Action |
|---|---|---|---|
| `PURCHASED` | current active session, not snoozed | not granted | **Grant + consume** |
| `PURCHASED` | ended session, other session, currently-snoozed session, or missing | not granted | **Stranded purchase: do NOT consume.** Google auto-refunds after 3 days. History shows "Not used, refunded automatically by Google". |
| `PURCHASED` | any | granted, not consumed | Consume (with retries) |
| `PENDING` | any | — | No grant; keep waiting |

- **Stranded purchase reuse (FR-RNG-10):** if a later purchase of the same product returns `ITEM_ALREADY_OWNED` (or the pre-launch query finds the owned token) because a stranded token exists, the app offers: "You already paid {price} earlier that wasn't used. Use it for this snooze?" → **Use it**: grant the snooze with that token and consume it; the history row changes from "Not used…" to a normal paid snooze for the current session. **Not now**: back to the ringing screen; Snooze at that price shows disabled with "An earlier {price} payment is being refunded" until the token is gone.
- `ITEM_ALREADY_OWNED` for a **granted** unconsumed token → consume it, then retry the purchase once.
- **Refunds:** Google lets users self-request a refund within 48 hours (limits apply). We accept this. Without a backend the app **cannot** detect refunds (the Voided Purchases API is server-side only), so history shows what was charged, except stranded purchases, which are labelled as auto-refunded.
- Play Billing Library **8+ required** (latest is 9.x); target API 36 required for new apps from Aug 31, 2026.
- MVP is **client-only** (no backend). Risk is low: someone who hacks the app gets a free snooze — they only cheat themselves. Server-side verification can be added later.
- Every row of the recovery table, the reuse offer and the `ITEM_ALREADY_OWNED` cases (pending→purchased during/after the session, lost callback, crash between grant and consume, duplicate delivery) has a unit test against a fake BillingClient.

### 6.4 Session state machine
The session is an explicit state machine in the shared module (states: ringing, grace window, in check, paying, snoozed, ended). **The full state × event transition table lives in ARCHITECTURE-SPINE.md**; the shared module encodes it with one parameterised test per row. The PRD fixes these conflict rules:

| Situation | Rule |
|---|---|
| Payment in progress when the grace window ends | The grace window ends normally; the alarm resumes at full set volume while the Play sheet is open. |
| Purchase granted while the user is in a check | **Snooze wins**: the ring stops, check progress is discarded; the re-ring starts a fresh check and a new grace window. |
| Another alarm is due during an active session | **Merged** (FR-SES-7): the active session keeps its frozen config and fee ladder; if due during a snooze, the snooze ends early and the session re-rings at that time (no fee, no extra grace window). |
| Incoming or ongoing call | Sound pauses on audio focus loss; grace countdown and 30-minute timer pause; everything resumes when focus returns (FR-SES-8). |
| Reboot or process death mid-session | Session restored from storage and the alarm resumes ringing (FR-SES-1); timers are persisted with wall-clock + monotonic time + boot count (details in ARCHITECTURE-SPINE.md); a snooze end already in the past rings immediately; a restored ring starts a fresh 30-minute timer. |
| Before first unlock after reboot | Direct Boot substitution; Snooze unavailable (FR-ALM-11). |

## 6.5 User journeys

Prices below are for a US account and appear as Google Play formats them.

### UJ1 — Linh's first paid morning (chronic snoozer)
Linh, 24, grad student, snoozes 4× most days. Base fee $1, max 5 snoozes, snooze length 9 min, check Math Medium ×3.
1. 6:30 the alarm rings over the lock screen and ramps up (FR-ALM-3, FR-ALM-4, FR-ALM-6). The ringing screen shows "I'm up" and "Snooze · $1.00" (FR-RNG-1).
2. Linh taps Snooze. The confirm sheet shows $1.00, 9 minutes, and "Next snooze: $2.00"; the alarm keeps ringing (FR-RNG-2, FR-RNG-5).
3. She taps "Pay $1.00 and snooze". A purchase intent is saved, the Play sheet opens, the purchase returns `PURCHASED` and the snooze is granted (§6.3, FR-RNG-3, FR-RNG-4). The screen says "Snoozed. Next ring at 6:39." (FR-RNG-6, FR-RNG-8).
4. 6:39 the re-ring starts (FR-SES-2 keeps a backup alarm armed). Snooze now shows $2.00.
5. **Climax:** Linh opens the confirm sheet, reads "Pay $2.00 and snooze", and taps "I'll get up" instead.
6. She taps "I'm up"; the alarm mutes for 20 s (FR-PWK-9). She's slow; at 20 s the alarm returns at full volume, her progress is kept, and she finishes the third problem 15 s later.
7. **Outcome:** session logged as Snoozed, $1.00 paid (FR-PRG-1, FR-PRG-4); no shaming copy (FR-MSG-3); streak stays at 0 (FR-PRG-2).

### UJ2 — Minh sets up a QR check at night and wakes on time
Minh, 29, product designer.
1. 23:00 Minh creates a 6:00 alarm and picks the QR/Barcode check (FR-ALM-1, FR-ALM-2, FR-PWK-2). Camera permission is requested now, with its reason (FR-ONB-2).
2. He registers the barcode on his toothpaste in the bathroom and tries the check once (FR-PWK-7, FR-PWK-12).
3. He raises his base fee to $3; raising is allowed instantly despite the commitment lock (§6.2). Home shows "Rings in 7 h" (FR-ALM-7).
4. 6:00 the alarm rings. He taps "I'm up"; the grace window starts muted with a countdown (FR-PWK-9).
5. **Climax:** he walks to the bathroom and scans the toothpaste at 17 s, inside the window. The alarm ends without ever returning; his partner never hears the second half.
6. **Outcome:** On time, a short zero-snooze celebration (FR-MSG-3), streak +1, "Nothing paid this week" on Home (FR-MSG-2, FR-PRG-2).

### UJ3 — Anna's partner and the grace window
Anna, 31, nurse on early shifts; her partner Tom sleeps later. Grace window 30 s, "vibrate in grace" off, check mode All: Memory Sequence Medium then Word Unscramble (FR-PWK-2, FR-PWK-4, FR-PWK-8).
1. 5:45 the alarm rings; Anna taps "I'm up" within 3 s, so Tom hears only a moment of sound (FR-PWK-9).
2. She completes the memory sequence silently.
3. **Climax:** the second word stumps her; the countdown hits 0 and the alarm returns at the set volume on the alarm stream (FR-PWK-9, FR-SES-6). Tom stirs. She solves the word 10 s later and the alarm ends.
4. That evening she tries to lengthen the grace window. It's within 8 h of her next alarm, so the change is saved and applies after tomorrow's alarm (§6.2).
5. **Outcome:** On time (0 snoozes), even though the alarm came back.

### UJ4 — Tuan's payment fails on a bad connection
Tuan, 22, student with patchy Wi-Fi. Base fee $1.
1. 7:00 the alarm rings. The app was online at session start, so Snooze is offered (FR-RNG-7, NFR-3).
2. Tuan taps "Pay $1.00 and snooze". A purchase intent is saved with `obfuscatedProfileId = sessionId` (§6.3). The connection drops; Play returns an error. The alarm never stopped (FR-RNG-5) and the screen shows the error message for that response code (FR-RNG-4).
3. **Climax:** no snooze is coming. Tuan taps "I'm up" and does his check; waking up is free (Principle 1).
4. At 7:20 Google finishes processing the payment. On next app open, recovery finds a `PURCHASED` token for an **ended** session: it is a stranded purchase, not consumed; history shows "Not used, refunded automatically by Google" (§6.3, FR-PRG-4).
5. Next morning Tuan taps Snooze at $1.00; Play returns `ITEM_ALREADY_OWNED`. The app asks "You already paid $1.00 earlier that wasn't used. Use it for this snooze?" He taps Use it; the snooze is granted and the token consumed (FR-RNG-10).
6. **Outcome:** Tuan was charged exactly once, for exactly one snooze he received.

### UJ5 — Hoa's phone reboots overnight
Hoa, 27. Her alarm uses House Hunt and a custom sound. At 3:00 a system update reboots the phone, which stays locked.
1. The Direct Boot receiver re-arms the alarm from device-protected storage (FR-ALM-11, FR-ALM-5).
2. 6:15 the alarm rings over the lock screen. House Hunt and the custom sound need normal storage, so Direct Boot substitution applies: Math and the default built-in sound (FR-ALM-11).
3. Snooze shows disabled with "Unlock your phone to snooze" (FR-ALM-11, FR-RNG-7).
4. **Climax:** Hoa taps "I'm up", the grace window mutes the alarm, and she solves the Math problems with the phone still locked.
5. **Outcome:** On time, logged with a "Direct Boot" note; the next alarm uses her House Hunt check and custom sound again.

## 7. Functional requirements

IDs are stable so stories can reference them; new IDs are added at the end of each group and never renumbered.

**Priority tags** (MoSCoW for the launch build): **[Must]** ships at launch · **[Should]** ships at launch unless the build runs late · **[Could]** first to drop.

**Cut line.** Launch needs every [Must]. If the Ralph build runs past its 4-week budget (§13), cut in this order: (1) all [Could]: FR-SND-7 "Mix into alarm", FR-ALM-10 skip, FR-PWK-13 printable QR; (2) House Hunt (FR-PWK-6) — dropped immediately if Spike S3 is marginal; (3) FR-PRG-3 calendar view; (4) FR-PRG-5 weekly summary; (5) FR-SND-6 custom audio file; (6) FR-SND-3/FR-SND-4 motivation recordings. Minimum check set at launch: Math, Word Unscramble, Memory Sequence, QR/Barcode.

### 7.1 Alarms (ALM)
- **FR-ALM-1** [Must] Create, edit, delete, enable/disable multiple alarms.
- **FR-ALM-2** [Must] Each alarm has: time, repeat days (or one-time), label, sound, volume, vibration on/off, snooze length, check configuration, grace window length, motivation recording (optional, FR-SND-3).
- **FR-ALM-3** [Must] Alarms fire at the exact scheduled time, including in Doze, silent mode and Do Not Disturb (alarm audio stream). Automated check: `AlarmManager.setAlarmClock` is called with trigger time equal to the scheduled epoch ms (fake scheduler). On-device timing (≤ 2 s, NFR-1) is verified in the epic's device-verification checklist.
- **FR-ALM-4** [Must] When an alarm rings, a full-screen ringing screen appears over the lock screen (locked or screen off). On an unlocked, in-use phone see FR-SES-4.
- **FR-ALM-5** [Must] Alarms survive reboot, app update, time change, time zone change and DST.
- **FR-ALM-6** [Must] Per alarm the user chooses **Gradually increase volume** (on by default): when on, volume ramps from 20% of the set level to the set level over 30 s (fixed, not user-editable; owner decision 2026-09-27); when off, the alarm starts at the set level. Always on the alarm stream; ring volume ignores the phone's current media/ringer volume.
- **FR-ALM-7** [Must] Home screen shows the next alarm ("Rings in 7 h 12 min").
- **FR-ALM-8** [Must] "Test alarm" rings the full flow (sound, checks, grace window) **without any payment**; the snooze button shows the price but is disabled with the label "Test · no charge".
- **FR-ALM-9** [Must] If a **single ring** continues for 30 minutes with no interaction (any tap on a wake screen), the alarm stops and the session is logged as Missed (prevents endless ringing if the phone is left at home). Each interaction restarts the 30-minute timer; each re-ring starts a new timer; snooze time never counts; the timer pauses during a call (FR-SES-8). Timers use monotonic time (`elapsedRealtime`), not the wall clock (reboot handling: §6.4).
- **FR-ALM-10** [Could] *(Approved by owner 2026-09-26, Q9 closed)* A pre-alarm notification (optional, off by default) lets the user skip the next occurrence up to 2 hours before — skipping is free and logged.
- **FR-ALM-11** [Must] Alarms ring after an **overnight reboot even before the phone is unlocked** (Direct Boot: `directBootAware` receiver for `LOCKED_BOOT_COMPLETED`, alarm schedule, frozen session config and active session in device-protected storage). Before first unlock: checks that need normal storage (House Hunt, QR references, custom sounds, recordings) are replaced by Math + the default built-in sound (Direct Boot substitution), and **Snooze is unavailable**, shown disabled with "Unlock your phone to snooze". Once the phone is unlocked, Snooze becomes available; the substituted check stays for the current ring.
- **FR-ALM-12** [Must] On Android 12–12L (API 31–32) request `SCHEDULE_EXACT_ALARM` (manifest `maxSdkVersion="32"`) with a `canScheduleExactAlarms()` check; API 33+ uses `USE_EXACT_ALARM`.

### 7.1b Session integrity — no free escapes (SES)
Within the app, every path leads back to the ringing alarm, but the **phone itself always stays usable** (FR-SES-9, NFR-13). **Accepted escapes** (the app never tries to prevent them): **force-stop**, **uninstall**, the **30-minute no-interaction timeout** (FR-ALM-9, outcome Missed, breaks the streak) and **powering off the phone** (the app never blocks the power menu; if the phone is turned back on while the session is still active, it resumes per FR-SES-1).
- **FR-SES-1** [Must] **Active-session state** (ringing, grace window, in check, paying, snoozed) is persisted immediately and restored after process death, reboot or app update — the alarm resumes ringing.
- **FR-SES-2** [Must] **Backup alarm:** while a session is active, a backup exact alarm is always scheduled ≤ 60 s ahead (re-armed continuously; during a snooze it sits at snooze end). If the app is killed (Task Manager "Stop", OEM kill, swipe from Recents), the backup alarm re-rings.
- **FR-SES-3** [Must] **App locked during a session (inside the app only):** while a session is active (including snoozed), opening the app shows only the wake screens. Alarm editing, deleting, settings and "Delete all data" are unavailable. This never affects other apps or the rest of the phone.
- **FR-SES-4** [Must] **Leaving the ringing screen:** when the user presses Home, switches apps or opens Recents, the app does **not** relaunch its activity from the background. Instead, a foreground service keeps the alarm sound playing and shows an ongoing, high-priority notification; tapping it (or opening the app) returns to the ringing screen within 1 s. If the user dismisses the notification (possible on Android 14+), the sound continues and opening the app returns to the ringing screen. Pass condition: sound never stops, tap returns to the ringing screen ≤ 1 s, no background activity start.
- **FR-SES-5** [Must] System clock or time-zone changes during a session don't end, skip or shorten it.
- **FR-SES-6** [Must] **Sound stays on:** the alarm plays on the **alarm stream at the set volume**, which is (re)applied at the start of each ring and when a grace window ends. Volume keys are captured **only while the ringing screen is in the foreground** (standard alarm-app behaviour). Vibration continues during the grace window only if the user enabled "vibrate in grace".
- **FR-SES-7** [Must] **Overlapping alarms:** if another alarm is due during an active session, it is merged into the current session: the session keeps its frozen config and fee ladder; if it is due during a snooze, the snooze ends early and the session re-rings at that time with no fee and no extra grace window. The absorbed occurrence is logged as Merged and its next occurrence is scheduled normally.
- **FR-SES-8** [Must] **Phone calls:** detected via **audio focus loss** (no `READ_PHONE_STATE` permission). An incoming or ongoing call pauses the alarm sound, the grace countdown and the 30-minute timer; the paused alarm resumes when the call ends (audio focus regained).
- **FR-SES-9** [Must] **Phone always usable during a session:** Home, Recents, other apps, incoming and outgoing calls and the emergency dialer always work. The app never blocks, covers or overlays other apps (no `SYSTEM_ALERT_WINDOW`, no accessibility service, no lock-task/kiosk mode, no device admin).
- **FR-SES-10** [Must] Session conflicts follow the rules in §6.4 (grace ends during payment, purchase granted during a check, overlapping alarm, call, reboot, Direct Boot).

### 7.2 Ringing screen & snooze payment (RNG)
- **FR-RNG-1** [Must] Ringing screen shows: time, alarm label, primary action **"I'm up"** (starts the check), secondary action **"Snooze · {localized price}"**, today's snooze count and amount paid this session (currency-formatted).
- **FR-RNG-2** [Must] Tapping Snooze opens a **confirmation step**: the exact Play price, snooze length, what the next snooze would cost, a tax note where prices exclude tax (§6.2) and a short supportive line (e.g., "Is 9 more minutes worth {price}? You've got this."). Buttons: "I'll get up" / "Pay {price} and snooze". No pre-selection. Exact copy and layout: EXPERIENCE.md → Voice and Tone; EXPERIENCE.md → Snooze confirm sheet.
- **FR-RNG-3** [Must] On confirm, the purchase intent is saved (§6.3) and the Google Play purchase sheet opens. The device must be unlocked if Play requires it (see Spike S1).
- **FR-RNG-4** [Must] A snooze is granted **only** when the purchase is `PURCHASED` and linked to the current session (rules in §6.3). On cancel, error, no connection or pending, the user returns to the ringing screen and sees the message mapped to that `BillingResponseCode` / purchase state (one string resource per reason; mapping table unit-tested; copy in EXPERIENCE.md).
- **FR-RNG-5** [Must] The alarm keeps ringing at full volume during the payment flow (no quiet period while the Play sheet is open). If Snooze is tapped during a muted grace window, the mute continues only until the window's countdown ends (§6.4).
- **FR-RNG-6** [Must] When the snooze is granted, the alarm stops and re-rings after the snooze length; the next snooze costs B × (N+1).
- **FR-RNG-7** [Must] If snooze is not available (the reasons returned by `snoozeAvailability`, ARCHITECTURE-SPINE AD-7: test mode, offline, before first unlock, prices not loaded yet, max snoozes reached, price cap reached, payment pending, earlier payment being refunded), the Snooze button is shown disabled with the reason.
- **FR-RNG-8** [Must] After a paid snooze, the screen shows exactly "Snoozed. Next ring at {time}." (no guilt, no celebration).
- **FR-RNG-9** [Must] Snooze is never the only way to stop the sound: "I'm up" is reachable in ≤ 1 tap from every ringing state (ringing screen, confirm sheet, payment error), online or offline.
- **FR-RNG-10** [Must] **Stranded purchase reuse:** when a snooze purchase hits a stranded token for the same product, offer "You already paid {price} earlier that wasn't used. Use it for this snooze?" with "Use it" / "Not now" (§6.3).

### 7.3 Proof-of-wake checks (PWK)
- **FR-PWK-1** [Must] Available check types: **Memory Sequence, Math, House Hunt, QR/Barcode, Word Unscramble** (House Hunt is [Should], FR-PWK-6).
- **FR-PWK-2** [Must] Per alarm the user selects one or more check types and a mode:
  - **Random** — one randomly chosen from the selected types.
  - **All** — complete every selected type in the order the user arranged them at setup.
- **FR-PWK-3** [Must] Each type has a difficulty (Easy / Medium / Hard) and a count (e.g., number of math problems), per alarm.
- **FR-PWK-4** [Must] **Memory Sequence** — tiles on a grid light up in sequence; the user repeats it. Length 4 / 6 / 8 by difficulty; a wrong tap restarts that round with a new sequence.
- **FR-PWK-5** [Must] **Math** — arithmetic problems (Easy: 2-digit add/subtract; Medium: 2-digit × 1-digit plus add; Hard: multi-step with multiplication). 1–10 problems.
- **FR-PWK-6** [Should] **House Hunt** — at setup the user photographs a spot far from the bed (e.g., bathroom sink). To dismiss, they photograph the same spot; on-device image matching accepts it when similarity ≥ the threshold chosen in Spike S3. Several reference photos allowed. (Spike S3)
- **FR-PWK-7** [Must] **QR/Barcode** — at setup the user registers any barcode/QR (e.g., toothpaste). To dismiss, they scan it (on-device scanning). (App-generated printable QR: FR-PWK-13.)
- **FR-PWK-8** [Must] **Word Unscramble** — unscramble N words (English at launch). Word length 4–5 / 6–7 / 8+ by difficulty.
- **FR-PWK-9** [Must] **Grace window** — when the user taps "I'm up", the alarm is **muted for the grace window** (user sets 15–30 s per alarm, default 20 s) with a visible countdown.
  - Finish the check within the window → the alarm ends, session success.
  - Window runs out → the alarm **returns at full set volume** and stays on until the check is completed. Progress already made in this ring is kept.
  - One grace window per ring (after a re-ring, the user gets a new window).
- **FR-PWK-10** [Must] The Snooze action remains available (subject to RNG rules) during the check; if granted, check progress is discarded (§6.4).
- **FR-PWK-11** [Must] **Fallback check** — if a check can't physically be done (camera fails = CameraX error callback or no frame within 5 s; QR code lost; House Hunt fails to match 5 times), the user can switch to a fallback check, picking one of the non-camera checks (Math is listed first and is TalkBack-friendly) at Hard difficulty with double the count. The link appears immediately when the camera or its permission is unavailable, otherwise after 5 failed attempts (see EXPERIENCE.md → Fallback check flow). The alarm keeps ringing (no new grace window). The fallback check is limited to **once per session**, shown prominently in progress stats, and 3 fallback checks in 7 days prompts the user to re-register the reference photo/code.
- **FR-PWK-12** [Must] Setup screens let the user try each check before saving.
- **FR-PWK-13** [Could] The app generates a printable QR code for the QR/Barcode check.

### 7.4 Sounds & motivation (SND)
- **FR-SND-1** [Must] Built-in library of alarm sounds (≥ 10, royalty-free) plus the phone's system ringtones. Every bundled sound peaks ≥ −3 dBFS with integrated loudness ≥ −14 LUFS (checked by a script over the bundled files in the quality gate). (User audio file: FR-SND-6.)
- **FR-SND-2** [Must] Preview any sound before choosing.
- **FR-SND-3** [Should] Record a **motivation message** in the app (up to 60 s), re-record, play back, delete. Multiple recordings allowed; choose one per alarm or "random".
- **FR-SND-4** [Should] Motivation playback per alarm: **After I'm up** (plays once the check is completed). ("Mix into alarm": FR-SND-7.)
- **FR-SND-5** [Must] **Default sound fallback:** if a chosen file/recording is missing or broken, the default built-in sound plays. The alarm must never be silent.
- **FR-SND-6** [Should] The user can pick an audio file from the device as an alarm sound (subject to FR-SND-5).
- **FR-SND-7** [Could] Motivation playback option **Mix into alarm** (alternates with the alarm sound).

### 7.5 Wake-up progress (PRG)
- **FR-PRG-1** [Must] Every session is logged: scheduled time, first ring, end time, snoozes, amount paid (priceAmountMicros + currency per snooze), check types, time to complete, fallback check used, Direct Boot flag, outcome (Glossary: Outcome values).
  - **On time:** check completed with 0 snoozes.
  - **Snoozed:** check completed after ≥ 1 paid snooze.
  - **Missed:** session ended by the 30-minute no-interaction timeout (even if snoozes were paid earlier).
  - **Skipped / Test:** as named; excluded from rates and streaks.
- **FR-PRG-2** [Must] Progress screen shows:
  - Current and best **zero-snooze streak**.
  - On-time rate (7 / 30 days).
  - Average minutes from first ring to up.
  - Snoozes over the last 30 days (a count; each snoozed morning is marked on the 30-morning ring).
  - **Money paid** this week / month / all-time — framed as a cost to reduce. Totals are computed **per currency** from stored micros (one line per currency if the user has paid in more than one); zero is shown as "Nothing paid", never as a hard-coded "$0".
  - Layout and motion follow EXPERIENCE.md (ring of the last 30 mornings, stat tiles, streak card, money and insight; no period tabs).
- **FR-PRG-3** [Should] Calendar view: each day coloured by outcome.
- **FR-PRG-4** [Must] Purchase history with date, alarm, snooze number and currency-formatted price. Stranded purchases show "Not used, refunded automatically by Google" (or the reused snooze once FR-RNG-10 applies).
- **FR-PRG-5** [Should] Weekly summary notification (optional, default on; Sunday 19:00 local time [ASSUMPTION — A6]): "3 on-time mornings, nothing paid. Nice."
- ~~**FR-PRG-6** [Could] Export history as CSV.~~ Removed (owner decision 2026-10-01).

### 7.6 Messaging: "we don't want you to pay" (MSG)
- **FR-MSG-1** [Must] Onboarding explains the mission in one screen: "This app makes money only when you snooze. We hope you never pay us."
- **FR-MSG-2** [Must] Home screen highlights zero-snooze progress (streak, "Nothing paid this week" or the currency-formatted amount) above everything else.
- **FR-MSG-3** [Must] Zero-snooze mornings get a celebration after the check (one screen, ≤ 3 s animation, copy and motion in EXPERIENCE.md); paid mornings do not get shamed.
- **FR-MSG-4** [Must] Copy guidelines (tone, words to avoid) live in EXPERIENCE.md → Voice and Tone and apply to all screens and notifications.

### 7.7 Onboarding & reliability setup (ONB)
- **FR-ONB-1** [Must] First launch: mission screen → alarm behaviour disclosure and consent (FR-ONB-5) → set base fee → create first alarm → choose checks → permissions → analytics choice (FR-ONB-6) → test alarm.
- **FR-ONB-2** [Must] Permissions & settings checklist, each with a reason and a status tick:
  - Notifications.
  - Full-screen alarm (verify it's granted; deep-link if not).
  - Exact alarms (Android 12–12L only).
  - Do Not Disturb allows alarms (detected via `NotificationManager` interruption filter and notification policy; warn if alarms are blocked). OEM "total silence" modes the API doesn't expose are covered by the OEM guidance item, not detected.
  - Battery optimization exemption (deep-link to settings).
  - Manufacturer-specific auto-start / background guidance (Xiaomi, Samsung, Huawei, Oppo/Realme, Vivo, OnePlus).
  - Camera (only when House Hunt or QR/Barcode is selected).
  - Microphone (only when recording).
- **FR-ONB-3** [Must] The checklist is available later in Settings and flags anything that becomes revoked ("Alarm may not ring: battery optimization turned back on").
- **FR-ONB-4** [Must] The onboarding test-alarm step asks the user to run a test alarm with the screen locked; it can be skipped only via an explicit "Skip for now", and the checklist item stays unticked until a locked-screen test has completed.
- **FR-ONB-5** [Must] **Alarm behaviour disclosure and consent:** before the first alarm is saved, one screen states plainly that the alarm keeps ringing until the check is done or a snooze is paid, that the phone stays fully usable (calls, other apps, emergency dialer), and lists the accepted escapes (force-stop, uninstall, powering off, 30 minutes with no interaction). The user must tap "I understand" to continue. Also shown under You → "How payments & refunds work".
- **FR-ONB-6** [Must] Ask once whether to share anonymous usage statistics (NFR-15); default **off**; changeable in Settings (FR-SET-6).

### 7.8 Settings (SET)
FR-SET-3, FR-SET-4 and FR-SET-5, together with Purchase history (FR-PRG-4), are reached from the **You** tab, not Settings. Settings keeps app behaviour only: Snooze, Wake, Appearance, Notifications, Usage stats and the Reliability checklist. There is still no account or sign-in (NFR-4).

- **FR-SET-1** [Must] Base fee (with commitment lock rules), max snoozes per session, default grace window (Quiet time), default snooze length.
- **FR-SET-2** [Must] Reliability checklist (FR-ONB-3).
- **FR-SET-3** [Must] Privacy policy, terms, support contact, "How payments & refunds work" (including the alarm behaviour disclosure, FR-ONB-5).
- **FR-SET-4** [Must] Delete all data.
- **FR-SET-5** [Must] **"Problem with a charge?"**: explains Google's 48-hour self-refund, links to Google Play order history, and offers a support email pre-filled with the order ID and time from local history.
- **FR-SET-6** [Must] Toggle for anonymous usage statistics (NFR-15), default off.

## 8. Non-functional requirements

- **NFR-1 Reliability:** alarm starts within 2 s of the scheduled time on the device test matrix (the owner's Oppo A96 (ColorOS, Android 13) plus Gradle Managed Device emulators (API 26, 31, 34, 36, 37); other makers (Xiaomi, Pixel hardware, budget phones) via optional Firebase Test Lab runs), with screen off, locked, Doze, DND and battery saver. Verified by: Spike S2, E2 device-verification checklist.
- **NFR-2 Never silent:** any failure (sound file, audio focus, crash in the checks) triggers the default sound fallback (FR-SND-5); a crash or kill in the ringing flow is recovered by FR-SES-1/2.
- **NFR-3 Offline:** everything except payment works offline. Snooze is unavailable offline and says so.
- **NFR-4 Privacy:** no account and no backend; data, photos and recordings stay on device except as listed in Principle 5; camera images for House Hunt/QR processed on-device and never uploaded.
- **NFR-5 Payments:** only Google Play Billing; price always shown before purchase; complies with Google Play Payments policy. The content rating comes from the IARC questionnaire (likely low + "In-app purchases"). **Target audience = 18+** in Play Console App content (decided; Q6 closed). This reduces but does not prevent minors paying; Google Play parental controls and purchase authentication are the real guard, and onboarding recommends turning on Play purchase authentication.
- **NFR-6 Platform:** minSdk 26 (Android 8.0), targetSdk 36 (Android 16), Play Billing Library 8+.
- **NFR-7 Performance:** ringing screen visible ≤ 1 s after alarm trigger; app cold start ≤ 1.5 s on the reference mid-range device (Pixel 6a [ASSUMPTION — A7]).
- **NFR-8 Battery:** no background work except scheduled alarms; no persistent service while idle.
- **NFR-9 Accessibility:** TalkBack labels, 48dp touch targets, supports large font; each check type has an accessible alternative or the fallback check. (Known gap: the fallback check's Memory Sequence is visual; see Q12.)
- **NFR-10 Localization-ready:** all strings in resources; currency always formatted from Play data, never hard-coded. English only at launch; more languages only if demand shows up (Q7 closed).
- **NFR-11 Maintainability & testability (required for the Ralph loop):**
  - Business logic (fee engine, session state machine, check generators/validators, stats, billing recovery rules) lives in a platform-independent shared module with ≥ 90% line coverage, **enforced by Kover** in the quality gate.
  - All time goes through an injectable `Clock` (wall + monotonic); billing, alarm scheduling, audio and camera sit behind interfaces with **fakes** for tests.
  - Every feature story has acceptance checks runnable from the command line (unit tests with fakes, Robolectric, emulator tests). A debug-only "fire now / time warp" hook (never in release builds) lets device checks run in minutes.
  - **Human-verify protocol:** behaviour that can't be verified automatically (lock screen, Doze, real billing, camera, OEM behaviour) is collected into **one device-verification checklist story at the end of each epic**, tagged `human-verify`. The owner runs it on the device matrix and records pass/fail per item (with device, Android version and date) in the story file. **Ralph must never mark a `human-verify` story done**; a failed item becomes a new bug story. Expected owner time ≈ 2–4 h per epic [ASSUMPTION — A4].
  - Instrumented tests run on an emulator (Gradle Managed Devices) in CI.
- **NFR-12 Shareability with iOS:** shared module must not depend on Android APIs.
- **NFR-13 Play policy — no device hostage (Malware / Device and Network Abuse policy):** the app never blocks Home, Recents, the power menu, calls, the emergency dialer or other apps; never starts activities from the background; never uses overlay (`SYSTEM_ALERT_WINDOW`), accessibility services, lock-task mode or device admin; never routes audio away from the user's chosen output (it plays on the alarm stream at the set volume); captures volume keys only while the ringing screen is in the foreground. The free path ("I'm up" → check) is always shown first and works offline (FR-RNG-9). The App content "core functionality" declaration and FGS demo video show the free path and the phone staying usable.
- **NFR-14 Backup:** Android Auto Backup (`dataExtractionRules`) backs up alarms, settings and history to the user's own Google account. **Excluded:** House Hunt photos, motivation recordings, custom audio references, the active session and pending purchase intents. After a restore, House Hunt alarms ask the user to re-take reference photos. Disclosed in the privacy policy and Data safety form.
- **NFR-15 Telemetry (no own backend):** Firebase Crashlytics for crash reports (always on, disclosed, no personal content, no free-text or media). Firebase Analytics **only if the user opts in** (FR-ONB-6, default off), limited to anonymous aggregate events: `session_outcome` (outcome value), `snooze_count`, `check_type`. No other events, no advertising ID, no user properties beyond these. Disclosed in the privacy policy and Data safety form. [ASSUMPTION — A1, owner to confirm]

## 9. Technical direction (input for the Architecture step)

Recommended stack — to be finalized in ARCHITECTURE-SPINE.md:

- **Kotlin Multiplatform (KMP)**
  - `shared` module (commonMain): domain models, fee engine, alarm session state machine (§6.4), billing recovery rules (§6.3), check generators & validators, stats, repository interfaces. Reusable by a future iOS app.
  - Persistence: Room (KMP-compatible) or SQLDelight; settings via DataStore.
- **UI: Compose Multiplatform**, written so the same screens can later run on iOS (stable on iOS since v1.8, May 2025). Platform-only parts sit behind interfaces with Android implementations:
  - Alarm scheduling: `AlarmManager.setAlarmClock()` + `USE_EXACT_ALARM`; boot/time-change receivers.
  - Ringing: full-screen intent notification + `showWhenLocked` activity; foreground service (media playback type) for audio on the alarm stream; no background activity starts (FR-SES-4).
  - Billing: Play Billing Library 9.x, `obfuscatedProfileId = sessionId`.
  - Camera/scanning/matching: CameraX + ML Kit barcode scanning; on-device image similarity for House Hunt (e.g., MediaPipe image embedder).
  - Audio recording: MediaRecorder.
  - Telemetry: Firebase Crashlytics; Firebase Analytics gated by the opt-in flag (NFR-15).
- **iOS later:** AlarmKit (iOS 26+) for alarms, StoreKit 2 consumables for payments, same shared module.
- **Quality gates (every Ralph iteration):** `./gradlew qualityGate` as defined in ARCHITECTURE-SPINE AD-14 (includes the bundled-sound loudness script, FR-SND-1). Instrumented emulator tests run in CI, not every iteration.
- **CI:** GitHub Actions running the same gates on every push; release builds signed via Play App Signing.
- **No backend in MVP.**

### Play Console declarations needed
- `USE_EXACT_ALARM` (alarm clock is an allowed core use case); `SCHEDULE_EXACT_ALARM` with `maxSdkVersion="32"` for Android 12–12L.
- Direct Boot receiver (`LOCKED_BOOT_COMPLETED`). Note: Android 15+ doesn't allow starting a media-playback foreground service from `BOOT_COMPLETED` — re-arm via exact alarm instead.
- `USE_FULL_SCREEN_INTENT` (automatically granted for alarm apps; declare core functionality in App content).
- Foreground service type declaration with demo video (showing the free path and that the phone stays usable, NFR-13).
- Data safety form (Auto Backup, Crashlytics, opt-in Analytics), privacy policy URL, content rating, target audience 18+.
- Trader status with publishable contact details (EU DSA) — launch blocker (§13).

## 10. Technical spikes (do before feature epics)

- **S1 — Payment while ringing:** can the Google Play purchase sheet be shown from the lock-screen ringing activity (with `requestDismissKeyguard`)? How long does a typical purchase take while the alarm rings? Does `obfuscatedProfileId` round-trip on every purchase path (including pending)? Test with license testers. **Result decides FR-RNG-3/5.**
- **S2 — Alarm reliability & escapes:** prototype alarm + full-screen ringing + backup alarm + FGS notification return path; run overnight tests on the device matrix (Doze, battery saver, DND, OEM killers, overnight reboot before unlock, Task Manager "Stop", swipe from Recents, headphones connected, incoming call). Confirm FR-SES-4 behaviour without background activity starts.
- **S3 — House Hunt matching:** on-device image similarity accuracy (same spot, different lighting / angle) vs false accepts (photo of a photo, random room). Pick a threshold or downgrade the feature (cut line, §7).

## 11. Success metrics

[ASSUMPTION — A1, owner to confirm] There is no own backend, so every metric names its data source. Behaviour metrics come only from users who opted in to anonymous statistics (NFR-15), a self-selected sample.

| ID | Metric | Target | Source |
|---|---|---|---|
| SM-1 | Reliability: confirmed "alarm didn't ring" bugs | 0 in closed test | Closed-test device checklists + tester reports |
| SM-2 | Reliability after launch: crash-free sessions; ANR and crash rates | ≥ 99.5% crash-free; below Android vitals bad-behaviour thresholds | Firebase Crashlytics; Play Console Android vitals |
| SM-3 | Behaviour change (primary): snoozes per session, week 4 vs week 1, users active 4+ weeks | ≥ 40% lower | Opt-in Firebase Analytics (`session_outcome`, `snooze_count`) |
| SM-4 | Retention D30 | ≥ 20% | Play Console (retained installers) |
| SM-5 | Trust: Play rating | ≥ 4.3 | Play Console |
| SM-6 | Revenue: tracked, **not** optimized directly | — | Play Console order management |

**Counter-metrics** (a rise is a warning, not a win):

| ID | Counter-metric | Alarm threshold | Source |
|---|---|---|---|
| CM-1 | Refund rate (refunds / purchases) | > 5% | Play Console order management |
| CM-2 | Uninstall within 24 h after first paid snooze | > 15% [ASSUMPTION — A8] | Opt-in Firebase Analytics (`app_remove` after first `session_outcome` with `snooze_count` ≥ 1) |
| CM-3 | Week-1 heavy payers (≥ 3 paid snoozes) still active at D30 | < half the overall D30 | Opt-in Firebase Analytics |
| CM-4 | Fallback check share of sessions | > 10% | Opt-in Firebase Analytics (`check_type`) |

## 12. Risks

| Risk | Impact | Mitigation |
|---|---|---|
| Alarm fails on some OEM phones | Critical | Spike S2, reliability checklist, device matrix, test-alarm onboarding |
| Play flags the app as device hostage / ransomware-like (Malware policy: loud alarm + pay to stop) | High | FR-SES-4 (no background relaunch), FR-SES-6 (alarm stream, volume keys only in foreground), FR-SES-9 + NFR-13 (phone always usable, no overlays), FR-RNG-9 (free path ≤ 1 tap), FR-ONB-5 disclosure and consent, accepted escapes listed, demo video, pre-submission policy review (E9) |
| Can't pay over the lock screen / slow payment | High | Spike S1; clear unlock prompt; keep ringing |
| Play policy rejection (payment framing, permissions) | High | Clear pricing, no dark patterns, proper declarations, 18+ target audience |
| Users feel exploited ("app wants me to fail") | High | Mission messaging, caps, commitment lock only protects the user, free wake-up always; CM-1..3 |
| User charged for a snooze they never got | High | Purchase intent + `obfuscatedProfileId`; stranded purchases never consumed (auto-refund) or reused with consent (§6.3) |
| Minors pay despite 18+ target audience | Medium | Google Play parental controls / purchase authentication; onboarding recommendation; FR-SET-5 support path |
| Refund abuse | Medium | Accept; Google limits self-refunds; monitor CM-1 |
| House Hunt false rejects | Medium | Spike S3, multiple reference photos, fallback check, cut line |
| EU DSA trader status exposes personal address | Medium | Launch blocker: business address/contact before production (§13) |

## 13. Release plan

1. **Planning (≈1 week):** this PRD → UX design (DESIGN.md, EXPERIENCE.md) → ARCHITECTURE-SPINE.md → epics & stories → readiness check.
2. **Spikes S1–S3 (≈1 week).**
3. **Build with Ralph loop (≈3–4 weeks)** [ASSUMPTION — A3], one epic at a time; each epic ends with its device-verification checklist story (NFR-11).
4. **Internal test → closed test** (≥ 12 testers × 14 days, required for new personal Play accounts). The closed test **overlaps final polish**.
5. **Production access review → production launch** (Android). iOS planning starts after stable Android release.

### Timeline

| Week | Work |
|---|---|
| 1 | Planning |
| 2 | Spikes S1–S3 |
| 3–6 | Ralph build E0, E2–E8 (cut line applies if late) |
| 7–8 | Internal test, then closed test (14 days) running alongside final polish and E9 |
| 9–10 | Production access application, review, launch |

### Launch blockers (must be done before production submission)
- **EU DSA trader status:** a publishable business address and contact (not the owner's home address) set in Play Console, or EU countries excluded at launch.
- Privacy policy and Data safety form covering Auto Backup (NFR-14), Crashlytics and opt-in Analytics (NFR-15).
- FGS / full-screen intent / exact alarm declarations with demo video (NFR-13).
- Pre-submission policy review against the device-hostage risk (§12).
- All `human-verify` checklist stories passed on the device matrix.
- Closed test completed (≥ 12 testers × 14 days).

### Draft epic list (to be expanded by the BMAD epics & stories workflow)
Every epic ends with a device-verification checklist story (NFR-11).
- **E0** Project foundation: KMP skeleton, CI, quality gates, Ralph harness, Crashlytics. → NFR-6, NFR-11, NFR-12, NFR-15 (Crashlytics)
- **E1** Spikes S1–S3.
- **E2** Alarm core: scheduling, reliability, ringing screen, session integrity. → FR-ALM-*, FR-SES-*, §6.4, NFR-1, NFR-2, NFR-13
- **E3** Proof-of-wake engine + grace window. → FR-PWK-2, -3, -9, -10, -11, -12
- **E4** Check types. → FR-PWK-1, -4, -5, -6, -7, -8, -13
- **E5** Snooze payments: fee engine, Play Billing, recovery, history. → §6.2, §6.3, FR-RNG-*, FR-PRG-4, FR-SET-5
- **E6** Sounds & motivation recordings. → FR-SND-*
- **E7** Progress tracking. → FR-PRG-1..3, -5, -6, FR-MSG-2, FR-MSG-3
- **E8** Onboarding, permissions, reliability checklist, settings. → FR-ONB-*, FR-MSG-1, FR-MSG-4, FR-SET-1..4, FR-SET-6, NFR-14, NFR-15 (Analytics opt-in)
- **E9** Release readiness: store listing, privacy policy, data safety, declarations, launch blockers, closed test.

## 14. Open questions

- **Q1** Max base fee — $10 OK?
- **Q2** Per-snooze cap — $50 OK?
- **Q3** Default max snoozes per session — 5?
- **Q4** Commitment lock — delay "weakening" changes made within 8 h of an alarm (§6.2)? Is 8 h right?
- **Q5** ~~Lower volume during payment?~~ Decided: no, keep ringing (FR-RNG-5). Revisit only if Spike S1 shows payment takes too long.
- **Q6** ~~Set Play target audience to 18+?~~ Decided: yes (NFR-5), with the clarification that it doesn't prevent minors paying.
- **Q7** ~~Second language?~~ Decided 2026-09-26: English only.
- **Q8** ~~App name?~~ Decided 2026-09-26: **Yawn & Pawn**, package id `com.yawnandpawn.app`. No same-name app found on Google Play at decision time; a trademark search is still recommended before launch.
- **Q9** ~~Allow skipping the next alarm?~~ Decided 2026-09-26: keep FR-ALM-10 as written, [Could] priority.

Deferred review findings (each with a revisit condition):

- **Q10** Check parameters are incomplete: count ranges for Memory Sequence rounds, Word Unscramble and House Hunt; Math Hard operand bounds; word-list source and offensive-word filter (rubric, medium). *Revisit:* before E4 stories are written.
- **Q11** Fallback check can be triggered on purpose (deliberate failed House Hunt matches); should fallback require an objective failure signal and be at least as hard? (adversarial M7). *Revisit:* after Spike S3, or if CM-4 > 10% in closed test.
- **Q12** Accessible fallback path for TalkBack users (fallback check uses visual Memory Sequence) (rubric medium, adversarial L4). *Revisit:* before E3 stories are written.
- **Q13** Refund-and-repeat makes snoozes free for savvy users; add server-side voided-purchase checks post-MVP? (adversarial M12). *Revisit:* if CM-1 > 5% in closed test or first month.
- **Q14** Cap the call pause (a self-call or VoIP call pauses the alarm indefinitely) (adversarial M5, partly). *Revisit:* if closed testers report it as an escape.
- **Q15** Calendar day colour when a day has several sessions; outcome logged for an alarm disabled/deleted inside the commitment-lock window (rubric medium). *Revisit:* before E7 stories are written.
- **Q16** Commitment lock can be bypassed by deleting and recreating an alarm; accept as a nudge or make new alarms inherit strength? (adversarial L5). *Revisit:* with Q4.
- **Q17** EU/UK right-of-withdrawal consent for instantly delivered consumables (rubric low). *Revisit:* with the privacy/terms author before production (alongside the DSA blocker).
- **Q18** Backup alarm via `setAlarmClock` changes the system "next alarm" indicator every minute (adversarial L3). *Revisit:* Spike S2 result.
- **Q19** Property-based and mutation tests for the fee engine and state machine beyond the Kover gate (adversarial L6). *Revisit:* E0 planning.
- **Q20** Trim §2 differentiators, fold or justify the "routine builder" persona, renumber "7.1b" (rubric low). *Revisit:* next PRD revision; no build impact.

### Assumptions index
| ID | Assumption | Where |
|---|---|---|
| A1 | Metrics plan: Crashlytics always on + opt-in Firebase Analytics with only three events is acceptable to the owner and Play Data safety | NFR-15, §11 |
| A2 | Users will pay at least $1 to snooze, and Play accepts pay-to-snooze consumables with the NFR-13 safeguards | §1, §12 |
| A3 | Ralph build fits 3–4 weeks; total ≈ 9–10 weeks with closed test | G4, §13 |
| A4 | Owner can run device-verification checklists at ≈ 2–4 h per epic | NFR-11 |
| A5 | Competitor landscape claims in §2 | §2 |
| A6 | Weekly summary on Sunday 19:00 local | FR-PRG-5 |
| A7 | Pixel 6a as the mid-range reference device | NFR-7 |
| A8 | 15% threshold for CM-2 | §11 |

## 15. Research sources
- Google Play Billing integration (consumables, acknowledgement, PBL 9.1, PBL 8 deadline): https://developer.android.com/google/play/billing/integrate
- Product catalog limits (1,000 products/app): https://support.google.com/googleplay/android-developer/answer/16431770
- Purchase options & offers for one-time products: https://developer.android.com/google/play/billing/one-time-product-multi-purchase-options-offers
- Pricing setup (templates discontinued Oct 27, 2025): https://support.google.com/googleplay/android-developer/answer/6334373
- Refund policy (48 h): https://support.google.com/googleplay/answer/15574908
- Service fees 2026: https://support.google.com/googleplay/android-developer/answer/16954621
- Exact alarms / setAlarmClock: https://developer.android.com/develop/background-work/services/alarms/schedule
- Full-screen intent & FGS requirements: https://support.google.com/googleplay/android-developer/answer/13392821
- Target API level (API 36 from Aug 31, 2026): https://support.google.com/googleplay/android-developer/answer/11926878
- Compose Multiplatform iOS stable: https://blog.jetbrains.com/kotlin/2025/05/compose-multiplatform-1-8-0-released-compose-multiplatform-for-ios-is-stable-and-production-ready/

## Changes in v0.3

- **Design preview decisions (2026-10-01, sprint-change-proposal-2026-10-01):** FR-ALM-6 ramp starts at a fixed 20% of the set level; FR-PRG-2 shows snoozes over the last 30 days instead of a weekly chart, with layout per EXPERIENCE.md; FR-PRG-6 CSV export removed (and taken off the cut line); Glossary notes that the grace window is shown to users as "Quiet time"; §7.8 note that FR-SET-3, FR-SET-4, FR-SET-5 and Purchase history live on the You tab; FR-SET-1 and FR-ONB-5 wording; NFR-1 device matrix is the owner's Oppo A96 (ColorOS, Android 13).

## Changes in v0.2

- **Billing (§6.3):** purchases linked to sessions via purchase intent + `obfuscatedProfileId = sessionId`; recovery table replaces the old rule (stranded purchases never consumed, auto-refunded, labelled in history); stranded purchase reuse offer (FR-RNG-10); `ITEM_ALREADY_OWNED` handling; pending/recovery contradiction removed.
- **Money:** priceAmountMicros + currency stored per purchase; per-currency totals; no hard-coded "$" in UI copy; tax note on the confirm sheet.
- **Priorities:** MoSCoW tag on every FR and a cut line (§7). Split for priority: printable QR → FR-PWK-13, custom audio file → FR-SND-6, "Mix into alarm" → FR-SND-7.
- **Glossary (§5a)** added; "backup check" renamed "fallback check"; sound fallback renamed "default sound fallback"; Direct Boot substitution named.
- **User journeys (§6.5)** UJ1–UJ5 added.
- **Device-hostage risk:** new risk row (High), Principle 6, FR-SES-9, FR-RNG-9, FR-ONB-5, NFR-13; FR-SES-4 no longer relaunches the activity; FR-SES-6 no longer forces the speaker; accepted escapes listed.
- **Interaction and calls:** "interaction" defined (FR-ALM-9); calls via audio focus loss, no `READ_PHONE_STATE` (FR-SES-8).
- **Direct Boot:** Snooze unavailable before first unlock (FR-ALM-11).
- **Session state machine (§6.4):** conflict rules added; full table deferred to ARCHITECTURE-SPINE.md; FR-SES-10; FR-SES-7 merge rules specified.
- **Metrics (§11):** data source per metric (Play Console, Android vitals, Crashlytics, opt-in Firebase Analytics); SM/CM IDs; counter-metrics; NFR-15; FR-ONB-6, FR-SET-6. Marked [ASSUMPTION — A1].
- **Human-verify protocol (NFR-11):** one checklist story per epic, owner records pass/fail, Ralph never marks it done; debug fire-now hook.
- **Backup and charges:** NFR-14 Auto Backup rules; FR-SET-5 "Problem with a charge?".
- **Release (§13):** timeline table; launch blockers incl. EU DSA trader status; closed test overlaps final polish; FR-group → epic mapping.
- **18+ (NFR-5):** clarified as a reduction, not a guard; Q6 closed.
- **References:** design.md section numbers replaced by EXPERIENCE.md → Voice and Tone / Snooze confirm sheet; frontmatter lists DESIGN.md, EXPERIENCE.md, ARCHITECTURE-SPINE.md.
- **Testable wording:** FR-ALM-3, FR-SES-2, FR-RNG-4, FR-RNG-8, FR-SND-1, FR-PWK-6, FR-PWK-11, FR-MSG-3, FR-ONB-2, FR-ONB-4, NFR-7 given measurable pass conditions; catalogue headroom and `productFor` test (§6.3).
- **Assumptions index** added; deferred review findings logged as Q10–Q20.
