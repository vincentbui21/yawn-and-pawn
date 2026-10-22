# Story 5.13: Epic 5 device verification checklist

Status: ready for the owner session (planned 2026-10-22 evening). Not run yet.

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
| 1 | First launch opens step 1, not Home: "This app makes money only when you snooze. We hope you never pay us." and "Snooze costs money. Waking up is free.". 8 dots at the top, not tappable. | | | |
| 2 | Step 2 shows the disclosure text, with "I understand" as the only way on (no skip). Back goes to step 1; Back on step 1 leaves the app. | | | |
| 3 | Step 3 online: the base fee stepper with euro prices "Snooze 1: … · 2: … · 3: …", the lowering note and the purchase-authentication tip. | | | |
| 4 | Step 3 in airplane mode: USD amounts and "Approximate. Your local price shows when you're online."; "Continue" still works. Airplane mode off afterwards. | | | |
| 5 | On step 3 or 4, swipe the app away in Recents and reopen it: the same step shows. | | | |
| 6 | Step 4: "When should it ring?", the time wheel and Once · Weekdays · Custom. A time already passed today shows "Rings tomorrow at {time}.". Set the first alarm for tomorrow morning so it can't ring during the session. | | | |
| 7 | Step 5: "How will you prove you're up?" with Random selected. With no check ticked, "Continue" shows "Pick at least one check.". Pick Math only; on "Continue", Android asks for notifications once. Allow. | | | |
| 8 | Go back from step 6 to step 4, change the time, and go forward again. Later, Home shows one alarm, not two. | | | |
| 9 | Step 6 "Make sure it rings": the checklist rows show, with no "Test alarm" row. "Continue" works even with a "Fix" row. Leave the fixes for Part B. | | | |
| 10 | Step 7: "Share anonymous usage stats? Off unless you turn it on." with "Share" and "No thanks" the same size, neither selected. Tap **"No thanks"** (Part D needs it). | | | |
| 11 | Step 8 shows "Your alarm screen turns bright to help you wake. Change it in Settings.". Tap "Ring a test alarm" and keep the phone **unlocked**; finish the test. Back in the app: "Your phone wasn't locked. Try again with it locked.". | | | |
| 12 | "Ring a test alarm" again: "Lock your phone. We'll ring in 10 seconds.". Lock it. It rings over the lock screen with "Test · no charge", and Snooze can't be used. Let it ring past a minute change: the big clock shows the time now and turns over at the minute (Epic 4 follow-up 1, part 1). | | | |
| 13 | Same test, bright wake screen (option C): full brightness while it rings; back to the phone's level on the check and on Success ("Test finished. Your alarm works."). After Done, onboarding ends on Home, and Settings > Reliability checklist shows "Test alarm" OK. | | | |

## Part B: Reliability checklist (Settings > Reliability checklist)

| # | Item | Result | Date | Notes |
|---|---|---|---|---|
| 14 | Rows on this phone: Notifications, Do Not Disturb, Battery, Manufacturer settings, Test alarm. No Full-screen alarm row (Android 14 and later), no Exact alarms row (Android 12 only), no Camera row (no QR alarm). Each row shows title, reason and "OK" or "Fix"; "Ring a test alarm" sits at the bottom. | | | |
| 15 | Notifications: turn the app's notifications off in Android settings. Home shows "Alarms may not ring. Fix settings" with no close button. The banner's "Fix" opens the checklist, and the row's "Fix" opens the app's notification settings. Turn them on and go back: the row turns OK with no tap, and the banner goes. | | | |
| 16 | Battery: make the row OK first. Then set the app back to optimized: Home shows the banner, and the row shows "Alarm may not ring: battery optimization turned back on". "Fix" opens the battery screen (note which one); fixing clears both. | | | |
| 17 | Do Not Disturb, manual: in ColorOS DND, stop it allowing alarms, then turn DND on. The row shows "Fix" and the banner shows. "Fix" opens the DND settings; allow alarms again: OK on return. | | | |
| 18 | Do Not Disturb, schedule: with DND off right now, keep a DND schedule that does not allow alarms. The row shows "Fix" and the banner shows. (Story AC item 4.) | | | |
| 19 | **(v2 ok)** DND rule with its own setting (5.3 review note): a ColorOS Bedtime or Sleep mode schedule with its own "alarms" off, while the main DND allows alarms. Record whether the row still says OK. Expected: OK, a known limit (the app can't see a rule's own setting without a permission we don't add). | | | |
| 20 | Manufacturer settings (Oppo): the row shows. "Fix" opens the guidance with 3 steps. "Open settings": note which screen opens. Check the step words against the A96 menus ("Battery usage", "Allow background activity", "Allow auto launch"); note the real labels if they differ. "I've done this" marks the row OK, and it stays OK after reopening the app. Afterwards, put every setting back to OK. | | | |

## Part C: Theme, defaults and the bright wake screen

| # | Item | Result | Date | Notes |
|---|---|---|---|---|
| 21 | Appearance: Light, then Dark. Home, Progress, Settings and You change at once, with no restart. System follows the phone's dark mode. The choice stays after swiping the app away and reopening it. | | | |
| 22 | With the app in Dark, a test alarm still shows the Sunrise wake screens. | | | |
| 23 | Set Default quiet time to 25 s and Default snooze length to 5 min. A new alarm (+) shows 25 s and 5 min; the first alarm still shows 20 s and 9 min. Keep 5 min (it shortens Part G). | | | |
| 24 | Bright wake with a real alarm (option C), phone locked: full brightness while it rings. Tap Snooze: the confirm sheet is still bright; "I'll get up" closes it with no charge. On the check and on Success, the screen is back at the phone's level. | | | |
| 25 | Turn "Bright wake screen" off: the next test alarm leaves the brightness alone. Turn it back on. | | | |
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
| 32 | You shows "Money" (Purchase history, How payments & refunds work), "Privacy and your data" (Privacy policy, "Delete all data" in red) and "Help" (Support, Terms, About). | | | |
| 33 | "How payments & refunds work" shows the disclosure text word for word and the purchase-authentication tip. Its links to "Problem with a charge?" and "Purchase history" open. | | | |
| 34 | Privacy policy, Terms and Support open the browser at `vincentbui21.github.io/yawn-and-pawn/…`. If the Pages are not live yet, write "not live" and what the browser shows; the link passes if the address is right. Support also offers the email. | | | |
| 35 | During item 29's ring, open the app: only the alarm-in-progress panel shows. You, Settings and "Delete all data" can't be reached. | | | |

## Part F: Delete all data, second onboarding, reboot, accessibility

| # | Item | Result | Date | Notes |
|---|---|---|---|---|
| 36 | First add an alarm 5 min ahead and leave the theme on Dark. "Delete all data" opens "Delete all data?" with its text. "Keep it" closes it and nothing is removed. | | | |
| 37 | "Delete": the app opens onboarding step 1. Settings are back to defaults (theme System, quiet time 20 s, snooze length 9 min). The alarm set 5 min ahead does not ring (keep the phone near you past its time). | | | |
| 38 | Second onboarding, fast: steps 1 to 7 (step 7 is asked again). On step 8, Back goes to step 7 and does not finish onboarding. "Skip for now" opens Home with "Ring a test alarm with your phone locked to check it works.", and the checklist's "Test alarm" row is not OK. | | | |
| 39 | **(v2 ok)** Reboot before unlock (from `deferred-work.md`, Story 4.18): add an alarm 3 min ahead, restart the phone and don't unlock. It rings over the lock screen, with no crash. Then unlock, open the app online: prices show in the editor's fee ladder. | | | |
| 40 | **(v2 ok)** TalkBack: each onboarding step reads its headline first; checklist rows read as title, reason, status. | | | |
| 41 | **(v2 ok)** Font size at the largest: onboarding steps, the checklist and the guidance screen, nothing cut off. | | | |
| 42 | Copy: note any text seen today that looks wrong, differs from EXPERIENCE.md or has an em dash. Fill this in at the end. | | | |

## Part G: Payment re-checks (Play build 0.1.3 or later)

| # | Item | Result | Date | Notes |
|---|---|---|---|---|
| 43 | Uninstall the debug build. Install the Internal testing build from Play and check App info shows 0.1.3 or later (versionCode 103 or higher). Run onboarding quickly ("Skip for now" is fine) and set Default snooze length to 5 min. | | | |
| 44 | Re-ring clock (Epic 4 follow-up 1): a real alarm 2 min ahead. Snooze, Pay (the owner's PIN), "Test card, always approves". The Snoozed screen is at the phone's level. The re-ring 5 min later shows the time it rings now, not the first ring's time, and turns over at the minute; it is bright again. | | | |
| 45 | Slow card, round 1 (Epic 4 follow-up 2): a real alarm, Snooze, Pay, "Slow test card". Finish the check before it approves (Success: "Your pending payment wasn't used. Google refunds it automatically."). Have the next alarm ringing when Play's approval notification comes, and Pay at the same price within about 10 s. Log the approval time, the Pay time and which shows first: the app's "You already paid … earlier that wasn't used" sheet or Play's "You already own this item.". "Use it" snoozes with no new charge. | | | |
| 46 | Slow card, round 2: the same, but Pay about 1 minute after the approval. Log the same three things. | | | |
| 47 | **(v2 ok)** Slow card, round 3: Pay about 5 minutes after the approval. Then Purchase history: each charge shows once, and "Use it" added no new charge. | | | |

**After Part G:** if Play's "You already own this item." still shows first in any round, the logged times decide the new value of `PurchaseCoordinator.PRE_LAUNCH_REFRESH` (now 1 s).

## Bugs found

(none yet)

## Owner decision

(none yet; record any items moved to v2 here, with the date)
