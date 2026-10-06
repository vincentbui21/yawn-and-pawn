# Epic 3 Context: Prove you're awake

<!-- Generated from planning artifacts. Regenerate with compile-epic-context if planning docs change. -->

## Goal

Replace the Epic 1 placeholder step with real wake-up checks. After "I'm up", the alarm goes quiet for a 15–30 s grace window with a visible countdown, and the user must pass the checks chosen for that alarm: Math, Word Unscramble, Memory Sequence or QR/Barcode, each with its own difficulty and count, in Random or All mode. Waking up must stay free for everyone. When a camera check can't physically be done, an accessible fallback check (Math first, usable with TalkBack) is offered once per session. Every check can be tried before saving. A basic Success screen closes the flow (Epic 6 adds the celebration). The epic plugs into the Epic 1–2 session machine, engine, Direct Boot substitution and session lock without adding states, and ends with a device checklist.

## Stories

- Story 3.1: Check plugin contract and the Math generator in core
- Story 3.2: Solve Math to stop the alarm
- Story 3.3: Success screen after the check (basic)
- Story 3.4: Quiet time (grace window) with the countdown ring
- Story 3.5: Choose the checks for each alarm
- Story 3.6: "Try it" previews for every check
- Story 3.7: Word Unscramble check
- Story 3.8: Memory Sequence check
- Story 3.9: Fallback check picker
- Story 3.10: QR/Barcode: register a code and scan it to stop the alarm
- Story 3.11: QR/Barcode when the camera fails, and before first unlock
- Story 3.12: Checks with TalkBack, end to end
- Story 3.13: Suggest re-registering after 3 fallbacks in 7 days
- Story 3.14: Epic 3 device verification checklist

## Requirements & Constraints

- **Checks and plans:** each alarm has one or more checks, each with a difficulty (Easy/Medium/Hard, where the type has one) and a count. In Random mode a ring picks one entry by seed, so a re-ring can pick a different one. In All mode the user's order is kept. At least one check per alarm, no type twice, and the count must be within the type's range.
- **Fairness and determinism:** a puzzle is fully determined by its seed. A restored session shows the same puzzle at the same position, and digits typed but not submitted are lost. The UI never decides correctness: answers go to the engine and are validated in core.
- **Grace window:** muted for the alarm's 15–30 s (default 20) from "I'm up". There is one window per ring, a new one only after a re-ring from a snooze, and none on a merged ring (`noGraceThisRing`). When it runs out the alarm returns at the set volume, a strong haptic plays and progress is kept. The countdown is read from the engine's grace `Deadline`, never from a UI timer. It pauses during calls and continues correctly after a restore.
- **Fallback (FR-PWK-11):** it is allowed only on a camera check. The link shows at once if the camera or its permission is unavailable (permission missing, CameraX error, disconnect, or no frame within 5 s on the monotonic clock), otherwise after 5 failed attempts. It is offered once per session. The fallback entry is the chosen non-camera type at Hard with twice its default count. Timers are unchanged, so no new grace window starts. The fallback plan stays for the rest of the session.
- **Direct Boot:** before the first unlock, any entry that is not Direct Boot safe becomes Math · Medium · 3. The ringing and check screens show "Your phone restarted, so today's check is Math." The substitution stays for that ring even after unlock, and the next ring uses the configured check.
- **Camera:** the `CAMERA` permission is asked only when QR/Barcode is selected (never at app start). Frames are analysed on the device only and no image is ever written to storage. A code counts only after the same value is seen in 3 frames in a row. A repeated wrong code within 2 s is not submitted twice.
- **Previews ("Try it"):** they send no engine events, play no sound, have no grace window or snooze, write no history and make no scheduler calls. Unsaved editor state survives.
- **Accessibility (NFR-9):** a TalkBack user can finish flow F5 (QR with the camera revoked → fallback → Math) with taps only. Every control has a label, role and state. The problem is a heading. Feedback and the countdown are polite live regions. At 200% font on 360 × 640 dp, the main input, the fallback link and the snooze control fit without scrolling, and the clock is capped at 1.3×. Wake targets are at least 64 dp, app targets at least 48 dp.
- **Owner-approved defaults (2026-09-26), recorded in `docs/decisions/q10-check-parameters.md` (new):**
  - **Math:** count 1–10 (default 3). Easy is a±b; Medium is a×b+c; Hard is a×b+c×d.
  - **Word Unscramble:** count 1–5 (default 2). Words have 4–5, 6–7 or 8–10 letters by difficulty.
  - **Memory Sequence:** 1–5 rounds (default 2). Sequences are 4, 6 or 8 long, on a 3×3 grid (4×4 on Hard).
  - **QR/Barcode:** count is fixed at 1.
  - **Success screen:** times out after 60 s.
  - **Default plan:** until 3.5, every alarm gets Random · Math · Medium · 3.
  - PRD Q12 (the accessible fallback path) is closed in `docs/decisions/q12-accessible-fallback.md` (3.12).
- **Every UI story:** the story has the `pps-design` Done checklist with every item ticked. All strings are Compose resources and match the EXPERIENCE.md Key strings verbatim, and `CopyRulesTest` passes. Strings that EXPERIENCE.md does not define yet are marked `(EXPERIENCE.md Key strings)` and listed for the owner. Roborazzi covers Sunrise for wake screens and Light/Dark for app screens, at 100% and 200% font scale. `./gradlew qualityGate` passes.

## Technical Decisions

- **What Epics 1–2 already built (extend it, don't rebuild):**
  - **Reducer model (`core.session`):** `CheckStep` has only `Placeholder`, with a stable `typeName` that history stores. `CheckPlan(steps)` and `CheckRun(plan, seeds, step: Int, failedAttempts, fallbackUsed)` also exist, and `CheckAnswer` has `Placeholder` and `ImageMatched`.
    - Seeds are not pure yet. A `SeedSource` port (random) fills `seeds` in `AlarmFired`, `TestAlarmFired` and the purchase/reuse events. Story 3.1 replaces this with the pure `SeedDeriver` and adds the detekt ban on unseeded `Random` in `core.checks`.
    - The session-level `StepResult` enum (`Invalid`/`ValidNext`/`ValidLast`) stays the AD-2 row mapping. The new plugin result (`ItemCorrect`/`Correct`/`Wrong`/`WrongRestart`) needs a different name or package.
    - `CheckValidator` (`PlaceholderCheckValidator`) and `FallbackPolicy` (`NoFallbackPolicy`) are the seams to replace. `CheckRules.onFallbackRequested` already swaps the plan, sets `fallbackUsed` and keeps timers.
    - `FallbackRequested` is a data object today. Story 3.9 gives it `(type, reason)`, which adds no new table row.
    - `ConfigResolver` and `SessionConfig` freeze the plan, and `SessionConfig.graceSeconds` exists.
  - **Persisted shape:** `CheckRun` and `CheckPlan` are persisted in `runtime.db` through `SessionJson` (golden fixtures). A session stored by the previous app version must still restore after an update, so change the shape with a decode path and update the fixtures on purpose.
  - **Direct Boot:** `DirectBootSubstitution` (`apply`, `plan`, `lockedPlan`) substitutes one entry for one. `DIRECT_BOOT_CHECK` is `Placeholder` until 3.2 makes it Math · Medium · 3, and `CheckStep.isDirectBootSafe` becomes per type. `SessionData.directBootRing` keeps the substitution for the ring and also applies it to the fallback plan. The effect `LiftDirectBootSubstitutions` and the live `UserLockState` port (with a fake) exist.
  - **Grace and Loud:** `ImUpTapped` emits `Mute` and `StartCheckStep`, or only `StartCheckStep` without grace. `GraceElapsed` emits `UnmuteToVolume(config.volumePercent)` and `StrongHaptic`, and Story 2.8 applies it on the alarm stream. `SessionConfig.vibrateInGrace` already exists, but comes from global settings with default false. Story 3.4 makes it a per-alarm setting with default true.
  - **History:** `SessionRecorder` is the only writer. It already writes `check_types` (the `typeName` of each step, in order) and `fallback_used`, so each new type needs a stable `typeName` that never changes. `fallback_from` (3.9) also goes through `SessionRecorder`.
  - **Wake runtime:** `WakeActivity` currently auto-answers the placeholder in Grace or Loud (`answerPlaceholder` with a retry). Story 3.2 replaces this with the real check screen. `RingingScreen`, `RingingMapping` and the snooze control (rendering `SnoozeAvailabilityPolicy`, `NoBillingSnoozeAvailability`) are reused in the check footer.
  - **Design-preview UI, already in `:composeApp` `commonMain` (wire it to the engine, don't redraw it):**
    - **Wake screens:** `ui/wake/CheckScreen.kt`, plus the contract in `WakeContract.kt`: `CheckUiState`, `CheckContent.{Math, WordUnscramble, MemorySequence, QrBarcode, HouseHunt}`, `GraceState.Running/Expired`, `WakeNote.DirectBoot` and `FallbackPickerUiState`. `WakeEndScreens.kt` holds `SuccessScreen` and `FallbackPickerScreen`.
    - **Shared types:** `ui/checks/CheckTypes.kt` is a UI `CheckType` enum (House Hunt included) with `Difficulty` and count labels. It must map to the core sealed `CheckType`, not replace it.
    - **Setup screens:** `ui/checkpicker/CheckPicker.kt`, `ui/checksetup/CheckSetup.kt`, `ui/checksetup/CheckPreviewScreen.kt` and `ui/qr/QrRegistration.kt`.
    - **Editor:** `EditorPane.WakeCheck` and `QuietTime` render from `EditorUiState.full` (`FullEditorSections`), which the production ViewModel leaves null today.
    - **Gaps:** `CheckContent.Math` holds only two operands, so Medium and Hard need the display and spoken forms. `MemorySequence` is 3×3 only, so Hard needs 4×4.
    - **Reference states (`docs/design-preview/states.md`):** `check-math`, `check-math-wrong`, `check-word`, `check-memory-watch`, `check-memory-turn`, `check-qr`, `check-qr-wrong`, `check-qr-camera-unavailable`, `fallback-picker`, `success-first`, `success-test`, `success-after-snooze`, `ringing-locked`, `editor-wake-check`, `editor-wake-check-none`, `editor-quiet-time`, `check-picker*`, `check-setup-*`, `try-it-*` and `qr-*`. The preview sources are in `androidApp/src/debug/.../debug/preview`.
  - **Enforcement already in place:**
    - **`NoHostageApis`:** no background activity starts, overlays and similar.
    - **`CredentialStorageAccess`:** no credential-storage APIs outside `android.media`. The word list as an APK asset and QR codes in `app.db` are fine.
    - **Allowlists:** the permission allowlist already lists `CAMERA`, but the manifest does not declare it yet, so 3.10 adds the `uses-permission`. `config/dependency-allowlist.txt` lists every resolved `group:artifact`, so CameraX, bundled ML Kit and their transitive artifacts must be added in the same change. List any Google usage-logging transitive dependency (for example datatransport) for the Epic 8 Data safety form.
    - **Release content:** `checkReleaseContent` keeps the debug-only hooks (3.2's answer hook on `SessionEngine.state`) out of the release build.
  - **Session lock:** mutating use cases (`SaveAlarm`, `DuplicateAlarm`, `DeleteAlarm`) return `DomainError.SessionActive` through `SessionLockGuard`. New check-config writes must go through the same guard.
- **The state machine is fixed:** the AD-2 table stays normative and the table-coverage test passes with no new rows. Plugin results map onto the existing rows:
  - `ItemCorrect`, or `Correct` on an entry that is not the last → "valid, not last" (advance).
  - `Correct` on the last entry → `Completed`.
  - `Wrong` or `WrongRestart` → "invalid" (`attempts++`). `WrongRestart` also stores a new seed for the entry.
  - `failedAttempts` resets when the entry advances.
- **Plugin contract (AD-9):**
  - **Core type:** `CheckType` is a sealed `@Serializable` hierarchy in `core.checks.<type>`. Each type has `id`, `usesCamera`, `directBootSafe`, `hasDifficulty`, `countRange`, `defaultCount`, `generate` and `validate`.
  - **Plan resolution:** the pure `PlanResolver` resolves the plan for each ring, and `CheckRun.step` becomes a `StepPointer(entry, item)`.
  - **UI registry:** the `CheckRegistry` in `:composeApp` maps each type to one wake composable and one preview composable. A test fails if a type that can appear in a production plan has no wake composable. The picker lists only types that have both a core plugin and a wake composable, so House Hunt stays out until Epic 7.
  - **Placeholder:** it leaves production code and Koin, and survives only as `FakeCheck` in `:testing`. `FakeCameraCheck` drives the fallback tests.
- **Storage:**
  - `app.db` is already **v4** (Story 2.9's `session_merge`), and `AppDatabase.SCHEMA_VERSION` is the single constant. The epic's numbers (3.4 "3→4", 3.5 "4→5", 3.9 "5→6", 3.10 "6→7") are each one too low. Use the next version at merge time, with the exported schema and a migration test each time.
  - **Additions:** `alarm.vibrate_in_grace` (3.4); `check_config` plus `alarm.check_mode` (3.5); `session_history.fallback_from` (3.9); `check_config.code_format` and `code_value` (3.10).
  - The banner dismissal in 3.13 lives in the device-protected DataStore and must be covered by `BackupRulesCoverageTest`.
- **Camera stack:**
  - **Dependencies:** CameraX 1.6.2 (`camera-core`, `camera-camera2`, `camera-lifecycle`, `camera-view`) and bundled ML Kit barcode-scanning 17.3.0, with no model download.
  - **Scanner:** `CodeScanner` lives in `:composeApp` `androidMain`, with `FakeCodeScanner`. The camera is released on pause and rebound on resume, which restarts the 5 s watchdog.
  - **Tests:** the watchdog is tested with `FakeMonotonicClock` (4.9 s gives no message, 5.0 s gives the message).
- **Accessibility state:** an `AccessibilityState` port with `FakeAccessibilityState` selects the numbered Memory Sequence variant when the plan is resolved.
- **Stats:** `reRegisterSuggestion` is a pure function in `core.stats` (AD-18). Nothing stores derived stats.

## UX & Interaction Patterns

- **Check screen (Sunrise, no loading state):**
  - **Header:** progress ("Problem {n} of {count}", "Word…", "Round…"). In Grace, the 120 dp `countdown-ring` with "Quiet for {seconds}s. Finish before it rings again.", a haptic tick every 5 s, and TalkBack announcing every 10 s and at 5 s. In Loud after grace, a bell with "Alarm's back on" and "Time's up. Alarm's back on until you finish.". A ring without grace shows neither.
  - **Body and footer:** the check fills the body. The footer has the same 64 dp snooze control as the ringing screen, with the `fallback-link` "Can't do this check?" centred above it when the fallback is allowed.
- **Wrong answer:** a 200 ms shake, an error haptic, the field clears, and "Not quite. Try again." shows in `error-sunrise`, announced politely. With the animator duration scale at 0 every motion is instant, the countdown shows plain numbers, and the Memory Sequence timing is kept.
- **Inputs:**
  - **Math:** a 3×4 `number-pad-key` grid of 64 dp keys with 8 dp gaps, a light haptic per tap, at most 5 digits; "Check" with an empty field does nothing.
  - **Word Unscramble:** 48 dp `letter-tile`s with "Shuffle" and "Clear"; the answer is submitted automatically when every slot is filled.
  - **Memory Sequence:** 350 ms highlights with 150 ms gaps; input is disabled during playback; "Watch the sequence" and "Your turn".
  - **QR:** the `viewfinder` with a square guide and a 48 dp torch toggle.
  - Every tap also dispatches `UserInteracted`.
- **Fallback check picker:** a wake screen titled "Pick a fallback check", with Math first, then Word Unscramble, then the numbered Memory Sequence. Its close button ("Back to check") returns without using the fallback, and Back does nothing.
- **Success:** "Up on time." / "You're up. That's what counts." / "Test finished. Your alarm works.", with one 72 dp "Done" in the thumb zone and one success haptic, but no animation. It closes on "Done" or after 60 s. The next app open shows Home, and a new alarm switches the single `WakeActivity` to ringing.
- **Editor and setup (grouped cards, progressive disclosure):**
  - **Wake-up check row:** opens the "Checks" card, "Mode" Random/All (shown for two or more checks) and "Your checks", each with a value such as "Medium · 2 problems" and a chevron to Check setup. In All mode, "Move up" and "Move down" are in each row's menu and are TalkBack custom actions. With no checks, "Pick at least one check." blocks Save.
  - **Check setup:** difficulty as radio rows and the count as a `stepper` ("Problems", "Words", "Rounds").
  - **Quiet time row:** opens a 15–30 s slider and "Vibrate during quiet time".
  - **Home:** `card-alarm` shows the alarm's check icons (20 dp).
- **Picker notes:** camera cards show "Needs the camera. If it can't be used, you'll get a fallback check.". With TalkBack on, Memory Sequence shows "Uses numbered tiles with TalkBack.". If the camera permission is denied, the card stays unselected with "Camera isn't available." and "Fix" (opens the app's system settings).
- **Re-register banner (3.13):** the info variant of `banner-warning`, which is dismissible and sits below the reliability banner.

## Cross-Story Dependencies

- **Order:**
  - 3.1 → 3.2 → 3.3 and 3.4 build the core contract, then Math end to end.
  - 3.5 (per-alarm configs) comes before 3.6 (previews), and both come before the other check types.
  - 3.7 and 3.8 can run in parallel.
  - 3.9 needs `FakeCameraCheck` only, so it can come before QR.
  - 3.10 → 3.11 (camera failures, Direct Boot note).
  - 3.12 runs after every check and the picker exist. 3.13 needs 3.9's `fallback_from` and 3.10's registration.
  - 3.14 is human-verify, comes last, and automation never marks it done.
  - Stories that touch the same files (the reducer, `CheckRun`, `WakeActivity`, the editor) should start on a reviewed and fixed base.
- **Open deferred items assigned to Epic 3:**
  - **3.2:** in a multi-step plan, a valid answer advances without `StartCheckStep(step + 1)`. Emit it, or make the UI follow `CheckRun`.
  - **3.2:** a user who presses Home right after "I'm up" left the placeholder unanswered (muted, then Loud). The real check screen must come back through the notification and launcher on the current step.
  - **3.9:** the fallback keeps the old seeds and `failedAttempts` and emits no `StartCheckStep`. It needs new seeds for the fallback entry and reset attempts.
  - **3.11 (with 3.2):** the Direct Boot note "Your phone restarted, so today's check is Math." (preview `ringing-locked`) and Math as `DIRECT_BOOT_CHECK` were left to Epic 3. Epic 2 shows only the lock-icon snooze.
  - **Epic 7 (7.7):** the fallback policy cannot see a matcher error. Out of scope here, but keep the policy input open for it.
- **Testing on CI:**
  - Any `androidTest` runs on the CI Gradle Managed Device, an **ATD API 34 image** (`atdApi34`, `aosp-atd`). It has no notification shade, few system apps and most likely no camera, and UiAutomator is no longer a dependency.
  - Prefer Robolectric host tests. Use `FakeCodeScanner` instead of a real camera, and `FakeAccessibilityState` and `FakeUserLockState` instead of system state.
  - Keep the GMD tests in 3.2 and 3.12 to what ATD can do: firing a debug alarm, the debug answer hook and Compose accessibility checks. Drive "camera unavailable" through the fake rather than through `pm revoke` on a camera the image may lack.
  - Every test assertion must be able to fail.
- **Owner priority:** ship fast. Real-camera, TalkBack-on-device and reboot items are batched into the 3.14 checklist, with the test alarm at a low volume. Items that only the owner can run go to v2 (owner decision 2026-10-06), and failures become bug stories.
- **Downstream:**
  - Epic 4 adds the commitment lock for a longer grace window, the "{paid} paid this morning" line and the new grace window after a paid snooze. Paying a snooze discards check progress (new seeds).
  - Epic 6 adds streak layouts, the count-up animation on Success, and "Fallback check: {type}" in Day detail.
  - Epic 7 adds House Hunt (`ImageMatcher`, matcher-error fallback) and the printable QR.
