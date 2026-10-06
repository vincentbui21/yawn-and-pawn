---
title: 'Story 3.9: Fallback check picker'
type: 'feature'
created: '2026-10-06'
status: 'blocked'
baseline_revision: 'e4dd3ea'
review_loop_iteration: 0
followup_review_recommended: false
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-3-context.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-3-2-solve-math-to-stop-the-alarm.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-3-4-quiet-time-grace-window-with-the-countdown-ring.md'
warnings: ['blocked: two decisions needed before implementation (see Open Questions)']
deferred: []
---

<intent-contract>

## Intent

**Problem:** When a camera check cannot be done (camera or permission unavailable, code lost), nothing lets the user wake up some other way. Waking up must always be free (FR-PWK-11). Today the fallback is a stub (`NoFallbackPolicy`, `FallbackRequested` without a type), and it keeps the old seeds and failed attempts.

**Approach:**
- A production `FallbackPolicy` allows the fallback once per session, on a camera entry, for a non-camera type, when the camera is unavailable or after 5 failed attempts.
- `FallbackRequested(type, reason)` replaces the rest of the plan with one entry of the chosen type at Hard × 2 × its default count. Timers do not change. The fallback gets its own seeds and a reset attempt count, and the engine emits `StartCheckStep`.
- The wake screen shows the approved `fallback-link` and the `fallback-picker`.
- History records `fallback_from` (`app.db` +1 version).

## Boundaries & Constraints

**Always:**
- The AD-2 rows are unchanged (R12 gains the type and reason).
- The fallback plan stays for the rest of the session, with new seeds each ring.
- Grace keeps counting and Loud keeps ringing in the picker; Back does nothing; the close button ("Back to check") returns without using the fallback.
- Math is always first and always available. The picker lists only types with a core plugin and a wake composable: Math on this stack, then Word Unscramble and Memory Sequence (numbered) once Lane 2's 3.7 and 3.8 land.
- `SessionRecorder` is the only writer of `fallback_from`.
- The CheckRun names follow main's 3.1 fixes (`fallbackSource`, `totalFailedAttempts`), so the rebase is easy.

**Never:** No matcher-error input (Story 7.7; the policy input stays open for it). No QR scanning or registration (3.10, 3.11).

## Open Questions (blocking; stopped at the spec as instructed)

1. **There is no camera check type for `FakeCameraCheck`.** `CheckType` is sealed in `:core` (AD-9), so a `FakeCameraCheck` in `:testing` cannot be a `CheckType`, and no camera type exists before Story 3.10 (Lane 2). The options are:
   - **(a)** 3.9 adds the core `CheckType.QrBarcode` (camera, count 1, not Direct Boot safe). That overlaps with Lane 2's 3.10, which also needs the registered code to validate.
   - **(b)** Stack 3.9 on 3.10's core type, waiting for Lane 2.
   - **(c) (recommended)** The policy reads camera use through an injected predicate, `usesCamera: (CheckType) -> Boolean = CheckType::usesCamera`. `FakeCameraCheck` in `:testing` is a test plan whose entry the predicate marks as a camera check. The policy, reducer and screen are fully tested now, and the link stays inert in production until 3.10 adds `QrBarcode` with `usesCamera = true`.
2. **The picker footer.** The AC says the picker is a wake screen "with the snooze control in the footer". The approved `FallbackPickerScreen` (`fallback-picker` preview) has no footer, only the title, close button and cards. The options are:
   - **(a)** Add the same `button-snooze` footer as the Check screen. This changes the approved composable and the `fallback_picker` preview baseline.
   - **(b)** Keep the approved screen without snooze.

## Notes for the merge (not blocking)

- **Schema:** `fallback_from` is the next `app.db` version after the stack's base. It is v5→v6 on top of 3.4 here; after Lane 2's 3.5 (v6) it becomes v6→v7.
- **Fallback seeds:** they use this stack's `SeedDeriver.FALLBACK_BASE` and `seedKey`. Main's fixes renamed these to `seed(..., fallback = true)` and `seedOf`, so the rebase maps one onto the other.

</intent-contract>

## Verification

**Commands:**
- `./gradlew qualityGate` -- expected: BUILD SUCCESSFUL (after the decisions).
