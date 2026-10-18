# Story 4.18: Epic 4 payment verification with license testers

Status: done (owner decision 2026-10-19: items 9, 10, 12, 16 and the unchecked parts of 1, 2, 4, 14 deferred to v2, "yes, skip them")

**Device:** Oppo A96 (CPH2333), Android 13, ColorOS.
**Account:** the owner's licence-tester account (Finland, EUR), `RESPOND_NORMALLY`. Every purchase was a Play test order ("This is a test order, you will not be charged.").

**Builds:**
- **2026-10-15:** debug builds from main, sideloaded, used for items 1, 2, 3 and 14.
- **2026-10-18/19:** release **0.1.2 (102)** from main `c03bdee0`, uploaded to Internal testing by the owner and installed from Google Play (installer `com.android.vending`), used for every purchase item.

**Why the release build was needed:** on ColorOS a sideloaded build always reports its installer as `pc`; `adb install -i` and `pm set-installer` don't change it. With that installer, Play refused every purchase with "The item that you were attempting to purchase could not be found." (`ITEM_UNAVAILABLE`), even though the product details loaded.

**Test conditions:** alarms at 10% volume with the alarm stream at step 1, vibration off (owner request). The 50 products were created on 2026-10-15 (`docs/decisions/play-catalog-run.md`).

| # | Item | Result | Date | Notes |
|---|---|---|---|---|
| 1 | Local price; offline | Pass | 2026-10-18 | "Snooze · €1.09" (`snooze_usd_01`). The editor fee ladder shows €1.09 / €2.19 / €3.39. In airplane mode the button reads "Snooze unavailable, offline" and turns back into "Snooze · €2.19" in place when the phone is back online. The "clear app data, then stay offline" part was not run. |
| 2 | Confirm sheet | Pass (partial) | 2026-10-15/18 | Shows the title "Snooze for 9 min?", the price, "This one costs €1.09. The next one costs €2.19.", and the nudge "Is 9 more minutes worth €2.19? You've got this." on snooze 2. "I'll get up" closes the sheet with no charge. **Bug found and fixed (#63):** the sheet flashed for about 0.1 s and closed, because the live price refresh made it look like a different offer. Proposed for v2: the 500 ms guard and swipe/Back checks on the device, and the tax note (needs a US account). |
| 3 | Locked phone | Pass | 2026-10-15 | Pay shows the PIN prompt. Backing out shows "Phone still locked. No charge.". Entering the PIN opens Play's sheet over the ringing flow. |
| 4 | Ringing under the Play sheet | Pass (partial) | 2026-10-15 | The alarm keeps playing under Play's sheet. Full volume was not checked (night-time test at 10%). The grace-mute part was not run. |
| 5 | Successful test purchase | Pass | 2026-10-18 | "Test card, always approves", then 1-tap buy. The snooze is granted, the Snoozed screen shows and closes itself (owner saw it), and the alarm re-rings 9 min later with "Snooze 1 of 5 · €1.09 paid this morning" and "Snooze · €2.19" (2× B). Play shows the EU "Review and agree" screen once, before the first purchase. **Note:** on re-ring the big clock shows the first ring's time (23:01 at 23:11). Follow-up below. |
| 6 | Cancel; offline | Pass | 2026-10-18 | Cancelling Play's sheet shows "Payment cancelled. No charge.". Going offline while the sheet is open closes it, and Snooze reads offline. |
| 7 | Slow test card | Pass | 2026-10-18 | "Payment not confirmed yet. If it goes through before you finish, your snooze starts. Otherwise, finish the check to stop the alarm." and "Snooze unavailable, payment pending". When the card approved within about 1 min, the snooze started by itself. When the check was finished first, Success showed "Your pending payment wasn't used. Google refunds it automatically." |
| 8 | Stranded purchase reuse | Pass (with a note) | 2026-10-18 | The next Pay at €1.09 offered "You already paid €1.09 earlier that wasn't used. Use it for this snooze?". "Use it" snoozed with no new charge (history shows 3 charges, not 4). **Note:** Play's own "You already own this item." error appeared first, so the pre-launch query hadn't seen the purchase that had only just been approved; the reuse sheet came after "Got it". Follow-up below. "Not now" was not run. |
| 9 | Kill after purchase | Deferred to v2 (owner) | | On ColorOS the only kill available over adb (`am force-stop` / "Close app") also cancels the app's alarms, which is a different case (see Story 3.14 #16). Covered by the 4.10/4.11 automated tests. |
| 10 | Snooze from the check footer | Not run | | Proposed for v2. |
| 11 | Max snoozes; test alarm | Pass | 2026-10-19 | With max snoozes at 1: "Snooze 1 of 1 · €1.09 paid this morning" and "Snooze unavailable, max snoozes reached". A test alarm shows "Snooze unavailable, Test · no charge" (tapping does nothing) and ends with "Test finished. Your alarm works.". Max snoozes set back to 5 afterwards. |
| 12 | Lock window at 23:40 | Deferred to v2 (owner) | | Needs real overnight timing. Covered by the 4.4/4.6 tests. |
| 13 | Purchase history | Pass | 2026-10-18 | "October 2026, €3.27 paid", with Sun, Oct 18 at 23:44, 23:26 and 23:01, each "Snooze 1 · €1.09". |
| 14 | Problem with a charge | Pass (partial) | 2026-10-15 | "Open Google Play order history" opens the Play Store, and "Email support" opens the mail chooser. The pre-filled email body was not opened (it would create a draft in the owner's mail); it is covered by `SupportEmailWriterTest`. |
| 15 | Non-USD account | Pass | 2026-10-18 | The owner's account is EUR. Prices, the session line and history all use Play's euro formatting. |
| 16 | TalkBack and 200% | Deferred to v2 (owner) | | |
| 17 | Copy; "No charge." | Pass (seen so far) | 2026-10-15/18 | "Payment didn't go through. No charge.", "Phone still locked. No charge.", "Payment cancelled. No charge.", "You're up. That's what counts.", "Up on time.". |

## Follow-ups (bug candidates)
1. **Re-ring clock (owner noticed):** after a snooze, the ringing screen's big time shows the session's first ring (`config.scheduledAt`, Story 1.15), not the current time. EXPERIENCE.md says "clock", and stock clock apps show the current time. Proposed fix: show the current time.
2. **Stranded reuse right after approval:** Play's "You already own this item." dialog shows before the app's reuse sheet. The app recovers correctly, with no money at risk. Proposed fix: on `ITEM_ALREADY_OWNED`, or when the pre-launch query might be stale, refresh the owned purchases before launching.

## Bugs found and fixed during this story
- **#63:** the confirm sheet closed itself when Play's live price refreshed the cache.
- **#64:** Pay now waits for the price asked when the sheet opened (no false "No connection" while Play reconnects), and an unpriced already-paid sheet is declined instead of showing nothing.

## Owner decision
Owner confirmed on 2026-10-19 ("yes, skip them"): items 9, 10, 12 and 16, and the unchecked parts of 1, 2, 4 and 14, move to v2.
