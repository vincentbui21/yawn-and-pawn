# Story 5.13: Epic 5 device verification checklist

Status: in progress. Sessions 2026-10-26 (Play 0.1.3) and 2026-10-27 (Play 0.1.4, re-test of the failures).

**Device:** Oppo A96 (CPH2333), Android 13, ColorOS.
**Emulators:** not planned (owner decision: ship fast; the NFR-1 emulator matrix and the Xiaomi/Samsung/Pixel rows of the story's AC wait for v2 or the closed test).
**Sources:** Story 5.13 AC in `epics.md`; the 5.13 notes in `epic-5-context.md` ("Notes from implemented stories"); the `deferred-work.md` items for 5.13; the two Epic 4 follow-up re-checks (`spec-epic-4-device-followups.md`); Story 5.2 changed to option C "Ringing only" (owner).

## Before you start

**Builds**
- **Parts A to F:** a debug build from `main`, installed fresh. Uninstall any older Yawn & Pawn first, so onboarding starts at step 1. `main` must include Story 5.2 (option C) and Story 5.12 (step 8). If either is missing, mark its rows "Not built".
- **Part G (payment re-checks):** Internal testing release **0.1.3 or later (versionCode 103 or higher; 102 is taken by 0.1.2)**, installed from Google Play. A sideloaded build can't buy on ColorOS (Story 4.18: Play says "item could not be found"), so the Epic 4 re-checks need the Play install.
- Going from the debug build to the Play build needs an uninstall first (different signing key), and that wipes the app's data. So Part G comes last.

**Phone**
- Test alarms at the lowest volume: the app's Sound slider at 10% and the alarm volume at step 1, vibration off (owner request).
- The session needs the phone's PIN: **the owner's PIN** (lock-screen tests, Pay, some system screens). Never write the PIN in this file.
- Screen lock on (PIN), Wi-Fi on, battery over 50%, the owner's licence-tester account in Google Play.
- Auto-brightness off and the brightness slider at about a quarter, so the bright wake screen is easy to see.
- Don't use ColorOS "Close app" or force stop: it cancels the app's alarms (Story 3.14 #16). Swiping the app away in Recents is fine.
- A quick real alarm: add an alarm 2 minutes ahead with Math · Easy. A quick test alarm: the editor's "Test alarm".

**Firebase (Part D)**
- The debug build needs the debug project's `google-services.json` on the build PC.
- Before the first launch, turn DebugView on from the PC: `adb shell setprop debug.firebase.analytics.app com.yawnandpawn.app`. Open Firebase console > Analytics > DebugView.

**How to record**
- Result: Pass, Fail, Partial or Not run, with the date. Each failure becomes a bug story that names its item number.
- **(v2 ok)** marks an item that can move to v2 if time runs short. The owner confirms any move in this file.

**Time plan (about 2 h 10 min with everything, about 1 h 45 min without the (v2 ok) items)**

| Part | What | Items | Minutes |
|---|---|---|---|
| A | First onboarding (debug build) | 1–13 | 30 |
| B | Reliability checklist | 14–20 | 25 |
| C | Theme, defaults and bright wake screen | 21–27 | 20 |
| D | Usage stats and DebugView | 28–31 | 10 |
| E | You tab | 32–35 | 8 |
| F | Delete all data, second onboarding, reboot, accessibility | 36–42 | 20 |
| G | Payment re-checks (Play build) | 43–47 | 35 |

## Part A: First onboarding (debug build, fresh install)

| # | Item | Result | Date | Notes |
|---|---|---|---|---|
| 1 | First launch opens step 1, not Home: "This app makes money only when you snooze. We hope you never pay us." and "Snooze costs money. Waking up is free.". 8 dots at the top, not tappable. | Pass | 2026-10-26 | Play build 0.1.3. Step 1 texts exact, 8 dots. |
| 2 | Step 2 shows the disclosure text, with "I understand" as the only way on (no skip). Back goes to step 1; Back on step 1 leaves the app. | Pass | 2026-10-26 | Disclosure text; "I understand" is the only way on; Back goes to step 1. |
| 3 | Step 3 online: the base fee stepper with euro prices "Snooze 1: … · 2: … · 3: …", the lowering note and the purchase-authentication tip. | Pass | 2026-10-26 | €1.09 · €2.19 · €3.39, lowering note, purchase-authentication tip. |
| 4 | Step 3 in airplane mode: USD amounts and "Approximate. Your local price shows when you're online."; "Continue" still works. Airplane mode off afterwards. | Pass (note) | 2026-10-26 | Offline still showed the cached euro prices (0.1.2 had cached them); the USD "Approximate" text only shows with no cache. Continue works. |
| 5 | On step 3 or 4, swipe the app away in Recents and reopen it: the same step shows. | Pass | 2026-10-26 | App process killed in the background on step 3; reopening shows step 3. |
| 6 | Step 4: "When should it ring?", the time wheel and Once · Weekdays · Custom. A time already passed today shows "Rings tomorrow at {time}.". Set the first alarm for tomorrow morning so it can't ring during the session. | Pass | 2026-10-26 | Wheel, Once · Weekdays · Custom; "Rings tomorrow at 07:00." |
| 7 | Step 5: "How will you prove you're up?" with Random selected. With no check ticked, "Continue" shows "Pick at least one check.". Pick Math only; on "Continue", Android asks for notifications once. Allow. | Pass (note) | 2026-10-26 | Math is preselected; the All/Random row appears only with 2+ checks. No check + Continue: "Pick at least one check." Notification permission already granted from 0.1.2, so no prompt. |
| 8 | Go back from step 6 to step 4, change the time, and go forward again. Later, Home shows one alarm, not two. | Pass | 2026-10-26 | Back to step 4, 07:00 changed to 08:00: Home shows one alarm. |
| 9 | Step 6 "Make sure it rings": the checklist rows show, with no "Test alarm" row. "Continue" works even with a "Fix" row. Leave the fixes for Part B. | Pass (note) | 2026-10-26 | Rows Notifications, Full-screen alarm (shown on this Android 13 phone), DND, Battery Fix, Manufacturer Fix; no Test alarm row; Continue works with Fix rows. |
| 10 | Step 7: "Share anonymous usage stats? Off unless you turn it on." with "Share" and "No thanks" the same size, neither selected. Tap **"No thanks"** (Part D needs it). | Pass | 2026-10-26 | Both buttons the same size, neither selected; tapped "No thanks". |
| 11 | Step 8 shows "Your alarm screen turns bright to help you wake. Change it in Settings.". Tap "Ring a test alarm" and keep the phone **unlocked**; finish the test. Back in the app: "Your phone wasn't locked. Try again with it locked.". | Pass (note) | 2026-10-26 | Unlocked test: "Your phone wasn't locked. Try again with it locked." Rang loud: a new alarm defaults to 80% volume. |
| 12 | "Ring a test alarm" again: "Lock your phone. We'll ring in 10 seconds.". Lock it. It rings over the lock screen with "Test · no charge", and Snooze can't be used. Let it ring past a minute change: the big clock shows the time now and turns over at the minute (Epic 4 follow-up 1, part 1). | Pass (re-test 0.1.4) | 2026-10-27 | 2026-10-26 FAIL on 0.1.3: phone locked with the screen off, sound only, screen black (full-screen intent sent as an update). Fixed by PR #95. Re-test on Play 0.1.4 (104), owner watching: test rang with the app on top, phone then locked: the screen woke by itself in about 3 s and the wake screen showed over the lock screen; "Test finished. Your alarm works." Clock part checked in 44. |
| 13 | Same test, bright wake screen (option C): full brightness while it rings; back to the phone's level on the check and on Success ("Test finished. Your alarm works."). After Done, onboarding ends on Home, and Settings > Reliability checklist shows "Test alarm" OK. | Pass (re-test 0.1.4) | 2026-10-27 | 2026-10-26: locked test passed (Home note cleared) but no bright wake (Extra dim read blocked by SecurityException), fixed by PR #93. 0.1.4: window brightness 3276 (about 80%) against the phone setting 1092 while ringing. Note: started unlocked then locked, the Home "Ring a test alarm" note stayed (check in a later test). |

## Part B: Reliability checklist (Settings > Reliability checklist)

| # | Item | Result | Date | Notes |
|---|---|---|---|---|
| 14 | Rows on this phone: Notifications, Do Not Disturb, Battery, Manufacturer settings, Test alarm. No Full-screen alarm row (Android 14 and later), no Exact alarms row (Android 12 only), no Camera row (no QR alarm). Each row shows title, reason and "OK" or "Fix"; "Ring a test alarm" sits at the bottom. | Pass (note) | 2026-10-26 | Same rows as item 9 plus Test alarm and "Ring a test alarm"; the Full-screen alarm row shows on Android 13. |
| 15 | Notifications: turn the app's notifications off in Android settings. Home shows "Alarms may not ring. Fix settings" with no close button. The banner's "Fix" opens the checklist, and the row's "Fix" opens the app's notification settings. Turn them on and go back: the row turns OK with no tap, and the banner goes. | Pass | 2026-10-26 | Notifications off: row "Alarm may not ring: notifications turned off", Home banner "Alarms may not ring. Fix settings" with no close button; the row's Fix opens the app's notification settings; on again: row OK on return. |
| 16 | Battery: make the row OK first. Then set the app back to optimized: Home shows the banner, and the row shows "Alarm may not ring: battery optimization turned back on". "Fix" opens the battery screen (note which one); fixing clears both. | Pass (note) | 2026-10-26 | Not optimized: row OK on return. Optimized again: "Alarm may not ring: battery optimization turned back on". Fix opens Android's "Battery optimization" list (Settings$HighPowerApplicationsActivity), where the user must find the app (not a direct dialog). Left not optimized. |
| 17 | Do Not Disturb, manual: in ColorOS DND, stop it allowing alarms, then turn DND on. The row shows "Fix" and the banner shows. "Fix" opens the DND settings; allow alarms again: OK on return. | | | |
| 18 | Do Not Disturb, schedule: with DND off right now, keep a DND schedule that does not allow alarms. The row shows "Fix" and the banner shows. (Story AC item 4.) | | | |
| 19 | **(v2 ok)** DND rule with its own setting (5.3 review note): a ColorOS Bedtime or Sleep mode schedule with its own "alarms" off, while the main DND allows alarms. Record whether the row still says OK. Expected: OK, a known limit (the app can't see a rule's own setting without a permission we don't add). | | | |
| 20 | Manufacturer settings (Oppo): the row shows. "Fix" opens the guidance with 3 steps. "Open settings": note which screen opens. Check the step words against the A96 menus ("Battery usage", "Allow background activity", "Allow auto launch"); note the real labels if they differ. "I've done this" marks the row OK, and it stays OK after reopening the app. Afterwards, put every setting back to OK. | Pass | 2026-10-26 | Guidance shows 3 steps; "Open settings" opens App info; Battery usage labels match ("Allow background activity", "Allow auto launch"). Turned both on; "I've done this" marks OK. All rows OK, Home banner gone. |

## Part C: Theme, defaults and the bright wake screen

| # | Item | Result | Date | Notes |
|---|---|---|---|---|
| 21 | Appearance: Light, then Dark. Home, Progress, Settings and You change at once, with no restart. System follows the phone's dark mode. The choice stays after swiping the app away and reopening it. | Pass | 2026-10-26 | Dark: Home, Progress, Settings change at once; stays Dark after the app process was killed; Light and System also switch. Set back to System. |
| 22 | With the app in Dark, a test alarm still shows the Sunrise wake screens. | Pass | 2026-10-27 | Play 0.1.4, app theme Dark: the locked test alarm (05:33) showed the light Sunrise wake screen (screenshot t22-dark-wake.png in the session scratchpad). Theme set back to System after. |
| 23 | Set Default quiet time to 25 s and Default snooze length to 5 min. A new alarm (+) shows 25 s and 5 min; the first alarm still shows 20 s and 9 min. Keep 5 min (it shortens Part G). | Pass | 2026-10-26 | Default quiet time 25 s and snooze 5 min: a new alarm shows 25 s and 5 min; the existing alarm keeps 20 s. |
| 24 | Bright wake with a real alarm (option C), phone locked: full brightness while it rings. Tap Snooze: the confirm sheet is still bright; "I'll get up" closes it with no charge. On the check and on Success, the screen is back at the phone's level. | Pass (re-test 0.1.4) | 2026-10-27 | 2026-10-26: wake, Snooze sheet ("Snooze for 9 min? €1.09 … The next one costs €2.19."), "I'll get up" with no charge and Success "1 day in a row" passed; brightness failed (see 13). 0.1.4: real alarm 04:38 locked woke the screen, bright (window brightness about 80%). |
| 25 | Turn "Bright wake screen" off: the next test alarm leaves the brightness alone. Turn it back on. | Moved to v2 (owner) | 2026-10-27 | Bright wake off, locked test alarm 05:33: no window brightness override (NaN), so the app left the brightness alone. Owner, watching: "it didn't work, but let's put it to v2". Also seen in this run: the check was slow (adb), the 20 s quiet time ran out and the test ring came back with the alarm stream set to 13 of 16 (the test alarm rings at 80%), with alarm vibration: too loud for a test (see 11b). Bright wake turned back on after. |
| 26 | **(v2 ok)** During a ring, unlock and press Home: the brightness goes back at once. Back through the notification: bright again while it rings. | | | |
| 27 | **(v2 ok)** Extra dim: if ColorOS has it (Settings > Accessibility), turn it on; the ringing screen is not forced bright. If the phone has no Extra dim, write "Not on this phone". | | | |

## Part D: Usage stats and Firebase DebugView

| # | Item | Result | Date | Notes |
|---|---|---|---|---|
| 28 | After "No thanks" (item 10), the Settings switch "Share anonymous usage stats" is off. After item 24's real alarm, DebugView shows nothing from the phone. | | | |
| 29 | Turn the switch on and finish a real alarm (2 min ahead). DebugView shows `session_outcome`, `snooze_count` and `check_type`, and otherwise only Firebase's own automatic events. List those in Notes for the Data safety form (Epic 8). No other custom events. | | | |
| 30 | **(v2 ok)** With the switch on, a test alarm adds no events. | | | |
| 31 | **(v2 ok)** Turn the switch off and finish one more real alarm: nothing new in DebugView. | | | |

## Part E: You tab

| # | Item | Result | Date | Notes |
|---|---|---|---|---|
| 32 | You shows "Money" (Purchase history, How payments & refunds work), "Privacy and your data" (Privacy policy, "Delete all data" in red) and "Help" (Support, Terms, About). | Pass (note) | 2026-10-26 | Money (Purchase history, How payments & refunds work), Privacy and your data (Privacy policy, Delete all data), Help (Support, Email support, Terms). **No "About" row.** |
| 33 | "How payments & refunds work" shows the disclosure text word for word and the purchase-authentication tip. Its links to "Problem with a charge?" and "Purchase history" open. | Pass | 2026-10-26 | Disclosure text and purchase-authentication tip; "Problem with a charge?" and "Purchase history" open. History: "October 2026, €5.45 paid". |
| 34 | Privacy policy, Terms and Support open the browser at `vincentbui21.github.io/yawn-and-pawn/…`. If the Pages are not live yet, write "not live" and what the browser shows; the link passes if the address is right. Support also offers the email. | Pass | 2026-10-26 | Chrome opens vincentbui21.github.io/yawn-and-pawn/privacy/, /terms/ and /support/ (live); Support shows the email. |
| 35 | During item 29's ring, open the app: only the alarm-in-progress panel shows. You, Settings and "Delete all data" can't be reached. | Pass | 2026-10-27 | Seen on 0.1.4 during the 04:38, 05:01 and 05:21 sessions (ringing and snoozed): opening the app shows only "Alarm in progress" with "Back to alarm"; no tab bar, so You, Settings and "Delete all data" can't be reached. |

## Part F: Delete all data, second onboarding, reboot, accessibility

| # | Item | Result | Date | Notes |
|---|---|---|---|---|
| 36 | First add an alarm 5 min ahead and leave the theme on Dark. "Delete all data" opens "Delete all data?" with its text. "Keep it" closes it and nothing is removed. | Pass | 2026-10-26 | Alarm 3 min ahead, theme Dark. "Delete all data?" with "Alarms, history, purchase records and settings are removed from this phone. This can't be undone."; "Keep it" kept the alarm. |
| 37 | "Delete": the app opens onboarding step 1. Settings are back to defaults (theme System, quiet time 20 s, snooze length 9 min). The alarm set 5 min ahead does not ring (keep the phone near you past its time). | Pass | 2026-10-26 | Delete opened onboarding step 1; settings back to defaults (9 min, 20 s, theme System); the deleted 23:29 alarm did not ring. |
| 38 | Second onboarding, fast: steps 1 to 7 (step 7 is asked again). On step 8, Back goes to step 7 and does not finish onboarding. "Skip for now" opens Home with "Ring a test alarm with your phone locked to check it works.", and the checklist's "Test alarm" row is not OK. | Pass (note) | 2026-10-26 | Second onboarding fast, step 7 asked again. On step 8, Back went to **step 6**, not 7 (5.12 rule: skip step 7 when already answered). "Skip for now" opens Home with "Ring a test alarm with your phone locked to check it works." |
| 39 | **(v2 ok)** Reboot before unlock (from `deferred-work.md`, Story 4.18): add an alarm 3 min ahead, restart the phone and don't unlock. It rings over the lock screen, with no crash. Then unlock, open the app online: prices show in the editor's fee ladder. | | | |
| 40 | **(v2 ok)** TalkBack: each onboarding step reads its headline first; checklist rows read as title, reason, status. | | | |
| 41 | **(v2 ok)** Font size at the largest: onboarding steps, the checklist and the guidance screen, nothing cut off. | | | |
| 42 | Copy: note any text seen today that looks wrong, differs from EXPERIENCE.md or has an em dash. Fill this in at the end. | | | |

## Part G: Payment re-checks (Play build 0.1.3 or later)

| # | Item | Result | Date | Notes |
|---|---|---|---|---|
| 43 | Uninstall the debug build. Install the Internal testing build from Play and check App info shows 0.1.3 or later (versionCode 103 or higher). Run onboarding quickly ("Skip for now" is fine) and set Default snooze length to 5 min. | | | |
| 44 | Re-ring clock (Epic 4 follow-up 1): a real alarm 2 min ahead. Snooze, Pay (the owner's PIN), "Test card, always approves". The Snoozed screen is at the phone's level. The re-ring 5 min later shows the time it rings now, not the first ring's time, and turns over at the minute; it is bright again. | Pass (re-test 0.1.4) | 2026-10-27 | 2026-10-26 FAIL on 0.1.3: re-ring with the screen off was sound only (same cause as 12, fixed by PR #95); re-ring clock already showed the time now. 0.1.4, owner watching: 04:38 alarm, Snooze, Pay, owner tapped Agree, test order €1.09 at 04:40:13. Re-ring 04:49:14 with the screen off: the screen lit up by itself, wake screen over the lock screen, bright (owner confirmed); "Snooze 1 of 5 · €1.09 paid this morning", next "Snooze · €2.19". I'm up + Math: "You're up. That's what counts." |
| 45 | Slow card, round 1 (Epic 4 follow-up 2): a real alarm, Snooze, Pay, "Slow test card". Finish the check before it approves (Success: "Your pending payment wasn't used. Google refunds it automatically."). Have the next alarm ringing when Play's approval notification comes, and Pay at the same price within about 10 s. Log the approval time, the Pay time and which shows first: the app's "You already paid … earlier that wasn't used" sheet or Play's "You already own this item.". "Use it" snoozes with no new charge. | Partial | 2026-10-27 | Play 0.1.4, owner watching. The slow test card approves in about 50 to 60 s on this account. Try 1 (05:01): the card approved during the ring (pending 05:02:45, approved 05:03:45): "Payment not confirmed yet. If it goes through before you finish, your snooze starts." then "Snoozed. Next ring at 05:12." (Pass for that path). Try 2 (05:18): pending 05:18:25, check finished 05:19:15: Success "Up on time. Your pending payment wasn't used. Google refunds it automatically." (Pass). Approval 05:19:15 came before the next alarm (05:21), so Pay within 10 s of approval was not reached; owner decision 2026-10-27: enough, the 10 s timing moves to the closed test. |
| 46 | Slow card, round 2: the same, but Pay about 1 minute after the approval. Log the same three things. | Pass | 2026-10-27 | Approval (order time) 05:19:15; next alarm 05:21:00; Snooze 05:21:09, Pay 05:21:10 (about 1 min 55 s after approval). The app's "already paid" sheet showed first (ShowReuseSheet), not Play's "You already own this item."; "Use it" 05:21:14 snoozed. Orders today: 04:40:13, 05:03:45, 05:19:15 only, so "Use it" added no new charge. Log: log-0518.txt (scratchpad). |
| 47 | **(v2 ok)** Slow card, round 3: Pay about 5 minutes after the approval. Then Purchase history: each charge shows once, and "Use it" added no new charge. | Moved to v2 / closed test | 2026-10-27 | Owner decision 2026-10-27. Purchase history per 46: each charge shows once. |

**After Part G:** if Play's "You already own this item." still shows first in any round, the logged times decide the new value of `PurchaseCoordinator.PRE_LAUNCH_REFRESH` (now 1 s).

## Bugs found

- **Item 12 / 44 (HIGH):** with the screen off, a test ring after locking and a snooze re-ring played sound only; the screen stayed black. Android 13 starts a full-screen intent only on a new notification post. Fixed by PR #95 (each ring posts a new notification). Passed on 0.1.4.
- **Items 13 / 24:** no bright wake: reading Extra dim throws SecurityException for a normal app on Android 12+. Fixed by PR #93 (unreadable counts as off). Passed on 0.1.4.
- **Owner report (not a numbered item):** screens looked wrong in landscape. Fixed by PR #94 (portrait only). Passed on 0.1.4 (owner turned the phone, 2026-10-27).

## Owner decision

- 2026-10-27: item 25 (Bright wake off) moves to v2.
- 2026-10-27: Part D (Firebase DebugView, 28 to 31) and DND 17 and 18 not run this session (the owner needs quiet time; Part D needs the debug build, which wipes the app's data); to the next phone session or the closed test.
- 2026-10-27: slow card, the "Pay within about 10 s of approval" timing of item 45 and round 3 (47) move to the closed test / v2; the owner judged the slow-card behaviour fine after 45 (two paths) and 46.
