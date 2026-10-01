---
title: 'Story 1.15: Ringing screen over the lock screen with "I''m up"'
type: 'feature'
created: '2026-10-01'
status: 'in-review'
baseline_revision: 'da4b52d3025b6155bd3717c2e19dadcaadb71d1a'
review_loop_iteration: 0
followup_review_recommended: false
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-1-context.md'
  - '{project-root}/.claude/skills/pps-design/SKILL.md'
warnings: ['oversized']
deferred: []
---

<intent-contract>

## Intent

**Problem:** When an alarm rings, `WakeActivity` shows only the Story 1.14 skeleton: the time and a bare "I'm up". The approved Ringing screen (`ui/wake/RingingScreen`, DESIGN.md / EXPERIENCE.md v0.5) is only used by the design preview. Nothing answers the Epic 1 placeholder check, so "I'm up" leaves the session muted in Grace, and then Loud, instead of ending it.

**Approach:** Map the engine's in-memory session to `RingingUiState` with a pure mapper. Snooze comes from the `SnoozeAvailabilityPolicy` result. Render the real `RingingScreen` in `WakeActivity`. Turn its intents into session events launched on `ApplicationScope`. While the wake screen shows a Grace or Loud placeholder step, it answers that step itself, so "I'm up" alone ends the session (OnTime). Keep every Story 1.14 behaviour.

## Boundaries & Constraints

**Always:**
- **Design baseline.** `WakeActivity` renders the existing `RingingScreen` under its own `WakeSurface` (`PpsTheme(wake = true)`, Sunrise). Do not change how it looks: `git status --porcelain androidApp/src/test/screenshots/preview` must stay empty.
  - Semantics-only changes are allowed. For example, "I'm up" gets the traversal slot right after the clock, so TalkBack reads the clock, then "I'm up" (EXPERIENCE.md Accessibility Floor).
- **Mapper** (`composeApp/.../ui/wake/RingingMapping.kt`, pure, commonMain).
  - `SessionData` + `SnoozeAvailability` + `TimeZone` (+ a `priceOf(productId): Money?` lookup, which returns null in Epic 1) → `RingingUiState`.
  - **time and date:** `config.scheduledAt` in the zone. This is the alarm's time, as in Story 1.14 and the notification title.
  - **label:** `config.label`. A null or blank label means no label.
  - **note:** `WakeNote.PhoneCall` when `session.paused`, otherwise none.
  - **sessionLine:** none. The paid amount arrives with Money in Epic 4.
  - **snooze**, from the availability result:

    | Availability | Snooze offer |
    |---|---|
    | `TestMode` | `SnoozeOffer.TestMode` ("Test · no charge") |
    | `CatalogueNotLoaded` | `Unavailable(PricesNotLoaded)` |
    | `Offline` | `Unavailable(Offline)` |
    | `MaxSnoozesReached` | `Unavailable(MaxSnoozesReached)` |
    | `PriceCapReached` | `Unavailable(PriceCapReached)` |
    | `PaymentPending` | `Unavailable(PaymentPending)` |
    | `BeforeFirstUnlock` | `LockedBeforeUnlock` |
    | `EarlierPaymentRefunding` | `StrandedRefund(price of declinedReuseProduct)` |
    | `Available(offer)` | `Available(price of offer.productId)` |

    A price that `priceOf` doesn't know falls back to `Unavailable(PricesNotLoaded)`.
  - **Emergency ring** (no session): the emergency alarm time, no label, `Unavailable(PricesNotLoaded)`.
- **WakeActivity.** It keeps:
  - the lock-screen flags and the manifest;
  - Back does nothing;
  - it waits while Idle, before a session or an emergency ring;
  - it finishes once what it showed is over;
  - emergency mode, where "I'm up" calls `stopEmergency()`.

  What it renders:
  - The UI state comes from `engine.state` + `runtime.emergency`, with availability from the Koin `SnoozeAvailabilityPolicy`, `is24HourClock()` and `TimeZoneProvider`.
  - There is no loading state, and no repository or other suspend call before the first frame.
  - While Idle and waiting, it renders the Ringing screen for the notification's alarm time (`runtime.shownAlarmAt()`) if one is posted. Otherwise it renders the plain wake surface.
- **Intents.** Every dispatch is launched on `ApplicationScope`, never inside composition or an engine effect.
  - `ImUpClicked` → `UserInteracted`, then `ImUpTapped`, in one launched job.
  - Every other intent (`SnoozeClicked` included: buying arrives in Epic 4), and any tap outside a button → `UserInteracted`.
- **Placeholder check.** When the state is Grace or Loud and `checkRun.currentStep` is `CheckStep.Placeholder`, the wake screen dispatches `CheckAnswerSubmitted(CheckAnswer.Placeholder)` once for that session, ring and step. The engine ignores repeats. The session then reaches Completed → Recorded → Idle, the 1.14 runtime stops the sound and removes the notification, and the activity finishes. History records OnTime. Epic 3 replaces this with the check screen.
- **Strings.** All are existing composeResources strings, which match EXPERIENCE.md. No new copy. `CopyRulesTest` passes.
- **Deferred items assigned to 1.15:**
  - The disabled snooze uses the `disabled-container-sunrise` / `disabled-content-sunrise` pair explicitly, never Material alpha. A pixel test asserts the fill is the token.
  - 24-hour time tests for the notification and the wake screen.

**Never:**
- No layout, colour, size or copy change to the preview composables.
- No snooze purchase or confirm sheet behaviour (Epic 4).
- No check screens (Epic 3).
- No new permission, dependency or test infrastructure.
- No `startActivity` from the background. No loading spinner.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| First ring | Ringing, label "Work", NoBilling policy | Label, clock, date, "I'm up", "Snooze unavailable: prices not loaded yet" | No error expected |
| No label | label null or blank | No label text | No error expected |
| Test alarm | `testMode = true` | Snooze reads "Test · no charge"; TalkBack "Snooze unavailable, Test · no charge" | No error expected |
| Available | `Available(offer)`, priceOf knows it | "Snooze · $1" | Unknown price → prices not loaded |
| Other reasons | Offline / MaxSnoozes / PriceCap / PaymentPending / BeforeFirstUnlock / EarlierPaymentRefunding | Matching `SnoozeOffer` per the mapping table | Unknown refund price → prices not loaded |
| Phone call | `session.paused` | `WakeNote.PhoneCall` | No error expected |
| I'm up | Tap "I'm up" while Ringing | Grace → placeholder answered → Completed → Idle; sound off, notification gone, activity finishes, history OnTime | No error expected |
| Other tap | Tap the clock or the disabled snooze | `UserInteracted`: the interaction deadline moves | No error expected |
| Restored in Loud | Activity opens on Loud with a placeholder step | Placeholder answered; session ends | No error expected |
| Emergency | Emergency ring, Idle engine | Ringing screen with the emergency time; "I'm up" stops it, the activity finishes | No error expected |
| 24 h | System 24-hour setting | Notification "07:00", wake clock "HH:mm" | No error expected |
| Large font | 200% font scale | Nothing clips; both actions on screen, "I'm up" in the bottom 40% | No error expected |

</intent-contract>

## Code Map

- `composeApp/src/commonMain/kotlin/com/yawnandpawn/app/ui/wake/RingingScreen.kt` -- the approved screen. The clock has `traversalIndex = -1f`. Add a semantics-only traversal index to "I'm up" (`WakePrimaryButton` takes a `modifier`). `WakeContract.kt` holds `RingingUiState`, `SnoozeOffer` (UI), `SnoozeUnavailableReason` and `WakeIntent`.
- `composeApp/.../ui/wake/WakeComponents.kt` -- `SnoozeButton` already fills `colors.disabledContainer` / `disabledContent` (the Sunrise tokens under `PpsTheme(wake = true)`). `WakeSurface`, `WakePrimaryButton` (pulse off with `rememberReducedMotion()`) and `wakeContentPadding`.
- `core/.../session/SessionPolicies.kt` -- `SnoozeAvailability`, `UnavailableReason`, `SnoozeAvailabilityPolicy`, `NoBillingSnoozeAvailability`. Note the naming clash: core `SnoozeOffer(productId, snoozeNumber)` versus UI `SnoozeOffer`; import with aliases.
- `core/.../session/SessionState.kt` -- `Ring` (Ringing / Grace / Loud), `SessionData.config` / `paused` / `checkRun` / `declinedReuseProduct`. `CheckRun.kt` holds `CheckStep.Placeholder` and `CheckAnswer.Placeholder`.
- `core/.../session/RingRules.kt:56` -- `ImUpTapped` only from Ringing. Every user event resets the interaction deadline.
- `androidApp/src/main/kotlin/com/yawnandpawn/app/android/wake/WakeActivity.kt` -- replace `WakeSkeleton` and keep the rest. `IM_UP_TAG` is used by `WakeActivityTest`.
- `androidApp/.../wake/WakeRuntime.kt` -- `emergency`, `stopEmergency()`, `shownAlarmAt()` (read-only use).
- `androidApp/src/main/kotlin/com/yawnandpawn/app/YawnAndPawnApp.kt:67` -- the `SnoozeAvailabilityPolicy` binding.
- `composeApp/src/androidMain/.../ui/format/TimeFormat.android.kt` -- `is24HourClock()` reads `DateFormat.is24HourFormat`. In Robolectric, set `Settings.System.TIME_12_24` to "24".
- Tests:
  - `androidApp/src/test/kotlin/com/yawnandpawn/app/android/wake/WakeActivityTest.kt` and `WakeApp.kt` (it can replace the repository and the store);
  - `WakeNotifierTest.kt` (12 h case at line 69);
  - `ui/HomeScreenshotTest.kt` + `ui/ScreenHost.kt` (`withScreen`) + `ScreenshotOptions.kt` for the Roborazzi pattern;
  - `testing/.../SessionEngineFakes.kt` (`aSessionConfig`, `aSession`);
  - `composeApp/src/commonTest` for the mapper tests.

## Tasks & Acceptance

**Execution:**
- `composeApp/.../ui/wake/RingingMapping.kt` -- the pure mapper and the emergency variant -- one tested place for session → UI.
- `composeApp/.../ui/wake/RingingScreen.kt` -- the traversal index on "I'm up" (semantics only).
- `composeApp/src/commonTest/.../ui/wake/RingingMappingTest.kt` -- every mapping row, the label, the note, the time and date in a zone, the emergency variant, and the unknown-price fallbacks.
- `androidApp/.../wake/WakeActivity.kt` -- render `RingingScreen`, the intent dispatch, the placeholder answer, and the Idle-wait and emergency mapping. KDoc updated.
- `androidApp/src/test/.../wake/WakeActivityTest.kt` -- extended:
  - I'm up → Completed → Idle, the activity finishes, the sound and notification are gone, history OnTime;
  - the other tap and the snooze tap → `UserInteracted`;
  - the test session snooze label;
  - the 24 h clock;
  - Loud placeholder answered on open;
  - no repository call before the first frame;
  - the emergency path kept.
- `androidApp/src/test/.../wake/WakeNotifierTest.kt` -- the 24 h title and text.
- `androidApp/src/test/.../ui/RingingScreenshotTest.kt` -- Roborazzi, Sunrise, at 100% and 200%: first ring with a label, without a label, snooze unavailable (offline), test alarm, and the enabled-snooze preview. Mapper-built states, UTC zone, 12 h.
- `androidApp/src/test/.../ui/RingingSemanticsTest.kt` -- at 100% and 200%:
  - "I'm up" ≥ 72 dp, the largest action, in the bottom 40%;
  - snooze ≥ 64 dp, 16 dp below it, on screen;
  - the clock's content description is the full time, with the traversal order clock < "I'm up";
  - TalkBack "Snooze unavailable, prices not loaded yet";
  - the disabled snooze fill pixel equals `PpsTokens.Sunrise.disabledContainer`;
  - with animator duration scale 0, two frames 600 ms apart are identical.

**Acceptance Criteria:**
- Given a Ringing session, when `WakeActivity` renders, then it shows `RingingScreen` from the in-memory state with the policy's snooze reason, and no suspend repository call happened before the first frame.
- Given "I'm up", when it is tapped, then `UserInteracted` and `ImUpTapped` are dispatched, the placeholder is answered, and the session ends OnTime with no sound, no notification and the activity finished.
- Given `./gradlew qualityGate`, when it runs, then it passes with no preview baseline change.

## Spec Change Log

## Review Triage Log

## Design Notes

- **Placeholder answer in the wake screen, not the runtime.** `StartCheckStep` is a one-shot effect and is never replayed on restore. A runtime-side answer would leave a restored Grace or Loud placeholder session ringing with no way to end it, because `ImUpTapped` is ignored outside Ringing. The wake screen sees the state on every open, so it answers it there. That is also where Epic 3's check screen goes.
- **GMD timing test:** the story's instrumented test (fire a debug-scheduled alarm, `WakeActivity` resumed within 1,000 ms) is deferred to Story 1.21, with the fire-now hook from Story 1.18. It needs things the CI ATD device job doesn't provide today:
  - a debug fire hook (Story 1.18);
  - the runtime `POST_NOTIFICATIONS` grant (Story 1.19, plus `androidx.test:rules`, a new dependency);
  - a locked or screen-off emulator, because an unlocked API 34 device shows a full-screen intent as a heads-up.

  It also can't run on this PC (no KVM).
- **Environment:** `JAVA_HOME=C:/Users/BuiTua/AppData/Local/Programs/jdk17/jdk-17.0.20.1+1`. Never use the owner's phone.

## Verification

**Commands:**
- `./gradlew qualityGate` -- expected: BUILD SUCCESSFUL.
- `git status --porcelain androidApp/src/test/screenshots/preview` -- expected: empty.
- `git status --porcelain androidApp/src/test/screenshots` -- expected: only the new `wake_ringing_*` baselines.

## pps-design Done checklist

- [x] Only tokens from `DESIGN.md` are used: no raw hex, no new radii, no new font sizes. The story reuses the preview composables. The only Compose change is a semantics traversal index.
- [x] Sunrise checked with screenshots: `wake_ringing_*_sunrise[_font200].png`. Wake screens are Sunrise only, and Light and Dark don't apply.
- [x] Every colour pair used is in the `DESIGN.md` contrast table: Sunrise disabled-content / disabled-container 5.63, text / bg, and the accent pairs already in the preview.
- [x] Touch targets are at least 48 dp, and wake actions at least 64 dp: "I'm up" is at least 72 dp and snooze at least 64 dp (`RingingSemanticsTest`).
- [x] Works at 200% font scale and with TalkBack: both actions stay on screen, "I'm up" is in the bottom 40%, the clock is read first as the full time and then "I'm up", and snooze reads "Snooze unavailable, {reason}". Outcome glyphs: not applicable on Ringing.
- [x] Reduced-motion path works. With the test clock paused before composing, the frames of "I'm up" 600 ms apart differ at the default animator scale (the pulse runs) and are identical at scale 0.
- [x] Copy matches `EXPERIENCE.md > Voice and Tone`. Only existing resources are used ("I'm up", "Snooze unavailable: prices not loaded yet", "Test · no charge", "Snooze · {price}"), and `CopyRulesTest` passes.
- [x] Every Epic 1 state row in `EXPERIENCE.md > State Patterns` for Ringing is handled: first ring, snooze unavailable, test alarm, phone-call note (mapped), and restored. After-snooze and Direct Boot rows need Epic 2 and Epic 4 data and stay preview-only.
- [x] "I'm up" is the most prominent wake action: filled accent, 72 dp, first after the clock. Snooze is visible and plain, and is priced when available. See the deferred note on the two-line snooze label.
- [x] Previews for each state exist (preview catalogue, unchanged), and the screenshot tests are updated (Roborazzi `RingingScreenshotTest`).

## Auto Run Result

**Summary:** the wake screen is now the approved Ringing screen.
- **Rendering:** `WakeActivity` renders `RingingScreen` from `SessionEngine.state` through the pure mapper `ui/wake/RingingMapping.kt`, with the snooze from the Koin `SnoozeAvailabilityPolicy`. In Epic 1 that shows "Snooze unavailable: prices not loaded yet", or "Test · no charge" for a test session.
- **Intents:** "I'm up" sends `UserInteracted` + `ImUpTapped`, and every other tap sends `UserInteracted`. All are launched on `ApplicationScope`.
- **Placeholder check:** in Grace or Loud, the screen answers the placeholder check step itself, so "I'm up" ends the session OnTime. The sound and notification stop, and the activity finishes.
- **Kept from 1.14:** the Idle wait (now showing the notification's alarm time), emergency mode, Back doing nothing and the finish logic.
- **Accessibility:** "I'm up" gets a semantics-only traversal index, so TalkBack goes from the clock to "I'm up". The preview baselines are unchanged.

**Deferred items closed:**
- The disabled snooze's token pair is now checked by a pixel test.
- 24-hour time tests now cover the notification and the wake clock.

**Verification:** `./gradlew qualityGate` gives BUILD SUCCESSFUL. The suites ran:
- `RingingMappingTest` (11), `WakeActivityTest` (16), `WakeNotifierTest` (6), `RingingScreenshotTest` (10), `RingingSemanticsTest` (7) and `CopyRulesTest`;
- the preview screenshots verified with `git status --porcelain androidApp/src/test/screenshots/preview` empty;
- 10 new `wake_ringing_*` baselines.

**Deferred (in `deferred-work.md`):**
- The GMD timing test, moved to Story 1.21 (it needs the Story 1.18 hook, the Story 1.19 notification grant and a locked emulator).
- A design question for the owner: the two-line "prices not loaded yet" snooze is as tall as "I'm up" at 100% and taller at 200%.
- The placeholder answer needs the wake screen to be shown.

**Residual risks:**
- None of this has run on a device. Story 1.21 checks the timing, the pulse and the lock-screen behaviour on the Oppo A96.

### 2026-10-01 — Review fixes

- **Placeholder retry:** when the placeholder answer's dispatch fails (the commit fails), it is retried every 2 s while the same step is due, including after Grace becomes Loud. Test: a failing commit that later succeeds.
- **Early "I'm up":** "I'm up" tapped on the Idle-wait screen is kept and replayed (`UserInteracted` + `ImUpTapped`, or stopping an emergency ring) once the session rings. Test added.
- **Disabled snooze pixel test:** it now checks the label and icon. The darkest snooze pixel must equal `disabled-content-sunrise`, which is darker than Material's 38% onSurface blend. Before, it compared two theme reads.
- **Reduced motion:** the test pauses the clock before composing, so the pulse runs. Its frames differ at the default scale (a new control test) and are identical at scale 0.
- **Load-only test races, fixed in tests:** both showed up in a slow gate run.
  - The "I'm up" test now also waits for the end effects to remove the notification; the engine publishes Idle before they run.
  - `AndroidAlarmPlayerTest` waits for the app-start volume restore before it starts.
