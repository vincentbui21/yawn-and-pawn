# Design preview feedback

Owner decisions and issues from trying the design preview on the Oppo A96. Each item is applied in the next fix batch and, where it changes the design, recorded in EXPERIENCE.md / DESIGN.md.

## Round 1 (2026-09-27)

### Owner decisions
1. **Time picker:** scrolling wheel, no keyboard time entry. (Applied in round 1.)
2. **"Grace window" is renamed "Quiet time"** in all user-facing copy: editor row "Quiet time" with value "{seconds} seconds", switch "Vibrate during quiet time", Settings "Default quiet time". Check screen copy "Quiet for {seconds}s. Finish before it rings again." stays. Internal names (`graceSeconds`, glossary) may keep "grace".
3. **No starting-volume slider.** The editor shows only the "Gradually increase volume" switch. When on, the ramp always starts at 20% of the alarm volume and rises to the set volume over 30 s; when off, it starts at the set volume. `rampStartPercent` stays in the model as the fixed default 20 and is no longer user-editable.

### Issues found in the device walkthrough
4. Status-bar icons are white on light backgrounds (Light theme and Sunrise): use dark system-bar icons on light themes.
5. Fake prices use the phone's currency with a tiny amount ("Snooze · ₫1" on an en-VN phone): use realistic fake amounts per currency.
6. In the preview, Back does nothing on wake screens (correct for real alarms), but the menu says "Back returns here": give wake screens in the preview a way back to the menu (e.g. Back returns to the menu in preview only).
7. Repeat summary "Weekdays" for Mon–Fri and "Weekends" for Sat–Sun (adopted with the "Once · Weekdays · Custom" quick choices, item 10).

### Design direction (owner, 2026-09-27, after comparing with the Oppo and Samsung stock Clock apps)
8. **Grouped card sections ("divided areas")**: related rows sit together in rounded cards with dividers between rows, on every app screen (editor, settings, pickers), like the stock Clock apps.
9. **Progressive disclosure**: don't put every option on one screen. A row shows its title, the current value as a subtitle and a chevron; tapping opens a sub-screen to set it.
10. **Alarm editor layout**:
    - Header "New alarm" / "Edit alarm" with "Rings in {…}" under it.
    - The time wheel in its own large card, with "h" / "min" unit labels (and AM/PM on 12 h phones).
    - Repeat quick choices "Once" · "Weekdays" · "Custom"; Custom reveals the M–S day chips with an expand animation.
    - Card 1: Alarm name (inline field) · Sound (value ›) · Vibration (switch).
    - Card 2: Wake-up check (value ›) · Quiet time (value ›) · Snooze (value ›) · Motivation (value ›).
    - "Test alarm" as a text button under the cards.
    - **Floating "Cancel | Save" pill at the bottom** (Samsung style), kept above the keyboard.
11. **Sub-screens** with a back arrow:
    - Sound: volume slider, "Gradually increase volume" switch, then sectioned list Built-in / System / Your files with preview.
    - Snooze: length 5 / 9 / 10 / 15 min and the fee ladder.
    - Wake-up check: check types, Random / All, difficulty.
    - Quiet time: 15–30 s slider and "Vibrate during quiet time".
    - Motivation: record a message, when it plays.
12. **Animation** (respecting the reduced-motion setting):
    - Screens slide in and out.
    - "Custom" expands.
    - Switches and chips animate state.
    - The wheel scrolls with momentum and a light haptic tick per value.
    - Alarm cards animate in and out when added or removed.
    - Gentle pulse on the Ringing screen's "I'm up".
13. **Colours and fonts stay Yawn & Pawn's own** (DESIGN.md tokens); only layout, grouping and motion follow the stock apps.
14. The same grouped-card, rows → sub-screen pattern applies to Settings in round 2 and to onboarding and check setup in round 3.
15. **Glass ("frosted") design everywhere** (owner, 2026-09-27, reference: Samsung Weather): every screen in Dark, Light and Sunrise has a subtle gradient background in Yawn & Pawn's own tones, and cards, sheets and the bottom pill are translucent "glass" surfaces with a soft background blur and a faint hairline edge.
    - Real blur on Android 12+ (API 31+); below that, the same translucent surfaces without blur.
    - New DESIGN.md tokens for the background gradients (per theme) and the glass surface (fill with alpha, hairline, blur radius).
    - The contrast test covers text on glass against the worst case of the gradient behind it.
    - Blur only on cards, sheets and the pill, never on constantly animating lists.
    - A blur library (e.g. Haze) needs a dependency-allowlist review; Android's built-in `RenderEffect` is the fallback option.

## Round 1 rework feedback (2026-09-28)
16. **The Cancel | Save pill must not cover content.** It gets its own bottom area, like the Samsung editor: the scrolling content ends above that area (the last row fully visible when scrolled to the end), and the area stays above the keyboard.
17. **Wheel feedback like Samsung:** each value that passes the centre of the time wheel gives a light haptic tick and a short, quiet tick sound. The sound is bundled, not the system click that "Touch sounds" turns off, and plays at a low level. Both are silent when the phone is on silent or vibrate for the sound, and the haptic respects the system haptic setting.
18. **Collapsing Home header like Samsung Weather** (owner video, "Lahti" / "8°"):
    - "Yawn & Pawn" stays pinned top-left like "Lahti" and gains a frosted glass chip behind it once content scrolls under it.
    - The streak hero (big "12", "days on time", money line) collapses as you scroll into a compact pinned row ("12 days on time"). Secondary lines fade or move beside the number.
    - Alarm cards scroll up underneath the pinned header.
    - The transition is continuous and tied to scroll position. It is not a snap, and reduced motion turns it into an instant switch.
19. **Success screen (on time) copy, no repeated number** (owner, 2026-09-28): the big streak number, then the label "days in a row" directly under it, then the headline "Up on time.". No second "12" in the sentence, and each line fits on one line on a 360 dp phone at 100% font size. This replaces the EXPERIENCE.md string "Up on time. {n} days in a row." for this screen.
20. **Success celebration** (owner, 2026-09-28):
    - The number counts up from n−1 to n with a small bounce.
    - A short confetti burst in Sunrise/brand colours, about 1.5 s, then the screen is calm.
    - One success haptic.
    - Built with Compose animation, not a GIF.
    - Reduced motion shows the final state instantly with no confetti.
    - After-snooze success stays without confetti (EXPERIENCE.md "No animation").

## Round 2 feedback (2026-09-30)
21. **Progress page redesign** (owner: "it looks so bad"). Inspiration: a cycle-tracker ring of day dots, a learning app's bar-chart and streak cards, and a fitness app's compact calendar and small stat tiles. Brand colours, glass and the money and outcome rules are unchanged. Layout, top to bottom:
    - **Hero ring card:** the last 30 mornings as dots around a circle, each coloured by outcome with its glyph (on time / snoozed / missed / today; skipped and test days neutral). The centre shows the current streak number large, with "day streak" under it and "/ 30". Legend below with outcome markers. Tapping a dot opens Day detail.
    - **Three small stat tiles in one row:** icon, number and label for the on-time rate, the average time to get up, and snoozes. Each fits at 360 dp and wraps to 2+1 at 200%.
    - **Bar chart card**, "Snoozes this week", with rounded bars, today highlighted, and a one-line average summary. Tap a bar for its value.
    - **Accent streak card:** current streak and best streak, with a short line such as "Keep it going.". Glass with an accent tint; contrast checked.
    - **Compact month calendar:** small outcome dots under dates, today in a pill, ‹ › for months, and tap a day for Day detail.
    - **Two cards side by side:** "Money paid" (month total, link to Purchase history), and **Insight**, one short data-based line such as "You get up fastest on weekdays.".
    - Export CSV as a text button at the end. No Week/Month/All-time tabs.
    - Empty state keeps the same layout with an empty ring and a short prompt.
22. **Preview menu search and direct links** (owner, 2026-10-01): a search bar at the top of the debug preview menu that filters states as you type, and a stable id per preview state so a walkthrough can open it directly (`adb shell am start -n com.yawnandpawn.app/.debug.preview.PreviewActivity --es state <id>`, plus `--es theme light|dark` and `--ez font200 true`), listed in `docs/design-preview/states.md`.
23. **Progress page notes** (owner, 2026-10-01), applied on top of item 21:
    - **No "Progress" heading.** The page starts with the hero ring; the bottom tab names the page.
    - **No outcome legend.** Each ring and calendar dot carries its meaning in its shape as well as its colour: filled dot for on time, dot with a small clock for snoozed, hollow ring for missed, neutral small dot for skipped and test, outlined pill for today. Tapping a dot shows a small label chip ("Tue 23 · Snoozed"), and a second tap or the chip opens Day detail. TalkBack reads "{date}, {outcome}". This meets pps-design rule 6 without a legend.
    - **Stat tile labels on one line** at 100% font on 360 dp: short labels ("on time", "to get up", "snoozes"). Only 200% may wrap.
    - **Remove Export CSV** from Progress and the preview. Owner decision against PRD FR-PRG-6; the export story is to be removed via correct-course when its epic comes up. Record it in EXPERIENCE.md and deferred-work.md.
    - **Animation on entering Progress:**
      - The ring dots appear in a sweep around the circle, staggered.
      - The streak number counts up.
      - The chart bars grow from the bottom, staggered.
      - Tiles and cards fade and slide up in sequence.
      - The calendar slides between months.
      - A tapped dot pops its label chip.
      - Reduced motion shows the final state instantly.
