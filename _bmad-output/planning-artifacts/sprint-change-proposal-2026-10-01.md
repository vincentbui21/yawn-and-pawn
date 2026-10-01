---
title: Sprint Change Proposal: approved design preview
date: 2026-10-01
status: approved (owner, 2026-10-01)
mode: batch
scope: moderate (backlog and document edits, no replan)
trigger: owner approval of the whole-app design preview (rounds 1 to 3)
---

# Sprint Change Proposal: approved design preview (2026-10-01)

## 1. Issue summary

After Story 1.8 the owner tried the editor on their phone and asked to see the whole app before more logic was built. A debug "Design preview" was built in three rounds (`spec-design-preview-whole-app.md`, PR #7). Every screen in EXPERIENCE.md now exists as a stateless production composable in `:composeApp` (`ui.<screen>`), driven by fake states. The owner approved all three rounds on the Oppo A96 ("everything is look good", 2026-10-01).

The preview changed the design in ways the PRD, the UX design requirements in `epics.md` and the remaining stories don't reflect yet. The 26 owner decisions are in `docs/design-preview/feedback.md`, and DESIGN.md / EXPERIENCE.md are now v0.5. If the stories stay as written, the auto-run of Epic 1 would build the old design, for example a FAB, a 3-item nav bar, keyboard time entry and a starting-volume slider.

**Type:** a new requirement from the stakeholder (owner design direction), found by a UI review before the logic work.

**Evidence:**
- `docs/design-preview/feedback.md`, items 1 to 26;
- device captures in `docs/design-preview/round-1/` and `round-2/`;
- DESIGN.md / EXPERIENCE.md v0.5;
- `pps-design` rules 11, 14 and 15;
- `deferred-work.md`, which holds two entries that say "remove via correct-course" (FR-PRG-6 export and the FR-PRG-2 chart).

## 2. Impact analysis

### Checklist results

| # | Item | Status | Finding |
|---|---|---|---|
| 1.1 | Trigger | [x] | The design preview (between Stories 1.8 and 1.9), approved 2026-10-01 |
| 1.2 | Problem | [x] | The planning documents describe the pre-preview design |
| 1.3 | Evidence | [x] | See above |
| 2.1 | Current epic | [x] | Epic 1 can finish as planned. Several stories change their UI acceptance criteria, and most get smaller because the screens already exist |
| 2.2 | Epic-level changes | [x] | Epic 1: modify 1.9, 1.14, 1.15, 1.17, 1.18, 1.20 and 1.21, and add notes to 1.10, 1.12 and 1.13. No new epic |
| 2.3 | Remaining epics | [x] | Epics 3, 4, 5 and 6 change UI acceptance criteria. Epic 6 loses Story 6.9. Epics 2, 7 and 8 get small notes |
| 2.4 | Obsolete / new epics | [x] | No epic becomes obsolete or is needed. One story is dropped (6.9 Export CSV) |
| 2.5 | Order | [x] | Unchanged. One sequencing note: Spike 1.5 versus Story 1.11 (§3) |
| 3.1 | PRD | [!] | FR-ALM-6, FR-PRG-2, FR-PRG-6, the FR-SET section note, FR-ONB-5, the Glossary, the cut line and NFR-1 (device matrix) |
| 3.2 | Architecture | [x] | Minor. AD-11 gets the five-slot shell and editor panes. Glass blur uses the platform `RenderEffect` (API 31+), with no new dependency. No data or contract changes |
| 3.3 | UX specs | [x] | Already updated (v0.5). The UX-DR list in `epics.md` must defer to it |
| 3.4 | Other artifacts | [x] | `sprint-status.yaml` (drop 6.9), `deferred-work.md` (assign items), and the `pps-design` skill (already updated). CI and release are unaffected |
| 4.1 | Direct adjustment | Viable | Effort low to medium, risk low |
| 4.2 | Rollback | Not viable | Nothing to roll back. Story 1.8's production editor was upgraded in place by the preview |
| 4.3 | MVP review | Not needed | The MVP is unchanged. One [Could] item (FR-PRG-6) and one chart are dropped |

### Technical impact
- No schema, port or state-machine changes.
- `rampStartPercent` stays in the model as a fixed 20, now read as 20% of the set volume.
- `graceSeconds` keeps its internal name.
- UI stories now wire the existing composables to ViewModels instead of building screens. That makes the auto-run faster, and the result matches what the owner approved.

## 3. Recommended approach

**Direct adjustment (Option 1).** Edit the PRD, the UX-DR inventory and the affected stories as shown in §4, drop Story 6.9, and assign the deferred items. Then auto-run Epic 1.

- **Effort:** about one session of document edits, then the normal story loop.
- **Risk:** low. The design is approved and already built as composables.
- **Timeline:** no delay. Stories 1.9, 1.15, 1.17 and 1.18 get shorter.

**Standing rule added to `epics.md` (new "Design baseline" paragraph under UX Design Requirements):**
> From 2026-10-01 the approved design is DESIGN.md / EXPERIENCE.md v0.5 and the design-preview composables in `:composeApp` (`ui.*`). A UI story wires those stateless screens to its ViewModel and real data. It does not redesign them. Where a UX-DR below disagrees with the v0.5 spines, the spines win. A layout change needs the owner (memory: UI review before build).

**Sequencing note, Spike 1.5 versus Story 1.11:**
- Story 1.11 adds the `UnlockRequested` / `UnlockFailed` rows only "if `docs/spikes/S1.md` decided them".
- Spike 1.5 needs the owner and the phone, and is not part of the auto-run.
- **Proposal:** 1.11 goes ahead without those two rows. If S1 later decides they're needed, they become a small story at the start of Epic 4, which is where the purchase flow lives. Spike 1.5 is done with the owner during the end-of-epic review, now that the Play app and the license tester exist.

**Copy for the auto-run:**
- **Drafts:** approving this proposal also approves the drafts in `docs/design-preview/copy-to-approve.md` (rounds 1 to 3), unless the owner marks one. The owner already approved those screens as they look.
- **Two new storage-failure strings** (deferred from 1.8): Home list "Couldn't load your alarms." with the action "Try again", and the editor snackbar "Couldn't open this alarm.". Both are short, have no em dash and no blame.

## 4. Detailed change proposals

### 4.1 PRD (`prds/prd-pay-per-snooze-2026-09-26/prd.md`, version 0.2 → 0.3)

**FR-ALM-6**
- OLD: "...when on, volume ramps from a user-set start level to the set level over ≤ 30 s..."
- NEW: "...when on, volume ramps from 20% of the set level to the set level over 30 s (fixed, not user-editable; owner decision 2026-09-27)..."
- Rationale: feedback item 3, no starting-volume slider.

**FR-PRG-2**
- OLD bullet: "Snoozes per week (chart)."
- NEW bullet: "Snoozes over the last 30 days (a count; each snoozed morning is marked on the 30-morning ring)."
- Add a line: "Layout and motion follow EXPERIENCE.md (ring of the last 30 mornings, stat tiles, streak card, money and insight; no period tabs)."
- Rationale: feedback item 24. The ring already shows each snoozed day.

**FR-PRG-6** [Could] "Export history as CSV."
- NEW: struck through and marked "Removed (owner decision 2026-10-01)".
- **Cut line:** remove "FR-PRG-6 CSV export" from step (1).
- Rationale: feedback item 23.

**Glossary, "Grace window"**
- Add: "Shown to users as **Quiet time** (editor row, sub-screen, 'Vibrate during quiet time', Settings 'Default quiet time'). 'Grace window' remains the internal and spec term."
- Rationale: feedback item 2.

**§7.8 Settings (SET), note under the heading**
- Add: "FR-SET-3, FR-SET-4 and FR-SET-5, together with Purchase history (FR-PRG-4), are reached from the **You** tab, not Settings. Settings keeps app behaviour only: Snooze, Wake, Appearance, Notifications, Usage stats and the Reliability checklist. There is still no account or sign-in (NFR-4)."
- **FR-SET-1:** "default grace window" → "default grace window (Quiet time)".
- **FR-ONB-5:** "Also shown in Settings → 'How payments & refunds work'" → "Also shown under You → 'How payments & refunds work'."
- Rationale: feedback item 26.

**NFR-1 device matrix** (and its copy in the `epics.md` Requirements Inventory)
- OLD: "the owner's Samsung Galaxy A57 (One UI) plus Gradle Managed Device emulators..."
- NEW: "the owner's Oppo A96 (ColorOS, Android 13) plus Gradle Managed Device emulators..."
- Rationale: the owner's test phone is the Oppo A96.

**Changelog row:** 0.3, 2026-10-01, design preview decisions (sprint-change-proposal-2026-10-01).

### 4.2 Epics: UX design requirements inventory (`epics.md`)

Insert the "Design baseline" paragraph (§3), then amend these rows:

| UX-DR | OLD (short) | NEW (short) |
|---|---|---|
| DR6 | Elevation flat, surfaces separated by tone | Every screen draws the theme gradient (`PpsBackground`). Cards are `glass`. Bars and sheets over moving content are `glass-strong` with a hairline `glass-edge`, and are blurred only on API 31+. No decorative shadows |
| DR9 | Sunrise gradient only in the top 40% of Ringing | Ringing follows the DESIGN.md v0.5 `ringing` row (the preview composable); accent never sits on the gradient |
| DR30 | `card-hero`, not tappable | Hero inside the **collapsing Home header**: "Yawn & Pawn" pinned top-left with a glass chip once content scrolls under it; the hero collapses into a compact pinned row as you scroll, tied to scroll position. Reduced motion makes it an instant switch |
| DR31 | `card-alarm` on `surface` | `card-alarm` is glass. Repeat summaries add "Weekdays" (Mon–Fri) and "Weekends" (Sat–Sun) |
| DR32 | `fab` bottom-right | Removed. The raised accent "+" in the centre of the nav capsule ("Add alarm", 56 dp) creates an alarm from any tab |
| DR40 | Slider used for grace, volume and ramp start level | Slider for Quiet time (15–30 s) and volume only; no ramp start level |
| DR42 | `time-picker`: M3 time input, keyboard first | Scrolling wheel (hour, minute, AM/PM on 12 h phones), snap, a haptic tick and a quiet bundled tick sound per value, no keyboard ever. TalkBack adjustable "Hour, 6" |
| DR43 | `top-app-bar` | Sub-screens have a back arrow and a pinned title. Save screens use the `SaveCancelPill` in its own bottom area, above the keyboard, never over content |
| DR44 / DR59 | M3 nav bar, 3 items | Floating glass capsule with five slots: Alarms · Progress · (+) · Settings · You. Selected tab in accent with a filled icon. Animated (instant with reduced motion). Hidden during the session lock and on sub-screens |
| DR46 | `stat-tile`, two per row, streaks and rates | Three small tiles in one row ("on time", "to get up", "snoozes"), 2 + 1 at large font. The streaks move to the ring and `card-streak` |
| DR47 | `bar-chart` | Removed |
| DR48 | `outcome-marker` with a legend | Shape plus colour (filled dot, clock dot, hollow ring, small neutral dot, outlined pill for today). No legend. A label chip on tap. TalkBack reads "{date}, {outcome}" |
| DR60 | Surface list incl. Export CSV; editor bottom bar "Test alarm" + "Save" | Remove Export CSV, add **You**. Editor: grouped cards with rows that open sub-screens (Sound, Snooze, Wake-up check, Quiet time, Motivation), "Test alarm" as a text button under the cards, Cancel \| Save pill |
| DR71 | Motion: success 600 ms scale only | Plus `pps-design` rule 11: screen and sub-screen slides, expand and collapse, chip and switch states, cards animating in and out, the collapsing header, the "I'm up" pulse, the wheel tick, Progress entry, the Day detail timeline drawing in. On-time Success: count-up from n−1 with a bounce, about 1.5 s of confetti and one success haptic. After-snooze Success has no animation |
| DR80 | Includes "Export no data" | Remove that state. Add the two storage-failure strings (§3) |
| DR84 | Success "Up on time. {n} days in a row." | On-time Success shows the big number, then "days in a row", then "Up on time." (no repeated number) |
| DR85 | Glossary "Grace window" | UI says "Quiet time"; specs may say grace window |
| DR87 | F2 copy | As DR84 |
| DR94 | F9 ends with Export CSV | F9 ends at Purchase history |

### 4.3 Epic 1 stories (auto-run next)

**Story 1.9 Alarm list on Home**
- OLD AC 1: "...each alarm is a `card-alarm` (`surface`, `rounded.md`)... repeat summary 'Every day', 'Once' or locale short day names... **And** ... the `fab` stays bottom-right 20 dp from the edges"
- NEW AC 1: "...each alarm is the preview's `card-alarm` (glass), sorted by time of day... repeat summary 'Every day', 'Weekdays', 'Weekends', 'Once' or short day names. **And** Home is the Alarms tab of `AppShell`, the floating glass nav capsule (Alarms · Progress · + · Settings · You); '+' opens the editor with defaults; the production FAB is removed. **And** the Home header collapses on scroll ('Yawn & Pawn' pinned, glass chip under it) with the next-alarm countdown in it (the streak hero joins at Story 6.3); reduced motion makes it instant. **And** Progress, Settings and You open their existing screens in their empty or default state, with rows whose stories are not done hidden (no dead links)."
- Add AC: "A storage read failure shows 'Couldn't load your alarms.' with 'Try again' instead of the list; an editor load failure closes the editor with the snackbar 'Couldn't open this alarm.'" (deferred item from 1.8)
- Add AC: "Alarm cards animate in and out on add and delete."
- Refs add UX-DR44, UX-DR59.
- Rationale: feedback items 7, 18 and 26, and the deferred storage-copy item.

**Story 1.10 Schedule alarms exactly**
- Add AC: "Request codes are never reused. A persisted high-water mark in `app.db` gives each new alarm max(ever used) + 1 (test: delete the highest, create, the new code is higher)."
- Add AC: "After a reboot, `rescheduleAll()` uses the wall clock as AD-3 says. The reboot-before-network-time case is recorded in `docs/decisions/reboot-clock.md` and carried to Story 2.2. No extra logic here."
- Add AC: "A Robolectric test opens `app.db` with credential storage locked (device-protected context only)."
- Rationale: three deferred items.

**Story 1.12 SessionEngine**
- Add AC: "Robolectric tests no longer each call `stopKoin()`. A test Application or shared rule starts and stops Koin."
- Rationale: deferred item from 1.1.

**Story 1.13 Record every session**
- Add AC: "`docs/decisions/db-downgrade.md` states the policy for restoring a newer-schema `app.db` on an older install. Proposed: the backup agent skips restoring an `app.db` whose version is above the installed schema, and logs it."
- Rationale: deferred item from 1.7.

**Story 1.14 Ring the alarm**
- OLD: "player gain ramps linearly from `rampStartPercent` to full over 30 s using a pure `rampGain(elapsed, start, duration)` ... (unit-tested at 0 s, 15 s, 30 s, 45 s and with start = 100%)"
- NEW: "player gain ramps linearly from `rampStartPercent`% **of the set volume** (fixed 20) to the set volume over 30 s using a pure `rampGain(elapsed, startFraction, duration)` in core (unit-tested at 0 s, 15 s, 30 s and 45 s); the `AlarmValidation` rule 'ramp start must not exceed volume' and the editor's min(20, volume) are removed"
- Rationale: feedback item 3 and the deferred ramp item.

**Story 1.15 Ringing screen**
- OLD AC 1: "...optional `sunrise-gradient-top` → `bg-sunrise` gradient only in the top 40%... flat `bg-sunrise` thumb zone"
- NEW AC 1: "`WakeActivity` renders the preview's `ui/wake` Ringing composable from `SessionEngine.state` (Sunrise, layout per DESIGN.md v0.5 `ringing`); a gentle pulse on 'I'm up' that stops with reduced motion"
- Keep the 72 dp, 64 dp, snooze, TalkBack and timing ACs.
- Add AC: "The disabled snooze uses the `disabled-container-sunrise` / `disabled-content-sunrise` pair explicitly (not Material alpha)."
- Rationale: feedback item 12, and the deferred item from 1.3.

**Story 1.17 Built-in sound library**
- OLD: "the Sound picker (pushed screen, `top-app-bar`) lists built-in sounds then system ringtones as `sound-row`s..."
- NEW: "the editor's Sound sub-screen (the preview's `ui/sound`) shows the volume slider and the 'Gradually increase volume' switch, then sectioned lists Built-in and System (Your files arrives in Story 7.4), each row with radio selection, name, and a 48 dp preview button"
- Change the loudness gate: "`checkSoundLoudness` measures alarm sounds only (`res/raw/alarm_*`); UI sounds such as `wheel_tick.wav` are listed as exempt in the task."
- Rationale: feedback item 11, and the deferred wheel-tick item.

**Story 1.18 Test alarm**
- OLD: "the bottom bar ... contains a `button-text` 'Test alarm' next to 'Save'"
- NEW: "'Test alarm' is a `button-text` under the editor's cards; Save and Cancel stay in the `SaveCancelPill`"
- Screenshot AC: "editor bottom bar" → "editor with the Test alarm button".
- Add: "the release-content test also fails if any `debug.preview` or `ThemeShowcase` class, activity or the 'Yawn & Pawn Preview' label is present" (deferred item from 1.3).
- Rationale: feedback item 10.

**Story 1.20 Spike S2 and Story 1.21 Epic 1 checklist**
- OLD device matrix: "a Pixel, a Samsung, a Xiaomi and one budget device"
- NEW: "the owner's Oppo A96 (ColorOS, Android 13), plus GMD emulators for the API levels in NFR-1; other makers optional via Firebase Test Lab"
- 1.21 item 4: "ramps from the starting volume" → "ramps from 20% of the set volume".
- 1.21 new items:
  - 17. the time wheel ticks (haptic and quiet sound) and is silent when the phone is on silent;
  - 18. Save stays above the keyboard and the pill never covers content (uiautomator bounds, the deferred check);
  - 19. the app draws edge-to-edge with correct status-bar icons in Light, Dark and Sunrise;
  - 20. the nav capsule, "+" from every tab and the collapsing header;
  - 21. predictive back on editor sub-screens looks right (if not, sub-screens become Nav 3 routes in a bug story, which is the deferred item).

### 4.4 Later epics (applied now so the plan is consistent; each story still gets its own spec when it comes up)

| Story | Change |
|---|---|
| 2.6 Session lock | The lock covers all five tabs; the nav capsule is hidden while it shows |
| 3.3 Success (basic) | On-time copy as DR84 (number / "days in a row" / "Up on time."), no animation yet |
| 3.4 Grace window | Title → "Quiet time (grace window) with the countdown ring"; UI copy says Quiet time; the editor's Quiet time sub-screen (15–30 s slider, "Vibrate during quiet time") |
| 3.5 Choose the checks | Through the editor's Wake-up check sub-screen, Check picker and Check setup (preview round 3; difficulty lives in Check setup) |
| 4.5 Snooze settings | Base fee and Max snoozes are Settings sub-screens with the `stepper` |
| 4.6 Fee ladder | The ladder shows on the editor's Snooze sub-screen and the Settings Base fee sub-screen |
| 4.16 Purchase history, 4.17 Problem with a charge | Reached from the You tab |
| 5.1 Settings defaults | Grouped cards, rows open sub-screens; "Default quiet time" |
| 5.5 Manufacturer guidance | Add Oppo / ColorOS steps (EXPERIENCE.md has none yet; the owner's phone) |
| 5.8 Payments, privacy, terms, support | Title "...in the You tab"; rows live on You |
| 5.9 Delete all data | On You, with the preview's dialog |
| 5.10 to 5.12 Onboarding | Use the round 3 screens (progress dots, shared time wheel and repeat cards, ChecklistCard, equal "Share" / "No thanks") |
| 6.2 Stats in core | Snooze count over the last 30 days (no weekly series for a chart; weekly totals stay for 6.8); add the Insight rule (one short data-based line, or hidden when data is thin) |
| 6.3 Hero on Home | The hero fills the collapsing header from 1.9 |
| 6.4 Celebration | Count-up and bounce, about 1.5 s of confetti, one success haptic; reduced motion shows the final state |
| 6.5 Progress screen | Title → "Progress screen with the 30-morning ring, stat tiles and money"; no heading, no legend, no chart; ring, three tiles, `card-streak`, Money paid plus Insight; entry animation |
| 6.6 Calendar | Shape-coded dots, today pill, label chip on first tap, Day detail on the second |
| 6.7 Day detail | One-line title, hero, three tiles, morning timeline drawing in |
| 6.9 Export history as CSV | **Dropped** (FR-PRG-6 removed) |
| 6.10 Epic 6 checklist | Remove export items; add the ring, chip and timeline items |
| 7.2 Recordings | Use round 3 `ui/recordings` and the editor's Motivation sub-screen |
| 7.4 Own audio file | Adds the "Your files" section of the Sound sub-screen |
| 8.2 Store screenshots | May be captured from the preview deep links with demo data, or from the real app; the store name is "Yawn & Pawn" |

### 4.5 Architecture (`ARCHITECTURE-SPINE.md`, AD-11 note)
- Add: "The main shell has four tab routes (Alarms, Progress, Settings, You). The centre '+' is an action that pushes the editor route, not a tab. Editor sub-screens are in-screen pane state of the editor ViewModel, not routes (revisit only if Story 1.21 item 21 fails). Glass blur uses the platform `RenderEffect` on API 31+, with no blur dependency."

### 4.6 Tracking files
- **`sprint-status.yaml`:**
  - `6-9-export-history-as-csv: dropped  # FR-PRG-6 removed, sprint-change-proposal-2026-10-01`;
  - story keys stay as they are, so no files are renamed.
- **`deferred-work.md`:** each item gets a `status:` line pointing to its new home, listed below.

| Deferred item | Goes to |
|---|---|
| NoPrintlnInCore through real config | Parked for the Epic 1 retrospective (low) |
| Lint severity, backup rules, icon | Backup rules: 1.12 (already there). Icon: 8.2. Lint config: retrospective |
| Robolectric on SDK 36 (needs JDK 21) | Retrospective (a portable JDK 21 is possible without admin) |
| stopKoin trap | 1.12 |
| Release workflow end to end | 8.7, after the Play service account exists |
| Disabled token pair | 1.15 |
| No debug code in release | 1.18 |
| Reboot wall clock | 1.10 (record) → 2.2 |
| Request-code reuse | 1.10 |
| Downgrade policy | 1.13 |
| app.db before first unlock | 1.10 |
| Storage failure copy | 1.9 (strings in §3) |
| Save above the keyboard, edge-to-edge (device confirmation) | 1.21 items 18 and 19 |
| Ramp start semantics | 1.14 |
| Blur only on bars and sheets | Kept as designed; the owner may revisit |
| Editor sub-screens as pane state | 1.21 item 21 |
| Wheel tick loudness exemption | 1.17 |
| FR-PRG-6 export, FR-PRG-2 chart | Resolved by this proposal |

## 5. Implementation handoff

**Scope: moderate.** The backlog and documents change, with no replan.

| Who | What |
|---|---|
| Developer agent (this session) | After approval: apply §4.1 to §4.6 in one commit on a branch, as a PR to main through CI, after PR #7 is merged |
| Developer agent, auto-run | `bmad-build-auto` for 1.9, 1.10, 1.11 (without the S1 rows), 1.12, 1.13, 1.14, 1.15, 1.16, 1.17, 1.18 and 1.19, in order, one PR each, stopping only for real blockers |
| Owner, end of Epic 1 | Install the build. Do Spike 1.5 (licence-test snooze over the lock screen), Spike 1.20 and checklist 1.21 on the Oppo A96. Review in one sitting |

**Success criteria:**
- The PRD is v0.3 and the epics have the design baseline paragraph, with no story still asking for a FAB, a 3-tab nav bar, keyboard time entry, a ramp start slider, the bar chart, a legend or CSV export.
- `sprint-status.yaml` and `deferred-work.md` are consistent.
- Each Epic 1 UI story ships the approved composables, wired to real data, with `qualityGate` and CI green.

## Approval

Approved by the owner on 2026-10-01 ("it look good ... everything else is good"). Applied in branch docs/correct-course-2026-10-01.
