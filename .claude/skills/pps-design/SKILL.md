---
name: pps-design
description: Pay Per Snooze UI rules. Load before building or changing any screen, component, theme token, string or animation in this repo.
---

# Pay Per Snooze design rules

The spec is a spine pair under `_bmad-output/planning-artifacts/ux-designs/ux-pay-per-snooze-2026-09-26/`:

- `DESIGN.md`: how it looks. YAML frontmatter tokens (colors for Light / Dark / Sunrise, typography, rounded, spacing, components), the verified contrast table, Components visual specs, Do's and Don'ts.
- `EXPERIENCE.md`: how it works. IA, Voice and Tone (all UI strings), Component Patterns, State Patterns (per surface, including payment outcomes), Interaction Primitives, Session Integrity and Device Safety, Accessibility Floor, Key Flows.

Read the component rows (same name in both files) and the state rows for the surface you're building before writing UI code. The spines win over any mock, screenshot or older copy (`docs/design.md` is superseded). These rules are adapted from Taste Skill (MIT, github.com/Leonxlnx/taste-skill) for native Compose; Taste Skill itself is not installed in the app.

## Hard rules
1. **Tokens only.** Colours, type, radii and spacing come from the theme (`PpsTheme`), generated from `DESIGN.md` frontmatter. No raw hex, no ad-hoc dp radii, no new font sizes. New tokens need a `DESIGN.md` update first.
2. **Three locks:** one accent (Sunrise orange), one radius system (8 / 16 / 28 / full), one theme strategy (MaterialTheme with Light, Dark, Sunrise token sets). Dynamic colour stays off.
3. **Wake screens** (ringing, snooze confirm, checks, fallback check picker, success, snoozed) always use the Sunrise tokens. "I'm up" is the largest action (72 dp). Snooze is visible, outlined, and shows its price; when unavailable it is disabled with its reason as the label. Never hide or disguise snooze.
4. **Money is neutral:** prices in `text` colour. Never green (reward) or red (shame) for payments. Every non-payment outcome says "No charge."
5. **Contrast:** WCAG AA (text ≥ 4.5, graphics ≥ 3.0). Any new colour pair must be computed and added to the contrast table in `DESIGN.md > Colors` with its ratio. Never place accent on `surface-variant-sunrise` or on the Sunrise gradient.
6. **Outcomes never rely on colour:** use `outcome-marker` glyphs (filled check / clock / cross / hollow ring) plus labels.
7. **Targets:** ≥ 48 dp; **every wake action ≥ 64 dp** (ringing Snooze, both confirm-sheet buttons, check-footer Snooze), in the thumb zone, never scrolled off screen. Must work at 200% font scale (clock capped at 1.3×) and with TalkBack.
8. **Snooze confirm sheet:** "I'll get up" (filled) at the bottom, "Pay {price} and snooze" (outlined) above it, no pre-selection, input ignored for 500 ms after the sheet opens or changes state.
9. **Waking up is always free:** the fallback link appears immediately when the camera or its permission is unavailable, otherwise after 5 failed attempts; Math with TalkBack-friendly input is always offered.
10. **Device safety:** the phone stays usable during a session; never start an activity from the background; the ongoing notification returns to the ringing screen; volume keys are captured only while the wake screen is foreground; never capture the accessibility shortcut.
11. **Motion only for feedback or state change.** Respect animator duration scale 0 (instant fallback).
12. **Copy:** strings in resources, taken verbatim from `EXPERIENCE.md > Voice and Tone`; short; supportive; no em dashes; no filler verbs (Elevate, Seamless, Unleash, Supercharge); no invented numbers. Say "fallback check", never "backup check".
13. **Banned:** purple gradients, gradient text, glows, pure #000000 / #FFFFFF backgrounds, decorative shadows, three-equal-card feature rows, decorative eyebrow labels.

## Done checklist (every UI story)
- [ ] Only tokens from `DESIGN.md` used (no raw hex, no new radii, no new font sizes).
- [ ] Light, Dark (and Sunrise where relevant) checked with previews / screenshots.
- [ ] Every colour pair used is in the `DESIGN.md` contrast table.
- [ ] Touch targets ≥ 48 dp; wake actions ≥ 64 dp.
- [ ] Works at 200% font scale and with TalkBack; outcome glyphs present.
- [ ] Reduced-motion path works.
- [ ] Copy matches `EXPERIENCE.md > Voice and Tone` (no em dashes, no filler, strings in resources).
- [ ] Every state row in `EXPERIENCE.md > State Patterns` for this surface is handled.
- [ ] "I'm up" is the most prominent wake action; snooze is visible, plain and priced.
- [ ] Compose `@Preview`s for each state (light/dark/Sunrise, empty/error) exist; screenshot tests updated (Paparazzi or Roborazzi).
