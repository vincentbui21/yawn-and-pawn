# Story 3.14: Epic 3 device verification checklist

Status: in-progress (owner session 2026-10-08; remaining items deferred to v2 by the owner)

Device: Oppo A96 (CPH2333), Android 13, debug build of `main` (772ed43 on 2026-10-07, 75b1877 on 2026-10-08).
Emulators: not run (owner decision: ship fast; NFR-1 emulator matrix deferred).
Test alarms at 10% volume, vibration off (owner request).

| # | Item | Result | Date | Notes |
|---|---|---|---|---|
| 1 | Math difficulties and counts | Partial | 2026-10-08 | Medium solved on device. **Owner: Medium is too hard** (chained +/− or only ×/÷). Decision: make **Easy the default**, keep Medium as is. Haptic not checked. |
| 2 | Grace window / quiet time | Partial | 2026-10-08 | Countdown shown and "Time's up. Alarm's back on until you finish." seen. **Bug: the ongoing-alarm heads-up notification covers the countdown at the top of the wake screen** (owner noticed too). Stopwatch and vibration-in-quiet-time not checked. |
| 3 | 30 s / 15 s windows | Deferred to v2 | | |
| 4 | Word Unscramble | Pass | 2026-10-07 | Easy 4–5 letters (STAIR, CABLE, TRAIN), Medium 7 (MAILBOX), Hard 8 (PAINTING). Shuffle and Clear work; wrong word shows "Not quite. Try again." 90 words sampled from the list (30 per difficulty): all plain everyday words, none to block. |
| 5 | Memory Sequence | Partial | 2026-10-07 | Try it: playback, "Your turn", a wrong tap shows "Not quite. Try again." and replays a new sequence. Minor: the grid moves down when "Not quite" appears; the lit tile shows its number. Sequence lengths 4/6/8, 4×4 on Hard and readability not checked. |
| 6 | All / Random modes | Deferred to v2 | | |
| 7 | QR/Barcode | Pass (partial) | 2026-10-07/08 | Camera permission asked only when QR/Barcode is ticked. Product barcode (EAN-13) registered and scanned at wake time: the real alarm stopped ("Up on time."). Printed QR registration and dim-room torch not checked. |
| 8 | Wrong code / fallback after 5 | Partial | 2026-10-08 | Try it: a different code shows "That's a different code. Scan your registered one." Fallback link after 5 wrong codes not reached on device. |
| 9 | Camera failures (revoke, call app, privacy toggle, screen off/on) | Deferred to v2 | | |
| 10 | Fallback picker | Deferred to v2 | | |
| 11 | Re-register banner | Deferred to v2 | | |
| 12 | Reboot before unlock with QR | Deferred to v2 | | |
| 13 | Try it on every check | Pass | 2026-10-07 | Math, Word, Memory, QR: no sound (alarm audio not playing), editor keeps unsaved changes (Words 5, Easy kept). QR Try it is disabled until a code is registered. |
| 14 | TalkBack F5 | Deferred to v2 | | |
| 15 | 200% font | Deferred to v2 | | |
| 16 | Kill mid-check | **Fail (deferred to v2)** | 2026-10-08 | `am crash` on Oppo shows "keeps stopping"; after "Close app" the alarm did **not** come back by itself within 164 s (the session-slot alarm was cancelled, which suggests Oppo's "Close app" acts like a force-stop). Opening the app resumed the same step ("Scan your code") and ringing. In Epic 2 the crash re-ring worked. Recheck with a plain process death in v2. |
| 17 | Success screen variants | Pass (partial) | 2026-10-08 | "Up on time." for a real alarm, "Test finished. Your alarm works." for a test alarm, Done closes. 60 s auto-close not checked. |
| 18 | Copy matches EXPERIENCE.md | Deferred to v2 | | |
| 19 | Reboot before unlock with Word | Deferred to v2 | | |
| 20 | Heads-up over the wake screen (bug 2 fix) | Deferred to v2 | | With the phone unlocked, let a test alarm ring, open the wake screen and tap "I'm up": no "Tap to return to your alarm" heads-up drops over the countdown, also when the grace window ends ("Time's up") and at the 60 s heartbeat. Press Home: the notification heads up as the way back, and a tap returns to the alarm. Press power during the ring: note whether and when the wake screen comes back by itself (leaving posts the notification without its full-screen intent; the next ring step, such as the grace end or the heartbeat, posts the full one). Check on the Oppo (ColorOS) and one stock Android phone. |
| 21 | Bug fixes 1, 3, 4 and 5 | Deferred to v2 | | Test alarm from the editor with QR (code registered, not saved) shows "Scan your code", with Word shows the Word step; the Sound slider stops at 10%; a new alarm shows "Easy · 3 problems"; the Memory grid stays put when "Not quite. Try again." appears. |
| 22 | Quiet channel back to the Alarms channel (PR #41 review) | Deferred to v2 | | On the Oppo (ColorOS): during a ring, open the wake screen, then leave it. (a) Unlocked, press Home: the update from the quiet "Alarm in progress" channel back to the Alarms channel really pops up as a heads-up. (b) Locked, press power so the screen is off: the notification shows on the lock screen, and when quiet time ends (or the next 60 s heartbeat) the full-screen intent turns the screen on with the wake screen. Also rotate the wake screen and switch dark mode during a ring: no heads-up drops over it. |

## Bugs found (to fix before or early in Epic 4)

Fixed in `fix/epic-3-device-check-bugs` (2026-10-08, host tests only; rows 20 and 21 recheck them on a device).

1. **"Test alarm" ignores the alarm's checks.** A test alarm always rings Math · Medium · 3, both from unsaved editor changes and from a saved QR alarm (`check_config` had the QR row with its code). Test alarms should use the alarm's checks (the editor's current ones).
2. **Heads-up notification covers the wake screen's countdown** while the wake screen is in front.
3. **Volume can be set to 0 %** in Sound, making a silent alarm possible.
4. Math default difficulty: change to **Easy** (owner decision 2026-10-08).
5. Minor: Memory grid jumps down when "Not quite" appears.

## Owner notes
- Celebration (confetti) after finishing: planned in **Epic 6** (Story 3.3 is the basic Success screen by design). Owner asked to come back to it.
