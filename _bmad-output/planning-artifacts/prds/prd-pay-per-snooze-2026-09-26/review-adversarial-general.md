---
title: Adversarial Review — Pay Per Snooze PRD v0.1
reviewed: prd.md (v0.1 draft, 2026-09-26)
lens: adversarial (general)
also_considered: Google Play policy risk, payment edge cases, automatic verifiability of derived stories
fixed_owner_decisions_not_attacked: revenue only from snooze payments; $1 min base fee rising B×N; user-chosen checks; 15–30 s grace window; Android first
date: 2026-09-26
---

# Adversarial Review — Pay Per Snooze PRD

Each finding uses the lens's fields: **location**, **trigger_condition**, **guard_snippet** (suggested fix) and **potential_consequence**. The caller asked for severity tags, so each finding also has one (Critical / High / Medium / Low). They are sorted with the most severe first.

**Counts:** Critical 2 · High 5 · Medium 13 · Low 6 · **Total 26**

---

## Critical

### C1. Session-integrity features plus "pay to stop" look like Play's ransomware / device-hostage pattern
- **Location:** §7.1b FR-SES-3, FR-SES-4, FR-SES-6; §7.2 FR-RNG-1/2; §12 Risks ("Play policy rejection")
- **trigger_condition:** Several features together match how Google Play's Malware / Device and Network Abuse policies describe apps that take control of a device and ask for money. Those features are: an app that locks its own UI, captures volume keys, re-applies volume, sends audio to the speaker even when headphones are connected, relaunches itself when the user leaves, and keeps a notification that can't be dismissed, all while a paid button stops the noise. The risk table only lists "payment framing, permissions" and does not name this pattern.
- **guard_snippet:** Add a Play-policy subsection that states the free exit (the check) is always shown first and always works, even offline. List exactly what the app does not do: it never blocks Home or Recents at the OS level, never blocks the power button, and never touches other apps. Add FR: "Snooze is never the only way to stop the sound; the 'I'm up' path must be reachable in ≤ 1 tap from every ringing state." Write reviewer notes and a demo video script for the App content "core functionality" declaration that show the free path. Add a pre-submission policy review to E9. Also consider dropping the forced speaker routing (FR-SES-6), because it is the feature most likely to be read as hostile.
- **potential_consequence:** The app is rejected or suspended, and the developer account gets a strike. A new personal account can't absorb that, and it restarts the 14-day closed-test clock.

### C2. The recovery rule consumes paid purchases that never granted a snooze, so users are charged for nothing
- **Location:** §6.3 "Pending purchases", "Recovery", "Grant rule"
- **trigger_condition:** The Recovery rule says that on app start, any purchased-but-unconsumed token that isn't for the current session gets "consume[d] and record[ed] in history". That clashes with other flows:
  - (a) A pending purchase that clears after the session ends will show up as `PURCHASED`. Recovery then consumes it, which directly contradicts the promise that it will "auto-refund after 3 days".
  - (b) Suppose the purchase succeeded on Google's side but the client lost the result (network drop, process death). If the user then finished the check, recovery charges them for a snooze they never got.
  - (c) Nothing says how a token is tied to a session. `developerPayload` no longer exists, and a Purchase object carries no session ID.
- **guard_snippet:** Before calling `launchBillingFlow`, persist `{sessionId, productId, launchTime}`. Pass a per-install `obfuscatedAccountId` and a per-session `obfuscatedProfileId` so each token can be matched to a session. Replace the recovery rule with: "A `PURCHASED` token that maps to the active session and hasn't been granted yet → grant, then consume. **Any token that did not grant a snooze is never acknowledged or consumed; it auto-refunds after 3 days.** Only tokens already granted get consumed on recovery." Add a unit-test table in the fee/billing module that covers every row (pending→purchased during or after the session, lost callback, crash between grant and consume, duplicate delivery).
- **potential_consequence:** Users are charged without getting what they paid for, which breaks G3 and principle 3. It leads to refund requests, 1-star reviews ("charged me and kept ringing") and possible Play Payments policy action.

---

## High

### H1. FR-SES-4 "relaunches it" can't be done on modern Android
- **Location:** §7.1b FR-SES-4; §10 S2
- **trigger_condition:** Background activity launch restrictions (Android 10+) stop the app from bringing its activity back after the user presses Home on an unlocked device. A full-screen intent only goes full screen when the device is locked or the screen is off. The FR promises a relaunch in one clause and then describes the real behaviour (a heads-up notification) in the next, so a story built from it can't be passed as written.
- **guard_snippet:** Rewrite FR-SES-4 by device state. Locked or screen off → full-screen activity. Unlocked → a sticky, high-priority heads-up/ongoing notification, with the sound continuing; the app does not try to relaunch itself. Remove the word "relaunches". Add a policy note that the app must not use workarounds (overlay permission, accessibility service) to force a relaunch.
- **potential_consequence:** Ralph builds workarounds (SYSTEM_ALERT_WINDOW, accessibility abuse) that break Play policy (see C1), or the story never passes human-verify.

### H2. The session state machine is only a list of states, so the edge cases stories depend on are untestable
- **Location:** §6.1; FR-SES-1; FR-RNG-5; FR-PWK-9/10/11; FR-ALM-9; FR-SES-7/8
- **trigger_condition:** The PRD lists the states (ringing, grace, in check, paying, snoozed) but defines no transitions. These interactions are left undefined:
  - Does the Play sheet being open count as "interaction" for the 30-minute timeout?
  - Does grace countdown or check progress survive a snooze?
  - What happens when a call arrives during grace, or during payment?
  - What happens when a merged overlapping alarm has a different B or different checks?
  - What happens when the grace window expires while the Play sheet is open?
  - Does a pending payment return to "ringing" or to "in check"?
- **guard_snippet:** Add §6.4 "Session state machine" with an explicit transition table: state × event → next state plus side effects (sound on/off, timers started or reset, fee N). Include the events ring, tap I'm up, grace expire, check complete, tap snooze, purchase PURCHASED / PENDING / CANCELLED / ERROR, call start/end, process death, reboot, clock change, overlapping alarm due, 30-min timeout, fallback requested. Require the shared module to encode the table, with one parameterised test per row.
- **potential_consequence:** Each story makes its own guess, the guesses conflict, and the gaps only show up during owner device tests. The Ralph loop then churns on behaviour that was never specified.

### H3. Most of the risky surface is `human-verify`, so the Ralph loop hits an owner bottleneck
- **Location:** §8 NFR-11; §13 release plan (build ≈3–4 weeks)
- **trigger_condition:** Nearly all of E2 (alarm core), E4 (camera checks), E5 (real billing) and E8 (permissions/OEM) are lock screen, Doze, billing or camera work, so all of it ends up as `human-verify`. The PRD doesn't say what a human-verify check consists of, what evidence it needs, how many are allowed per epic, or how Ralph carries on while it waits. "Every story has acceptance checks runnable from the command line" contradicts the existence of human-verify stories.
- **guard_snippet:**
  - Require every human-verify story to also ship automated coverage behind the platform interface: fake BillingClient, fake AlarmScheduler, a Robolectric or emulator test of the activity flags (`showWhenLocked`, `turnScreenOn`), and an adb-driven emulator script (`adb shell cmd alarm`, `dumpsys deviceidle force-idle`, `am broadcast` for boot and time-change) that covers the parts an emulator can run.
  - Define a human-verify protocol: a numbered checklist in the story, a pass/fail record committed to the repo, and a screen recording for anything touching the lock screen or payments.
  - Add a debug-only "time warp / fire now" hook (never in release builds) so the owner can check in minutes, not overnight.
  - Cap human-verify stories per epic, or batch them at the end of each epic.
- **potential_consequence:** The build phase goes well past 3–4 weeks. Stories get marked done without real verification, and the most important reliability behaviour ships the least tested.

### H4. Success metrics can't be measured with no backend, no analytics and on-device-only data
- **Location:** §11 Success metrics; §5 principle 5; NFR-4; §6.3 "Refunds"
- **trigger_condition:** None of these can be observed by the developer: "snoozes per session week 4 vs week 1", "alarm-failure reports < 0.1% of sessions", "D30 ≥ 20%" per active user, and "refund rate < 5%" (§6.3 itself admits refunds can't be detected in-app). The data never leaves the device, and no telemetry is planned.
- **guard_snippet:** For each metric, name its source:
  - Play Console (installs, uninstalls, retained-installer cohorts, order management for refunds and chargebacks).
  - An opt-in, anonymous, aggregate-only weekly ping (disclosed in Data safety).
  - Closed-test surveys.
  Alternatively, downgrade the behaviour metric to "measured in closed test via opt-in CSV export (FR-PRG-6)". Add an explicit FR for any opt-in telemetry, or state that the metric is only measured in closed test.
- **potential_consequence:** The primary goal (G1) and the trust goal can't be evaluated after launch, and launch decisions get made blind.

### H5. Many FR phrasings are subjective, so derived stories can't be checked automatically
- **Location:** FR-SND-1 ("loud and tested"), FR-RNG-4 ("clear message"), FR-RNG-8 ("gentle message"), FR-ONB-4 ("strong recommendation"), FR-ONB-2 ("OEM 'total silence' mode"), NFR-7 ("mid-range devices"), FR-PWK-6 ("verifies it"), FR-PWK-11 ("camera fails"), FR-ALM-3 ("exact scheduled time")
- **trigger_condition:** These wordings have no measurable pass condition. Ralph will write an assertion to match whatever it built, or the story ends up as human-verify by default.
- **guard_snippet:** Replace each with a testable criterion. Examples:
  - FR-SND-1: "each bundled sound peaks ≥ −3 dBFS, RMS ≥ −14 dBFS (checked by a script over res/raw)".
  - FR-RNG-4: "shows string resource `err_payment_<reason>` per BillingResponseCode; mapping table unit-tested".
  - FR-ALM-3: "`AlarmManager.setAlarmClock` called with trigger time == scheduled epoch ms (fake scheduler assertion); on-device ≤ 2 s per NFR-1 is human-verify".
  - NFR-7: name the reference device.
  - FR-PWK-11: define "camera fails" as a CameraX error callback or no frame within 5 s.
  - FR-ONB-2: list the OEM modes the app actually detects.
- **potential_consequence:** Stories pass while the real behaviour is wrong or inconsistent across screens, and review time moves to the owner.

---

## Medium

### M1. An unconsumed consumable blocks buying the same price again (ITEM_ALREADY_OWNED)
- **Location:** §6.3 "Grant rule" / consumption with retries
- **trigger_condition:** Say consumption of `snooze_usd_02` failed or is still retrying. The next time the fee is $2 (the next session, or B=$2 N=1 vs B=$1 N=2), `launchBillingFlow` returns `ITEM_ALREADY_OWNED` and the user can't snooze. Also, if consumption keeps failing for 3 days, Google auto-refunds a snooze the user already received.
- **guard_snippet:** Add rules:
  - Before launching billing for product P, if an owned unconsumed token for P exists and has already been granted, consume it first.
  - Map `ITEM_ALREADY_OWNED` to "consume the granted token, then retry once".
  - Retry consumption with backoff on every app start or WorkManager run until it succeeds.
  Add unit tests for each case.
- **potential_consequence:** Snooze randomly fails for paying users, or charged snoozes quietly turn into refunds.

### M2. The price on the confirm screen isn't what gets charged (tax, stale cache)
- **Location:** §6.2 last bullet (cached product details); G3; FR-RNG-2
- **trigger_condition:** In the US and some other regions, Play prices exclude sales tax, which is added on the Play sheet. Cached `ProductDetails` can also go stale after a price change or a change of Play country. Either way, "the exact price before paying" isn't true.
- **guard_snippet:** Refresh `ProductDetails` whenever a session starts if online, and don't offer snooze if the cache is older than N days. Write the copy as "{price} + tax where applicable" when the region is tax-exclusive, or phrase it as "Google Play will show the final total". Update G3 to say "exact price as returned by Google Play".
- **potential_consequence:** The user sees $3, gets charged $3.27, and that looks like a hidden fee, which undercuts the app's honesty message and invites refunds.

### M3. "Target audience = 18+" does not keep minors from paying
- **Location:** NFR-5; Q6; §12 Risks mitigation "18+ rating"
- **trigger_condition:** The Play target-audience setting controls Families policy obligations and where the app is promoted. It does not age-gate installs or purchases. The IARC rating comes from the questionnaire and will probably be low. Minors on accounts with no parental controls can still buy.
- **guard_snippet:** Keep 18+ as the target audience, but restate the mitigation accurately: "Relies on Google Play parental controls / purchase authentication; app shows an 18+ notice in onboarding." Consider an onboarding age-confirmation screen, and a documented refund-on-request policy for purchases by minors.
- **potential_consequence:** False confidence, parental chargebacks, and Play or regulator complaints about charging minors.

### M4. Auto Backup contradicts "all data stays on the device"
- **Location:** §5 principle 5; NFR-4; §9 Play declarations (Data safety)
- **trigger_condition:** Android Auto Backup is on by default. It uploads app data, including recordings, House Hunt photos and history, to the user's Google Drive, which contradicts the privacy claim and the Data safety answers. Restoring on a new phone also brings back purchase tokens and session state.
- **guard_snippet:** Add an NFR that makes backup explicit: set `android:allowBackup` / `dataExtractionRules` either to exclude media, tokens and active session, or to disable backup entirely. Reflect that choice in the Data safety form and the privacy policy.
- **potential_consequence:** A Data safety mismatch (a policy violation), a false privacy claim, and odd restored state (ghost sessions, double-grant checks against tokens that don't exist).

### M5. Pausing for calls needs a permission that isn't listed, and it's an escape
- **Location:** FR-SES-8; FR-ONB-2 permissions list
- **trigger_condition:** Detecting call state on API 31+ needs `READ_PHONE_STATE`, which is a runtime permission and isn't in the onboarding checklist. The rule also doesn't say whether VoIP calls count. And it's an escape: starting any call, or a VoIP call to yourself, pauses the alarm indefinitely.
- **guard_snippet:** Define detection through audio focus loss (`AUDIOFOCUS_LOSS_TRANSIENT` from a call, `AudioManager.getMode() == MODE_IN_CALL/MODE_IN_COMMUNICATION`), which needs no permission. Cap the pause (for example, the sound resumes after 10 minutes or when the call ends, whichever comes first). Don't pause the 30-minute ring timer, or define exactly how it interacts.
- **potential_consequence:** Missing permission handling means the feature silently doesn't work, or users find a free way to snooze.

### M6. The 30-minute "Missed" timeout is a free escape, and "interaction" is undefined
- **Location:** FR-ALM-9; FR-PRG-1 "Missed"; §7.1b preamble ("no free escapes")
- **trigger_condition:** Putting the phone in a drawer for 30 minutes ends the session for free. "No interaction" isn't defined: does a volume-key press, a screen touch, or an open Play sheet reset the timer? It's also unclear whether the timeout restarts on each snooze re-ring.
- **guard_snippet:** Define "interaction" as a list of events. Define whether the timer resets and when. Accept the escape explicitly in the §7.1b preamble ("30-min unattended ring ends as Missed, which breaks the streak"). Consider escalating (vibration plus max volume) at 20 minutes instead of stopping.
- **potential_consequence:** Stories implement different definitions, and power users learn that "Missed" is a cheaper snooze than paying.

### M7. The fallback check can be triggered on purpose to get an easier check
- **Location:** FR-PWK-11
- **trigger_condition:** Five deliberate failed House Hunt matches, or covering the camera, unlocks Hard Math ×2 plus Memory Sequence, which can be done from bed. That defeats the "get out of bed" check, and "camera fails" has no definition.
- **guard_snippet:** Only allow fallback after an objective failure signal: CameraX error, no frames, or 5 match attempts where the image differs enough from the last attempt (not the same dark frame repeated). Make the fallback at least as hard as the original (for example, more problems, or a House Hunt re-registration prompt the same day). Log it in stats, which the PRD already does.
- **potential_consequence:** The flagship differentiator (House Hunt / QR) is trivially bypassed, and G1 is weakened.

### M8. Overlapping-alarm merge rule is underspecified
- **Location:** FR-SES-7
- **trigger_condition:** It isn't defined which alarm's config wins (B, checks, grace, sound) when alarm B is due during alarm A's session. It also isn't defined what happens if B is due while A is snoozed: does B ring at its own time, or wait for A's snooze to end? B's session log and repeat schedule are unclear too.
- **guard_snippet:** Specify: the active session keeps its frozen config. If B is due during a snooze, the snooze ends early and the session re-rings at B's time with no new fee and no extra grace window. B's occurrence is logged as "Merged into <session>" and B's next occurrence is scheduled normally.
- **potential_consequence:** The user sleeps through an alarm they set as a backup, which breaks the reliability promise (G2).

### M9. Before first unlock, snooze can't be paid and the fee data may not be readable
- **Location:** FR-ALM-11; §6.3; FR-RNG-3
- **trigger_condition:** In Direct Boot (before first unlock), credential-encrypted storage, the cached ProductDetails and Play billing aren't available. The PRD only defines check fallbacks, not what the Snooze button does in this state.
- **guard_snippet:** Add to FR-ALM-11: "Before first unlock, Snooze is shown disabled with reason 'Unlock your phone to snooze'". Alternatively, unlocking moves the session to normal storage and snooze becomes available. Keep the frozen session config (B, N, max snoozes) in device-protected storage.
- **potential_consequence:** A crash or undefined UI in the ringing flow after an overnight reboot, which is exactly when reliability matters most.

### M10. Nothing is specified for accidental or disputed charges
- **Location:** FR-SET-3; §12 "Refund abuse"; §6.3 "Refunds"
- **trigger_condition:** A half-asleep user with a loud alarm confirms a purchase they didn't mean to (Play purchase authentication may be off). The only in-app help is an info page. There's no developer refund process (Play Console order management) and no response-time commitment.
- **guard_snippet:** Add FR-SET-5, "Report an accidental charge": it pre-fills a support email with the order ID and time from local history. Define an owner process for refunding through Play Console within X days. In onboarding, recommend turning on Play purchase authentication.
- **potential_consequence:** Chargebacks (which cost more than refunds and can trigger Play risk flags) and bad reviews.

### M11. Trader status has to be settled before launch, not "later"
- **Location:** §12 Risks, last row
- **trigger_condition:** Apps that monetize must declare trader status. In the EU, traders must have contact details shown publicly (the Digital Services Act). This can't be deferred until after production launch, and it may block EU distribution.
- **guard_snippet:** Move it to E9 as a launch-blocking task: decide on the business entity or address before the production submission, or explicitly exclude EU countries at launch.
- **potential_consequence:** The launch is blocked in the EU, or the owner's home address is published.

### M12. Refund-and-repeat defeats the behaviour mechanism, and the metric can't see it
- **Location:** §6.3 "Refunds"; §11 Trust metric; §12
- **trigger_condition:** Users can pay for a snooze and then self-refund within 48 hours, which makes snoozes free in practice. The app can't detect it, and progress stats still show it as paid.
- **guard_snippet:** Accept the risk explicitly, but check Play Console order management weekly during the closed test. Add a note to the "How payments & refunds work" copy. Keep server-side voided-purchase checks on the post-MVP roadmap with a trigger condition (for example, refund rate > 5% in closed test).
- **potential_consequence:** The core incentive quietly disappears for savvy users, and revenue numbers overstate reality.

### M13. The 7–10 week timeline doesn't fit the mandatory steps
- **Location:** G4; §13 Release plan
- **trigger_condition:** The plan needs 1 week of planning, 1 week of spikes and 3–4 weeks of build across 9 epics with human-verify gates. On top of that come a mandatory 14-day closed test with at least 12 testers, a production-access application review, and FGS/full-screen-intent declaration reviews. That leaves no slack for a failed S1/S3 or a policy rejection (C1).
- **guard_snippet:** Add a critical-path schedule that counts the Play closed-test and review time, and name the scope to cut if spikes fail (for example, House Hunt moves post-MVP if S3 fails, and QR plus Math is the minimum set of checks).
- **potential_consequence:** The date slips, or quality corners get cut on reliability to hit it.

---

## Low

### L1. The product catalog is oversized and the fee-to-product mapping isn't verified
- **Location:** §6.3 (50 products)
- **trigger_condition:** B ∈ 1..10 and N ∈ 1..5 only produce 31 distinct prices. Some local price tiers may be unavailable, or may round to the same value in some currencies. Nothing checks that every reachable fee has an active product.
- **guard_snippet:** Generate the product list from the fee engine (the set of all B×N values), and add a unit test that `productFor(fee)` exists for every reachable (B, N). Optionally have the pricing script check that local prices are strictly increasing.
- **potential_consequence:** A missing or duplicate product shows up at runtime as "snooze unavailable".

### L2. Monotonic time doesn't survive a reboot
- **Location:** FR-ALM-9; FR-SES-1; NFR-11 `Clock`
- **trigger_condition:** `elapsedRealtime` resets on reboot, so persisted timers (30-minute ring, snooze end, backup alarm) computed from it are wrong after a reboot mid-session.
- **guard_snippet:** Have `Clock` expose both wall and monotonic time. Persist a boot count (`Settings.Global.BOOT_COUNT`) with each timestamp, and define how timers resume after a reboot (for example, a snooze end in the past means ring immediately). Add unit tests.
- **potential_consequence:** After a reboot, sessions re-ring at the wrong time or time out instantly.

### L3. Continuously re-arming the backup alarm pollutes the system "next alarm"
- **Location:** FR-SES-2
- **trigger_condition:** Scheduling the backup with `setAlarmClock` every minute keeps changing the system's next-alarm indicator and the time other apps and widgets show.
- **guard_snippet:** Use `setExactAndAllowWhileIdle` for the backup where that's reliable enough, keeping `setAlarmClock` for user alarms, or state the side effect is accepted. Confirm in S2.
- **potential_consequence:** User confusion ("why does my clock say alarm 07:13?"), and possibly extra battery use.

### L4. The fallback check isn't accessible
- **Location:** NFR-9; FR-PWK-11; FR-PWK-4
- **trigger_condition:** The single fallback includes Hard Memory Sequence, a visual grid, which isn't usable with TalkBack. NFR-9 relies on "the fallback check" as the accessible alternative.
- **guard_snippet:** Define an accessible fallback path (for example, Math plus Word Unscramble with TalkBack-friendly input) that's used when accessibility services are on.
- **potential_consequence:** Blind or low-vision users can't stop the alarm for free, which breaks principle 1 and is an accessibility complaint risk.

### L5. The commitment lock can be bypassed by deleting and recreating the alarm
- **Location:** §6.2 Commitment lock; Q4
- **trigger_condition:** Deletion is allowed, so a user can delete the alarm and create a new one with a lower B or easier checks inside the 8-hour window. It's also unclear whether B is global (FR-SET-1) or per alarm.
- **guard_snippet:** State whether new alarms created within 8 hours of a deleted one inherit the previous strength, or accept the bypass explicitly because the lock is a nudge. Clarify whether B is global or per alarm.
- **potential_consequence:** Inconsistent story implementations; a minor loss of commitment value.

### L6. The Kover 90% gate can be met without catching logic errors
- **Location:** NFR-11
- **trigger_condition:** Line coverage is easy to hit in an autonomous loop with assertion-free tests. The fee engine and state machine are where a bug would cost the user money.
- **guard_snippet:** For the fee engine and state machine, add property-based tests (kotest-property) and consider mutation testing (Pitest) as a periodic, non-blocking gate. Require the tests to assert on the state machine table from H2.
- **potential_consequence:** Coverage passes while billing and session bugs ship.
