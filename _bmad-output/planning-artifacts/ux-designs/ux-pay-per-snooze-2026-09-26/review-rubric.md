# Spine Pair Review — Pay Per Snooze

- **Under review:** `ux-pay-per-snooze-2026-09-26/design.md` (single combined file; not yet split into `DESIGN.md` + `EXPERIENCE.md`)
- **Source:** `prds/prd-pay-per-snooze-2026-09-26/prd.md`
- **Lens:** BMAD rubric walker, passes 1–8, plus an accessibility lens (consumer app, used half-asleep)
- **Run at:** 2026-09-26

## Overall verdict

The content is strong for a v0.1 draft. The visual direction is committed and well argued, the colour hex values exist for three themes, the stated contrast ratios check out when recomputed, and nearly every PRD surface has a screen spec. As a **contract for downstream consumers** it is not yet extractable. There are no YAML tokens. There are no Key Flows with protagonists or failure paths. There is no Components or Component Patterns section, so roughly 25 components are specified only in passing inside screen prose. The Sunrise (wake) token set also cannot express the states its own wake screens need: error, success and disabled. The riskiest gaps sit on the money path and on accessibility: an unlock step before payment, per-failure payment outcomes, wake-action target sizes below the file's own 64 dp floor, a fallback check that is not accessible, and outcome colours (success vs snoozed) with identical luminance.

## 1. Flow coverage — thin

Checked: every PRD FR group (ALM, SES, RNG, PWK, SND, PRG, MSG, ONB, SET), the personas (§4) and the product principles (§5) against IA (§8), screen specs (§9) and states (§10). Almost every group has at least one surface. **No group has a Key Flow** with a named protagonist, numbered steps, a climax beat and a failure path.

### Findings
- **critical** No Key Flows section. None of the core journeys is written as a flow: first-run setup, zero-snooze morning, paid snooze with rising fee, grace window expiry, payment failure, fallback check, commitment-lock weakening. The PRD personas (chronic snoozer, routine builder, affected bystander) never appear as protagonists (whole file). *Fix:* add Key Flows to EXPERIENCE.md (proposed list in Split plan). Each flow needs a named protagonist and context, numbered steps, a **Climax** beat and a Failure line.
- **high** The lock-screen unlock step before payment is missing (FR-RNG-3, Spike S1). §8 and §9.6 go straight from Confirm sheet to Google Play sheet, but the ringing screen shows over the lock screen and Play may require an unlocked device. *Fix:* add the keyguard-dismiss step, its copy and a branch for a failed or cancelled unlock that returns to ringing with the alarm still at full volume (FR-RNG-5).
- **high** Payment outcomes collapse into "back to ringing with a snackbar" (§9.6). FR-RNG-4 lists four outcomes: cancel, error, no connection and pending. Only pending has copy (§7). *Fix:* add an outcome table (outcome → message → return state → whether snooze stays offered) and put the pending path into a failure flow.
- **high** The fallback check (FR-PWK-11) is under-specified. §9.7 only offers "Can't do this check?" for camera checks after 5 failed attempts. The PRD also triggers it for camera failure and a lost QR code. No screen shows the backup check (Hard Math ×2 + Memory Hard). There is no surface for the "3 fallbacks in 7 days → re-register" prompt, and fallback use is missing from Progress (§9.10), which the PRD says should show it prominently. *Fix:* write a fallback flow and state rows, and add a fallback marker to Progress and Day detail.
- **high** Sound picker and Recordings have no spec (FR-SND-1/2/3). They are named in the IA (§8) only. Nothing covers built-in sounds vs system ringtones vs user file, preview, recording up to 60 s, re-record, delete, or the microphone permission prompt. *Fix:* add surface specs, component rows and a "Record motivation message" flow.
- **medium** The missed session (FR-ALM-9, 30-minute timeout) has no user-facing surface. Nothing says what Home or Progress shows next time, or whether a notification appears. *Fix:* add a state row and the copy.
- **medium** The Direct Boot fallback (FR-ALM-11) is invisible. Before first unlock, checks switch to Math and the sound to the default, but the wake screen never tells the user why their House Hunt or QR check was swapped. *Fix:* add a one-line notice state on the check screen.
- **medium** Session-integrity surfaces are unspecified. Missing: the non-dismissible ringing notification and Android 14+ heads-up (FR-SES-4), the ringing screen during a phone call (FR-SES-8), merged overlapping alarms (FR-SES-7), and what the user sees after resuming from process death (FR-SES-1). *Fix:* add state rows for the wake surfaces and a notification component row.
- **medium** Several PRD controls have no UI home. Missing: "vibrate in grace" (FR-SES-6), the volume ramp start level (FR-ALM-6, where §9.3 has only a "gradual volume" toggle), the weekly summary toggle and notification content (FR-PRG-5). *Fix:* add them to the editor (§9.3) and Settings (§9.11), and put the summary copy in Voice and Tone.
- **medium** The confirmation for disabling or deleting an alarm under the commitment lock (PRD §6.2) is missing. §9.2 offers long-press delete and a toggle with no confirm step and no "logged" feedback. *Fix:* add a confirm dialog spec and a flow "Weaken or disable an alarm within 8 h".
- **medium** Progress is incomplete. It lacks "average minutes from first ring to up" (FR-PRG-2). Day detail, Purchase history (FR-PRG-4) and Export CSV (FR-PRG-6) are named with no spec: no columns, no share-sheet behaviour, no empty state. *Fix:* add them to the surface specs and state rows.
- **low** Calendar (§9.10) shows a "skipped" outcome, but no surface creates one. FR-ALM-10 is pending Q9. *Fix:* mark the skipped rendering as conditional on Q9.

## 2. Token completeness — broken

Checked: every colour, type, radius and spacing value, and every token reference in the prose. Recomputed every contrast pair in §4.5 and the unlisted pairs the screens use.

### Findings
- **critical** There is no YAML frontmatter token block. All values live in markdown tables with camelCase names (`surfaceVariant`, `onAccent`) and dotted radius names (`r.sm`). Downstream code cannot source-extract them, and the `{path.to.token}` syntax is never used. *Fix:* port the values to `colors:` (kebab-case, with `-dark` / `-sunrise` suffixes), `typography:`, `rounded:`, `spacing:` and `components:`, as in the Split plan.
- **critical** The Sunrise token set (§4.4) is incomplete for the wake screens that use it. It defines no `error`, `success`, `disabled`, `surface-variant` or `accent-text`. Yet wake screens need the wrong-answer error (§6.3, §9.7), the "Matched" success state (§9.7), the disabled Snooze button with its reason (§9.5, FR-RNG-7), the snackbar (§9.6) and the "Alarm's back on" state. *Fix:* complete the Sunrise set and add those pairs to the contrast table.
- **high** No theme has a disabled-state colour token. The disabled Snooze button carries its **reason as its label** (§9.5), so it is load-bearing text. The Material default of 38% opacity would fall far below 4.5:1. *Fix:* add `text-disabled` / `outline-disabled` per theme, with a stated ratio of at least 4.5:1 for the label.
- **high** The contrast table (§4.5) misses load-bearing pairs. All 17 stated ratios recompute correctly, for example Light accent/bg at 3.18 and Sunrise onAccent/accent at 5.57. Missing: the disabled Snooze label; `textSecondary` on `surface` and `surfaceVariant` (about 6.1, passes); Sunrise `textSecondary`/bg (6.57, passes); the accent countdown ring on the Sunrise `surface` card (3.37, passes); and **accent on the gradient top `#FFE3C2` (2.73, fails 3:1)**. *Fix:* add all of these, and either restrict the gradient to areas with no accent elements or darken it.
- **medium** The gradient colour `#FFE3C2` is a raw hex in prose (§4.4), not a token, although §12 bans raw hex. *Fix:* add a `sunrise-gradient-top` token.
- **medium** Home's hero streak uses `accent` as text colour in light mode (§9.2, 3.18:1). The file's own rule says accent-coloured text on light backgrounds uses `accentText` (§4.2). It passes only as large text. *Fix:* use `accent-text` in light mode, or state the large-text exception.
- **medium** Spacing and component sizes are not tokens. The grid values in §3 (4, 20, 24, 16 dp) and sizes in §9 (72, 56, 64, 48 dp, 8 dp ring stroke) are prose. The "I'm up" label at 20 sp is off the type scale, which breaks the file's own "no new font sizes" rule (§12). *Fix:* add `spacing` tokens (`screen-margin`, `section-gap`, `card-padding`, `target-min`, `target-wake`), component tokens for the wake buttons, and a `button-wake` type role.
- **low** `r.full` is set to 50%, while the spec convention is `full: 9999px`. Motion durations (§6.3) have no token home, which is fine if they move to EXPERIENCE.md Interaction Primitives. *Fix:* normalise `full`, and move motion to EXPERIENCE.md.

## 3. Component coverage — broken

Checked: every component named anywhere, against a DESIGN.md Components row (visual spec) and an EXPERIENCE.md Component Patterns row (behaviour spec). Neither section exists. Components named only inline include: I'm up button, Snooze button, Snooze confirm sheet, Countdown ring, Number pad, Memory tile, Letter tile, Viewfinder (with ghost thumbnail), Shutter, Hero card, Alarm card, FAB, Warning banner, Alarm-in-progress panel, Day chips, Check chips, Segmented control, Stepper, Slider, Stat tile, Bar chart, Calendar dot, Checklist row, Settings row, Progress dots, Motivation player, Snackbar, Ringing notification.

### Findings
- **high** No Components section and no Component Patterns table. About 28 components are specified only as scattered fragments in §9. Stories cannot cite a single row for visuals or behaviour. *Fix:* add one row per component in both spines, with real rules rather than one-word descriptions.
- **high** The Snooze button has three unreconciled variants: ringing at 56 dp outlined (§9.5), check footer at 48 dp "smaller" (§9.7), and disabled with its reason (§9.5). The ringing and footer sizes contradict the 64 dp wake-action floor in §3. *Fix:* one component with variants and states, all at least 64 dp on wake screens.
- **medium** Component names drift: "Snooze confirm" (§7) vs "Confirm sheet" (§8); "Check picker" / "Check setup" / "Wake-up checks" (§8, §9.3, §9.4); "Hero card" vs streak card. *Fix:* pick one name each and use it verbatim across both spines.
- **medium** Several components have no behaviour rules. Snackbar: duration and dismissal on a wake screen. Warning banner: dismissibility and when it clears. Motivation player: what happens on "Done" mid-playback. Countdown ring: what happens when Snooze is tapped during grace (FR-RNG-5). *Fix:* write these into Component Patterns.

## 4. State coverage — thin

Checked: every IA surface against empty, cold-load, focus, error, offline and permission-denied states, plus wake-specific states. §10 lists generic states for every screen. Per-surface coverage exists only for Home empty, Progress empty, commitment-lock note, snooze unavailable, payment pending and grace expired.

### Findings
- **high** Camera permission denied or camera unavailable at wake time (House Hunt, QR) has no state. The only way out is the fallback link, which appears after 5 failed attempts. With the camera denied, the user can never make an attempt. That breaks principle 1, "waking up is always free". *Fix:* show fallback immediately when the camera or permission is unavailable, and add a state row.
- **high** The onboarding base-fee step (§9.1.2) needs localized prices from Google Play, and they are only cached after first load (PRD §6.2). First launch while offline has no state. *Fix:* define an offline or price-unavailable state, such as USD tiers labelled "approximate" or a retry, and do not block the rest of onboarding.
- **medium** Wake-surface states are missing: ringing during a phone call, resumed after crash or reboot, Direct Boot fallback, grace expiring while the confirm sheet is open, Snooze tapped during grace (FR-RNG-5), and Play sheet open while the alarm rings. *Fix:* add state rows per wake surface.
- **medium** Per-surface empty and error states are missing for: Purchase history (none yet), Recordings (none, or microphone denied), Sound picker (custom file missing, FR-SND-5), Day detail (skipped or test day), Reliability checklist (all green), and Export CSV (no data). *Fix:* add rows.
- **medium** §10 says "Loading (skeletons)" for every screen, but wake screens must render within 1 s with no skeleton (NFR-7). *Fix:* scope loading rules per surface and state explicitly that wake screens never show a loading state.
- **low** Alarm editor validation is missing: no check selected, one-time alarm set in the past, save attempted during an active session. *Fix:* add error rows.

## 5. Visual reference coverage — strong (vacuous)

Checked: `mockups/`, `wireframes/` and `imports/` in the workspace. None exist, so there are no orphans and no unspecific references. The only diagram is an ASCII IA tree in §8. §0 says "this doc wins" over code, which is analogous to the required "spines win on conflict" line.

### Findings
- **low** There are no visual references for the highest-risk layouts: ringing, confirm sheet and check screens. *Fix:* add wireframes for those three. Link each inline where it applies, and state once that the spines win on conflict.

## 6. Bloat & overspecification — adequate

### Findings
- **medium** §2 "How Taste Skill is used" is tooling and process meta (install decisions, Ralph loop, marketing-site plans). *Fix:* keep the adopted visual rules as Do's and Don'ts, move install and process notes to `.memlog.md` or `.claude/skills/pps-design/SKILL.md`, and move the reference to Inspiration.
- **medium** §12 (pre-flight checklist) and §13 (assets to produce) are process and project-plan material, not spec. *Fix:* move §12 to the pps-design skill or story template, and §13 to epic E9. Keep the visual rules in Do's and Don'ts.
- **low** The research narrative in §4.1 (sleep-inertia citations, blue-light caveat) is more than a consumer needs. *Fix:* one decision line plus the source link.
- **low** The screen specs (§9) use layout prose where component rows and state tables would work, and restate token choices inline (for example "`display`, `text` colour, not accent"). *Fix:* move these into Components and State Patterns once they exist.
- **low** The design dials table (§1) is decorative once the visual rules exist. *Fix:* reduce it to one sentence in Brand & Style.

## 7. Inheritance discipline — thin

### Findings
- **high** There is no `sources` frontmatter. `depends_on: docs/prd.md` points to a duplicate PRD outside the planning artifacts, not the canonical `prds/prd-pay-per-snooze-2026-09-26/prd.md`. `docs/design.md` is also an identical copy of this spec, so there are two sources of truth. *Fix:* add `sources:` with the planning-artifacts path, and remove or redirect the `docs/` copies.
- **high** Section-number cross-references will break on the split. The PRD cites "design.md §7, §9.6" (FR-RNG-2) and "the UX design doc" (FR-MSG-4). The spec itself cites §4.5, §7, §9.9 and §12 internally. *Fix:* use section names, not numbers, and update the PRD references to `EXPERIENCE.md > Voice and Tone` and the relevant Key Flow or component.
- **medium** Copy drifts from the PRD's wording. Mission: PRD "This app makes money only when you snooze." vs spec "We make money only when you snooze." Next alarm: PRD "Rings in 7 h 12 min" vs spec "Next alarm in 7 h 12 min". The weekly summary copy "…$0 paid. Nice." (FR-PRG-5) is missing from §7. The snooze-unavailable reasons "cap reached" and "max snoozes reached" are merged into "limit reached". *Fix:* reconcile, then keep one form verbatim in both documents.
- **medium** Glossary drifts. PRD "proof-of-wake check" vs spec "wake-up checks" / "check"; PRD "Skipped / Test" outcomes vs the spec's calendar ("skipped hollow outline", no Test). Also "Android 12 only" in §9.9 vs the PRD's "Android 12–12L". *Fix:* add a short glossary and use its terms verbatim.
- **low** The spec adds scope the PRD does not have and does not flag it: theme override, the "Bright wake screen" setting and brightness raise, and D2 torch. *Fix:* mark these as design-originated and send them back to the PRD for acknowledgement.

## 8. Shape fit — broken

### Findings
- **critical** This is a single combined file. It is not a spine pair: visual identity, experience rules, screen specs, process checklist and project assets are interleaved. *Fix:* split per the Split plan below.
- **high** EXPERIENCE.md required defaults are missing. **Component Patterns**, **Interaction Primitives** (scattered across §6.3, §9.2 long-press and §9.5 volume and back keys) and **Key Flows** are absent. State Patterns is generic (§10). IA is an ASCII tree, not a Surface / Reached from / Purpose table. *Fix:* create these sections.
- **medium** DESIGN.md order and completeness are off. §6 combines Shapes (6.1) before Elevation (6.2), which inverts the locked order, and bundles Motion, which belongs in EXPERIENCE.md. There is no Do's and Don'ts section; the rules are scattered across §2, §4.1, §6.2, §7 and §12. There is no Components section. *Fix:* reorder to Brand & Style → Colors → Typography → Layout & Spacing → Elevation & Depth → Shapes → Components → Do's and Don'ts.
- **medium** Inspiration & Anti-patterns is triggered but absent. The PRD names reference products (Alarmy; Unsnooze, Paid Alarm Clock, Slooze and others), §4.1 argues against category palettes (sleep-app navy, hard-alarm red), and Taste Skill is an adopted reference. *Fix:* add the section with lifted and rejected items.
- **low** Several sections are invented: §9 Screen specs (valuable content, but it should be split into Components, Component Patterns, State Patterns and Key Flows), §14 Open questions and §15 Sources. Responsive is correctly omitted (single platform; iOS later). *Fix:* keep Open questions as a short trailing section in EXPERIENCE.md, and fold sources into frontmatter or inline links.

## Accessibility lens — thin

Consumer app, used half-asleep, over the lock screen, with a payment one tap away.

### Findings
- **high** The `success` (`#1F7A4D`) and `snoozed` (`#B4472F`) outcome colours have **identical luminance: 1.02:1 against each other** in light mode. In calendar dots and bar charts (§9.10) they cannot be told apart by protan and deutan users or in greyscale. "Tap to see the detail" does not make them readable at a glance, which contradicts §11's "don't rely on colour alone". *Fix:* give each outcome a glyph or shape (for example check, "z", dash, hollow) in dots and in the legend, and state this in Components.
- **high** Wake-action target sizes break the file's own floor. §3 says ringing actions are at least 64 dp tall, but ringing Snooze is 56 dp (§9.5), both confirm buttons are 56 dp (§9.6) and the check-footer Snooze is 48 dp (§9.7). The confirm sheet also rises under the same thumb position, so a repeated tap from ringing can land on "Pay {price} and snooze". *Fix:* make every wake action at least 64 dp, and add a short input lock (about 500 ms) when the confirm sheet opens.
- **high** The fallback check is not an accessible alternative. Hard Math ×2 plus Hard Memory Sequence (FR-PWK-11) is the hardest possible combination. Users with dyscalculia, low vision or TalkBack may have **no guaranteed free way to stop the alarm**, which breaks principle 1 and NFR-9 ("each check type has an accessible alternative or the fallback check"). §11 says the "check picker says so", with no spec. *Fix:* spec the picker warning and an accessible fallback path, or send the question back to the PRD as an open question.
- **medium** At 200% font scale, `clockXL` (88 sp) becomes about 176 sp. §5 requires no clipping, and the wake buttons must stay in the thumb zone without scrolling. *Fix:* cap the clock's scaling (with a content description) and state that wake actions never scroll off screen.
- **medium** Capturing the volume keys (§9.5, FR-SES-6) conflicts with TalkBack's volume-key shortcut and speech volume. TalkBack announcements (§11: every 10 s and at 5 s) are also inaudible while the alarm plays at full volume, since grace mute covers only part of the session. *Fix:* exempt the accessibility shortcut, and specify haptic countdown cues outside the muted window.
- **medium** The fallback link appears only after 5 failed attempts at a camera check (§9.7). Aiming a camera for House Hunt or QR is disproportionately hard for low-vision users. *Fix:* offer the fallback earlier when TalkBack is on, or after one failure caused by the camera being unavailable.
- **low** Snackbars on wake screens (§9.6) have no duration rule. Messages read half-asleep need at least 10 s or to stay until dismissed. *Fix:* state it in Component Patterns.
- **low** The single-letter day chips "M T W T F S S" (§5) need full-day content descriptions ("Tuesday" vs "Thursday"). *Fix:* add this to the Accessibility Floor.
- **low** Maximum brightness plus the bright Sunrise theme at wake (§4.1) may bother photosensitive or migraine users. The setting exists and defaults to on. *Fix:* mention it in onboarding, and respect the system "extra dim" setting.

## Mechanical notes

- **Frontmatter:** `title, version, status, owner, date, depends_on, platform`. It has no `name`, `description` or `sources`, and no tokens. `depends_on` resolves, but to `docs/prd.md`, a duplicate. `docs/design.md` is byte-identical to the spec under review.
- **`.memlog.md`** has only a header. There is no decision log, so the rationale for exceptions (the three-theme exception to the single-theme lock, dynamic colour off) lives only in the spec prose.
- **Token naming** is camelCase (`surfaceVariant`, `onAccent`, `accentText`, `textSecondary`, `outlineSubtle`) and radii are `r.sm`/`r.md`/`r.lg`/`r.full`. They need converting to kebab-case under `colors.` and `rounded.`.
- **Placeholders** such as `{price}`, `{minutes}`, `{nextPrice}`, `{time}`, `{seconds}`, `{streak}` and `{reason}` are string interpolations, not token references. That is fine, but they should be documented as runtime variables so a resolver does not treat them as `{path.to.token}`.
- **Internal cross-references** (§4.5, §7, §9.9, §12) and PRD references (FR-SES-3 in §9.2, FR-ONB-2 in §9.9) all resolve today. They will break on the split unless converted to names.
- **Diagrams:** no Mermaid. The ASCII IA tree in §8 renders correctly but cannot be extracted; convert it to a table.
- **Recomputed contrast:** every ratio in §4.5 matches to within 0.05. New failing pair: accent on the gradient top `#FFE3C2`, 2.73:1.

## Split plan

### DESIGN.md (Google Labs spec order, YAML tokens)

**Frontmatter (new, from existing values):**
- `name: Pay Per Snooze`; `description:` from §1's one sentence.
- `colors:` from §4.2–4.4, in kebab-case with mode suffixes: `bg`, `surface`, `surface-variant`, `outline`, `outline-subtle`, `text`, `text-secondary`, `accent`, `on-accent`, `accent-text`, `success`, `snoozed`, `missed`, `error`, plus `…-dark` and `…-sunrise`. **Missing:** the Sunrise `error`, `success`, `surface-variant`, `accent-text` and `outline-subtle` tokens; `text-disabled` / `outline-disabled` in all three modes; `sunrise-gradient-top: '#FFE3C2'`.
- `typography:` from §5: `clock-xl`, `display`, `headline`, `title`, `body`, `label`, `caption`, each with `fontFamily: Geist`, `fontSize`, `lineHeight`, `fontWeight`, and a note on `tnum`. **Missing:** a `button-wake` role (20 sp), or remove the off-scale size.
- `rounded:` from §6.1: `sm: 8dp`, `md: 16dp`, `lg: 28dp`, `full: 9999px`.
- `spacing:` from §3: `'1': 4dp`, `screen-margin: 20dp`, `section-gap: 24dp`, `card-padding: 16dp`, `target-min: 48dp`, `target-wake: 64dp`.
- `components:` **(missing, new):** `button-wake-primary`, `button-snooze` (plus a disabled variant), `sheet-snooze-confirm`, `countdown-ring`, `number-pad-key`, `memory-tile`, `letter-tile`, `fab`, `card-alarm`, `card-hero`, `banner-warning`, `stat-tile`, `calendar-dot` (with outcome glyphs), `chip-day`, `chip-check`, all written with `{colors.*}` / `{rounded.*}` / `{spacing.*}` references.

**Body, in locked order:**
1. **Brand & Style** ← §1 (direction, personality, three goals; dials cut to one line) plus the visual principles adopted from Taste Skill in §2 (three locks, no AI-purple and so on). Editorial voice is allowed here.
2. **Colors** ← §4.1 (decision and theme strategy as visual rules), the colour story per token, and §4.5 contrast table. **Missing:** disabled pairs, the missing Sunrise state pairs, gradient pairs, and textSecondary on surface or surfaceVariant.
3. **Typography** ← §5 (scale, `tnum`, sentence case, day-chip exception). Font-scaling behaviour is cross-referenced to EXPERIENCE.md. **Missing:** a clock-scaling cap.
4. **Layout & Spacing** ← the grid and padding values in §3, the touch target floors, and the "thumb zone = bottom 40%" definition from §9.
5. **Elevation & Depth** ← §6.2.
6. **Shapes** ← §6.1 ("No other radii").
7. **Components** **(missing, new)** ← visual fragments pulled from §9.2–9.11 (anatomy, sizes, token usage and state appearance for each component listed under the frontmatter `components:` key, plus Stepper, Segmented control, Slider, Viewfinder with ghost thumbnail, Shutter, Bar chart, Checklist row, Settings row, Progress dots, Motivation player, Snackbar, Alarm-in-progress panel). **Missing:** a single Snooze-button spec at 64 dp or more, and outcome glyphs.
8. **Do's and Don'ts** **(missing, new)** ← the rules in §2 (no gradient text, no glows, no pure black, no three-equal-cards), in §4.1 (no green for money, no red for snoozing), the gradient rule in §4.4, §6.2 (no shadows), and the visual items in §12, as a table.

**Leaves DESIGN.md:** §6.3 Motion and the haptics (→ EXPERIENCE.md), §7 (→ EXPERIENCE.md), §8–§11 (→ EXPERIENCE.md), the process parts of §12 and all of §13 (→ pps-design skill / epic E9), and the tooling notes in §2 (→ memlog).

### EXPERIENCE.md

**Frontmatter (new):** `name: Pay Per Snooze`, `status: draft`, `sources: [{planning_artifacts}/prds/prd-pay-per-snooze-2026-09-26/prd.md]`, `updated: 2026-09-26`.

1. **Foundation** ← §3 (Material 3 base, dynamic colour off, Material Symbols Rounded) and the platform line from the frontmatter. From §4.1, theme behaviour: follow the system with a manual override, Sunrise theme on wake screens plus brightness raise, dark as the primary design target. One line from §2 saying Taste Skill is not installed in the app. States that DESIGN.md is the visual reference.
2. **Information Architecture** ← §8, converted to a Surface / Reached from / Purpose table (Onboarding, Alarms, Alarm editor, Check picker, Check setup, House Hunt registration, QR registration, Sound picker, Recordings, Progress, Day detail, Purchase history, Export CSV, Settings, Reliability checklist, and the wake surfaces Ringing, Snooze confirm, Check, Success, Snoozed). Includes the session-lock rule (FR-SES-3). **Missing:** specs for Sound picker, Recordings, Day detail, Purchase history and Export CSV.
3. **Voice and Tone** ← §7 rules, with the key strings table as microcopy in a Do / Don't table. **Missing:** failure copy per payment outcome, missed-session copy, the Direct Boot notice, weekly summary copy, and wording reconciled with the PRD (mission line, "Rings in").
4. **Component Patterns** **(missing, new)** ← behaviour pulled from §9: long-press to delete or duplicate, FAB, base-fee stepper and lock note, no pre-selection on the confirm sheet, disabled Snooze with reason, fallback-link trigger, "Try it" previews, motivation player, snackbar duration, banner lifecycle. One row per component, with names identical to DESIGN.md.
5. **State Patterns** ← §10, plus the per-surface states now in §9 (commitment-lock note in §9.3, alarm in progress in §9.2, grace expired in §9.7, payment pending, snooze unavailable), as a State / Surface / Treatment table. **Missing:** camera denied at wake, offline first-launch prices, phone call, resumed session, Direct Boot, missed session, empty history and recordings, missing sound file, no loading state on wake screens.
6. **Interaction Primitives** **(missing, new)** ← the motion table and reduced-motion rule in §6.3, the haptics bullet, volume and back key capture from §9.5, long-press, the input lock on sheet open (new), and banned patterns (decorative motion, pre-selection, disguised snooze).
7. **Accessibility Floor** ← §11, the 200% font-scale rule from §5, the TalkBack bullet from §10, and a cross-reference to the DESIGN.md contrast table. **Missing:** outcome glyphs, the accessible fallback path, the volume-key and TalkBack exemption, a haptic countdown, day-chip content descriptions, and the clock-scaling cap.
8. **Inspiration & Anti-patterns** **(missing, triggered)** ← Taste Skill (lifted), the category-palette rejection in §4.1, and the PRD's reference products (lifted from Alarmy: missions; rejected: single-mechanism pay-to-snooze apps, casino and shaming tones).
9. **Key Flows** **(missing, new)**, each with a named protagonist taken from the PRD personas, numbered steps, a **Climax** beat and a Failure line:
   - F1 First-run setup and test alarm (chronic snoozer, night before first use), from §9.1 and FR-ONB-1/4.
   - F2 Zero-snooze morning (routine builder): Ringing → I'm up → check in grace → Success with streak. Failure: grace expires and the alarm returns.
   - F3 Paid snooze with rising fee (chronic snoozer): Ringing → Confirm → unlock → Play → Snoozed → re-ring at the higher price. Failure: cancel, error, offline or pending.
   - F4 Grace window expiry with a sleeping partner (bystander context), FR-PWK-9.
   - F5 Fallback check (camera fails or QR lost), FR-PWK-11, including the "re-register" prompt.
   - F6 Weakening change or disable under the commitment lock, PRD §6.2.
   - F7 Review progress and purchase history, then export CSV (FR-PRG-2/4/6).
   - F8 Record a motivation message and attach it to an alarm (FR-SND-3/4).
   - F9 Recover from a revoked permission (FR-ONB-3): banner → checklist → fix.
   - F10 Recovery after an app kill or reboot during a session (FR-SES-1/2, FR-ALM-11).
10. **Open questions** (trailing, invented section that earns its place) ← §14 D1–D3, plus new questions: an accessible fallback, and D2 torch vs photosensitivity.

**Dissolved:** §9 Screen specs are split across DESIGN.md Components, Component Patterns, State Patterns and Key Flows (or kept as a short "Surface notes" appendix if that proves clearer). §12 moves to `.claude/skills/pps-design/SKILL.md`. §13 moves to epic E9. §15 becomes inline links or frontmatter.
