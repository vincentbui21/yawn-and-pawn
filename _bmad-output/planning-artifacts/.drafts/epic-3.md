## Epic 3: Prove you're awake

Dismissing the alarm requires the check or checks the user chose for that alarm (Math, Word Unscramble, Memory Sequence, QR/Barcode), each with its own difficulty and count, in Random or All mode, with a 15–30 s muted grace window and a visible countdown. When a camera check can't physically be done, an accessible fallback check (Math first, TalkBack-friendly) keeps waking up free for everyone. Every check can be tried before saving. The AD-9 check plugin contract replaces the Epic 1 `Placeholder` step; the Epic 1 `SessionEngine`, `CheckRun`, `CheckValidator` and `FallbackPolicy` seams, the Epic 2 `DirectBootSubstitution` and the session lock are reused unchanged. A basic Success screen closes the flow (the celebration arrives in Epic 6). The epic ends with a human-verify device checklist.

Every UI story in this epic carries the two standing acceptance criteria from Epic 1, repeated in the story so the build loop can check them: (1) the `pps-design` Done checklist is copied into the story file with every item ticked; (2) all new user-facing strings live in Compose Multiplatform resources, match `EXPERIENCE.md > Voice and Tone > Key strings` verbatim where a key string exists, and pass the automated copy-rules test from Story 1.3 (FR-MSG-4). A string that EXPERIENCE.md does not define is marked `[ASSUMPTION: add to EXPERIENCE.md Key strings]` in the story and listed for the owner. Check parameters that PRD Q10 leaves open are marked `[ASSUMPTION: Q10]` and recorded with their chosen values in `docs/decisions/q10-check-parameters.md` in the story that introduces them.

### Story 3.1: Check plugin contract and the Math generator in core

As a user,
I want every check to follow the same fair rules for difficulty, progress and correctness,
So that no check type can be easier, flakier or judged differently than the others.
**Refs:** FR-PWK-1, FR-PWK-2 (plan resolution), FR-PWK-3, FR-PWK-5, NFR-11, NFR-12, AD-1, AD-2, AD-9 · **Priority:** Must · **Verify:** auto

**Acceptance Criteria:**

**Given** `core.checks`
**When** the contract is added
**Then** `CheckType` is a sealed `@Serializable` hierarchy, each type declaring `id`, `usesCamera`, `directBootSafe`, `hasDifficulty`, `countRange`, `defaultCount`, `generate(seed, difficulty, count): Puzzle` and `validate(puzzle, position, answer): StepResult`, where `StepResult` is `ItemCorrect` (more items remain), `Correct` (puzzle done), `Wrong` or `WrongRestart(newSeed)`
**And** `Puzzle` and `CheckAnswer` are sealed `@Serializable` types, and `generate` is deterministic per seed (a test generates 10,000 seeds twice and compares)
**And** only `Math` is added in this story; each later check story adds its own type, and House Hunt is added in Epic 7

**Given** a per-alarm `CheckPlan(mode: Random | All, entries: List<CheckEntry(type, difficulty, count)>)`
**When** a ring starts (first ring, re-ring after a snooze or merge)
**Then** a pure `PlanResolver` produces the ring's resolved plan: `All` keeps every entry in order, `Random` picks one entry using a seed, so a re-ring can pick a different type
**And** seeds come only from a pure `SeedDeriver.seed(sessionId, ringIndex, entryIndex, attempt)` (no random calls in `:core`; detekt rule bans `kotlin.random.Random` without a seed in `core.checks`), so a restored session regenerates the same puzzle
**And** `CheckRun.step` is a `StepPointer(entry, item)` into the resolved plan, and `failedAttempts` counts invalid answers on the current entry and resets when the entry advances

**Given** the Epic 1 `CheckValidator` seam
**When** the production `PluginCheckValidator` replaces the `Placeholder` validator in core (the Android wiring switches in Story 3.2)
**Then** it maps `StepResult` onto the existing AD-2 rows: `ItemCorrect` and `Correct` on a non-last entry → "valid, not last step" (advance), `Correct` on the last entry → "valid, last step" (`Completed`), `Wrong` and `WrongRestart` → "invalid" (`attempts++`, wrong-answer feedback; `WrongRestart` also stores the new seed for that entry)
**And** the AD-2 table-coverage test still passes with no new rows

**Given** the Math type (FR-PWK-5)
**When** it generates problems
**Then** Easy is `a + b` or `a − b` with a, b in 10–99 and `a ≥ b` for subtraction; Medium is `a × b + c` with a in 10–99, b in 2–9, c in 10–99; Hard is `a × b + c × d` with a, c in 10–99 and b, d in 2–9 `[ASSUMPTION: Q10]`
**And** count is 1–10 with default 3, Math is `directBootSafe` and has no camera, each problem exposes a display form ("47 + 38", "23 × 4 + 17") and a spoken form ("47 plus 38", "23 times 4 plus 17") for TalkBack, and validation accepts only the exact non-negative integer (leading zeros ignored, empty rejected)
**And** table-driven tests over 10,000 seeds per difficulty assert every operand is in range, every answer is between 0 and 9,999, and the spoken form matches the display form
**And** Kover shows `core.checks` ≥ 90% line coverage, and production sessions still use the `Placeholder` step until Story 3.2 (no user-visible change)
**And** `./gradlew qualityGate` passes

### Story 3.2: Solve Math to stop the alarm

As a user,
I want to solve a few arithmetic problems on a big number pad after "I'm up",
So that I have to be awake enough to think before the alarm stops.
**Refs:** FR-PWK-1, FR-PWK-5, FR-PWK-9 (behaviour), FR-ALM-11 (Direct Boot check), FR-MSG-4, NFR-2, NFR-7, NFR-9, AD-2, AD-9, AD-11, UX-DR12, UX-DR13, UX-DR17, UX-DR28, UX-DR63, UX-DR64, UX-DR66, UX-DR67, UX-DR70, UX-DR71, UX-DR72, UX-DR78, UX-DR84 · **Priority:** Must · **Verify:** auto

**Acceptance Criteria:**

**Given** `CheckRegistry` in `:composeApp`
**When** a check type is registered
**Then** it maps the `CheckType` to one wake composable and one preview composable, and a test fails if any type that can appear in a production plan has no wake composable
**And** the `Placeholder` step is removed from production code and Koin wiring (it remains only as `FakeCheck` in `:testing`), and `ConfigResolver` gives every alarm the default plan `Random` with one entry Math · Medium · 3 `[ASSUMPTION: owner confirms the default check]` until per-alarm check configs arrive in Story 3.5; the test alarm uses the same plan
**And** Math becomes the Direct Boot check for Epic 2's `DirectBootSubstitution`: any entry whose type is not `directBootSafe` is replaced by the default Math entry (Math · Medium · 3)

**Given** `ImUpTapped` has moved the session to `Grace` (or `Loud` when `noGraceThisRing`)
**When** `WakeActivity` renders the Check screen (Sunrise tokens, no loading state)
**Then** the header shows progress "Problem {n} of {count}" `[ASSUMPTION: add to EXPERIENCE.md Key strings]` and, in `Grace`, the line "Quiet for {seconds}s. Finish before it rings again." updated every second from the grace `Deadline` (the countdown ring replaces the plain number in Story 3.4); in `Loud` after a grace window it shows "Time's up. Alarm's back on until you finish." (EXPERIENCE.md Key strings)
**And** the body shows the problem's display form in `display` with tabular figures, a read-only answer `text-field` in `display` digits filled only from the pad, and a 3×4 `number-pad-key` grid (1–9, backspace, 0, "Check") of 64 dp keys with 8 dp gaps and a light haptic per tap
**And** the footer shows the same snooze control component as the ringing screen at the bottom, 64 dp, rendering `SnoozeAvailabilityPolicy`
**And** the answer accepts at most 5 digits, and "Check" with an empty field does nothing

**Given** the user taps "Check"
**When** the answer is submitted as `CheckAnswerSubmitted`
**Then** the UI never decides correctness; on an invalid result the field shakes for 200 ms (an instant change with animator duration scale 0), an error haptic plays, the field clears, and "Not quite. Try again." (EXPERIENCE.md Component Patterns) shows in `error-sunrise` and is announced politely
**And** a correct non-last answer shows the next problem with a cleared field, and the last correct answer reaches `Completed`: the sound stops, the notification is removed and `WakeActivity` finishes as in Epic 1 (the Success screen arrives in Story 3.3)
**And** every key tap also dispatches `UserInteracted`

**Given** TalkBack is on
**When** the Check screen appears
**Then** focus moves to the problem, which reads its spoken form ("47 plus 38"); each key announces its digit, backspace reads "Delete digit" `[ASSUMPTION: add to EXPERIENCE.md Key strings]`, the current answer is announced after each key as "Answer {value}" `[ASSUMPTION: add to EXPERIENCE.md Key strings]`, and "Check" has role button; nothing depends on timing or visual matching (UX-DR63)

**Given** a process death in the middle of the check
**When** the session is restored (Story 2.1)
**Then** the same problem appears at the same position (deterministic seed), and the digits typed but not submitted are cleared (UI-only state)

**Given** layout rules
**When** semantic and Roborazzi tests run at 100% and 200% font scale
**Then** the "Check" key and the snooze control stay on screen without scrolling at 200% on a 360 × 640 dp configuration (only the problem area may scroll), every key is ≥ 64 dp, and screenshots exist for Math in `Grace`, in `Loud` after grace, a wrong answer and the last problem
**And** an instrumented test on the Gradle Managed Device fires a debug alarm, taps "I'm up", reads the answer through a debug-only hook on `SessionEngine.state` (debug source set only), solves every problem and asserts the alarm stops
**And** the `pps-design` Done checklist is ticked in the story file, all strings are resources, key strings match EXPERIENCE.md verbatim and `CopyRulesTest` passes (FR-MSG-4)
**And** `./gradlew qualityGate` passes

### Story 3.3: Success screen after the check (basic)

As a user,
I want a short, calm confirmation when I finish my check,
So that I know the alarm is done and nothing was charged.
**Refs:** FR-PWK-9 (finishing ends the session), FR-MSG-3 (basic; the celebration is Epic 6), FR-MSG-4, NFR-9, AD-2, AD-11, UX-DR2, UX-DR64, UX-DR66, UX-DR70, UX-DR72, UX-DR78 · **Priority:** Must · **Verify:** auto

**Acceptance Criteria:**

**Given** a session reaches `Completed`
**When** `WakeActivity` observes it
**Then** instead of finishing it shows the Success screen (Sunrise tokens), held as UI-only state keyed by `sessionId`, while the engine continues `Recorded` → `Idle`, clears `runtime.db`, removes the notification and stops `WakeService` in the background (test)
**And** with 0 snoozes in a normal session the headline is "Up on time." `[ASSUMPTION: add to EXPERIENCE.md Key strings]` (the streak version "Up on time. {streak} days in a row." replaces it when streaks exist in Epic 6)
**And** after one or more snoozes the headline is "You're up. That's what counts." (EXPERIENCE.md Key strings; reachable only with `FakeBilling` until Epic 4, which also adds the "{paid} paid this morning" line)
**And** in a test session the headline is "Test done. No charge." `[ASSUMPTION: add to EXPERIENCE.md Key strings]`
**And** one full-width 72 dp `button-wake-primary`-style "Done" (EXPERIENCE.md Component Patterns) sits in the thumb zone, the success haptic pattern plays once, and there is no animation (Epic 6 adds the one streak scale)

**Given** the Success screen
**When** the user taps "Done", or 60 s pass without a tap `[ASSUMPTION: owner confirms the timeout]`
**Then** `WakeActivity` finishes; Back does nothing; pressing Home leaves it, and the next app open shows Home, not Success
**And** a new alarm that fires while Success is visible starts a new session and `WakeActivity` (single instance) switches to the ringing screen

**Given** TalkBack and layout rules
**When** semantic and Roborazzi tests run
**Then** initial focus is the headline, then "Done"; screenshots exist for the three variants in Sunrise at 100% and 200% font scale
**And** the `pps-design` Done checklist is ticked in the story file, all strings are resources, key strings match EXPERIENCE.md verbatim and `CopyRulesTest` passes (FR-MSG-4)
**And** `./gradlew qualityGate` passes

### Story 3.4: Grace window with the countdown ring

As a user sharing a bedroom,
I want the alarm to go silent for a few seconds after "I'm up", with a clear countdown,
So that I can do my check without waking anyone, and know exactly when it comes back.
**Refs:** FR-PWK-9, FR-SES-6, FR-ALM-2, FR-MSG-4, NFR-9, AD-2, AD-3, AD-6, UX-DR16, UX-DR40, UX-DR41, UX-DR65, UX-DR66, UX-DR70, UX-DR71, UX-DR72, UX-DR78, UX-DR87, UX-DR88 · **Priority:** Must · **Verify:** auto

**Acceptance Criteria:**

**Given** `:core` and `:data`
**When** the per-alarm grace vibration setting is added
**Then** `Alarm` gains `vibrateInGrace` (default true `[ASSUMPTION: owner confirms the default]`), `app.db` migrates from version 3 to 4 adding `alarm.vibrate_in_grace` with the exported v4 schema and a migration test with existing rows preserved, and `ConfigResolver` freezes it into `SessionConfig.vibrateInGrace`
**And** commitment-lock handling for a longer grace window is added in Epic 4; here changes apply immediately

**Given** the Alarm editor
**When** it renders
**Then** a "Grace window" section (glossary term) shows a `slider` of 15–30 s in 1 s steps (default 20, the existing `graceSeconds`) with the value announced on change as "{seconds} seconds" `[ASSUMPTION: add to EXPERIENCE.md Key strings]`, and a `switch` "Vibrate in grace" `[ASSUMPTION: add to EXPERIENCE.md Key strings]`
**And** both are saved with "Save" and covered by the existing "Discard changes?" check

**Given** a session in `Grace`
**When** the Check screen header renders
**Then** the `countdown-ring` (120 dp, 8 dp `accent-sunrise` stroke over `outline-subtle-sunrise` track, seconds centred in `display` with tabular figures) counts down linearly and exactly to the second from the grace `Deadline` read through the engine (never a separate UI timer), with the label "Quiet for {seconds}s. Finish before it rings again." (EXPERIENCE.md Key strings)
**And** it keeps counting behind any sheet, pauses showing the remaining seconds while `paused` for a call (Story 2.7), and after a restore continues from the remaining time or shows the expired state if the deadline passed
**And** a short haptic tick plays every 5 s, and TalkBack politely announces "{seconds} seconds left" `[ASSUMPTION: add to EXPERIENCE.md Key strings]` every 10 s and at 5 s while muted (UX-DR65)
**And** vibration continues during the window only when `vibrateInGrace` is on (Story 2.8 behaviour, now driven by the alarm's setting)

**Given** the grace deadline passes
**When** `GraceElapsed` moves the session to `Loud`
**Then** the ring is replaced by a solid 48 dp bell icon in `text-sunrise` with "Alarm's back on" (EXPERIENCE.md Component Patterns) and the line "Time's up. Alarm's back on until you finish." (EXPERIENCE.md Key strings), a strong haptic plays, the alarm returns at the full set volume (Story 2.8), and check progress is kept at the same item
**And** there is one grace window per ring: a new window only after a re-ring from a snooze, and none on a ring with `noGraceThisRing` (merged during a snooze), where the header shows neither the ring nor the expired line
**And** with animator duration scale 0 the ring is replaced by the plain seconds number and every change is instant

**Given** the new states
**When** Roborazzi and semantic tests run
**Then** screenshots exist for grace at 20 s and 5 s, paused, expired and no-grace ring in Sunrise at 100% and 200% font scale, and for the editor section in Light and Dark at 200%
**And** the `pps-design` Done checklist is ticked in the story file, all strings are resources, key strings match EXPERIENCE.md verbatim and `CopyRulesTest` passes (FR-MSG-4)
**And** `./gradlew qualityGate` passes

### Story 3.5: Choose the checks for each alarm

As a user,
I want to pick one or more checks for each alarm, their difficulty and count, and whether I get one at random or all of them in order,
So that each alarm is exactly as hard as I need it to be.
**Refs:** FR-PWK-1, FR-PWK-2, FR-PWK-3, FR-ALM-2, FR-MSG-4, NFR-9, AD-6, AD-9, AD-11, AD-16, UX-DR29, UX-DR31, UX-DR37, UX-DR38, UX-DR39, UX-DR60, UX-DR64, UX-DR66, UX-DR67, UX-DR80, UX-DR84, UX-DR85 · **Priority:** Must · **Verify:** auto

**Acceptance Criteria:**

**Given** `:core` and `:data`
**When** per-alarm check configuration is added
**Then** `app.db` migrates from version 4 to 5 adding `check_config` (`id`, `alarm_id` foreign key with cascade delete, `position`, `type`, `difficulty`, `count`, `created_at`, `updated_at`) and `alarm.check_mode` (Random or All, default Random), with the exported v5 schema and a migration test that gives every existing alarm one Math · Medium · 3 row
**And** a `CheckConfigRepository` port with `FakeCheckConfigRepository` exists; `SaveAlarm` writes the alarm and its configs in one transaction, `DuplicateAlarm` copies them, `DeleteAlarm` removes them, and all three still respect the Story 2.6 session guard
**And** validation returns `DomainError.InvalidAlarm(field)` for zero checks, a count outside the type's range, or the same type twice on one alarm
**And** `ConfigResolver` builds the `CheckPlan` from the alarm's configs (replacing the Story 3.2 default plan), ordered by `position`

**Given** the Alarm editor
**When** it renders
**Then** a "Checks" section shows one `chip-check` per selected check (icon, name and difficulty, for example "Math · Medium") that opens Check setup, a row that opens the Check picker, and, with two or more checks, a `segmented-control` "Random" / "All" (EXPERIENCE.md Information Architecture)
**And** in All mode each chip offers "Move up" and "Move down" `[ASSUMPTION: add to EXPERIENCE.md Key strings]` in its menu and as TalkBack custom actions to set the order
**And** removing the last check blocks "Save" with the inline error "Pick at least one check." (EXPERIENCE.md Key strings)
**And** `card-alarm` on Home now shows the alarm's check icons (20 dp, `text-secondary`)

**Given** the Check picker (pushed screen, `top-app-bar`)
**When** it opens
**Then** it lists one `check-type-card` per type registered in `CheckRegistry` with both a core plugin and a wake composable (only Math at this point; later stories add theirs), each with icon, name from the glossary, one line of description `[ASSUMPTION: add to EXPERIENCE.md Key strings]` and "Try it" (wired in Story 3.6); selected cards show a 2 dp accent border plus a check icon, and a tap toggles selection
**And** camera check cards show "Needs the camera. If it can't be used, you'll get a fallback check." (EXPERIENCE.md Key strings)

**Given** Check setup for one check
**When** it opens
**Then** it shows difficulty as a `segmented-control` Easy / Medium / Hard (hidden for types without difficulty) and count as a `stepper` within the type's range, labelled per type (Math: "Problems" `[ASSUMPTION: add to EXPERIENCE.md Key strings]`)

**Given** the new screens and states
**When** Roborazzi, semantic and ViewModel tests run
**Then** screenshots exist for the editor Checks section (one check, several in All mode, none with the error), the picker and Check setup in Light and Dark and at 200% font scale, all targets are ≥ 48 dp, and ViewModel tests cover add, remove, reorder, mode change, validation and discard
**And** the `pps-design` Done checklist is ticked in the story file, all strings are resources, key strings match EXPERIENCE.md verbatim and `CopyRulesTest` passes (FR-MSG-4)
**And** `./gradlew qualityGate` passes

### Story 3.6: "Try it" previews for every check

As a user,
I want to try a check before I save it,
So that I know tomorrow's check is doable half-asleep.
**Refs:** FR-PWK-12, FR-MSG-4, NFR-9, AD-9, AD-11, UX-DR27, UX-DR29, UX-DR60, UX-DR64 · **Priority:** Must · **Verify:** auto

**Acceptance Criteria:**

**Given** a `check-type-card` in the Check picker or the Check setup screen
**When** the user taps "Try it" (EXPERIENCE.md Key strings)
**Then** a pushed full-screen preview opens with Sunrise tokens, rendering the type's preview composable from `CheckRegistry` at the difficulty currently set, with count 1, seeded in the ViewModel (not in `:core`)
**And** correctness still comes only from the core `validate`, and wrong answers show the same feedback as the real check

**Given** a preview is running
**When** anything happens in it
**Then** no `SessionEngine` event is dispatched, no sound plays, there is no grace window and no snooze footer, no history row is written and no scheduler call is made (test with `FakeActiveSessionStore`, `FakeSessionHistoryRepository` and `FakeAlarmScheduler` untouched)
**And** completing it shows "Nice. That's how it works." `[ASSUMPTION: add to EXPERIENCE.md Key strings]` with a `button-filled` "Done", and Back or "Done" returns to the screen it came from with the unsaved editor state intact

**Given** the preview registry
**When** a test lists every type shown in the Check picker
**Then** each has a preview composable (Math now; each later check story registers its own)
**And** Roborazzi screenshots cover the Math preview (running and completed) at 100% and 200% font scale
**And** the `pps-design` Done checklist is ticked in the story file, all strings are resources, key strings match EXPERIENCE.md verbatim and `CopyRulesTest` passes (FR-MSG-4)
**And** `./gradlew qualityGate` passes

### Story 3.7: Word Unscramble check

As a user,
I want to unscramble words to stop the alarm,
So that I have a check that wakes my brain without numbers.
**Refs:** FR-PWK-1, FR-PWK-3, FR-PWK-8, FR-PWK-12, FR-MSG-4, NFR-9, NFR-10, AD-9, AD-15, UX-DR19, UX-DR64, UX-DR66, UX-DR67, UX-DR70, UX-DR71 · **Priority:** Must · **Verify:** auto

**Acceptance Criteria:**

**Given** an English word list bundled as an APK asset `words_en.txt` (readable before first unlock)
**When** `./gradlew checkWordList` runs (a `qualityGate` dependency)
**Then** it fails unless every entry is lowercase a–z, unique, 4–10 letters, absent from the committed `config/word-blocklist.txt` (offensive and sensitive words, reviewed by the owner), and each length bucket (4–5, 6–7, 8–10) has at least 300 words; fixture tests prove each failure
**And** the list's source and licence (public domain or a permissive licence) are recorded in `docs/checks/WORDS.md`, and resolving PRD Q10's word-list item is recorded in `docs/decisions/q10-check-parameters.md`

**Given** the core `WordUnscramble` type, built with the loaded list (no platform I/O in `:core`)
**When** it generates a puzzle
**Then** it picks `count` distinct words from the difficulty bucket by seed: Easy 4–5 letters, Medium 6–7, Hard 8–10 `[ASSUMPTION: Q10]`; count is 1–5 with default 2 `[ASSUMPTION: Q10]`
**And** each scramble is a deterministic permutation that differs from the word and is not itself a word in the list
**And** validation accepts the target word or any listed word with exactly the same letters, case-insensitive; the type is `directBootSafe` and has no camera

**Given** the Word Unscramble check screen
**When** it renders
**Then** scrambled letters are `letter-tile`s (48 dp, wrapping onto a second row when needed) above empty answer slots with dashed borders; tapping a letter moves it to the next empty slot, tapping a filled slot returns its letter, and "Shuffle" and "Clear" (EXPERIENCE.md Component Patterns) reorder the remaining letters (display only, the puzzle is unchanged) or return all letters
**And** when every slot is filled the answer is submitted automatically; a wrong word shakes, plays the error haptic, clears the slots and shows "Not quite. Try again."; progress shows "Word {n} of {count}" `[ASSUMPTION: add to EXPERIENCE.md Key strings]`
**And** each tile tap gives a light haptic and dispatches `UserInteracted`

**Given** TalkBack is on
**When** the user explores the screen
**Then** each letter tile reads "Letter {letter}" and each slot reads "Slot {n}, empty" or "Slot {n}, {letter}" `[ASSUMPTION: add to EXPERIENCE.md Key strings]`, with the current answer announced after each move

**Given** the picker and previews
**When** this story lands
**Then** Word Unscramble appears in the Check picker with count labelled "Words" `[ASSUMPTION: add to EXPERIENCE.md Key strings]`, and has a "Try it" preview
**And** Roborazzi screenshots cover Easy and Hard (wrapped) layouts, a wrong answer and the preview in Sunrise at 100% and 200% font scale, with "Shuffle", "Clear" and the snooze control on screen without scrolling
**And** the `pps-design` Done checklist is ticked in the story file, all strings are resources, key strings match EXPERIENCE.md verbatim and `CopyRulesTest` passes (FR-MSG-4)
**And** `./gradlew qualityGate` passes

### Story 3.8: Memory Sequence check

As a user,
I want to repeat a sequence of lit tiles to stop the alarm,
So that I have a quick check that needs focus rather than typing.
**Refs:** FR-PWK-1, FR-PWK-3, FR-PWK-4, FR-PWK-12, FR-MSG-4, NFR-9, AD-9, UX-DR18, UX-DR29, UX-DR63, UX-DR64, UX-DR67, UX-DR70, UX-DR71, UX-DR72 · **Priority:** Must · **Verify:** auto

**Acceptance Criteria:**

**Given** the core `MemorySequence` type
**When** it generates a puzzle
**Then** the grid is 3×3 on Easy and Medium and 4×4 on Hard, the sequence length is 4, 6 or 8 by difficulty (FR-PWK-4), count is the number of rounds, 1–5 with default 2 `[ASSUMPTION: Q10]`, and no tile repeats twice in a row
**And** each tile tap is submitted as its own `CheckAnswerSubmitted`; a correct tap returns `ItemCorrect` (or `Correct` at the end of the last round), and a wrong tap returns `WrongRestart(newSeed)`, so the round restarts with a new sequence (FR-PWK-4)
**And** an accessible variant, chosen when the plan is resolved and TalkBack is on (from an `AccessibilityState` port with `FakeAccessibilityState`), always uses a 3×3 grid with the same length; the type is `directBootSafe` and has no camera

**Given** the Memory Sequence check screen
**When** a round starts
**Then** it shows "Watch the sequence" `[ASSUMPTION: add to EXPERIENCE.md Key strings]` and plays the sequence with 350 ms highlights and 150 ms gaps (`accent-sunrise` fill with the tile number in `on-accent-sunrise`); input is disabled while it plays; then it shows "Your turn" `[ASSUMPTION: add to EXPERIENCE.md Key strings]` and progress "Round {n} of {count}" `[ASSUMPTION: add to EXPERIENCE.md Key strings]`
**And** each tap gives brief lit feedback, a light haptic and a `UserInteracted`; a wrong tap shakes, plays the error haptic, shows "Not quite. Try again." and replays a new sequence
**And** with animator duration scale 0 highlights are instant on and off state changes that keep the same timing
**And** a restore in the middle of a round replays that round's sequence from the start

**Given** the accessible variant
**When** it renders
**Then** every tile shows its number 1–9, each tile reads "Tile {number}" `[ASSUMPTION: add to EXPERIENCE.md Key strings]`, and the sequence is announced as numbers ("3, 7, 1, 9") before input
**And** with TalkBack on, the Check picker card shows "Uses numbered tiles with TalkBack." (EXPERIENCE.md Key strings)

**Given** the picker and previews
**When** this story lands
**Then** Memory Sequence appears in the Check picker with count labelled "Rounds" `[ASSUMPTION: add to EXPERIENCE.md Key strings]`, and has a "Try it" preview
**And** Roborazzi screenshots cover 3×3, 4×4, the accessible variant, playback and a wrong tap in Sunrise at 100% and 200% font scale, with tiles ≥ 64 dp
**And** the `pps-design` Done checklist is ticked in the story file, all strings are resources, key strings match EXPERIENCE.md verbatim and `CopyRulesTest` passes (FR-MSG-4)
**And** `./gradlew qualityGate` passes

### Story 3.9: Fallback check picker

As a user whose check can't be done (camera broken, permission gone, code lost),
I want to switch once to a check I can do without the camera,
So that waking up is always free, and the alarm never traps me.
**Refs:** FR-PWK-11, FR-PRG-1, FR-MSG-4, NFR-2, NFR-9, AD-2, AD-9, AD-18, UX-DR13, UX-DR22, UX-DR29, UX-DR63, UX-DR64, UX-DR78, UX-DR84, UX-DR85, UX-DR90 · **Priority:** Must · **Verify:** auto

**Acceptance Criteria:**

**Given** the production `FallbackPolicy` replacing the Epic 1 "not allowed" policy
**When** `FallbackRequested(type, reason)` arrives in `Grace` or `Loud`
**Then** it is allowed only when the current entry's type `usesCamera`, `fallbackUsed` is false, the chosen type has no camera, and either the reason is `CameraUnavailable` or `failedAttempts ≥ 5`
**And** the allowed effect follows the AD-2 row: the rest of the plan is replaced by one entry of the chosen type at Hard with count = 2 × that type's default count (Math 6, Word Unscramble 4, Memory Sequence 4) `[ASSUMPTION: resolves EXPERIENCE.md D4]`, `fallbackUsed = true`, timers unchanged: the grace window keeps counting and no new one starts
**And** the fallback plan stays for the rest of the session: a re-ring after a snooze gets new seeds for the fallback entry, never the camera check again
**And** a camera test type `FakeCameraCheck` in `:testing` drives table tests for allowed, denied (non-camera step, fewer than 5 failures, already used, camera type chosen) and the once-per-session rule

**Given** a camera check is showing
**When** the policy would allow a fallback for it
**Then** `fallback-link` "Can't do this check?" (EXPERIENCE.md Key strings) appears centred above the snooze control, `body` in `accent-text-sunrise`, 48 dp target, and it never appears after the fallback was used this session

**Given** the user taps the link
**When** the Fallback check picker opens (wake screen, Sunrise tokens, snooze control in the footer)
**Then** the title is "Pick a fallback check" (EXPERIENCE.md Key strings) and it lists only non-camera types as Sunrise `check-type-card`s with Math always first and always available, then Word Unscramble, then Memory Sequence in its numbered accessible variant
**And** tapping a card dispatches `FallbackRequested(type, reason)` and shows that check; a close icon (content description "Back to check" `[ASSUMPTION: add to EXPERIENCE.md Key strings]`) returns to the current check without using the fallback; Back does nothing
**And** the alarm keeps ringing in `Loud` and stays muted until the countdown ends in `Grace`

**Given** a session that used the fallback
**When** `SessionRecorder` writes history
**Then** `fallback_used` is true and `app.db` migrates from version 5 to 6 adding the nullable `session_history.fallback_from` (the replaced check type id), with the exported v6 schema and a migration test, written only by `SessionRecorder`
**And** Roborazzi screenshots cover the link and the picker in Sunrise at 100% and 200% font scale, using `FakeCameraCheck` for the camera screen
**And** the `pps-design` Done checklist is ticked in the story file, all strings are resources, key strings match EXPERIENCE.md verbatim and `CopyRulesTest` passes (FR-MSG-4)
**And** `./gradlew qualityGate` passes

### Story 3.10: QR/Barcode: register a code and scan it to stop the alarm

As a user,
I want to register a barcode far from my bed and have to scan it to stop the alarm,
So that I have to get up and walk to it.
**Refs:** FR-PWK-1, FR-PWK-3, FR-PWK-7, FR-PWK-12, FR-ONB-2 (camera asked only when needed), FR-MSG-4, NFR-4, NFR-9, AD-5, AD-6, AD-9, AD-15, UX-DR20, UX-DR29, UX-DR60, UX-DR64 · **Priority:** Must · **Verify:** auto (real scanning is human-verify in Story 3.14)

**Acceptance Criteria:**

**Given** the build
**When** CameraX 1.6.2 (`camera-core`, `camera-camera2`, `camera-lifecycle`, `camera-view`) and bundled ML Kit barcode-scanning 17.3.0 are added
**Then** their coordinates are added to `config/dependency-allowlist.txt` in the same change, merged permissions still pass the permission allowlist (`CAMERA` is already listed), and the bundled model is used (no model download through Play services)
**And** any Google usage-logging dependency pulled in by ML Kit is listed in the story file for the Epic 8 Data safety form (AD-15)
**And** frames are analysed on device only; the analyser never writes an image to storage (test on the analyser wrapper)

**Given** a `CodeScanner` abstraction in `:composeApp` `androidMain` (camera preview lives there per the Architecture) with a `FakeCodeScanner` for tests
**When** a code is detected
**Then** it emits `ScanResult(format, rawValue)` only after the same value is seen in 3 consecutive frames

**Given** the core `QrBarcode` type
**When** it is added
**Then** `usesCamera = true`, `directBootSafe = false`, no difficulty, count fixed at 1 `[ASSUMPTION: Q10]`; the puzzle is the registered code, and validation accepts only the same format and the same trimmed raw value; any other code is `Wrong` (a failed attempt)
**And** `app.db` migrates from version 6 to 7 adding nullable `check_config.code_format` and `check_config.code_value`, with the exported v7 schema and a migration test; `SaveAlarm` rejects a QR/Barcode config without a registered code, and the editor shows "Scan a code to use this check." `[ASSUMPTION: add to EXPERIENCE.md Key strings]`

**Given** the user selects QR/Barcode in the Check picker
**When** camera permission is not granted
**Then** the app asks for `CAMERA` at that moment only (never at app start, never for other checks), and if denied the card stays unselected with a `note-inline` "Camera is off. Turn it on in Settings." `[ASSUMPTION: add to EXPERIENCE.md Key strings]` and a `button-text` "Fix" opening the app's system settings; "don't ask again" is handled the same way

**Given** QR registration (pushed screen, reached from Check setup)
**When** it opens with permission granted
**Then** the `viewfinder` starts immediately with a centred square guide and a 48 dp torch toggle (content description "Torch" `[ASSUMPTION: add to EXPERIENCE.md Key strings]`); on a detection it pauses and offers "Use this code" and "Scan again" `[ASSUMPTION: add to EXPERIENCE.md Key strings]`, and "Use this code" stores format and value in the setup draft (saved with the alarm)

**Given** a ring whose current entry is QR/Barcode
**When** the Check screen opens
**Then** the `viewfinder` starts on screen open with the square guide, the header shows "Scan your code" `[ASSUMPTION: add to EXPERIENCE.md Key strings]`, and a matching scan submits a correct answer (completing the entry)
**And** a different code submits a wrong answer: error haptic and "That's a different code. Scan your registered one." `[ASSUMPTION: add to EXPERIENCE.md Key strings]`; the same wrong code seen again within 2 s is not submitted twice
**And** when permission is missing or CameraX fails to bind, "Camera isn't available. Pick a fallback check." (EXPERIENCE.md Key strings) and the fallback link (Story 3.9) show immediately (the no-frame watchdog, mid-scan failures and Direct Boot are in Story 3.11)

**Given** the picker and previews
**When** this story lands
**Then** QR/Barcode appears in the Check picker with the camera note, and "Try it" opens a preview that scans the registered code without a session
**And** ViewModel tests with `FakeCodeScanner` cover registration, match, wrong code, duplicate suppression and permission denied; Roborazzi screenshots (camera preview replaced by a placeholder surface) cover registration, the wake QR check and the camera-unavailable state in their themes at 100% and 200% font scale
**And** the `pps-design` Done checklist is ticked in the story file, all strings are resources, key strings match EXPERIENCE.md verbatim and `CopyRulesTest` passes (FR-MSG-4)
**And** `./gradlew qualityGate` passes

### Story 3.11: QR/Barcode when the camera fails, and before first unlock

As a user,
I want the app to notice quickly when the camera can't work and offer me another way, including after an overnight restart,
So that a broken camera or a locked phone never leaves the alarm ringing with no way out.
**Refs:** FR-PWK-11, FR-ALM-11, FR-SES-1, FR-MSG-4, NFR-2, NFR-9, AD-3, AD-9, UX-DR20, UX-DR22, UX-DR35, UX-DR64, UX-DR78, UX-DR90, UX-DR93 · **Priority:** Must · **Verify:** auto (real camera failures are human-verify in Story 3.14)

**Acceptance Criteria:**

**Given** the QR check has bound the camera
**When** no frame arrives within 5 s (measured with the monotonic clock), CameraX reports an error, or the camera is disconnected (for example another app takes it)
**Then** the check shows "Camera isn't available. Pick a fallback check." and the fallback link immediately (`FallbackRequested` reason `CameraUnavailable`)
**And** if the camera later recovers the scan resumes and the link stays available
**And** tests with `FakeCodeScanner` and `FakeMonotonicClock` cover 4.9 s (no message), 5.0 s (message), an error callback mid-scan and a disconnect

**Given** the user scans wrong codes
**When** the fifth failed attempt is recorded on the entry
**Then** the fallback link appears (reason `FailedAttempts`), which is also the path for a lost code (FR-PWK-11)

**Given** `WakeActivity` is paused (screen off, Home, notification shade)
**When** it resumes
**Then** the camera is released while paused and rebinds on resume with the 5 s watchdog restarted; a restored session after a kill rebinds the camera on the same step

**Given** a ring before first unlock whose plan contains QR/Barcode
**When** Epic 2's `DirectBootSubstitution` applies
**Then** the entry is replaced by the default Math entry (Math · Medium · 3), and the ringing and check screens show a Sunrise `note-inline` "Your phone restarted, so today's check is Math." (EXPERIENCE.md Key strings)
**And** the note and Math stay for that ring even after unlock, and the next ring after unlock uses QR/Barcode again (test with `FakeUserLockState`)

**Given** TalkBack is on
**When** the QR check is shown
**Then** the viewfinder reads "Camera viewfinder. Point at your code." `[ASSUMPTION: add to EXPERIENCE.md Key strings]`, the torch reads its state, results are announced politely, and the fallback link is focusable as soon as it appears
**And** Roborazzi screenshots cover the watchdog message, the link after 5 failures and the Direct Boot note on ringing and check screens in Sunrise at 100% and 200% font scale
**And** the `pps-design` Done checklist is ticked in the story file, all strings are resources, key strings match EXPERIENCE.md verbatim and `CopyRulesTest` passes (FR-MSG-4)
**And** `./gradlew qualityGate` passes

### Story 3.12: Checks with TalkBack, end to end

As a user who relies on TalkBack,
I want to stop my alarm without needing to see the screen,
So that waking up is free for me too.
**Refs:** NFR-9, FR-PWK-11, FR-MSG-4, UX-DR8, UX-DR63, UX-DR64, UX-DR65, UX-DR66, UX-DR67, UX-DR69, UX-DR90 · **Priority:** Must · **Verify:** auto, plus (human-verify) the flow on device in Story 3.14

**Acceptance Criteria:**

**Given** an instrumented test on the Gradle Managed Device with accessibility checks enabled for Compose
**When** it runs flow F5: a debug alarm with a QR/Barcode check, camera permission revoked (`pm revoke`)
**Then** initial focus is the clock, then "I'm up"; after "I'm up" the camera message and the fallback link are focusable immediately; the Fallback check picker lists Math first; the Math problem reads its spoken form; the pad announces keys and the answer; "Check" completes the session and the alarm stops
**And** no step needs sight, a timed gesture or anything other than a tap (every actionable node has a label and a role)

**Given** every check screen, the Fallback check picker and the Success screen
**When** semantic tests run
**Then** every control has a label, role and state; the problem or instruction is a heading; wrong-answer feedback and the grace countdown are polite live regions; focus order follows reading order and returns to the first input after a wrong answer
**And** the Memory Sequence accessible variant is chosen automatically when TalkBack is on (`FakeAccessibilityState`)

**Given** a 360 × 640 dp configuration at 200% font scale
**When** Roborazzi records every check screen, the picker and Success
**Then** the primary input, the fallback link when shown and the snooze control are all on screen without scrolling, and the clock stays capped at 1.3×

**Given** the DESIGN.md contrast table
**When** the Story 1.3 contrast test runs
**Then** it also checks the list of colour pairs used by the check screens (including `error-sunrise` feedback, lit memory tiles and the countdown ring), and fails on any pair missing from the table
**And** PRD Q12 is closed in `docs/decisions/q12-accessible-fallback.md`, describing the TalkBack path (Math first with spoken input, numbered Memory Sequence)
**And** the `pps-design` Done checklist is ticked in the story file, all strings are resources, key strings match EXPERIENCE.md verbatim and `CopyRulesTest` passes (FR-MSG-4)
**And** `./gradlew qualityGate` passes

### Story 3.13: Suggest re-registering after 3 fallbacks in 7 days

As a user,
I want to hear when my code keeps failing,
So that I fix the check instead of relying on the fallback every morning.
**Refs:** FR-PWK-11, FR-MSG-4, AD-18, UX-DR33, UX-DR80, UX-DR84 · **Priority:** Must · **Verify:** auto

**Acceptance Criteria:**

**Given** a pure `reRegisterSuggestion(history, alarms, checkConfigs, now)` in `core.stats`
**When** it runs
**Then** it returns the alarm and check type when at least 3 sessions of that alarm in the last 7 × 24 hours (by `first_ring_at`) have `fallback_used` with `fallback_from` equal to a camera type the alarm still has configured, counting only sessions after that check's last registration (`updated_at`), and excluding Test sessions
**And** table-driven tests cover 2 fallbacks (none), 3 within 7 days (suggested), one of 3 older than 7 days (none), Test sessions (excluded), re-registered after the fallbacks (none), and two alarms each with 2 (none)

**Given** a suggestion
**When** Home is shown and no session is active
**Then** the info variant of `banner-warning` (info icon in `text-secondary`, no error colour) shows "Fallback check used 3 times this week. Re-register your {checkName}?" with a `button-text` "Re-register" (EXPERIENCE.md Key strings), where `{checkName}` is the glossary name ("QR/Barcode")
**And** "Re-register" opens QR registration for that alarm, and saving a new code clears the banner
**And** the banner is dismissible (close icon, content description "Dismiss" `[ASSUMPTION: add to EXPERIENCE.md Key strings]`); dismissal is stored in the device-protected DataStore per alarm and type, and the banner returns only after 3 new fallbacks; the reliability `banner-warning` (Story 1.19), when present, stays above it
**And** the new DataStore key is covered by the Story 2.12 backup coverage test

**Given** the banner states
**When** Roborazzi and semantic tests run
**Then** screenshots exist with and without the reliability banner in Light and Dark and at 200% font scale, and targets are ≥ 48 dp
**And** the `pps-design` Done checklist is ticked in the story file, all strings are resources, key strings match EXPERIENCE.md verbatim and `CopyRulesTest` passes (FR-MSG-4)
**And** `./gradlew qualityGate` passes

### Story 3.14: Epic 3 device verification checklist

As the owner,
I want to confirm on real phones everything about checks that tests can't prove,
So that Epic 4 builds payments on checks I know work at 6 a.m.
**Refs:** FR-PWK-1–5, FR-PWK-7–9, FR-PWK-11, FR-PWK-12, FR-ALM-11, FR-MSG-4, NFR-2, NFR-9 · **Priority:** Must · **Verify:** human-verify

**Acceptance Criteria:**

**Given** the latest `main` debug build on each device of the matrix (Pixel, Samsung, Xiaomi, budget device)
**When** the owner runs the checklist
**Then** for each item the story file records pass/fail, device, Android version and date:
1. Math at Easy, Medium and Hard with counts 1 and 5: problems match the difficulty, the pad is easy to hit half-asleep, and wrong answers shake with a haptic.
2. Grace window: "I'm up" mutes the alarm, the ring counts 20 s exactly (stopwatch), at 0 a strong haptic and full volume return with progress kept; with "Vibrate in grace" on the phone vibrates during the window, with it off it is still.
3. A 30 s grace window and a 15 s one both work, and after the window expires no second window starts in the same ring (the new window after a paid snooze is checked in Epic 4).
4. Word Unscramble at each difficulty: word lengths match; "Shuffle" and "Clear" work; across 30 samples no offensive or obscure words appear (list any to add to the blocklist).
5. Memory Sequence: sequences of 4, 6 and 8, a 4×4 grid on Hard, highlight timing readable, a wrong tap restarts the round.
6. All mode with Memory Sequence then Word Unscramble runs in order; Random mode varies across 5 test alarms.
7. QR/Barcode: the camera permission prompt appears only when QR/Barcode is selected; register a product barcode and a printed QR; both scan at wake time, including in a dim room with the torch.
8. A different barcode shows the wrong-code message; after 5 wrong codes the fallback link appears.
9. Revoke the camera permission in system settings: the message and link appear immediately. Open a video call app that holds the camera, then fire an alarm: the message appears within 5 s.
10. The Fallback check picker lists Math first; the chosen check is Hard with double count; the alarm keeps ringing (or stays muted until the countdown ends); the link does not come back in the same session.
11. After 3 fallbacks in 7 days on real (non-test) debug alarms, Home shows the re-register banner; "Re-register" opens registration and a new code clears it.
12. Reboot before unlock with a QR/Barcode alarm: Math and "Your phone restarted, so today's check is Math." appear; after unlock Math stays for that ring; the next alarm uses QR/Barcode.
13. "Try it" on every check: no sound, nothing logged, the editor keeps unsaved changes.
14. With TalkBack on, flow F5 end to end without looking at the screen, on at least two devices.
15. At 200% font size on the smallest device, every check keeps its main input and the snooze control on screen.
16. Kill the process mid-check (`adb shell am kill com.payper.snooze`): within 60 s the same problem or step returns.
17. The Success screen shows the right variant (on time, test); "Done" closes it; untouched it closes after 60 s.
18. All copy seen matches EXPERIENCE.md, and every `[ASSUMPTION: add to EXPERIENCE.md Key strings]` string from this epic has been accepted or reworded by the owner in EXPERIENCE.md (FR-MSG-4).

**Given** any failed item
**When** the owner records it
**Then** each failure becomes a new bug story referencing this item, and this story stays open until all items pass or are explicitly waived by the owner in the story file
**And** automation never marks this story done
