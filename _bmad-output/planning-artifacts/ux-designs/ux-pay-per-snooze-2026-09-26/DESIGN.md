---
name: Yawn & Pawn
description: A calm, warm night-time alarm app that turns into a bright sunrise the moment the alarm rings.
status: draft
version: 0.2
owner: Kiet Bui
updated: 2026-09-26
sources:
  - _bmad-output/planning-artifacts/prds/prd-pay-per-snooze-2026-09-26/prd.md
  - EXPERIENCE.md
colors:
  # Light theme (app screens, system light or manual override). No suffix.
  bg: '#FAF8F5'
  surface: '#FFFFFF'
  surface-variant: '#F1EEE9'
  outline: '#8A837A'
  outline-subtle: '#E4DED6'
  text: '#1A1714'
  text-secondary: '#5E5850'
  accent: '#D96F14'
  on-accent: '#1A1004'
  accent-text: '#A8520A'
  success: '#0F5534'
  snoozed: '#B4472F'
  missed: '#6E675F'
  error: '#B3261E'
  disabled-container: '#EAE5DE'
  disabled-content: '#5F5951'
  inverse-surface: '#1A1714'
  inverse-text: '#FAF8F5'
  inverse-accent: '#F5A04E'
  # Dark theme (primary design target for app screens).
  bg-dark: '#111214'
  surface-dark: '#1A1B1E'
  surface-variant-dark: '#24262A'
  outline-dark: '#6B6D73'
  outline-subtle-dark: '#2E3035'
  text-dark: '#F2EFEA'
  text-secondary-dark: '#A9A39A'
  accent-dark: '#F5A04E'
  on-accent-dark: '#1A1004'
  accent-text-dark: '#F5A04E'
  success-dark: '#8BE0B2'
  snoozed-dark: '#E58A72'
  missed-dark: '#8E8880'
  error-dark: '#F2B8B5'
  disabled-container-dark: '#27292D'
  disabled-content-dark: '#A39D94'
  inverse-surface-dark: '#F2EFEA'
  inverse-text-dark: '#111214'
  inverse-accent-dark: '#A8520A'
  # Sunrise theme (wake screens only: ringing, snooze confirm, checks, success, snoozed).
  bg-sunrise: '#FFF6EA'
  surface-sunrise: '#FFFFFF'
  surface-variant-sunrise: '#F8ECDD'
  outline-sunrise: '#8A837A'
  outline-subtle-sunrise: '#EBDDCB'
  text-sunrise: '#1A1714'
  text-secondary-sunrise: '#5E5850'
  accent-sunrise: '#D96F14'
  on-accent-sunrise: '#1A1004'
  accent-text-sunrise: '#A8520A'
  success-sunrise: '#0F5534'
  error-sunrise: '#B3261E'
  disabled-container-sunrise: '#F2E6D7'
  disabled-content-sunrise: '#5F5951'
  inverse-surface-sunrise: '#1A1714'
  inverse-text-sunrise: '#FFF6EA'
  sunrise-gradient-top: '#FFE3C2'
typography:
  clock-xl:
    fontFamily: Geist
    fontSize: 88sp
    lineHeight: 92sp
    fontWeight: 300
    note: 'tnum on. Font scaling capped at 1.3x (max 114sp).'
  display:
    fontFamily: Geist
    fontSize: 48sp
    lineHeight: 52sp
    fontWeight: 500
    note: 'tnum on for numbers (streak, price, alarm time).'
  headline:
    fontFamily: Geist
    fontSize: 28sp
    lineHeight: 34sp
    fontWeight: 600
  title:
    fontFamily: Geist
    fontSize: 20sp
    lineHeight: 26sp
    fontWeight: 600
    note: 'tnum on for alarm times in lists.'
  button-wake:
    fontFamily: Geist
    fontSize: 20sp
    lineHeight: 24sp
    fontWeight: 500
    note: 'Wake-screen action labels only. tnum on (prices).'
  body:
    fontFamily: Geist
    fontSize: 16sp
    lineHeight: 24sp
    fontWeight: 400
  label:
    fontFamily: Geist
    fontSize: 14sp
    lineHeight: 20sp
    fontWeight: 500
  caption:
    fontFamily: Geist
    fontSize: 12sp
    lineHeight: 16sp
    fontWeight: 400
rounded:
  sm: 8dp
  md: 16dp
  lg: 28dp
  full: 9999px
spacing:
  '1': 4dp
  '2': 8dp
  '3': 12dp
  '4': 16dp
  '5': 20dp
  '6': 24dp
  '8': 32dp
  screen-margin: 20dp
  section-gap: 24dp
  card-padding: 16dp
  target-min: 48dp
  target-wake: 64dp
  target-wake-hero: 72dp
  ring-stroke: 8dp
  thumb-zone: 'bottom 40% of screen height'
components:
  # App-screen components reference light token names; Dark resolves the same role
  # with the -dark suffix. Wake components reference -sunrise tokens directly.
  button-wake-primary:
    backgroundColor: '{colors.accent-sunrise}'
    textColor: '{colors.on-accent-sunrise}'
    typography: '{typography.button-wake}'
    rounded: '{rounded.full}'
    height: '{spacing.target-wake-hero}'
    width: fill
  button-snooze:
    backgroundColor: transparent
    borderColor: '{colors.outline-sunrise}'
    borderWidth: 1dp
    textColor: '{colors.text-sunrise}'
    typography: '{typography.button-wake}'
    rounded: '{rounded.full}'
    height: '{spacing.target-wake}'
    width: fill
  button-snooze-disabled:
    backgroundColor: '{colors.disabled-container-sunrise}'
    borderColor: none
    textColor: '{colors.disabled-content-sunrise}'
    icon: 'block (Material Symbols Rounded); lock before first unlock'
    typography: '{typography.button-wake}'
    rounded: '{rounded.full}'
    height: '{spacing.target-wake}'
  sheet-snooze-confirm:
    backgroundColor: '{colors.surface-sunrise}'
    textColor: '{colors.text-sunrise}'
    rounded: '{rounded.lg}'
    priceTypography: '{typography.display}'
    priceColor: '{colors.text-sunrise}'
    padding: '{spacing.6}'
    buttonHeight: '{spacing.target-wake}'
  button-filled:
    backgroundColor: '{colors.accent}'
    textColor: '{colors.on-accent}'
    typography: '{typography.label}'
    rounded: '{rounded.full}'
    height: '{spacing.target-min}'
  button-outlined:
    backgroundColor: transparent
    borderColor: '{colors.outline}'
    textColor: '{colors.text}'
    typography: '{typography.label}'
    rounded: '{rounded.full}'
    height: '{spacing.target-min}'
  button-text:
    textColor: '{colors.accent-text}'
    typography: '{typography.label}'
    height: '{spacing.target-min}'
  countdown-ring:
    strokeColor: '{colors.accent-sunrise}'
    trackColor: '{colors.outline-subtle-sunrise}'
    strokeWidth: '{spacing.ring-stroke}'
    size: 120dp
    numberTypography: '{typography.display}'
    expiredIconColor: '{colors.text-sunrise}'
  number-pad-key:
    backgroundColor: '{colors.surface-variant-sunrise}'
    textColor: '{colors.text-sunrise}'
    typography: '{typography.title}'
    rounded: '{rounded.md}'
    size: '{spacing.target-wake}'
  memory-tile:
    backgroundColor: '{colors.surface-sunrise}'
    borderColor: '{colors.outline-sunrise}'
    litColor: '{colors.accent-sunrise}'
    litContentColor: '{colors.on-accent-sunrise}'
    rounded: '{rounded.md}'
    minSize: '{spacing.target-wake}'
  letter-tile:
    backgroundColor: '{colors.surface-variant-sunrise}'
    borderColor: '{colors.outline-sunrise}'
    textColor: '{colors.text-sunrise}'
    typography: '{typography.title}'
    rounded: '{rounded.sm}'
    size: '{spacing.target-min}'
  text-field:
    backgroundColor: '{colors.surface-variant}'
    borderColor: '{colors.outline}'
    errorColor: '{colors.error}'
    textColor: '{colors.text}'
    rounded: '{rounded.sm}'
  viewfinder:
    frameColor: '{colors.surface-sunrise}'
    rounded: '{rounded.md}'
    ghostThumbnailSize: 72dp
    ghostThumbnailRounded: '{rounded.sm}'
  shutter:
    backgroundColor: '{colors.accent-sunrise}'
    iconColor: '{colors.on-accent-sunrise}'
    rounded: '{rounded.full}'
    size: '{spacing.target-wake-hero}'
  fallback-link:
    textColor: '{colors.accent-text-sunrise}'
    typography: '{typography.body}'
    height: '{spacing.target-min}'
  check-type-card:
    backgroundColor: '{colors.surface}'
    selectedBorderColor: '{colors.accent}'
    textColor: '{colors.text}'
    rounded: '{rounded.md}'
    padding: '{spacing.card-padding}'
  card-hero:
    backgroundColor: '{colors.surface}'
    numberColor: '{colors.accent-text}'
    numberColorDark: '{colors.accent-dark}'
    numberTypography: '{typography.display}'
    rounded: '{rounded.md}'
    padding: '{spacing.card-padding}'
  card-alarm:
    backgroundColor: '{colors.surface}'
    textColor: '{colors.text}'
    timeTypography: '{typography.title}'
    rounded: '{rounded.md}'
    padding: '{spacing.card-padding}'
  fab:
    backgroundColor: '{colors.accent}'
    iconColor: '{colors.on-accent}'
    rounded: '{rounded.full}'
    size: 56dp
  banner-warning:
    backgroundColor: '{colors.surface-variant}'
    iconColor: '{colors.error}'
    infoIconColor: '{colors.text-secondary}'
    textColor: '{colors.text}'
    rounded: '{rounded.md}'
  panel-session-in-progress:
    backgroundColor: '{colors.surface}'
    textColor: '{colors.text}'
    rounded: '{rounded.md}'
  note-inline:
    iconColor: '{colors.text-secondary}'
    textColor: '{colors.text-secondary}'
    typography: '{typography.caption}'
  chip-day:
    backgroundColor: '{colors.surface-variant}'
    selectedColor: '{colors.accent}'
    selectedContentColor: '{colors.on-accent}'
    rounded: '{rounded.sm}'
    size: '{spacing.target-min}'
  chip-check:
    backgroundColor: '{colors.surface-variant}'
    borderColor: '{colors.outline}'
    textColor: '{colors.text}'
    rounded: '{rounded.sm}'
    height: '{spacing.target-min}'
  segmented-control:
    borderColor: '{colors.outline}'
    selectedColor: '{colors.accent}'
    selectedContentColor: '{colors.on-accent}'
    rounded: '{rounded.full}'
    height: '{spacing.target-min}'
  stepper:
    valueTypography: '{typography.display}'
    buttonRounded: '{rounded.full}'
    buttonSize: '{spacing.target-min}'
  slider:
    activeColor: '{colors.accent}'
    trackColor: '{colors.outline}'
  switch:
    checkedTrackColor: '{colors.accent}'
    uncheckedBorderColor: '{colors.outline}'
  time-picker:
    note: 'Material 3 time input, styled with theme tokens; digits in {typography.display}.'
  top-app-bar:
    backgroundColor: '{colors.bg}'
    textColor: '{colors.text}'
    typography: '{typography.headline}'
  nav-bar:
    backgroundColor: '{colors.surface}'
    selectedIconColor: '{colors.accent-text}'
    iconColor: '{colors.text-secondary}'
  progress-dots:
    activeColor: '{colors.accent}'
    inactiveColor: '{colors.outline}'
    size: 8dp
  stat-tile:
    backgroundColor: '{colors.surface}'
    numberTypography: '{typography.display}'
    numberColor: '{colors.text}'
    rounded: '{rounded.md}'
  bar-chart:
    barColor: '{colors.snoozed}'
    axisColor: '{colors.outline}'
    labelColor: '{colors.text-secondary}'
    barRounded: '{rounded.sm}'
  outcome-marker:
    onTime: 'check_circle, fill 1, {colors.success}'
    snoozed: 'schedule (clock), fill 1, {colors.snoozed}'
    missed: 'cancel (cross in circle), fill 1, {colors.missed}'
    skippedOrTest: 'radio_button_unchecked (hollow ring), {colors.outline}'
    fallbackBadge: 'alt_route, {colors.text-secondary}'
    size: 20dp
  calendar-day:
    textColor: '{colors.text}'
    todayBorderColor: '{colors.accent}'
    marker: '{components.outcome-marker}'
    size: '{spacing.target-min}'
  checklist-row:
    okIcon: 'check_circle, {colors.success}'
    problemIcon: 'error, {colors.error}'
    height: 64dp
  settings-row:
    textColor: '{colors.text}'
    valueColor: '{colors.text-secondary}'
    height: 56dp
  purchase-row:
    textColor: '{colors.text}'
    priceColor: '{colors.text}'
    metaColor: '{colors.text-secondary}'
    height: 64dp
  sound-row:
    textColor: '{colors.text}'
    selectedIconColor: '{colors.accent-text}'
    height: 56dp
  recorder:
    recordButtonColor: '{colors.accent}'
    recordIconColor: '{colors.on-accent}'
    timerTypography: '{typography.display}'
    size: '{spacing.target-wake-hero}'
  motivation-player:
    backgroundColor: '{colors.surface-sunrise}'
    iconColor: '{colors.text-sunrise}'
    rounded: '{rounded.md}'
    buttonSize: '{spacing.target-min}'
  dialog-confirm:
    backgroundColor: '{colors.surface}'
    textColor: '{colors.text}'
    rounded: '{rounded.lg}'
  snackbar:
    backgroundColor: '{colors.inverse-surface}'
    textColor: '{colors.inverse-text}'
    actionColor: '{colors.inverse-accent}'
    rounded: '{rounded.sm}'
  notification-ringing:
    note: 'System notification. Small icon monochrome; accent tint {colors.accent}; full-screen intent.'
  notification-summary:
    note: 'System notification. Small icon monochrome; no accent tint.'
  skeleton:
    color: '{colors.surface-variant}'
    rounded: '{rounded.sm}'
---

# Yawn & Pawn — DESIGN.md

The visual identity contract. How the app behaves, speaks and flows lives in `EXPERIENCE.md`. **Both spines win on conflict with any mock, wireframe, screenshot or import.** Values in the frontmatter are the source of truth; prose explains them.

## Brand & Style

A calm, warm night-time app that turns into a bright sunrise the moment the alarm rings. The personality is a supportive coach: not a drill sergeant, not a casino. Clear, short, warm, a little dry. Never shaming, never celebrating payments.

Three design goals drive every visual decision:

1. **Waking up is the hero.** The biggest, brightest thing on any wake screen is "I'm up". The biggest number on Home is the zero-snooze streak.
2. **Money is honest and quiet.** Prices are always visible and plain, in `{colors.text}`. Money is never coloured like a reward (no green) and never dramatized with red.
3. **Built for half-asleep hands.** Big targets in the thumb zone, high contrast, nothing clever to decode at 6 a.m.

Taste dials (adapted from Taste Skill): design variance 3/10 (predictable layouts; a utility used asleep should not surprise), motion intensity 4/10 (motion only for feedback and state change), visual density 4/10 (airy; one main job per screen).

Three locks hold the look together: **one accent colour** (Sunrise orange), **one corner-radius system** (8 / 16 / 28 / full), **one theme strategy** (Material 3 `MaterialTheme` with three token sets: Light, Dark, Sunrise). Material You dynamic colour is **off**, so the accent is identical on every phone.

## Colors

The palette is warm greys plus one accent. Nothing is pure black or pure white as a background.

- **Sunrise orange** (`{colors.accent}` light and Sunrise, `{colors.accent-dark}` dark) is the only chromatic brand colour. It fills the primary action ("I'm up", Save, FAB), selected states and the countdown ring. As a large filled shape it passes 3:1. Accent-coloured **text** on light backgrounds always uses `{colors.accent-text}` (5.1:1), including the Home streak number. Dark mode uses a lighter, softer orange that passes as text.
- **Warm greys** (`bg`, `surface`, `surface-variant`, `outline`, `outline-subtle`, `text`, `text-secondary`) carry everything else. `outline` is for borders that must be seen (3:1); `outline-subtle` is decorative only.
- **Outcome colours** (`success`, `snoozed`, `missed`) exist for Progress and Day detail. `success` means "on time" only; it is never used for money. `snoozed` is a muted brick, not an alarm red. **Colour is never the only signal:** `success` and `snoozed` differ in luminance by at least 1.6:1 in every theme, and each outcome has its own glyph (see `outcome-marker`).
- **Error** (`{colors.error}`) is for real errors only: payment failed, permission missing, wrong answer, alarm may not ring.
- **Disabled** (`disabled-container`, `disabled-content`) is a real token pair, not a 38% opacity. A disabled Snooze button carries its reason as its label, so the label must pass 4.5:1.
- **Inverse** (`inverse-surface`, `inverse-text`, `inverse-accent`) is for snackbars only.

**Theme strategy.** App screens follow the system light/dark setting, with a manual override (System / Light / Dark) in Settings. Dark is the primary design target; setup mostly happens at night. Wake screens (ringing, snooze confirm, checks, success, snoozed) always use the **Sunrise** set, and the "Bright wake screen" setting (default on) raises brightness to maximum. Bright light after waking reduces sleep inertia ([Hilditch et al., J Sleep Res 2022](https://onlinelibrary.wiley.com/doi/10.1111/jsr.13558)). Three token sets under one `MaterialTheme` is a documented exception to the single-theme lock. No health claims about blue light.

**Sunrise gradient.** An optional solid-to-solid vertical fade from `{colors.sunrise-gradient-top}` to `{colors.bg-sunrise}` is allowed on the ringing screen only, and only in the **top 40%** (behind label, clock and date). Accent on the gradient top is 2.73:1 and fails, so no accent element ever sits on the gradient. The thumb zone is always flat `{colors.bg-sunrise}`.

### Verified contrast (WCAG 2.x)

Computed with a Python WCAG relative-luminance script (2026-09-26). Text needs ≥ 4.5, graphics and UI shapes ≥ 3.0. "Info" rows are luminance separations, not WCAG pairs. **Any new pair must be added here with its ratio before it ships.**

| Theme | Pair | Kind | Ratio |
|---|---|---|---|
| Light | text / bg | text | 16.84 |
| Light | text / surface | text | 17.85 |
| Light | text / surface-variant | text | 15.42 |
| Light | text-secondary / bg | text | 6.63 |
| Light | text-secondary / surface | text | 7.03 |
| Light | text-secondary / surface-variant | text | 6.07 |
| Light | on-accent / accent | text | 5.57 |
| Light | accent / bg | graphic | 3.18 |
| Light | accent / surface | graphic | 3.37 |
| Light | accent-text / bg | text | 5.11 |
| Light | accent-text / surface | text | 5.42 |
| Light | success / bg | text | 8.36 |
| Light | success / surface | text | 8.86 |
| Light | snoozed / bg | text | 5.10 |
| Light | snoozed / surface | text | 5.41 |
| Light | missed / bg | text | 5.26 |
| Light | missed / surface | text | 5.57 |
| Light | error / bg | text | 6.17 |
| Light | error / surface | text | 6.54 |
| Light | outline / bg | graphic | 3.53 |
| Light | outline / surface | graphic | 3.74 |
| Light | disabled-content / disabled-container | text | 5.52 |
| Light | disabled-content / bg | text | 6.53 |
| Light | inverse-text / inverse-surface | text | 16.84 |
| Light | inverse-accent / inverse-surface | text | 8.52 |
| Light | success / snoozed | info | 1.64 |
| Light | success / missed | info | 1.59 |
| Light | snoozed / missed | info | 1.03 (told apart by glyph) |
| Dark | text / bg | text | 16.34 |
| Dark | text / surface | text | 15.02 |
| Dark | text / surface-variant | text | 13.21 |
| Dark | text-secondary / bg | text | 7.49 |
| Dark | text-secondary / surface | text | 6.88 |
| Dark | text-secondary / surface-variant | text | 6.06 |
| Dark | on-accent / accent | text | 8.95 |
| Dark | accent / bg | graphic | 8.94 |
| Dark | accent / surface | graphic | 8.22 |
| Dark | accent-text / bg | text | 8.94 |
| Dark | accent-text / surface | text | 8.22 |
| Dark | success / bg | text | 11.96 |
| Dark | success / surface | text | 10.99 |
| Dark | snoozed / bg | text | 7.33 |
| Dark | snoozed / surface | text | 6.73 |
| Dark | missed / bg | text | 5.34 |
| Dark | missed / surface | text | 4.91 |
| Dark | error / bg | text | 10.98 |
| Dark | error / surface | text | 10.09 |
| Dark | outline / bg | graphic | 3.62 |
| Dark | outline / surface | graphic | 3.33 |
| Dark | disabled-content / disabled-container | text | 5.42 |
| Dark | disabled-content / bg | text | 6.97 |
| Dark | inverse-text / inverse-surface | text | 16.34 |
| Dark | inverse-accent / inverse-surface | text | 4.72 |
| Dark | success / snoozed | info | 1.63 |
| Dark | success / missed | info | 2.24 |
| Dark | snoozed / missed | info | 1.37 (told apart by glyph) |
| Sunrise | text / bg | text | 16.68 |
| Sunrise | text / surface | text | 17.85 |
| Sunrise | text / surface-variant | text | 15.33 |
| Sunrise | text / sunrise-gradient-top | text | 14.46 |
| Sunrise | text-secondary / bg | text | 6.57 |
| Sunrise | text-secondary / surface | text | 7.03 |
| Sunrise | text-secondary / surface-variant | text | 6.04 |
| Sunrise | text-secondary / sunrise-gradient-top | text | 5.70 |
| Sunrise | on-accent / accent | text | 5.57 |
| Sunrise | accent / bg | graphic | 3.15 |
| Sunrise | accent / surface (countdown ring, lit memory tile) | graphic | 3.37 |
| Sunrise | accent-text / bg | text | 5.06 |
| Sunrise | accent-text / surface | text | 5.42 |
| Sunrise | success / bg | text | 8.28 |
| Sunrise | success / surface | text | 8.86 |
| Sunrise | error / bg | text | 6.11 |
| Sunrise | error / surface | text | 6.54 |
| Sunrise | outline / bg (Snooze border) | graphic | 3.50 |
| Sunrise | outline / surface (memory tile border) | graphic | 3.74 |
| Sunrise | disabled-content / disabled-container | text | 5.63 |
| Sunrise | disabled-content / bg | text | 6.47 |
| Sunrise | inverse-text / inverse-surface | text | 16.68 |
| Sunrise | accent / surface-variant | graphic | **2.89, fails: never place accent on surface-variant** |
| Sunrise | accent / sunrise-gradient-top | graphic | **2.73, fails: no accent on the gradient** |

## Typography

- **Typeface: Geist** (SIL Open Font License), bundled. Fallback: system sans. Not the default Roboto/Inter look.
- **Tabular figures** (`fontFeatureSettings = "tnum"`) for clocks, countdowns, prices and stats so digits do not jump. Verify Geist `tnum` support in E0; if missing, use Geist Mono for `clock-xl` only.
- **Ramp:** `clock-xl` 88/92 w300 (ringing time) · `display` 48/52 w500 (streak, price in the confirm sheet, alarm time in the editor, countdown seconds) · `headline` 28/34 w600 (screen titles) · `title` 20/26 w600 (card titles, list alarm times) · `button-wake` 20/24 w500 (wake-screen action labels only) · `body` 16/24 w400 · `label` 14/20 w500 (buttons, chips) · `caption` 12/16 w400 (meta).
- Sentence case everywhere. No all-caps labels except the day chips (M T W T F S S).
- Font scaling up to 200% reflows, never clips. **Exception:** `clock-xl` scales to at most 1.3× (114 sp) so wake actions stay in the thumb zone; the clock always carries a full-time content description.

## Layout & Spacing

- 4 dp base unit: `{spacing.1}` to `{spacing.8}`.
- Screen side padding `{spacing.screen-margin}` (20 dp). Section gap `{spacing.section-gap}` (24 dp). Card inner padding `{spacing.card-padding}` (16 dp).
- Touch targets: `{spacing.target-min}` (48 dp) everywhere; **every wake-screen action ≥ `{spacing.target-wake}` (64 dp)**; "I'm up" and the House Hunt shutter are `{spacing.target-wake-hero}` (72 dp).
- **Thumb zone** = bottom 40% of the screen. All wake actions live there and never scroll off screen, at any font scale.
- Single column always. One main job per screen.

## Elevation & Depth

Flat by default. Surfaces separate by tone (`bg` → `surface` → `surface-variant`), not shadows. Only bottom sheets and dialogs get elevation, tonal Material 3 levels 1 to 3. No glows, no decorative shadows.

## Shapes

Locked radius system. No other radii.

| Token | Radius | Use |
|---|---|---|
| `{rounded.sm}` | 8 dp | Chips, inputs, letter tiles, snackbars, small thumbnails |
| `{rounded.md}` | 16 dp | Cards, list items, check tiles, number-pad keys, viewfinder frame |
| `{rounded.lg}` | 28 dp | Bottom sheets, dialogs |
| `{rounded.full}` | 9999px | Primary and wake buttons, FAB, shutter, segmented control, badges |

## Components

Visual specs. Behaviour for every row lives in `EXPERIENCE.md > Component Patterns` under the same name. Token values are in the frontmatter `components:` block.

| Component | Visual spec |
|---|---|
| `button-wake-primary` | "I'm up". Full width, 72 dp, `{rounded.full}`, `{colors.accent-sunrise}` fill, `{colors.on-accent-sunrise}` label in `{typography.button-wake}`. Largest element on every wake screen. Sits on flat `{colors.bg-sunrise}`. |
| `button-snooze` | One component, all wake screens. Full width, **64 dp**, `{rounded.full}`, 1 dp `{colors.outline-sunrise}` border, `{colors.text-sunrise}` label "Snooze · {price}" in `{typography.button-wake}`. Placed 16 dp below "I'm up" on ringing; at the bottom of the check footer on check screens (same height). Variant `button-snooze-disabled`: no border, `{colors.disabled-container-sunrise}` fill, `{colors.disabled-content-sunrise}` label with the reason (5.63:1), leading `block` icon, or `lock` icon for "Unlock your phone to snooze" before first unlock. Test alarm uses it with "Test · no charge". Same 64 dp height in every variant. |
| `sheet-snooze-confirm` | Bottom sheet, `{colors.surface-sunrise}`, top corners `{rounded.lg}`, 24 dp padding. Top to bottom: title (`{typography.headline}`), price (`{typography.display}`, `{colors.text-sunrise}`, never accent), next-price line (`body`), nudge (`body`, `{colors.text-secondary-sunrise}`), tax note (`caption`, `text-secondary-sunrise`) where prices exclude tax, then two stacked full-width 64 dp buttons: **"Pay {price} and snooze"** (outlined, like `button-snooze`) **above**, **"I'll get up"** (filled, like `button-wake-primary` but 64 dp) at the **bottom**. States: *confirm*, *unlocking* (lock icon, "Unlock to pay {price}", one outlined "Cancel" 64 dp), *already paid* (same layout: "Use it" outlined above, "Not now" filled at the bottom). |
| `button-filled` | App primary action (Save, Done, Let's set it up). 48 dp min, `{rounded.full}`, accent fill, on-accent label in `label`. |
| `button-outlined` | App secondary action. 48 dp, `{rounded.full}`, `outline` border, `text` label. |
| `button-text` | Tertiary action (Test alarm, links). `accent-text` label, 48 dp target. |
| `countdown-ring` | 120 dp circle, 8 dp `{colors.accent-sunrise}` stroke over `{colors.outline-subtle-sunrise}` track, seconds centred in `display`. Sits on `bg-sunrise` or `surface-sunrise`, never on `surface-variant`. Expired: ring replaced by solid `{colors.text-sunrise}` bell icon (48 dp) and "Alarm's back on". |
| `number-pad-key` | 64 dp square, `{rounded.md}`, `{colors.surface-variant-sunrise}` fill, digit in `title`, `text-sunrise`. 3×4 grid with 8 dp gaps; backspace and "Check" keys in the same grid. |
| `memory-tile` | ≥ 64 dp square, `{rounded.md}`, `surface-sunrise` fill with 1 dp `outline-sunrise` border. Lit: `accent-sunrise` fill (3.37:1 on surface) with its number in `on-accent-sunrise`. 3×3 (4×4 on Hard). Accessible variant shows numbers 1 to 9 on every tile. |
| `letter-tile` | 48 dp, `{rounded.sm}`, `surface-variant-sunrise` fill, `outline-sunrise` border, letter in `title`. Answer slots are empty tiles with a dashed `outline-sunrise` border. |
| `text-field` | Material outlined field, `{rounded.sm}`, `outline` border, `error` border and supporting text on error. Math answer field uses `display` digits. |
| `viewfinder` | Full-width camera preview inside a `{rounded.md}` frame. House Hunt adds a 72 dp ghost thumbnail of the reference photo, top-left, `{rounded.sm}`. QR adds a centred square guide. Torch toggle 48 dp. |
| `shutter` | 72 dp circle, `accent-sunrise` fill, `on-accent-sunrise` camera icon, centred in the thumb zone. |
| `fallback-link` | Text button "Can't do this check?" in `{colors.accent-text-sunrise}`, `body`, 48 dp target, centred above the snooze button. |
| `check-type-card` | `surface` card, `{rounded.md}`, icon + name (`title`) + one line (`body`, `text-secondary`), "Try it" `button-text`. Selected: 2 dp `accent` border plus a check icon (not colour alone). Used in onboarding, Check picker and the Fallback check picker (Sunrise tokens there). |
| `card-hero` | Home top card. Streak number in `display`, `{colors.accent-text}` (light) / `{colors.accent-dark}` (dark), "days on time" in `body`, "$X paid this week" in `text-secondary`. |
| `card-alarm` | `surface`, `{rounded.md}`. Time in `title`, repeat days and label in `caption`, check icons (20 dp, `text-secondary`), `switch` on the right. |
| `fab` | 56 dp, `{rounded.full}`, accent fill, "+" in on-accent. Bottom right, 20 dp from edges. |
| `banner-warning` | `surface-variant` fill, `{rounded.md}`, leading `error` icon in `{colors.error}`, message in `body`, `button-text` "Fix". Info variant: `info` icon in `text-secondary`, no error colour. |
| `panel-session-in-progress` | Replaces Home content during a session. `surface` card, "Alarm in progress" in `headline`, one `button-filled` "Back to alarm". |
| `note-inline` | Leading `info` icon + `caption` in `text-secondary`. Used for commitment-lock notes, Direct Boot notice, approximate prices. On wake screens uses `-sunrise` tokens. |
| `chip-day` | 48 dp, `{rounded.sm}`, one letter. Unselected `surface-variant`; selected accent fill with on-accent letter plus bold weight. |
| `chip-check` | `{rounded.sm}`, `surface-variant` fill, `outline` border, check icon + name + difficulty in `label`. |
| `segmented-control` | Material 3 segmented button, `{rounded.full}` ends, selected segment accent fill with a check icon. |
| `stepper` | Value in `display` between two 48 dp round icon buttons (− / +). Price values in `tnum`. |
| `slider` | Material 3 slider, accent active track, `outline` inactive track, value label above thumb. |
| `switch` | Material 3 switch, accent checked track, `outline` unchecked border. |
| `time-picker` | Material 3 time input (keyboard-first) or dial; digits in `display`. |
| `top-app-bar` | Flat on `bg`, title in `headline`, back arrow 48 dp. |
| `nav-bar` | Material 3 navigation bar, 3 items, Material Symbols Rounded; selected icon fill 1 in `accent-text`, label always shown. |
| `progress-dots` | 8 dp dots, 8 dp apart; active accent and 16 dp wide (pill), inactive `outline`. |
| `stat-tile` | `surface`, `{rounded.md}`, number in `display` (`text`), label in `caption`. Two per row. |
| `bar-chart` | Weekly bars in `{colors.snoozed}`, top corners `{rounded.sm}`, `outline` baseline, `text-secondary` labels, caption "Lower is better." |
| `outcome-marker` | 20 dp glyph, distinct shape per outcome: on time = **filled check circle** (`success`); snoozed = **filled clock** (`snoozed`); missed = **filled cross circle** (`missed`); skipped or test = **hollow ring** (`outline`). Fallback used adds a small `alt_route` badge. Same glyphs in calendar, legend, Day detail and history. |
| `calendar-day` | 48 dp cell, date number in `caption`, `outcome-marker` below. Today gets a 1 dp accent ring. |
| `checklist-row` | 64 dp. Leading icon, title (`body`), reason (`caption`), trailing status: `check_circle` in `success` with "OK", or `button-outlined` "Fix". |
| `settings-row` | 56 dp. Label left, value (`text-secondary`) or chevron right. |
| `purchase-row` | 64 dp. Date and alarm (`body`), "Snooze 2" (`caption`), localized price right-aligned in `text` (never accent, green or red). |
| `sound-row` | 56 dp. Radio selection, sound name (`body`), source caption, 48 dp preview play button. |
| `recorder` | 72 dp round record button (accent, on-accent mic icon), elapsed and max time "0:12 / 1:00" in `display`, level meter in `text-secondary`. |
| `motivation-player` | `surface-sunrise` card, `{rounded.md}`, 48 dp play/pause and replay, progress bar in accent. |
| `dialog-confirm` | Material 3 dialog, `{rounded.lg}`, title `headline`, body `body`, actions `button-text` (confirm uses `error` text colour only when destructive). |
| `snackbar` | `{colors.inverse-surface}` container, `{colors.inverse-text}` message, optional action in `{colors.inverse-accent}`, `{rounded.sm}`. On wake screens: no action, message only. |
| `notification-ringing` | Ongoing alarm notification: monochrome sunrise small icon, alarm time as title, "Tap to return to your alarm" text, accent tint. |
| `notification-summary` | Weekly summary: monochrome small icon, no accent tint, plain copy. |
| `skeleton` | `surface-variant` blocks, `{rounded.sm}`, no shimmer when animations are off. App screens only. |

## Do's and Don'ts

| Do | Don't |
|---|---|
| Use only frontmatter tokens (colours, type, radii, spacing) | Raw hex, new radii, new font sizes |
| One accent, used for the primary action and selection | A second accent, dynamic colour, AI-purple |
| Accent text on light surfaces via `accent-text` | Accent-coloured body text at 3.2:1 |
| Prices in `text` | Green (reward) or red (shame) for money |
| Outcomes as glyph + colour + label | Colour-only dots, legends or charts |
| Make "I'm up" the largest wake action; Snooze visible, outlined, priced | Hidden, disguised or greyed-out-without-reason Snooze |
| Every wake action ≥ 64 dp in the thumb zone | Wake actions under 64 dp or below the fold |
| Tonal separation between surfaces | Decorative shadows, glows, gradient text |
| Sunrise gradient in the top 40% of ringing only | Accent on the gradient; multi-colour mesh gradients |
| Warm greys | Pure `#000000` or pure `#FFFFFF` backgrounds |
| Design Light, Dark and Sunrise together | Shipping one theme and "fixing dark later" |
| Add every new colour pair to the contrast table | Shipping an unverified pair |
| Varied, purposeful layouts | Three equal cards in a row, decorative eyebrow labels |

### Taste Skill adaptation

[Taste Skill](https://www.tasteskill.dev/docs) (MIT, [repo](https://github.com/Leonxlnx/taste-skill)) is an anti "AI slop" rule set written for React / Next.js / Tailwind pages. It is **not installed** in the Android app. Its principles are adopted and translated to native Compose in this file: the three locks, no AI-purple or gradient text or neon glows, no pure black, controlled accent saturation, verified WCAG AA contrast, light and dark designed together, motivated motion only, no filler copy or em dashes in UI strings, no three-equal-cards rows or decorative eyebrows, and the dials in Brand & Style. The per-story pre-flight checklist lives in `.claude/skills/pps-design/SKILL.md`. Taste Skill may be used as-is later for the marketing web page.
