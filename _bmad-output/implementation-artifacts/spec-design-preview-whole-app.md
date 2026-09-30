---
title: 'Design preview: the whole app on the owner''s phone'
type: 'feature'
created: '2026-09-27'
status: 'in-progress'
baseline_commit: '54afe457febbb8fb52198673a6af180c6fad53b6'
route: 'dispatch'
review_loop_iteration: 0
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-1-context.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-1-8-create-and-edit-an-alarm.md'
  - '{project-root}/.claude/skills/pps-design/SKILL.md'
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** The owner tried the Story 1.8 editor on their Oppo A96 and wants to understand how the whole finished app will look and feel before the remaining ~100 stories are built, so later work doesn't drift. On the device, keyboard time entry covered half the screen (owner: replace it with a scrolling wheel), Save hid behind the keyboard, and the app doesn't draw edge-to-edge.

**Approach:** Build every surface in EXPERIENCE.md "Information Architecture" as real, stateless Compose screens in `:composeApp` driven by `UiState`, and a debug-only "Design preview" mode in `:androidApp` that shows each screen and its key states with realistic fake data, navigable like the finished app (bottom navigation, pushed screens, wake flow). Deliver in three rounds, each installed on the owner's phone for feedback. Also replace the editor's time input with a scrolling wheel in production, fix edge-to-edge and Save-behind-keyboard, and record the owner's decisions in EXPERIENCE.md/DESIGN.md. Logic stories reuse these composables.

## Boundaries & Constraints

**Always:**
- Screens are production code: stateless composables in `:composeApp` (`ui.<screen>` packages) taking `UiState` + `onIntent`, only `PpsTheme` tokens, all strings in resources, EXPERIENCE.md key strings verbatim, `CopyRulesTest` passing. Wake screens always Sunrise. The preview only supplies fake states.
- **Round 1 — the daily loop:** app shell with bottom navigation (Alarms, Progress, Settings; Progress/Settings may be placeholders until round 2); Alarms Home (streak and money hero, next-alarm countdown, `card-alarm` list with switches, empty, missed note, permission `banner-warning`, session-in-progress panel); Alarm editor, full (time wheel, repeat, label, checks section, grace window and vibrate-in-grace, snooze length with fee ladder, sound section, motivation section; "Test alarm" and "Save" bottom bar; weakening-under-lock note; no-check-selected state); Sound picker; wake flow: Ringing (first ring, after a snooze, test, before first unlock, snooze unavailable), Snooze confirm sheet (price and next price, unlock step, already paid, payment outcome snackbars), Check (Math, Word Unscramble, Memory Sequence, QR/Barcode and House Hunt with a placeholder viewfinder; grace `countdown-ring` running and expired; wrong answer; camera unavailable), Fallback check picker, Success (zero snooze, after snooze), Snoozed.
- **Round 2 — progress and settings:** Progress (streak, rates, average time to up, snoozes chart, calendar with `outcome-marker`s, money; empty), Day detail (incl. test/skipped day), Purchase history (incl. empty), Export CSV entry; Settings (all sections), Reliability checklist (missing items and all OK), Payments & refunds; session-active Settings state.
- **Round 3 — setup flows:** Onboarding (8 steps incl. behaviour disclosure, base fee with "prices never loaded" note, first alarm, checks, reliability, analytics choice, test alarm and "not locked" state); Check picker, Check setup ("Try it"), House Hunt registration, QR registration (incl. printable QR), Recordings (incl. empty / mic denied).
- **Wheel time picker** (owner decision 2026-09-27, replaces EXPERIENCE.md "keyboard input first"): hour and minute wheels (plus AM/PM on 12 h phones), snap scrolling, no keyboard ever opens; digits in `display` tabular; TalkBack announces "Hour, 6" / "Minute, 45" and supports swipe up/down; ≥ 48 dp targets. Used in the production editor, onboarding and preview.
- **Device fixes:** `MainActivity` (and the future wake activity) draw edge-to-edge with theme system-bar colours; the editor's Save stays visible above the keyboard with the label focused, verified on the Oppo A96 via uiautomator bounds.
- **Copy:** EXPERIENCE.md strings verbatim. Strings EXPERIENCE.md doesn't define are drafted in voice (short, supportive, no em dash, no hype words) and listed in `docs/design-preview/copy-to-approve.md` (key, draft, screen) for the owner; they live in resources like any other string.
- **Preview mode:** debug-only (`androidApp/src/debug`), its own launcher entry "Yawn & Pawn Preview"; a menu grouped by round and screen listing each state; Light/Dark and 200% font-scale toggles; tapping through works like the finished app (bottom nav, pushed screens, wake flow sequence Ringing → Check → Success). Fake prices go through the normal price formatting, never a hard-coded currency symbol. Release builds contain none of it.
- Each round: Roborazzi screenshots of every new screen/state (Light and Dark, Sunrise for wake screens) plus 200% for primary states; semantics tests for touch targets and TalkBack labels; `qualityGate` green; then install on the Oppo A96 (`adb -s 4d804fdd`), walk every preview item and capture device screenshots into `docs/design-preview/round-N/` for the owner.
- EXPERIENCE.md `time-picker` row updated to the wheel "(owner decision 2026-09-27)"; later owner feedback from the preview is recorded in EXPERIENCE.md/DESIGN.md the same way; `docs/` copies updated.

**Never:**
- No new logic: no scheduling, ringing service, sound playback, billing, camera, statistics computation or real timers (fake states only; the grace ring shows fixed values).
- No new colour, radius or type tokens without a DESIGN.md update approved by the owner.
- No change to production navigation beyond the wheel picker and device fixes; new screens are reachable only from the preview until their stories wire them.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Wheel scroll | fling hour wheel | snaps to one value; state updates | N/A |
| Wheel TalkBack | swipe up on hour | value +1, announced | N/A |
| 12 h phone | system 12 h | AM/PM wheel shown | N/A |
| Label focused | keyboard open | Save visible above keyboard | N/A |
| Countdown copy | 45 min / 7 h 12 min / 2 d 3 h | "Rings in 45 min" / "Rings in 7 h 12 min" / "Rings in 2 d 3 h" | N/A |
| Wake flow in preview | Ringing → "I'm up" | Check with grace ring → Success | N/A |
| Snooze confirm | open sheet | input ignored for 500 ms; "I'll get up" bottom, "Pay {price} and snooze" above | N/A |
| Ringing locked | before first unlock | Snooze disabled "Unlock your phone to snooze" with lock icon | N/A |
| Release build | bundleRelease | no preview activity or classes | N/A |

</frozen-after-approval>

## Code Map

- `composeApp/src/commonMain/kotlin/com/yawnandpawn/app/ui/` -- existing `alarms/`, `editor/` (`PpsTimeInput` → wheel), `components/`, `theme/`, `nav/`; add one package per new surface.
- `androidApp/src/debug/` -- Story 1.3 `ThemeShowcase` + `ThemeShowcaseActivity` pattern for debug-only activities and manifest entries; the preview menu and fake data go here.
- `androidApp/src/main/kotlin/com/yawnandpawn/app/MainActivity.kt` -- edge-to-edge.
- DESIGN.md frontmatter `components:` (visual specs for every named component) and "Components" section; EXPERIENCE.md Key strings (~88–256), Long-form copy (~256), Component Patterns (~276–325), State Patterns (~329–395), Key Flows F1–F10 (~452+) for realistic fake data.
- `_bmad-output/implementation-artifacts/deferred-work.md` -- 1.8 device items (keyboard/Save, edge-to-edge, keyboard-first decision) resolved here.
- `androidApp/src/test/.../ScreenshotOptions.kt` -- shared 0.1% threshold; CI re-record rule for Linux rendering noise.

## Tasks & Acceptance

**Execution:**
- [x] Round 1 -- wheel picker + device fixes; shell and daily-loop screens; preview menu and launcher entry; screenshots/semantics; install and device walkthrough screenshots.
- [x] Round 2 -- progress and settings screens; same checks and walkthrough.
- [ ] Round 3 -- setup-flow screens; same checks and walkthrough.
- [x] `docs/design-preview/copy-to-approve.md` -- kept current each round (rounds 1 and 2 done).
- [x] EXPERIENCE.md (+ `docs/` copy) -- time-picker decision.

**Acceptance Criteria:**
- Given the debug build on the Oppo A96, when the owner opens "Yawn & Pawn Preview", then every surface in EXPERIENCE.md's Information Architecture and its listed key states is reachable with fake data, and the wake flow can be tapped through.
- Given the label field focused on the device, then Save is visible above the keyboard, and the app draws edge-to-edge.

## Implementation Notes

Round 1 (2026-09-27):

- **Wheel picker:** `ui/components/PpsWheelTimePicker.kt` replaces `PpsTimeInput` in the production editor: hour, minute and (12 h) AM/PM wheels, `LazyColumn` + `rememberSnapFlingBehavior`, 3 values visible, 200 repeats for an endless feel, centre value in `text` on a `surface-variant` band, digits `display` tabular capped at 1.3x. TalkBack: one adjustable node per wheel ("Hour" / "Minute" / "AM or PM" + value), set-progress for swipe up/down. EXPERIENCE.md and DESIGN.md `time-picker` rows (and `docs/` copies) updated "(owner decision 2026-09-27)".
- **Device fixes:** `MainActivity` calls `enableEdgeToEdge()` and has `windowSoftInputMode="adjustResize"` (the editor's `imePadding()` now gets IME insets). Host tests pass; the Oppo A96 uiautomator check is **not done** (phone locked with a PIN during the run).
- **Screens (stateless, `UiState` + `onIntent`, tokens only):** `ui/shell` (AppShell, `nav-bar`, AppTab, tab placeholder), `ui/home` (hero, countdown, `card-alarm` with switch, empty, missed note, reliability banner, fallback info banner, session panel, disable-under-lock dialog), full editor sections in `ui/editor` (checks + mode + no-check error, grace slider + vibrate, fee ladder, sound row, motivation, "Test alarm" + Save bar, weakening note; `EditorUiState.full == null` keeps the Story 1.8 editor unchanged in production), `ui/sound` (Sound picker), `ui/wake` (Ringing, snooze button in every variant, confirm sheet with 500 ms input lock in all three states, payment snackbars, Check for all five checks with grace ring running/expired, wrong answer, camera unavailable, fallback link, Fallback picker, Success, Snoozed), `ui/checks`, `ui/format` (Money + `formatMoney`, countdown, AM/PM, long date). Production navigation is unchanged: new screens are reachable only from the preview.
- **Preview:** `androidApp/src/debug/.../debug/preview/` (PreviewActivity with its own launcher entry "Yawn & Pawn Preview" and task, PreviewCatalog of 55 states grouped by round and screen, Light/Dark and 200% toggles, two tap-through flows). Release APK checked: no preview activity, classes or label.
- **Tests:** `PreviewScreenshotTest` (+ editor classes) records 81 baselines under `androidApp/src/test/screenshots/preview/`; `PreviewSemanticsTest` / `PreviewEditorSemanticsTest` (48 dp, labels, roles, states; wake actions 64/72 dp and on screen, incl. 200%); `TapThroughTest` (Ringing → Check → Success, 500 ms lock, test alarm); `CountdownTest`, `WheelMathTest`; `MainActivityTest` time test now drives the wheels. Story 1.8 editor baselines re-recorded (wheel instead of the time input). `qualityGate` green.
- **Not done in round 1:** on-device walkthrough and screenshots into `docs/design-preview/round-1/`, and the on-device Save/edge-to-edge check (phone locked). Grace ring, snooze prices and timers are fixed fake values by design.

Round 1 rework (2026-09-27, owner direction in docs/design-preview/feedback.md items 2 to 15):

- **Tokens (DESIGN.md v0.3):** `gradient-top` (Light, Dark), `glass` (72%), `glass-strong` (92%), `glass-edge` per theme, `glass-blur` 24dp, `hairline` 1dp. `tools/tokens` now reads `#RRGGBBAA`; `ContrastTable` composites stacked pairs (`glass+gradient-top`, and `glass-strong+text` as the worst case under a bar); 40 new contrast rows. Accent on the Light/Sunrise gradient top and accent-text on the sunrise gradient are documented fails, so accent controls and the success streak sit on glass. Brand colours unchanged.
- **Components:** `PpsBackground` (gradient on every screen, Sunrise top 40%), `Modifier.glass` with `GlassBackdrop` / `glassSource` (real blur through Compose `BlurEffect` / RenderEffect on API 31+, no new dependency) for the bottom pill and the snooze sheet; cards, banners, hero, alarm cards, nav bar and fallback cards are glass without live blur (static gradient behind them). `GroupCard` / `GroupDivider`, rows with value subtitles and chevrons (`NavRow`, `RadioRow`, `CheckboxRow`, `TextFieldRow`), `SaveCancelPill`, `SubScreen`, `SystemBarIcons` (dark icons on Light and Sunrise, from `PpsTheme`), `rememberReducedMotion`.
- **Editor (production and preview share it):** header with "Rings in", wheel card with h / min labels and a haptic tick, Once / Weekdays / Custom (Custom expands to the chips), card 1 name / sound / vibration, card 2 wake-up check / quiet time / snooze / motivation, "Test alarm", floating Cancel | Save pill above the keyboard (the viewport ends above the pill while the keyboard is up). Sub-screens are `EditorUiState.pane` with slide transitions; Back returns to the main screen and keeps its scroll. Production shows only the Sound (volume, gradual) and Snooze sub-screens; no starting-volume slider (ramp start saved as min(20, volume)). "Quiet time" copy; "Weekdays" / "Weekends" summaries.
- **Motion:** pane and tap-through slides (250 ms emphasized), Custom expand, chip colour fade, alarm cards `animateItem`, a gentle "I'm up" pulse that stops with reduced motion.
- **Preview:** realistic fake base fee per currency (25,000 VND on the owner's en-VN phone), Back on wake screens returns to the menu (also with the confirm sheet open), menu in grouped cards, editor sub-screen items replace the Sound picker items.
- **Verification:** `qualityGate` green; preview baselines re-recorded (94) plus Sound / Snooze sub-screen baselines; new ViewModel, semantics and tap-through tests. Installed on the Oppo A96 (Android 13, 360 dp, dark system mode, en-VN, 24 h): walkthrough screenshots in `docs/design-preview/round-1/` (the earlier ones moved to `before/`); with the keyboard up, Save and the focused name field are both above it (uiautomator bounds); dark status icons on Light and Sunrise; blur visible under the pill. Rounds 2 and 3 not started.

Round 2 (2026-09-30, progress and settings, owner design direction in docs/design-preview/feedback.md items 1 to 20 and pps-design rules 11, 14, 15):

- **Screens (stateless, `UiState` + `onIntent`, tokens only, glass cards on the gradient, grouped rows that open sub-screens):** `ui/progress` (ProgressScreen: `stat-tile`s two per row, a pair whose numbers do not fit splits into one per row; "Snoozes per week" `bar-chart` with the tapped week's number, bars read by TalkBack as values ("Week of Sep 22, 3 snoozes") and tapped through one gesture area so no sub-48 dp buttons; calendar with `outcome-marker`s, the fallback badge, today's accent ring, month buttons and a legend; "Money paid"; Purchase history and "Export CSV" links; empty state with export disabled "Nothing to export yet."), `ui/daydetail` (one card per session: outcome, flags, rings, snoozes, paid, checks, time to up, merged alarm; logged changes as notes; test and skipped days show the outcome label only), `ui/purchases` (month cards of `purchase-row`s, stranded refund caption, empty, "Problem with a charge?" link), `ui/settings` (Snooze, Wake, Appearance, switches, checklist and payments, links, Delete all data with its dialog, reliability banner, "No browser found." snackbar, session lock panel; sub-screens Base fee with `stepper`, fee ladder, lock / weakening / approximate-price notes, Max snoozes, Default snooze length, Default quiet time), `ui/reliability` (checklist in one card with OK / Fix, revoked reasons in `error`, "Ring a test alarm", manufacturer steps sub-screen), `ui/payments` (How payments & refunds work with the long-form copy, the disclosure verbatim in three strings and the purchase authentication tip; Problem with a charge?). The Progress and Settings tab placeholders are gone.
- **Shared components:** `TabScreen` / `ScreenTitle` (pinned tab title, content clipped under it), `SessionInProgressPanel` (moved out of Home), `AppSnackbar`, `TextCard`, `PpsStepper`, `ValueEndRow`, `NavRow` gains `enabled` / `titleColor` / `chevron`, `subScreenTransition` (the editor's pane slide, now shared), `formatDate` (`DateStyle`: month and year, numeric, day and month, weekday). New Material Symbols Rounded drawables: remove, chevron_left, alt_route.
- **DESIGN.md / EXPERIENCE.md v0.4 (+ `docs/` copies):** eight new contrast rows (success, snoozed, missed and disabled-content on glass over the gradient top, Light and Dark; `ContrastTest` recomputes them), `stat-tile` is glass, calendar card 12 dp from the edges so seven 48 dp days fit 360 dp, stepper details; IA rows for Settings, Progress, Day detail and the checklist record the round 2 layout. No new tokens.
- **Preview:** menu grouped per round (round 2 has 28 states and two tap-throughs: Progress with day detail, August, purchase history; Settings with sub-screens, checklist fixes, payments, delete dialog, no-browser snackbar). The app tap-through now has working Progress and Settings tabs; Home's "Fix" opens the checklist; the session lock covers Settings.
- **Tests:** 64 new baselines under `androidApp/src/test/screenshots/preview/` (Light and Dark, 200% for primary states; tall windows for the scrolling screens), the four shell-placeholder baselines removed; `PreviewSemanticsTest` / `PreviewEditorSemanticsTest` cover every round 2 state; `Round2TapThroughTest` (day detail and back, previous month and purchase history, sub-screen values and the weakening note, checklist Fix and the delete dialog). `qualityGate` green.
- **Device (Oppo A96, 360 dp, en-VN, 24 h, 2026-09-30):** installed and walked through both round 2 tap-throughs and the menu states in Light and Dark; screenshots in `docs/design-preview/round-2/`. Found and fixed on the phone: "100%" wrapped in a half-width stat tile (tiles now pair only when both numbers fit, measured), Settings sub-screens showed the nav bar in the tap-through (hidden now, like the editor's), and returning from Day detail lost the Progress scroll position (the tap-through keeps each screen's saved state). VND prices ("₫25,000", cap "₫375,000") fit on one line.
- **Not done / owner to judge:** copy drafts listed under Round 2 in `docs/design-preview/copy-to-approve.md`; "Export CSV" and the links open nothing (no logic); the stepper has no long-press repeat; manufacturer steps exist only for Xiaomi (EXPERIENCE.md has no Oppo steps yet).

Round 2 rework (2026-10-01, owner feedback item 21: Progress redesign):

- **Progress** (`ui/progress`, Day detail, Purchase history and the rest of Round 2 unchanged): hero `progress-ring` of the last 30 mornings (outcome glyphs clockwise to today in an accent ring, streak with "/ 30" and "day streak" in the centre, legend; tapping a dot opens Day detail through one gesture area, dots are read by TalkBack), three small `stat-tile`s in one row (2 + 1 from 150% font), "Snoozes this week" (7 rounded day bars, zero days as stubs, today's initial in an accent pill, tapped value, average line), the accent `card-streak`, the compact calendar (14 dp glyphs, today in a pill, legend moved to the ring), "Money paid" beside Insight (stacked at large font), "Export CSV" as a text button. No period tabs. Empty: empty ring with the prompt, calendar, Purchase history, disabled export.
- **Tokens:** new `glass-accent` (12% accent, Light / Dark / Sunrise) per item 21's "glass with an accent tint"; seven contrast rows (text, text-secondary, accent-text on `glass-accent+glass+gradient-top`, Light and Dark, and the documented accent failure). `Modifier.glass(accentTint = true)`. `PpsTextButton` gains `enabled`; new `formatOneDecimal`; icons wb_sunny, timer, snooze, wb_twilight, lightbulb. DESIGN.md (`progress-ring`, `card-streak`, `stat-tile`, `bar-chart`, `calendar-day`) and EXPERIENCE.md (IA, key strings, component rows) updated, `docs/` copies too; drafts in `copy-to-approve.md`.
- **Preview data:** a 5-day streak (best 12) so the week chart has two snoozed mornings; ring and calendar share the same days; Day detail for any ring or calendar day follows its outcome; purchases include the 22nd and 23rd.
- **Tests:** Progress baselines re-recorded (full, a tapped day, empty; Light, Dark, 200%), plus Day detail two-sessions and Purchase history baselines whose data moved; `Round2TapThroughTest` taps a ring dot into Day detail. On the Oppo A96 (360 dp, en-VN): ring (280 dp) and the three tiles fit in one row, 2 + 1 at 200%; tapping a ring dot opens Day detail; captures via deep links in `docs/design-preview/round-2/` (02 to 04 top and scrolled, 07 ring dot to Day detail, 23 dark, 27 empty, 28 and 29 at 200%).
- **Preview navigation (owner request 2026-10-01):** search field above the list (case-insensitive on screen, state, round and id; clear button; "No matches"), with the Light/Dark and 200% toggles above it. Every item and tap-through has a stable kebab-case deep-link id (the screenshot id with hyphens, `tap-app` / `tap-morning` / `tap-progress` / `tap-settings` for the tap-throughs): `am start -S -n com.yawnandpawn.app/.debug.preview.PreviewActivity --es state <id> [--es theme dark] [--ez font200 true]` opens it directly, Back returns to the menu. `docs/design-preview/states.md` is generated from the catalog; `PreviewMenuTest` checks ids are unique and kebab-case, that states.md matches the menu, the deep links, the extras and the search, and records menu baselines (Light, Dark, search, no matches).

Round 2 rework 2 (2026-10-01, owner feedback item 23: Progress notes, on top of item 21):

- No "Progress" heading (`TabScreen(title = null)`), no legend: `outcome-marker` is now shape plus colour (filled dot on time, clock dot snoozed, hollow ring missed, small neutral dot skipped / test, outlined accent pill today), used in the ring, calendar and Day detail. A first tap on a ring dot or calendar day shows the label chip ("Wed 23 · Snoozed", `inverse-surface`, 48 dp button) under the ring or calendar; a second tap or the chip opens Day detail (`DaySelection`, `ProgressIntent.DaySelected`). Tile labels "on time" / "to get up" / "snoozes" on one line at 360 dp. Export CSV removed from Progress, the preview and EXPERIENCE.md (owner decision against FR-PRG-6), logged in deferred-work.md for correct-course.
- Entry animation (`ProgressMotion.kt`): cards fade and rise 16 dp in sequence (70 ms apart), ring dots sweep in clockwise (22 ms apart), the streak counts up (700 ms), bars grow from the bottom (60 ms apart), the calendar slides between months, the chip pops with a spring. Reduced motion (animator scale 0) shows the final state at once; TalkBack reads the final streak, not the count.
- Preview: new states `progress-dot-chip` and `progress-calendar-chip`; `progress-empty` without export. Tests: tap-through tests tap twice (chip, then Day detail); baselines re-recorded (Progress, Day detail, the menu).
- Device (Oppo A96, 360 dp, via deep links, 2026-10-01): `docs/design-preview/round-2/` 02 to 04 (top, scrolled, end), 05 (a tapped dot with its chip), 06 (the chip opened Day detail), 23 (dark), 27 (empty), 28 and 29 (200%), 08-entry-a to c (entry animation frames taken right after switching to the tab). ColorOS has no `screenrecord` and the animator scale cannot be changed over adb, so there is no video.

## Spec Change Log
- Owner renegotiation (2026-09-27, after the round 1 device walkthrough): the design direction in docs/design-preview/feedback.md items 2–15 overrides the Round 1 layout (grouped card sections, frosted glass surfaces on gradient backgrounds in every theme, progressive disclosure into sub-screens, bottom Cancel | Save pill, animation, "Quiet time" naming, no starting-volume slider; brand colours unchanged; new glass and gradient tokens go into DESIGN.md with contrast checks). Rounds 2–3 follow the same pattern. KEEP: stateless screens, preview menu, wheel picker, edge-to-edge and IME fixes, screenshots and semantics tests.


## Review Triage Log

## Design Notes

- Delivery: the orchestrator dispatches one implementation run per round and stops after each for the owner's on-device feedback; feedback that changes intent is written back here and into EXPERIENCE.md/DESIGN.md before the next round.
- Wheel picker: a `LazyColumn` per wheel with `rememberSnapFlingBehavior`, 3–5 visible items, centre highlighted, large repeated range for an endless feel; semantics with state description and custom increase/decrease actions.
- Environment (company PC): JDK 17 at `C:/Users/BuiTua/AppData/Local/Programs/jdk17/jdk-17.0.20.1+1`. Phone via `adb -s 4d804fdd`; in Git Bash set `MSYS_NO_PATHCONV=1` before adb paths like `/sdcard/...`.

## Verification

**Commands:**
- `./gradlew qualityGate` -- expected: BUILD SUCCESSFUL (each round).
- `ANDROID_SERIAL=4d804fdd ./gradlew :androidApp:installDebug` -- expected: "Yawn & Pawn Preview" icon on the phone.

**Manual checks (if no CLI):**
- On the Oppo A96 each round: walk every preview item; focus the editor label and confirm Save is above the keyboard.
