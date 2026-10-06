---
title: 'Story 2.13: Epic 2 escape-attempts checklist on the device matrix'
type: 'human-verify'
status: 'done'
owner_decision: 'Owner, 2026-10-06: run every item that can be done over adb; record the items that need the owner by hand as deferred to version 2 (after launch) so the project can move on.'
---

# Story 2.13 results: Epic 2 escape-attempts checklist

**Device:** Oppo A96 (CPH2333, ColorOS, Android 13, `BOOT_COUNT` present). **Build:** debug from `main` 99099b5 (all Epic 2 stories except 2.10, which only adds tests). **Date:** 2026-10-06, 09:55–10:40. **Run by:** Claude over adb with the owner's permission (ringing allowed), plus the owner for the reboot and lock-screen steps.

**Emulators and other makers** (NFR-1 matrix): not run. Deferred to v2 with the owner-only items below.

| # | Item | Result | Evidence |
|---|---|---|---|
| 1 | OEM task manager "Stop" during a ring | ✅ partly. ColorOS Recents **"Close all"** during a ring: the sound never stopped (no kill; `stopped=false`). The App info "Force stop" path is item 20. Other makers (Samsung, Xiaomi): ⏭ deferred to v2. | `dumpsys package` stopped=false, alarm audio started throughout |
| 2 | Swipe the app from Recents while ringing (and in grace, on the check) | ✅ while Ringing: 0 silent seconds over 70 s after the swipe, same process. Grace and the check are instant in Epic 2 (the check is a placeholder answered on "I'm up"), so they are re-checked in the Epic 3 checklist. | alarm `state:started` polled every 2 s |
| 3 | `am kill` / `kill` on the process during a ring | ✅ with a substitute. `am kill` does nothing to a foreground-service app, and `adb shell kill` / `run-as kill` are refused without root on this phone. Used `adb shell am crash com.yawnandpawn.app` (the process dies): the alarm **rang again after about 45 s** (limit 60 s) in a new process, same session, no "restored" message. Observation A below. | new pid, sound back at +45 s, notification `alarms` channel with full-screen intent |
| 4 | Reboot before unlock: alarm 5 min ahead, reboot, don't unlock | ✅ partly. A stored alarm set 4 min ahead rang **after the reboot at 10:36:00.4 (0.44 s late) over the lock screen**. The owner had unlocked once after boot, so the "before first unlock" UI (default sound, lock-icon snooze) was not observed on the device; it is covered by `DirectBootRingTest` and `SessionConflictScenariosTest`. ⏭ device re-check deferred to v2. | session_history row, first_ring_at − scheduled_at = 436 ms |
| 5 | Unlock on the lock screen while it rings: label changes in place | ⏭ deferred to v2 (owner step). Covered on the host by `UnlockDuringRingTest` (label changes in place, activity not recreated). | |
| 6 | Restart the phone while an alarm rings | ⏭ deferred to v2 (adb is unavailable before the first unlock on this phone, so it needs the owner). Covered on the host by `SessionKillRecoveryTest` / `SessionClockAndRebootTest`. | |
| 7 | Power off 10 min during a ring | ⏭ deferred to v2 (owner). | |
| 8 | Time +1 h / −1 h and time zone during a ring; 30 real minutes to the stop | ⏭ clock and zone changes deferred to v2 (owner, Settings UI). The 30-minute stop was verified on this phone in Story 1.21 item 12 (2026-10-05). | |
| 9 | Bluetooth and wired headphones | ⏭ deferred to v2 (owner). | |
| 10 | Incoming call (reject, answer) and a VoIP call during a ring | ⏭ deferred to v2 (owner, second phone). Covered on the host by `CallDetectorTest` (17 tests, incl. the real audio-mode listener and API 30 poll). | |
| 11 | A video playing during a ring, and one playing when the alarm fires | ✅ both. YouTube started during a ring: the alarm kept playing (`USAGE_ALARM` started) and was not paused. An alarm firing during a YouTube video: the alarm played; YouTube paused itself on losing audio focus. | `dumpsys audio` playback configs |
| 12 | Volume keys on the wake screen; normal after Home; accessibility shortcut | ✅ keys: on the wake screen volume down ×3 and mute left the alarm stream at 13/16; after Home, volume up changed the media volume normally. ⏭ accessibility shortcut (both keys 3 s) deferred to v2 (owner). | `cmd media_session volume --stream 4/3 --get` |
| 13 | Leave the wake screen; the notification returns within 1 s; launcher icon | ✅ Home: sound continues. Tapping the notification: wake screen back in about 0.6 s (measured over adb). The app's launcher icon opens the wake screen. Swiping the notification away (Android 14+): n/a on Android 13. | `topResumedActivity` |
| 14 | Phone unlocked and in use when the alarm fires: heads-up, tap opens the wake screen | ✅ with YouTube in front: heads-up "10:14 alarm · Tap to return to your alarm" shown; tapping it opened the wake screen. | screenshot, SystemUI "Expected to HUN" |
| 15 | Opening the app during a session shows only the wake screen | ✅ the launcher and `MainActivity` hand over to the wake screen during a ring; no alarm editing is reachable. Observation C below. | |
| 16 | Other apps, a normal call from the dialer, the emergency dialer | ✅ partly. Settings, the dialer and Chrome each stayed in front for 10 s; the wake screen never came back on its own; the alarm kept playing. ⏭ placing a real call and the lock-screen emergency dialer: deferred to v2 (owner). | |
| 17 | Two alarms one minute apart merge silently | ✅ second alarm merged into the running session: one `session_merge` row, no new sound start. | `session_merge` row, single SoundStarted |
| 18 | `bmgr backupnow`, uninstall, reinstall with restore | ✅ backup succeeded; after reinstall all 4 alarms, 6 history rows and the merge row were back; the enabled alarm was armed for 2026-10-07 09:58 **without opening the app**; no `runtime.db` (no session restored). Notification permission had to be granted again (expected). | app.db before and after, `dumpsys alarm` |
| 19 | No running service after a session ends (NFR-8) | ✅ `dumpsys activity services` shows no service after "I'm up". | |
| 20 | Force stop during a ring; next alarm rings after the app is next opened | ✅ force stop silenced the alarm (no re-ring in 70 s, accepted escape). After reopening the app, its alarms were re-armed. Observation B below. | |

## Observations (not failures)

- **A. Re-ring after a crash shows no heads-up.** When the process came back after the crash (item 3) while the owner was on the home screen, SystemUI logged "No heads up: unimportant notification" for the first post; the ringing notification (importance high, full-screen intent) was then in place, but the user only hears the alarm and has to pull the shade. Minor. → v2 note.
- **B. A force-stopped session rings again when the app is reopened.** After item 20, opening the app restored the interrupted session and it rang again (the owner stopped it). Stricter than the item asks; arguably right (force stop silences only until the app is used again). Owner to confirm the intended behaviour in v2.
- **C. The Recents thumbnail of the app can show the alarm list during a ring** (taken just before the wake screen opened). Cosmetic; tapping it still opens the wake screen. → v2 note.
- **D. One fire arrived 2.4 s after its time** (10:07:29, receiver `msSinceScheduled=2378`) while the app was in the foreground; all other fires were 0.02–0.4 s. Watch in v2 (NFR-1 target 2 s).
- **E. Test tooling:** the debug "fire in N s" hook does not store the new time, so after a reboot the alarm keeps its stored time (correct app behaviour, but the checklist needs a stored alarm, as done for item 4).

## Waived / deferred to version 2 (owner decision, 2026-10-06)

Items 5, 6, 7, 8 (clock and zone part), 9, 10, the accessibility shortcut in 12, the call and emergency-dialer parts of 16, item 4's before-first-unlock UI on the device, OEM task managers of other makers in item 1, and the emulator/other-maker matrix. The owner asked to record these and move on so the app can launch; they are listed in `deferred-work.md` for a v2 device pass.
