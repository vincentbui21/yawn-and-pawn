---
title: 'Story 4.15: Snoozed screen, session line and snoozing from a check'
type: 'feature'
created: '2026-10-14'
status: 'review'
baseline_revision: '0c3665c1'
review_loop_iteration: 0
followup_review_recommended: true
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-4-context.md'
  - '{project-root}/_bmad-output/planning-artifacts/epics.md (Story 4.15; boundary with 4.14 and 6.4)'
  - '{project-root}/_bmad-output/planning-artifacts/ux-designs/ux-pay-per-snooze-2026-09-26/EXPERIENCE.md (Key strings: session line, after paid snooze, Success; State Patterns: Ringing after a snooze, Snoozed, Success after snooze; Payment outcomes: Purchased / Use it)'
  - '{project-root}/_bmad-output/planning-artifacts/ux-designs/ux-pay-per-snooze-2026-09-26/DESIGN.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-4-7-snoozeavailability-with-every-reason.md (SessionData.paid; deferral to fill the grant price)'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-4-11-billing-orchestration-launch-recovery-item-already-owned-and-stranded-reuse.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-4-13-snooze-confirm-sheet.md'
  - '{project-root}/androidApp/src/debug/kotlin/com/yawnandpawn/app/debug/preview (ringing_after_snooze, snoozed, success_after_snooze, success_pending)'
deferred:
  - 'Story 4.14: the payment outcome messages over Ringing and Check (this story adds none).'
  - 'Epic 6 (Story 6.4): the celebration and streak on Success; this story only fills the after-snooze paid line and the pending note of the basic screen.'
---

<intent-contract>

## Intent

**Problem:** after a paid snooze the wake screen keeps showing the Ringing screen, with the screen kept on, until the next ring. The re-ring shows no session line, Success never says what was paid or that a pending payment was not used, and `SessionData.paid` stays empty in the app because the coordinator never passes the price with `PurchaseGranted` / `ReuseAccepted` (Story 4.7 deferral).

**Approach:**
- **Core (`PurchaseCoordinator`, `PurchaseLedger`):** a grant carries the price of the newest intent of its session for that product (`PurchaseGranted.price`); "Use it" carries what the reused payment cost from its purchase record (`ReuseAccepted.price`, new `PurchaseLedger.paidPrice(token)`, only when the record holds the amount actually paid). Unknown stays null (never an estimate).
- **`:composeApp` (pure mappings):**
  - `SessionLine.paid` and `SuccessKind.AfterSnooze.paidThisMorning` become `List<Money>` (one total per currency, "{amount1} + {amount2}" through `formatMoney(List)`).
  - `ringingUiState` adds `sessionLine(session)`: "Snooze {n} of {max} · {paid} paid this morning", n = `snoozesGranted`, max = frozen `config.maxSnoozes`, paid = `SessionData.paid`. None on the first ring, in a test session, or when no amount is known.
  - `snoozedUiState(session, zone)`: `SnoozedUiState(nextRingAt = snoozeEnd wall time in zone)`; null without a snooze end.
  - `successUiState`: after a snooze, `paidThisMorning = session.paid`; `pendingNotUsed = paymentPending` (never in a test session).
  - `RingingScreen`: the session line is read by TalkBack after the clock and before "I'm up" (traversal index between them). No visual change.
- **`:androidApp` (`WakeActivity`):** in `Snoozed` the wake screen shows `SnoozedScreen` ("Snoozed. Next ring at {time}.", 12/24 h from the system setting), takes no taps, and after `SNOOZED_SCREEN_TIME` (3 s) clears `FLAG_KEEP_SCREEN_ON` and finishes, if the session is still snoozed. The re-ring opens a new wake screen through the existing ringing notification (Epic 2). A state that leaves Snoozed first (a merged alarm re-rings early) cancels the close.
- **Snoozing from a check:** the check footer's `button-snooze` already opens 4.13's sheet; a grant from Grace or Loud (or a pending payment that clears while the user is mid-check) goes to the same Snoozed screen, the reducer drops the check progress, and the re-ring starts a fresh `CheckRun` and a new grace window. Tested end to end, no new code path.

## Boundaries & Constraints

**Always:**
- Alarm safety: the Snoozed screen sends no event, never stops or delays the slot armed at the snooze end, and never forces the screen off (it only stops keeping it on). "I'm up" and the check work on the re-ring exactly as on a first ring.
- Copy verbatim from EXPERIENCE.md (existing resources: `snoozed_next_ring`, `wake_session_line`, `success_paid_this_morning`, `success_pending_not_used`); no celebration, animation or payment wording on Snoozed; money in `text-secondary-sunrise`, never green or red.
- Tokens only; Sunrise only.

**Never:**
- No reducer or AD-2 change; no new string; no change to the confirm sheet or to the message mapping (4.14 owns it).
- No approved preview baseline changes.

## Decisions (fast mode: default taken, owner can change)

1. **No amount known, no session line.** A snooze whose price is unknown (an old session, a reuse with only an estimated record) shows no "Snooze {n} of {max} · …" line at all rather than a new count-only string or an invented amount. With the coordinator fix this only happens for sessions stored before this story.
2. **Mixed currencies** show one total per currency joined by " + " (EXPERIENCE.md "Money, mixed currencies"), in the session line and on Success.
3. **The 3 s are counted per wake screen.** A screen recreated (rotation) or reopened ("Back to alarm") during a snooze shows the Snoozed note for 3 s again, then closes.
4. **The Ringing clock keeps the alarm's scheduled time on a re-ring** (as the notification does). The design preview shows the re-ring time (7:39); changing the clock and the notification together is left for the owner.
5. **Pending not used** shows whenever the session ended with `paymentPending` set (also with no paid snooze), never for a test.
6. **Grant price** is the newest intent of the session for that product (the same rule the ledger uses to price its record).

## Code Map

- `core/.../billing/PurchaseCoordinator.kt` (grant and reuse price), `PurchaseLedger.kt` (`paidPrice`).
- `composeApp/.../ui/wake/WakeContract.kt` (`SessionLine.paid`, `AfterSnooze.paidThisMorning` as lists), `RingingMapping.kt` (`sessionLine`, `snoozedUiState`), `SuccessMapping.kt`, `RingingScreen.kt` (traversal), `WakeEndScreens.kt` (list formatting).
- `androidApp/.../wake/WakeActivity.kt` (Snoozed screen, 3 s close). Debug design preview: list types only.

## Rebase notes (4.14 in flight)

- `WakeActivity.kt`: 4.15 adds a `WakeScreen.Snoozed` branch in `sessionScreen`, a `SnoozedClose` effect and `SNOOZED_SCREEN_TIME`; it does not touch `sheetOverlay`, `onIntent` or the message mapping. Keep both sides.
- `WakeContent`: one new `is WakeScreen.Snoozed` branch.
- `WakeCheck.kt`, `ConfirmSheetHost.kt`: untouched.
- `RingingMapping.kt`: `ringingUiState` gains `sessionLine`; 4.14 sets `message` through the overlay copy, so no overlap.

## Verification

- `./gradlew qualityGate :androidApp:assembleDebugAndroidTest --no-daemon` -- expected: BUILD SUCCESSFUL.

</intent-contract>

## Test plan

- **core** `PurchaseCoordinatorTest`: a grant carries the intent's price into `SessionData.paid`. `PurchaseCoordinatorSequenceTest`: "Use it" carries the reused payment's recorded price.
- **composeApp** `RingingMappingTest` / `SuccessMappingTest`: session line (none on the first ring, n of max, USD and EUR totals, test mode, unknown amount), merged re-ring keeps the line, Snoozed next ring time, Success paid and pending note.
- **androidApp** Robolectric `SnoozedScreenTest` (real engine, real wake screen): Snoozed shows "Snoozed. Next ring at 6:24 AM." (and 24 h), stays 3 s, then closes and clears keep-screen-on while the slot stays armed; the re-ring shows the session line and the next price, and "I'm up" works; snoozing from a Math check discards the progress and the re-ring starts problem 1 with a new grace window; a pending payment granted mid-check goes to Snoozed with no tap; a merged alarm during the 3 s re-rings in place with the same session line.
- **androidApp** semantics: TalkBack reads clock, session line, then "I'm up"; the Snoozed note is a polite live region.
- **Roborazzi** (new baselines, Sunrise, 100% and 200%): Snoozed; re-ring with the session line (USD 1 of 5, EUR 4 of 5); check footer during a paid session; Success after snooze with paid; Success with pending not used.

## Auto Run Result

Status: implemented in fast mode (one agent, unattended Epic 4 run), no review pass yet. Branch `story/4-15-snoozed-screen` on `origin/main` (`0c3665c1`).

- **Tests:** `PurchaseCoordinatorTest` (grant price, no intent no amount), `PurchaseCoordinatorSequenceTest` (reuse price), `SessionLineMappingTest`, `SuccessMappingTest`, `SnoozedScreenTest` (6 Robolectric flows on the real engine and wake screen), `SnoozedScreenshotTest` (12 new baselines: `wake_snoozed_*`, `wake_ringing_session_line_{usd,eur}_*`, `wake_check_paid_session_*`, `wake_success_after_snooze_paid_*`, `wake_success_pending_not_used_*`). No existing baseline changed.
- **Changed tests:** `CheckFontScaleTest`, `CheckSemanticsSuiteTest` and the debug design preview only follow the `List<Money>` types.
- **Run locally** (coordinator instruction: the PC ran out of memory, so CI runs the full `qualityGate`): `:core:jvmTest` (PurchaseCoordinatorTest, PurchaseCoordinatorSequenceTest); `:composeApp:testAndroidHostTest` (SessionLineMappingTest, SuccessMappingTest, RingingMappingTest); `detekt spotlessCheck :androidApp:compileDebugUnitTestKotlin :androidApp:compileDebugAndroidTestKotlin :androidApp:testDebugUnitTest` (SnoozedScreenTest, SnoozedScreenshotTest, CheckFontScaleTest, CheckSemanticsSuiteTest, SnoozeConfirmSheetTest) with `--no-daemon --max-workers=2`: BUILD SUCCESSFUL. A first full `qualityGate` run crashed (out of native memory); the second stopped only at detekt `ReturnCount` in `WakeActivity.sessionScreen`, fixed in `fix(4.15)`.

**Residual risks:**
- If the screen closes at the very moment the snooze ends, the re-ring may post its notification without the full-screen intent; the next heartbeat posts the full one (existing Epic 2 behaviour for a screen left while ringing).
- A session stored before this story has `paid` empty, so its re-ring shows no session line (Decision 1).
- The re-ring clock shows the alarm's scheduled time, not the re-ring time (Decision 4; differs from the preview).

## pps-design Done checklist

- [x] Only tokens from `DESIGN.md` used (no raw hex, no new radii, no new font sizes): the approved composables, unchanged.
- [x] Light, Dark (and Sunrise where relevant) checked with previews / screenshots: wake screens are Sunrise only; new `wake_snoozed_*`, `wake_ringing_session_line_*`, `wake_check_paid_session_*` and `wake_success_*` baselines at 100% and 200%.
- [x] Every colour pair used is in the `DESIGN.md` contrast table: `text` and `text-secondary` on the Sunrise surface; no new pair.
- [x] Touch targets ≥ 48 dp; wake actions ≥ 64 dp: Snoozed has no action; the re-ring keeps "I'm up" 72 dp and Snooze 64 dp (asserted at 200%).
- [x] Works at 200% font scale and with TalkBack; outcome glyphs present: session line between the clock and "I'm up"; Snoozed note a polite live region; no outcome glyph on these surfaces (no colour-only meaning).
- [x] Reduced-motion path works: no motion added (Snoozed and the after-snooze Success are static).
- [x] Copy matches `EXPERIENCE.md > Voice and Tone` (existing resources, verbatim; `CopyRulesTest` passes).
- [x] Every state row in `EXPERIENCE.md > State Patterns` for this surface is handled: Ringing after a snooze, Snoozed, Success after snooze, Success pending not used, Payment outcomes Purchased / Use it → Snoozed, Restored (Snoozed shows the note again).
- [x] "I'm up" is the most prominent wake action; snooze is visible, plain and priced (next price B × (n + 1) on the re-ring).
- [x] Compose `@Preview`s for each state exist (design preview `ringing_after_snooze`, `snoozed`, `success_after_snooze`, `success_pending`); Roborazzi screenshots added.
