---
title: 'Story 4.14: Payment outcome messages'
type: 'feature'
created: '2026-10-09'
status: 'in-progress'
baseline_revision: '79aa8b4'
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-4-context.md'
  - '{project-root}/_bmad-output/planning-artifacts/ux-designs/ux-pay-per-snooze-2026-09-26/EXPERIENCE.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-4-11-billing-orchestration-launch-recovery-item-already-owned-and-stranded-reuse.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-4-13-snooze-confirm-sheet.md'
warnings:
  - 'Story 4.7 (snooze availability with Connectivity) is not merged: the "Snooze unavailable: offline" label and its return when online come from its policy.'
deferred:
  - 'Story 4.7: the Offline row''s availability ("Snooze unavailable: offline" until connectivity returns) is its Connectivity-driven policy; the wake screen already follows any policy change in place, with no new message (tested here with a flipping policy).'
  - 'Story 4.18: read every message on the Oppo with TalkBack (polite, after the current speech) and check the 10 s minimum by hand.'
---

<intent-contract>

## Intent

**Problem:** after a failed payment a half-asleep user must know at once that nothing was charged, and must still be one tap from the free path. Story 4.13 shows only "Phone still locked. No charge." and hides it on any tap or after 10 s; the other outcomes (cancelled, error, offline, pending) are silent (FR-RNG-4, FR-RNG-5, FR-RNG-7, FR-RNG-9, FR-MSG-4, NFR-3, NFR-9, AD-11).

**Approach:**
- **One pure table** (`ui/wake/PaymentOutcomes.kt` in `:composeApp`): `paymentOutcome(PaymentResult)` is an exhaustive `when` over the domain results the engine reports (`PurchaseOutcome` from `ShowPurchaseOutcome`, the pending effect, a grant, "Use it", "Not now"). Each row gives the message (a `WakeMessage` or none), where the user returns, and what Snooze offers next. `WakeMessage.text` maps each message to exactly one string resource (the one `WakeSnackbar` draws).
- **Host:** `ConfirmSheetHost.onEffect` shows the row's message for every `ShowPurchaseOutcome` and `ShowPaymentPending` (replacing 4.13's UnlockFailed-only branch), on the session's current ring. Grants and reuse show nothing.
- **Wake snackbar rules:** the message shows for at least 10 s and then until the next tap, so a tap in the first 10 s (often "I'm up") never hides "No charge." before it can be read. Any tap still does its own action at once. A newer outcome replaces the message; it belongs to one ring only.

## The table (EXPERIENCE.md > State Patterns > Payment outcomes, verbatim)

| Outcome (domain result) | Exact copy | Where shown | Duration | TalkBack |
|---|---|---|---|---|
| Unlock cancelled or failed (`PurchaseOutcome.UnlockFailed`) | "Phone still locked. No charge." | Ringing, or the check the user is on (footer, above Snooze) | at least 10 s, then until the next tap | polite live region, read once when it appears |
| Play sheet cancelled (`PurchaseOutcome.Cancelled`) | "Payment cancelled. No charge." | Ringing or Check | same | same |
| Billing error (`PurchaseOutcome.Failed`) | "Payment didn't go through. No charge." | Ringing or Check | same | same |
| No connection (`PurchaseOutcome.Offline`) | "No connection. No charge." | Ringing or Check; Snooze reads "Snooze unavailable: offline" until connectivity returns (4.7 policy) and comes back in place with no new message | same | same |
| Pending (`ShowPaymentPending`) | "Payment not confirmed yet. If it goes through before you finish, your snooze starts. Otherwise, finish the check to stop the alarm." | Ringing or Check; Snooze reads "Snooze unavailable: payment pending" for this session | same | same |
| Purchased (`PurchaseGranted`) | none | Snoozed (4.15) | n/a | n/a |
| Already paid, "Use it" (`ReuseAccepted`) | none | Snoozed (4.15) | n/a | n/a |
| Already paid, "Not now" (`ReuseDeclined`) | none | Ringing or Check; Snooze reads "An earlier {price} payment is being refunded" | n/a | n/a |

Every string above is already a resource copied from EXPERIENCE.md Key strings; no new string.

## Boundaries & Constraints

**Always:**
- **Alarm safety:** the snackbar is in the layout flow above "I'm up" (Ringing) and above Snooze in the check footer. It never overlays "I'm up" or the check input, never takes a tap, and has no action. "I'm up", the check keys and the sound work while it shows. The sound is at full set volume on every return (4.11 `reassertRingVolume()`, muted only during a running grace countdown).
- "No charge." appears whenever nothing was charged. A paid morning shows no message at all (no praise, no shame).
- Money stays neutral: the snackbar is `inverse-surface` / `inverse-text`, never green or red.

**Never:**
- No reducer, coordinator or AD-2 change; no new string; no new colour pair.

## Decisions (default taken, owner can change)

1. **"At least 10 s or until the next tap"** is read as: visible for 10 s whatever happens, and after that until the next tap. A tap in the first 10 s still acts at once and only makes the message end at the 10 s mark. Without any tap it stays for the rest of that ring.
2. **What counts as a tap:** any wake intent and any tap on empty space of the wake screen.
3. **Offline when the network looks online** (a captive portal): Snooze follows 4.7's Connectivity hint, so it can stay offered; another Pay fails again with "No connection. No charge." and nothing is charged. No separate offline latch.
4. **The message stays under a newly opened confirm sheet** and the next outcome replaces it; a new ring (after a snooze, a merge) shows none.
5. **A recreated wake screen** restarts the 10 s minimum (it can show longer, never shorter).

</intent-contract>

## Code Map

- **composeApp:** `ui/wake/PaymentOutcomes.kt` (`PaymentResult`, `PaymentOutcomeRow`, `paymentOutcome`, `WakeMessage.text`); `WakeComponents.kt` (`WakeSnackbar` uses `WakeMessage.text`).
- **androidApp:** `wake/ConfirmSheetHost.kt` (every outcome to its message, `RingMessage` with the minimum and tap rules); `wake/WakeActivity.kt` (the 10 s minimum, taps on empty space end it after the minimum).
- **Tests:** `PaymentOutcomesTest` (composeApp: rows, exhaustive over `PurchaseOutcome`, `PurchaseFailureKind`, `LaunchResult` and `PurchaseUpdate`); `ConfirmSheetHostTest`; `PaymentOutcomeMessagesTest` (Robolectric, real engine and wake screen); `PaymentOutcomeScreenshotTest` (Roborazzi, every message on Ringing and on a check, Sunrise, 100% and 200%); `PaymentOutcomeCopyTest` (copy verbatim from EXPERIENCE.md's table).

## Verification

**Commands:**
- `./gradlew qualityGate :androidApp:assembleDebugAndroidTest --no-daemon` -- expected: BUILD SUCCESSFUL.

## Auto Run Result

Status: implemented in fast mode (one agent, unattended Epic 4 run), with no review pass. Branch `story/4-14-payment-outcomes` on `origin/main` (`79aa8b4`).

- **Built:** `PaymentOutcomes.kt` (the table, exhaustive over the domain results; `WakeMessage.text` is the one resource per message and `WakeSnackbar` draws it). `ConfirmSheetHost` shows every `ShowPurchaseOutcome` and `ShowPaymentPending`, which replaces 4.13's UnlockFailed-only branch. `RingMessage` carries `serial`, `tapped` and `minimumShown`. `WakeActivity` runs the 10 s minimum per showing (`MESSAGE_MINIMUM`, renamed from `MESSAGE_TIMEOUT`), and taps on empty space or on the message count as taps.
- **Tests:** `PaymentOutcomesTest` (composeApp: the rows; `PurchaseFailureKind`, `LaunchResult` and `PurchaseUpdate` through exhaustive `when`s), `PaymentOutcomeCopyTest` (each row of EXPERIENCE.md's table matches its resource word for word, and "none" rows have no message), `ConfirmSheetHostTest` (every outcome, the minimum and tap rules, replacement, no message outside a ring or for reuse), `PaymentOutcomeMessagesTest` (Robolectric, real engine and wake screen: cancelled, error and offline above "I'm up" with the sound on; "I'm up" at once; check footer above Snooze with keys typing at once; offline back in place with no new message; pending; a grant shows none), `PaymentOutcomeScreenshotTest` (20 new `wake_payment_*` baselines: 5 messages × Ringing/Check × 100%/200%, asserting that the message, "I'm up" and the Check key are whole on screen).
- **Changed tests:** `SnoozeConfirmSheetTest` (a tap during the minimum keeps the message; after 10 s the next tap ends it).
- **Strings:** none new. Every message was already a resource copied word for word from EXPERIENCE.md Key strings, and nothing is marked "(EXPERIENCE.md Key strings)".

**Rebase notes:**
- **4.7:** its live `SnoozeConditions` re-renders the button in place. No message code depends on it. If 4.7 changed `WakeActivity.sheetOverlay` or the `onIntent` head, keep `sheet.onTap()` first, and keep `::screenTapped` as the screen's `onInteracted`.
- **4.12:** nothing to do here. Its response-code table only has to keep producing `PurchaseFailureKind` values, and a new kind fails to compile in `PurchaseRules.outcomeOf` and in `PaymentOutcomesTest`.
- **4.15:** the Snoozed surface owns the Purchased and "Use it" rows (no message). The 4.14 message is per ring, so a re-ring shows none.

**Risks:**
- The message has no timeout if nobody taps (Decision 1); it stays for that ring.
- At 200% on a check, the pending message pushes the problem line up under the pinned area (PinnedFit scrolls it); the keys and Snooze stay whole on screen.
- TalkBack polite announcement and the minimum are Robolectric-only until 4.18.

## pps-design Done checklist

- [x] Only tokens from `DESIGN.md` used: the existing `WakeSnackbar` (`inverse-surface`, `inverse-text`, `rounded.sm`); no new token.
- [x] Light, Dark (and Sunrise where relevant) checked: wake screens are Sunrise only; `wake_payment_*` screenshots at 100% and 200%.
- [x] Every colour pair used is in the `DESIGN.md` contrast table: no new pair.
- [x] Touch targets ≥ 48 dp; wake actions ≥ 64 dp: unchanged; the message takes no tap.
- [x] Works at 200% font scale and with TalkBack: whole on screen at 200% (asserted); polite live region, no action (asserted); "No charge." in words, never colour.
- [x] Reduced-motion path works: the message does not animate; the minimum is a plain delay.
- [x] Copy matches `EXPERIENCE.md > Voice and Tone`: `PaymentOutcomeCopyTest` and `CopyRulesTest`.
- [x] Every state row in `EXPERIENCE.md > State Patterns > Payment outcomes` is handled (all 8 rows).
- [x] "I'm up" is the most prominent wake action; snooze is visible, plain and priced: unchanged, and the message sits above them in the flow.
- [x] Compose previews for each state exist (design preview `payment_*`); Roborazzi screenshots added.
