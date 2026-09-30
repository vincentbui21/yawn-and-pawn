---
name: Yawn & Pawn
status: draft
version: 0.4
owner: Kiet Bui
updated: 2026-09-30
sources:
  - _bmad-output/planning-artifacts/prds/prd-pay-per-snooze-2026-09-26/prd.md
  - DESIGN.md
---

# Yawn & Pawn — EXPERIENCE.md

How the app works: structure, behaviour, states, words and journeys. How it looks lives in `DESIGN.md`; tokens are referenced as `{path.to.token}`. PRD requirement IDs (FR-*, NFR-*) are cited by ID, never by section number.

## Foundation

- **Form factor:** single-surface Android phone app (Kotlin Multiplatform, Compose Multiplatform). iOS later; no Responsive & Platform section until then.
- **UI system:** Material 3 components and interaction patterns, with our own tokens from `DESIGN.md`. This spine specifies only the behavioural delta from Material 3.
- **Dynamic colour off.** Icons: Material Symbols Rounded, weight 400, fill 0 (fill 1 for the selected nav item and outcome markers).
- **Themes:** app screens follow the system light/dark setting, with a manual override (System / Light / Dark). Wake screens always use the Sunrise token set and, with "Bright wake screen" on (default), raise brightness to maximum.
- **Design-originated scope** (not in PRD v0.1, sent back for acknowledgement): theme override, the "Bright wake screen" setting, the selectable accessible fallback check picker and the unlock step copy.
- **Taste Skill** is not installed in the app; its adapted rules live in `DESIGN.md > Do's and Don'ts`.

### Glossary (use verbatim)

| Term | Meaning |
|---|---|
| Check | A proof-of-wake check: Memory Sequence, Math, House Hunt, QR/Barcode, Word Unscramble. |
| Session | From first ring until the check is done or the session times out (PRD §6.1). |
| Grace window | The muted countdown (15 to 30 s) after "I'm up". Internal and planning name only. |
| Quiet time | The user-facing name of the grace window in every UI string (owner decision 2026-09-27): editor row "Quiet time", "Vibrate during quiet time", Settings "Default quiet time". Code keeps `graceSeconds`. |
| Fallback check | The replacement check offered when the configured check can't physically be done (FR-PWK-11). Never called "backup". |
| Commitment lock | Weakening changes within 8 h of an enabled alarm take effect after that alarm. |
| Outcomes | On time · Snoozed · Missed · Skipped · Test (FR-PRG-1). |
| Wake screens | Ringing, Snooze confirm, Check, Fallback check picker, Success, Snoozed. One full-screen activity. |

## Information Architecture

Bottom navigation with three items: **Alarms** (home), **Progress**, **Settings**. Everything else is a pushed screen or bottom sheet. Wake screens are one separate full-screen activity outside the navigation, shown over the lock screen.

| Surface | Reached from | Purpose |
|---|---|---|
| Onboarding (8 steps) | First launch | Mission, alarm behaviour disclosure and consent, base fee, first alarm, checks, reliability checklist, analytics choice, test alarm (FR-ONB-1/5/6). |
| Alarms (Home) | Nav, app open | Streak and money hero, next alarm, alarm list, add alarm. |
| Alarm editor | `card-alarm` tap, `fab` | Owner decision 2026-09-27 (grouped cards, progressive disclosure). Header "New alarm" / "Edit alarm" with "Rings in {...}" under it; the time wheel in its own card ("h" / "min" labels, AM/PM on 12 h phones); repeat "Once" · "Weekdays" · "Custom" (Custom expands to the day chips); card 1: "Alarm name" (inline field) · "Sound" (value ›) · "Vibration" (switch); card 2: "Wake-up check" (value ›) · "Quiet time" (value ›) · "Snooze" (value ›) · "Motivation" (value ›); "Test alarm" text button under the cards; floating "Cancel \| Save" pill at the bottom, above the keyboard. Each › row opens a sub-screen with a back arrow: **Sound** (volume slider, "Gradually increase volume" switch, then the Sound picker list); **Snooze** (length 5 / 9 / 10 / 15 min, fee ladder); **Wake-up check** (check types, Random / All, difficulty); **Quiet time** (15 to 30 s slider, "Vibrate during quiet time"); **Motivation** (message, "Record a message", when it plays). |
| Check picker | Editor "Wake-up check" sub-screen, onboarding | Choose check types and mode (Random / All). In the editor it is the Wake-up check sub-screen (with difficulty). |
| Check setup | Check picker | Difficulty, count, "Try it" preview (FR-PWK-12). |
| House Hunt registration | Check setup | Capture 1 to 3 reference photos, test match. |
| QR registration | Check setup | Scan a code or make a printable QR. |
| Sound picker | Editor "Sound" sub-screen | Built-in sounds, system ringtones, a user file, in sections "Built-in" / "System" / "Your files" under the volume card; preview (FR-SND-1/2). |
| Recordings | Editor "Motivation" sub-screen, "Record a message" | Record, re-record, play, delete motivation messages (FR-SND-3). |
| Progress | Nav | Streak, rates, average time to up, calendar, money (FR-PRG-2/3; no snoozes chart, owner decision 2026-10-01, feedback item 24). Owner redesign 2026-09-30 and notes 2026-10-01 (feedback items 21 and 23), top to bottom, no heading (the tab names it), no period tabs, no legend: `progress-ring` of the last 30 mornings (tap a dot for its label chip "Tue 23 · Snoozed", tap again or the chip for Day detail) with the streak, "/ 30" and "day streak" in the centre; three small `stat-tile`s in one row ("on time", "to get up", "snoozes", one line at 100% on 360 dp); `card-streak` ("Current streak", "Best streak", "Keep it going."); the compact calendar (‹ › for past months, slides between them; tap a day for its chip, again for Day detail); "Money paid" (this month, "Purchase history" link) beside the Insight card. Entry animation: cards fade and rise in sequence, the ring dots sweep in, the streak counts up; reduced motion shows the final state. Empty: the empty ring with "Your first morning shows up here.", the calendar and "Purchase history". No CSV export (owner decision 2026-10-01 against FR-PRG-6; the export story goes via correct-course). No snoozes chart (owner decision 2026-10-01 against the "snoozes chart" in FR-PRG-2: the ring shows each snoozed day and the "snoozes" tile the count; the chart part of the progress story goes via correct-course). |
| Day detail | `calendar-day` tap | One session: rings, snoozes, paid, checks, fallback, outcome. Title is the long date; one card per session titled with its alarm time and label: `outcome-marker` with the outcome label, the flags, then "Rings", "Snoozes", "Paid", "Checks", "Time to up" (drafts in docs/design-preview/copy-to-approve.md). Logged alarm changes follow as `note-inline`s. |
| Purchase history | Progress link | Every charge with date, alarm, snooze number, localized price (FR-PRG-4). |
| ~~Export CSV~~ | Removed | Owner decision 2026-10-01 (feedback item 23): no CSV export in the app, against PRD FR-PRG-6 (Could). The export story is to be removed via correct-course when its epic comes up. |
| Settings | Nav | Grouped cards, rows open sub-screens (owner decision 2026-09-27; design preview round 2 layout 2026-09-30). Title "Settings" pinned top-left. Card "Snooze": "Base fee" ›, "Max snoozes per session" ›, "Default snooze length" ›; card "Wake": "Default quiet time" ›, "Vibrate during quiet time" (switch), "Bright wake screen" (switch with its caption); card "Appearance": System / Light / Dark `segmented-control`; a card with "Weekly summary" and "Share anonymous usage stats" (switches); a card with "Reliability checklist" › and "How payments & refunds work" ›; a card with "Privacy policy", "Terms", "Support"; a card with "Delete all data" (`error` text, opens its dialog). Sub-screens: **Base fee** (`stepper`, fee ladder preview, lock note, approximate-price note), **Max snoozes per session** (`stepper` 1 to 5), **Default snooze length** (5 / 9 / 10 / 15 min), **Default quiet time** (15 to 30 s slider). |
| Reliability checklist | Settings, onboarding step 5, `banner-warning` | Permission and device-setting status with fixes (FR-ONB-2/3). All rows in one `card-group`; "Fix" on "Manufacturer settings" opens its numbered steps as a sub-screen ("Open settings", "I've done this"). |
| Payments & refunds | Settings | Plain explanation of fees, pending payments and Google refunds (FR-SET-3). |
| Ringing | Alarm fires (full-screen intent), `notification-ringing` tap | Time, "I'm up", "Snooze · {price}". |
| Snooze confirm | `button-snooze` tap | Price, next price, nudge, confirm. Includes the unlock step and "already paid" state. |
| Google Play purchase sheet | Snooze confirm (system UI) | Payment. |
| Check | "I'm up" | The chosen check(s) with the grace window (quiet time). |
| Fallback check picker | `fallback-link` | Pick an accessible fallback check. |
| Success | Check completed | Streak or plain "You're up", motivation playback. |
| Snoozed | Purchase granted | "Snoozed. Next ring at {time}." then screen off. |

**Session lock (FR-SES-3):** while a session is active (including snoozed), opening the app shows only `panel-session-in-progress` with "Back to alarm". Editing, deleting, settings and "Delete all data" are unavailable. The rest of the phone stays usable (see Session Integrity and Device Safety).

No wireframes or mockups exist yet. Candidates for the next pass: Ringing, Snooze confirm, Check.

## Voice and Tone

Microcopy. Brand voice lives in `DESIGN.md > Brand & Style`. These rules apply to every screen and notification (FR-MSG-4).

| Do | Don't |
|---|---|
| Short: headlines ≤ 8 words, paragraphs ≤ 25 words. | Long explanations on wake screens. |
| Supportive: "You've got this." | Shaming: "Lazy again?" |
| Money stated plainly: "Snooze · $3". | "Only $3!", "Treat yourself". |
| Periods, commas or "·". | Em dashes in UI strings. |
| Plain verbs. | Elevate, Seamless, Unleash, Supercharge. |
| Real numbers only. | Invented stats or fake precision. |
| One emoji at most, only in the zero-snooze success message. | Emojis in core flows. |
| Say "No charge." whenever a payment did not happen. | Leave the user guessing whether they paid. |

All strings live in resources (NFR-10). `{price}`, `{nextPrice}`, `{minutes}`, `{time}`, `{seconds}`, `{streak}`, `{reason}` and similar are **runtime string variables**, not token references.

### Key strings (English)

| Where | Copy |
|---|---|
| Mission (onboarding) | "This app makes money only when you snooze. We hope you never pay us." |
| Mission, how it works | "Snooze costs money. Waking up is free." |
| Alarm behaviour disclosure (FR-ONB-5) | "Your alarm keeps ringing until you finish your check or pay to snooze. Your phone stays usable: calls, other apps and emergency calls all work. Other ways to stop it: force-stop or uninstall the app, turn off the phone, or leave it 30 minutes." · "I understand" |
| Analytics choice (FR-ONB-6) | "Share anonymous usage stats? Off unless you turn it on." · "Share" / "No thanks" |
| Base fee, lock note | "You can raise it anytime. Lowering it waits until after your next alarm." |
| Base fee, offline | "Approximate. Your local price shows when you're online." |
| Bright wake screen (onboarding) | "Your alarm screen turns bright to help you wake. Change it in Settings." |
| Test alarm (onboarding) | "Lock your phone. We'll ring in 10 seconds." |
| Home, zero paid | "Nothing paid this week. Keep it that way." |
| Home, next alarm < 1 h | "Rings in {minutes} min" |
| Home, next alarm ≥ 24 h | "Rings in {days} d {hours} h" |
| Alarm repeat summary | "Every day" · "Once" · "Weekdays" (Mon to Fri) · "Weekends" (Sat and Sun) · otherwise locale short day names (e.g. "Mon, Wed, Fri") (Weekdays / Weekends: owner decision 2026-09-27) |
| Editor, repeat quick choices | "Once" · "Weekdays" · "Custom" (owner decision 2026-09-27) |
| Editor, header | "New alarm" / "Edit alarm" · "Rings in {...}" (the Home countdown strings) |
| Editor, bottom pill | "Cancel" · "Save" (owner decision 2026-09-27) |
| Editor, rows (owner decision 2026-09-27) | "Alarm name" · "Sound" · "Vibration" · "Wake-up check" · "Quiet time" · "Snooze" · "Motivation" · "Test alarm" |
| Editor, time wheel unit labels | "h" · "min" (owner decision 2026-09-27) |
| Editor, label too long | "Keep the label under 40 characters." |
| Editor, unsaved changes dialog | Title "Discard changes?" · actions "Discard" / "Keep editing" |
| Editor, volume ramp switch | "Gradually increase volume" (no starting-volume slider, owner decision 2026-09-27: on, the ramp starts at 20% and rises to the set volume over 30 s; off, it starts at the set volume) |
| Sound picker, source captions | "Built-in" · "System" |
| Sound picker, preview (TalkBack) | "Play preview" / "Stop preview" |
| Notification channel | "Alarms" |
| Check picker, card descriptions | Math "Solve a few quick sums." · Word Unscramble "Unscramble a few words." · Memory Sequence "Repeat a pattern of tiles." · QR/Barcode "Scan a code you placed away from bed." · House Hunt "Photograph a spot far from your bed." |
| Home FAB (TalkBack) | "Add alarm" |
| Home, next alarm | "Rings in {hours} h {minutes} min" |
| Home, reliability banner | "Alarms may not ring. Fix settings" |
| Home, missed session | "Your {time} alarm stopped after 30 minutes. Logged as missed." |
| Home, fallback re-register | "Fallback check used 3 times this week. Re-register your {checkName}?" |
| Session in progress | "Alarm in progress" · "Back to alarm" |
| Editor, weakening under lock | "Saved. Takes effect after tomorrow's {time} alarm." |
| Editor, no check | "Pick at least one check." |
| Disable under lock (dialog) | "Turn off your {time} alarm? It rings in {hours} h. This is logged." · "Turn off" / "Keep it on" |
| Delete alarm (dialog) | "Delete your {time} alarm? This is logged." · "Delete" / "Keep it" |
| Ringing, primary | "I'm up" |
| Ringing, snooze | "Snooze · {price}" |
| Ringing, session line | "Snooze {n} of {max} · {paid} paid this morning" |
| Snooze unavailable | "Snooze unavailable: {reason}" where reason is one of: offline · max snoozes reached · price cap reached · payment pending · prices not loaded yet |
| Snooze unavailable, stranded payment | "An earlier {price} payment is being refunded" |
| Before first unlock (disabled Snooze label) | "Unlock your phone to snooze" |
| Test alarm snooze | "Test · no charge" |
| Snooze confirm, title | "Snooze for {minutes} min?" |
| Snooze confirm, body | "This one costs {price}. The next one costs {nextPrice}." |
| Snooze confirm, nudge | "Is {minutes} more minutes worth {price}? You've got this." |
| Snooze confirm, tax note (regions where Play prices exclude tax) | "Google Play shows the final total, including any tax." |
| Snooze confirm, buttons | "Pay {price} and snooze" (upper) / "I'll get up" (bottom) |
| Unlock step | "Unlock to pay {price}" · "Cancel" |
| Already paid (stranded purchase) | "You already paid {price} earlier that wasn't used. Use it for this snooze?" · "Use it" (upper) / "Not now" (bottom) |
| Purchase history, stranded | "Not used, refunded automatically by Google" |
| Unlock cancelled or failed | "Phone still locked. No charge." |
| Payment cancelled | "Payment cancelled. No charge." |
| Payment error | "Payment didn't go through. No charge." |
| Payment offline | "No connection. No charge." |
| Payment pending | "Payment not confirmed yet. If it goes through before you finish, your snooze starts. Otherwise, finish the check to stop the alarm." |
| After paid snooze | "Snoozed. Next ring at {time}." |
| Grace window | "Quiet for {seconds}s. Finish before it rings again." |
| Grace ended | "Time's up. Alarm's back on until you finish." |
| Direct Boot notice | "Your phone restarted, so today's check is Math." |
| Camera unavailable | "Camera isn't available. Pick a fallback check." |
| Fallback link | "Can't do this check?" |
| Fallback check picker, title | "Pick a fallback check" |
| Phone call | "Paused for your call. Rings again when it ends." |
| Success, zero snooze | The streak number, then "days in a row" under it, then the headline "Up on time." (owner decision 2026-09-28: the number is never repeated in a sentence; each line fits one line on 360 dp at 100%) |
| Success, after snooze | "You're up. That's what counts." |
| Success, pending not used | "Your pending payment wasn't used. Google refunds it automatically." |
| Weekly summary (nothing paid) | "{onTime} on-time mornings, nothing paid. Nice." |
| Weekly summary (paid) | "{onTime} on-time mornings, {paid} paid this week." |
| Ringing notification | "{time} alarm · Tap to return to your alarm" |
| Progress empty | "Your first morning shows up here." |
| Home empty | "No alarms yet." · "Add your first alarm" |
| Purchase history empty | "No snoozes paid. Keep it that way." |
| Recordings empty | "Record a message for your morning self." |
| Math check, progress | "Problem {n} of {count}" |
| Math check, problem (TalkBack) | Spoken form with "plus" / "times", e.g. "47 plus 38" |
| Math check, backspace (TalkBack) | "Delete digit" |
| Math check, answer (TalkBack) | "Answer {value}" |
| Word Unscramble, progress | "Word {n} of {count}" |
| Word Unscramble (TalkBack) | "Letter {letter}" · "Slot {n}, empty" · "Slot {n}, {letter}" |
| Memory Sequence, phases | "Watch the sequence" · "Your turn" |
| Memory Sequence, progress | "Round {n} of {count}" |
| Memory Sequence, tile (TalkBack) | "Tile {number}" |
| Check setup, count labels | Math "Problems" · Word Unscramble "Words" · Memory Sequence "Rounds" |
| Check picker, All mode order | "Move up" / "Move down" |
| Check preview, done | "Nice. That's how it works." |
| Fallback check picker, close (TalkBack) | "Back to check" |
| Quiet time, row value and slider value | "{seconds} seconds" |
| Quiet time, switch (editor and Settings) | "Vibrate during quiet time" (owner decision 2026-09-27, was "Vibrate in grace window") |
| Grace window, countdown (TalkBack) | "{seconds} seconds left" |
| Success, zero snooze (before streaks) | "Up on time." |
| Success, zero snooze, streak of 1 | "1", then "day in a row", then "Up on time." |
| Success, test session | "Test finished. Your alarm works." |
| QR/Barcode, no code registered | "Scan a code to use this check." |
| QR/Barcode registration, detection | "Use this code" / "Scan again" |
| QR/Barcode registration, torch (TalkBack) | "Torch" |
| QR/Barcode check, header | "Scan your code" |
| QR/Barcode check, wrong code | "That's a different code. Scan your registered one." |
| QR/Barcode viewfinder (TalkBack) | "Camera viewfinder. Point at your code." |
| Camera unavailable at setup (Check picker, House Hunt registration) | "Camera isn't available." · "Fix" |
| Banner, dismiss (TalkBack) | "Dismiss" |
| Play product listing | Title "Snooze" · description "One snooze for your alarm." |
| Money, mixed currencies | "{amount1} + {amount2}" (one amount per currency) |
| Snooze confirm, body at last snooze | "This one costs {price}." |
| Settings, Snooze section | "Snooze" · "Base fee" · "Max snoozes per session" · "Default snooze length" |
| Settings, fee ladder preview (also onboarding) | "Snooze 1: {price1} · 2: {price2} · 3: {price3}" |
| Settings, weakening under lock (today) | "Saved. Takes effect after today's {time} alarm." |
| Settings, Wake section | "Wake" · "Default quiet time" · "Bright wake screen" (owner decision 2026-09-27, was "Default grace window") |
| Settings, bright wake screen caption | "Raises screen brightness on alarm screens." |
| Settings, Appearance section | "Appearance" · "System" / "Light" / "Dark" |
| Settings, usage stats | "Share anonymous usage stats" |
| Settings, weekly summary switch | "Weekly summary" |
| Settings, rows | "Reliability checklist" · "How payments & refunds work" · "Privacy policy" · "Terms" · "Support" · "Delete all data" |
| Settings, purchase authentication tip (also onboarding) | "Turn on purchase authentication in Google Play so every snooze needs your fingerprint or password." |
| Settings, delete all data (dialog) | "Delete all data?" · "Alarms, history, purchase records and settings are removed from this phone. This can't be undone." · "Delete" / "Keep it" |
| Links, no handler (snackbar) | "No browser found." |
| Disable under lock (dialog), under 1 h | "Turn off your {time} alarm? It rings in {minutes} min. This is logged." · "Turn off" / "Keep it on" |
| Onboarding, base fee | "Continue" |
| Onboarding, first alarm headline | "When should it ring?" |
| Onboarding, checks headline | "How will you prove you're up?" |
| Onboarding, reliability headline | "Make sure it rings" |
| Onboarding, test alarm not locked | "Your phone wasn't locked. Try again with it locked." |
| Onboarding, test skipped (Home note) | "Ring a test alarm with your phone locked to check it works." |
| Purchase history, link and title | "Purchase history" |
| Problem with a charge?, actions | "Open Google Play order history" · "Email support" |
| Problem with a charge?, support email subject | "Charge question" |
| Reliability checklist, rows (title · reason) | "Notifications" · "Needed to show your alarm." / "Full-screen alarm" · "Shows the alarm over the lock screen." / "Exact alarms" · "Lets the alarm ring on time." / "Do Not Disturb" · "Do Not Disturb must allow alarms." / "Battery" · "Stops Android from pausing your alarm." / "Manufacturer settings" · "Your phone may stop apps in the background." / "Camera" · "Needed for your QR/Barcode and House Hunt checks." / "Microphone" · "Needed to record messages." / "Test alarm" · "Ring a test with your phone locked." |
| Reliability checklist, revoked | "Alarm may not ring: battery optimization turned back on" · "Alarm may not ring: notifications turned off" · "Alarm may not ring: full-screen alarm turned off" · "Alarm may not ring: exact alarms turned off" · "Alarm may not ring: Do Not Disturb blocks alarms" · "Alarm may not ring: check your phone's background settings again" |
| Reliability checklist, manufacturer guidance | "Open settings" · "I've done this" (steps in Long-form copy) |
| Home hero, streak label | "days on time" · "day on time" (1) |
| Home hero, money line | "{paid} paid this week" |
| Progress, stat tiles | "on time" · "to get up" · "snoozes" (owner notes 2026-10-01); streak card "Current streak" · "Best streak" · "Keep it going." (owner redesign 2026-09-30) |
| Progress, ring (owner redesign 2026-09-30) | Centre "{streak}" · "/ 30" · "day streak"; a dot reads like a calendar day, plus ", today" for today; label chip "{weekday} {day} · {outcome}" (draft) |
| Progress, Insight card (owner redesign 2026-09-30) | Title "Insight" · one line from the user's data: "You get up fastest on weekdays." / "You get up fastest on weekends." |
| Progress, average time | "{minutes} min" · "Under 1 min" |
| Progress, money | "Money paid" · "This month" (the card shows the month total and links to "Purchase history", owner redesign 2026-09-30) |
| Progress, calendar navigation (TalkBack) | "Previous month" / "Next month" |
| Progress, calendar day (TalkBack) | "{weekday} {day}, {outcome}" + ", fallback check used" + ", {n} sessions" · empty day "{weekday} {day}, no alarm" |
| Day detail, flags | "Fallback check used" · "Rang before your first unlock" |
| Day detail, alarm changes | "Your {time} alarm was turned off. Logged." · "Your {time} alarm was deleted. Logged." |
| Weekly summary, channel | "Weekly summary" |
| Weekly summary, singular | "1 on-time morning, nothing paid. Nice." · "1 on-time morning, {paid} paid this week." |
| Weekly summary, no on-time mornings | "0 on-time mornings this week. Next week's a fresh start." |
| Recordings, auto name | "Message {n}" |
| Recordings, editor section | "Motivation" · row "Message" · values "None" / "Random" |
| Recordings, too short (snackbar) | "Too short. Try again." |
| Recordings, record button (TalkBack) | "Start recording" / "Stop recording" |
| Recordings, delete (dialog) | "Delete this message? Alarms using it will play no message." · "Delete" / "Keep it" |
| Recordings, playback (TalkBack) | "Play message" · "Pause message" · "Replay message" |
| Sound picker, user files | Section "Your files" · caption "Your file" · "Pick a file" |
| Sound picker, file errors (snackbar) | "This file can't be played. Pick another." · "This file is too big. Pick one under 20 MB." |
| House Hunt, check card | "House Hunt" · "Photograph a spot far from your bed." |
| House Hunt registration | Title "House Hunt photos" · "Take 1 to 3 photos of one spot far from your bed." |
| House Hunt registration, actions | "Take photo" (TalkBack) · "Remove" · "Test match" |
| House Hunt registration, errors | "Couldn't use that photo. Try again." · "Take at least one photo." |
| House Hunt, photos lost after restore | Banner "House Hunt photos weren't restored. Retake them." · "Retake" |
| House Hunt, photos missing (wake note) | "Your House Hunt photos are missing, so today's check is Math." |
| House Hunt check, matching (TalkBack) | "Checking" |
| Printable QR | "Make a printable QR" · "Stick it somewhere far from your bed." · "Print or save as PDF" |
| Printable QR, PDF line | "Scan this to stop your alarm." |
| Printable QR, replace (dialog) | "Replace your QR code? The old one stops working." · "Replace" / "Keep it" |
| Printable QR, image (TalkBack) | "QR code for your alarm" |
| Skip next alarm, editor switch | "Offer to skip 2 h before" |
| Skip next alarm, notification | Channel "Upcoming alarms" · action "Skip this one" |
| Skip next alarm (dialog) | "Skip your {time} alarm? This is logged." · "Skip" / "Keep it" |
| Skip next alarm, done (snackbar) | "Skipped. No charge." |
| Skip next alarm, alarm card | "Next ring skipped" · menu "Don't skip" |

### Long-form copy

Body copy, one bullet per paragraph (each ≤ 25 words).

- **Problem with a charge?**
  - "Google lets you request a refund yourself within 48 hours of a charge."
  - "Payments that weren't used are refunded automatically by Google."
  - "A pending payment only charges you if it goes through."
- **Problem with a charge?, support email body** (one line each): "Order ID: {orderId}" · "Date: {purchaseTime}" (ISO 8601 with offset) · "Price: {price}" · "Status: {status}"
- **How payments & refunds work** (followed by the alarm behaviour disclosure verbatim, the purchase authentication tip, and links to "Problem with a charge?" and "Purchase history")
  - "Each snooze costs your base fee times the snooze number. Google Play shows the price in your local currency."
  - "You can snooze up to your max snoozes per session, and one morning never costs more than {cap}."
  - "Your fee is locked for the morning. You can raise it anytime. Lowering it waits until after your next alarm."
  - "A pending payment only starts your snooze if it goes through before you finish. Otherwise, finish the check to stop the alarm."
  - "Payments that weren't used are refunded automatically by Google."
  - "Google lets you request a refund yourself within 48 hours of a charge."
- **Reliability checklist, manufacturer guidance** (2 to 4 numbered steps per maker, sourced from `docs/oem-guidance.md`)
  - Xiaomi: "Turn on Autostart for Yawn & Pawn." · "Set Battery saver to “No restrictions”." · "Lock the app in Recents so it stays open."
  - Samsung, Huawei, Oppo/Realme, Vivo, OnePlus: to be added from `docs/oem-guidance.md` when it is written.

## Component Patterns

Behavioural rules. Visual specs for every row live in `DESIGN.md > Components` under the same name.

| Component | Use | Behavioural rules |
|---|---|---|
| `button-wake-primary` | Ringing | Starts the check and the grace window. Always enabled. Never moves between rings. On Ringing a gentle pulse (scale 1 to 1.03, 1.2 s) draws the eye (owner decision 2026-09-27); none with reduced motion or while the confirm sheet is open; the touch target never moves. |
| `button-snooze` | Ringing, Check footer | One component everywhere. Enabled: opens `sheet-snooze-confirm`. `button-snooze-disabled`: not tappable, label states the reason, TalkBack reads "Snooze unavailable, {reason}". Before first unlock it uses the lock icon and "Unlock your phone to snooze"; once the phone is unlocked it becomes "Snooze · {price}" without leaving the screen (FR-ALM-11). Live-updates when connectivity returns or a stranded token clears. |
| `sheet-snooze-confirm` | Over Ringing or Check | Alarm keeps ringing (FR-RNG-5). **Ignores all input for 500 ms after it opens**, including with animations off. "I'll get up" sits at the bottom where the thumb was; "Pay {price} and snooze" is above it. Neither is pre-selected or focused. Swipe down or Back = "I'll get up" path (closes sheet, no charge). "Pay" → if locked: *unlocking* state ("Unlock to pay {price}") → keyguard → Play sheet; if unlocked: Play sheet directly. If a stranded purchase for that price exists (pre-launch query or `ITEM_ALREADY_OWNED`), the sheet switches to the *already paid* state instead of charging (FR-RNG-10), with "Not now" at the bottom and "Use it" above; the 500 ms input lock applies again on every state change. Tax note shown where prices exclude tax. Outcomes per the Payment outcomes table. |
| `button-filled` | App screens | Primary action; one per screen. |
| `button-outlined` | App screens | Secondary action. |
| `button-text` | App screens | Tertiary actions ("Test alarm", "Try it", "Fix"). |
| `countdown-ring` | Check header | Starts on "I'm up". Linear, exact to the second. Keeps counting behind the confirm sheet and the Play sheet. At 0: switches to "Alarm's back on", strong haptic, alarm returns at full volume. One window per ring. |
| `number-pad-key` | Math | Light haptic per tap. Backspace, then "Check". Wrong answer: shake, error haptic, field cleared, announcement "Not quite. Try again." |
| `memory-tile` | Memory Sequence | Sequence plays, then input. Wrong tap restarts the round with a new sequence (FR-PWK-4). Input disabled while playing. |
| `letter-tile` | Word Unscramble | Tap moves a letter to the next empty slot; tap a slot to return it. "Shuffle" and "Clear" actions. |
| `text-field` | Answer field, labels | Math answer is read-only; filled only from `number-pad-key`. Labels use the system keyboard. |
| `viewfinder` | House Hunt, QR, registration | Starts on screen open. If permission is denied or the camera fails to start (CameraX error or no frame within 5 s, FR-PWK-11), shows "Camera isn't available. Pick a fallback check." with `fallback-link` immediately. |
| `shutter` | House Hunt | Captures and matches on device. Result: "Matched" (success) or "Doesn't match yet. Try the same angle." Each non-match counts as a failed attempt. |
| `fallback-link` | Check footer | Appears **immediately** if camera permission is denied, the camera is unavailable or fails to start; otherwise after **5 failed attempts** on a camera check. Once per session. Opens the Fallback check picker. |
| `check-type-card` | Onboarding, Check picker, Fallback check picker | Tap toggles selection (picker) or starts that check (fallback picker). "Try it" opens a no-stakes preview. Camera checks show "Needs the camera. If it can't be used, you'll get a fallback check." |
| `card-hero` | Home | Not tappable in v0.2 (Progress is one tap away in nav). Streak number animates only on the morning it grows (success screen, not Home). |
| `card-alarm` | Home list | Animates in when added and out when removed (owner decision 2026-09-27). Tap → editor. Long-press → menu with Duplicate and Delete (both also in the editor overflow menu for TalkBack). `switch` toggles enabled; turning off within 8 h opens `dialog-confirm`. |
| `fab` | Home | Adds an alarm → editor with defaults. |
| `banner-warning` | Home, Settings | Shown when any reliability item fails. Not dismissible; clears itself when the checklist is all OK (re-evaluated on every app foreground). Info variant (info icon, no error colour) for the fallback re-register prompt; dismissible. |
| `panel-session-in-progress` | Home during a session | Replaces all Home content. "Back to alarm" opens the wake screen. |
| `note-inline` | Editor, onboarding, Check | Read-only notes: commitment lock, approximate price, Direct Boot notice, phone call pause. |
| `chip-day` | Editor, onboarding | Toggle, shown when "Custom" is chosen (expands with an animation). No days selected = one-time alarm at the next occurrence of the time. "Once" clears the days, "Weekdays" sets Monday to Friday. |
| `chip-check` | Editor | Shows selected checks with difficulty; tap → Check setup. |
| `segmented-control` | Difficulty, check mode, theme | Single select, always one selected. |
| `stepper` | Base fee, counts | − / + in single steps; long-press repeats. Base fee lowering under lock shows the lock note. |
| `slider` | Quiet time (15 to 30 s, default 20), volume | Value announced on change; steps of 1 s or 5%. No ramp start level slider (owner decision 2026-09-27). |
| `switch` | Toggles | Immediate effect; no save needed except inside the editor. |
| `time-picker` | Editor, onboarding | Scrolling wheels in their own card: hour and minute, plus AM/PM on 12 h phones, with "h" / "min" labels; momentum scrolling that snaps to one value, a light haptic tick and a short quiet bundled tick sound per value (owner decision 2026-09-28; the sound is silent on silent or vibrate, the haptic follows the system haptic setting), centre value selected; no keyboard ever opens. TalkBack reads each wheel as "Hour, 6" / "Minute, 45"; swipe up or down changes it by one. Targets ≥ 48 dp. (owner decision 2026-09-27, replaces keyboard input first) |
| `top-app-bar` | Pushed screens | Back returns; unsaved editor changes prompt "Discard changes?" |
| `nav-bar` | App root | Three items; hidden during the session lock. |
| `progress-dots` | Onboarding | Show step; back allowed; not tappable. |
| `progress-ring` | Progress | The last 30 mornings as outcome glyphs around the streak (owner redesign 2026-09-30). Tapping a dot with a session opens Day detail. The dots are read by TalkBack but are not separate buttons (30 × 48 dp does not fit a ring); the calendar's days are the accessible way to Day detail. Static: no animation. |
| `card-streak` | Progress | Current and best streak on the accent-tinted glass. Not tappable. |
| `stat-tile` | Progress | On-time rate (30 d), average minutes from first ring to up, snoozes (30 d). Three in a row, 2 + 1 at large font scales. Not tappable. |
| ~~`bar-chart`~~ | Removed | Owner decision 2026-10-01 (feedback item 24): no snoozes chart on Progress, against FR-PRG-2's "snoozes chart". |
| `outcome-marker` | Ring, calendar, Day detail | Meaning carried by shape as well as colour (owner decision 2026-10-01), so no legend: the label is on the tap chip and in Day detail, and spoken by TalkBack ("{date}, {outcome}"). |
| `chip-day` (label chip) | Progress ring, calendar | A first tap on a day shows "{Tue 23} · {Snoozed}" with a chevron under the ring or calendar; a second tap on the day or a tap on the chip opens Day detail. Pops in (instant with reduced motion). |
| `calendar-day` | Progress | Tap → Day detail. TalkBack: "Tuesday 14, on time" (or snoozed, missed, test, skipped; "fallback check used" when relevant). Skipped rendering is conditional on PRD Q9. |
| `checklist-row` | Reliability checklist | "Fix" deep-links to the system setting; status re-checked on return. Camera and microphone rows appear only when needed. |
| `settings-row` | Editor, Settings, pickers | Progressive disclosure (owner decision 2026-09-27): title with the current value as a subtitle and a chevron; tap opens the sub-screen that sets it (back arrow returns, the change is kept). A toggle row has a switch instead. Rows unavailable during a session. |
| `card-group` | Every app screen | Related rows grouped in one glass card with dividers (owner decision 2026-09-27, like the stock Clock apps). Not interactive itself. |
| `pill-save` | Editor (and later setup screens with a Save) | "Cancel \| Save" in its own bottom area (owner decision 2026-09-28): content scrolls above it, never under it, and the last row is fully visible at the end. Cancel = Back (unsaved changes ask "Discard changes?"). Stays above the keyboard with the focused field visible. Save is disabled while saving. |
| `header-collapsing` | Home | Continuous and tied to the scroll position, never a snap (owner decision 2026-09-28, Samsung Weather): the title stays pinned and gains its glass chip as content scrolls under it; the hero collapses into the compact "{streak} days on time" chip; alarm cards scroll underneath and fade out as they enter the header zone (no card text readable behind or above the chips); the title shrinks to `title` size so the compact chip stays on its row. Scrolled to the end, the last card is fully above the FAB. Reduced motion: each part switches instantly at the halfway point. The compact chip is not read by TalkBack (the hero card says the same). |
| `glass-bar` | Bottom pill, nav bar, snooze confirm sheet | Translucent glass over moving content; blurs it on Android 12+, plain translucent below. |
| `background-gradient` | Every screen | Static; never animates. |
| `purchase-row` | Purchase history | Read-only. Shows what was charged; stranded purchases read "Not used, refunded automatically by Google". Self-requested refunds are not detectable (PRD §6.3). |
| `sound-row` | Sound picker | Tap selects; play button previews at alarm volume, stops on leaving. Missing custom file shows "File missing. Default sound will play." |
| `recorder` | Recordings | Tap to record (mic permission asked on first use), tap to stop, auto-stop at 60 s. Then Play, Re-record, Save, Delete. |
| `motivation-player` | Success | Plays automatically when set to "After I'm up". Pause / replay. "Done" stops playback and closes. |
| `dialog-confirm` | Disable/delete under lock, delete alarm, delete all data, discard changes | Two actions; the safe action is the default dismiss. Logged actions say "This is logged." |
| `snackbar` | App and wake screens | App: 4 s, optional action. **Wake screens: no action, stays at least 10 s or until the next tap**, announced politely by TalkBack. |
| `notification-ringing` | During a session | Ongoing, alarm category, full-screen intent. Tap anywhere returns to the wake screen. The sound never stops from the notification. |
| `notification-summary` | Weekly (default on, FR-PRG-5) | Sunday evening. Tap opens Progress. Toggle in Settings > Notifications. |
| `skeleton` | App screens only | Shown after 300 ms of loading; never on wake screens. |

## State Patterns

### Wake screens (never a loading state; render within 1 s from cached data, NFR-7)

| State | Surface | Treatment |
|---|---|---|
| Ringing, first ring | Ringing | Label, clock, date, "I'm up", "Snooze · {price}". Volume ramps if "Gradually increase volume" is on, otherwise starts at the set level (FR-ALM-6). |
| Ringing after a snooze | Ringing | Session line "Snooze {n} of {max} · {paid} paid this morning"; price shows the next step. |
| Snooze unavailable | Ringing, Check | `button-snooze-disabled` with reason: offline, max snoozes reached, price cap reached, payment pending. |
| Test alarm | All wake screens | `button-snooze-disabled` "Test · no charge". No payment, no stats beyond Test. |
| Before first unlock (Direct Boot) | Ringing, Check | Snooze shows `button-snooze-disabled` with lock icon "Unlock your phone to snooze"; enabled once unlocked. House Hunt / QR swap to Math and custom sound or recording to default for this ring, with `note-inline` "Your phone restarted, so today's check is Math." (FR-ALM-11). |
| Locked, after first unlock | Snooze confirm | "Pay" moves the sheet to the *unlocking* state, then keyguard, then Play sheet. |
| Already paid (stranded purchase) | Snooze confirm | Shown after "Pay" when a stranded token for that price exists. "Use it" grants the snooze with no new charge. "Not now" returns to ringing with Snooze disabled "An earlier {price} payment is being refunded". |
| Grace running | Check | `countdown-ring` counting, alarm muted, vibration only if "vibrate in grace" is on (FR-SES-6). |
| Grace expired | Check | "Alarm's back on", full volume, progress kept (FR-PWK-9). |
| Snooze tapped during grace | Snooze confirm over Check | Mute continues only until the countdown ends (FR-RNG-5). |
| Grace expires while sheet is open | Snooze confirm | Sheet stays; alarm returns at full volume behind it. |
| Wrong answer | Check | Shake, error haptic, field cleared, error text in `{colors.error-sunrise}`. |
| Camera denied, unavailable or failed to start | Check (House Hunt, QR) | "Camera isn't available. Pick a fallback check." and `fallback-link` immediately. |
| Fallback check | Fallback check picker, Check | Alarm keeps ringing; no new grace window (FR-PWK-11). |
| Phone call | Wake screens | Sound, grace countdown and 30-minute timer paused; `note-inline` "Paused for your call. Rings again when it ends." (FR-SES-8). |
| Restored after crash, kill or reboot | Wake screens | Same step as before (ringing, check progress, snoozed); snooze count and paid amount kept (FR-SES-1/2). No "restored" message. |
| Overlapping alarm | Wake screens | Merged silently; noted in Day detail "{time} alarm merged into this session" (FR-SES-7). |
| Missed (30 min, no interaction) | Wake screens | Alarm stops, session logged Missed (FR-ALM-9); Home shows the missed note next open. |
| Success, zero snooze | Success | Celebration once (owner decision 2026-09-28): the number counts up from n−1 to n with a small bounce, a 1.5 s confetti burst, one success haptic; motivation playback if set, brightness restored on "Done". Reduced motion: the final state at once, no confetti (the haptic stays). |
| Success, after snooze | Success | "You're up. That's what counts." plus "{paid} paid this morning" in `{colors.text-secondary-sunrise}`. No animation. |
| Snoozed | Snoozed | "Snoozed. Next ring at {time}." for 3 s, then screen off. |

### Payment outcomes (Snooze confirm → return state; alarm at full volume throughout, FR-RNG-4/5)

| Outcome | Message (`snackbar`, wake rules) | Returns to | Snooze still offered? |
|---|---|---|---|
| Unlock cancelled or failed | "Phone still locked. No charge." | Ringing (or Check) | Yes |
| Play sheet cancelled | "Payment cancelled. No charge." | Ringing (or Check) | Yes |
| Billing error | "Payment didn't go through. No charge." | Ringing (or Check) | Yes |
| No connection | "No connection. No charge." | Ringing (or Check) | No: "Snooze unavailable: offline" until connectivity returns |
| Pending | "Payment not confirmed yet. If it goes through before you finish, your snooze starts. Otherwise, finish the check to stop the alarm." | Ringing (or Check) | No: "Snooze unavailable: payment pending" for this session |
| Purchased | none | Snoozed | n/a; next price is B × (N+1) |
| Already paid, "Use it" | none | Snoozed | n/a |
| Already paid, "Not now" | none | Ringing (or Check) | No at that price: "An earlier {price} payment is being refunded" |

### App screens

| State | Surface | Treatment |
|---|---|---|
| Loading | All app screens | `skeleton` after 300 ms; text if longer than 3 s. |
| Offline | All app screens | Everything works except payment (NFR-3). No banner. |
| Prices never loaded | Onboarding base fee, Settings base fee | USD tiers with `note-inline` "Approximate. Your local price shows when you're online."; onboarding continues. |
| Empty | Home | "No alarms yet." + `button-filled` "Add your first alarm". |
| Permission or setting missing | Home, Settings | `banner-warning` "Alarms may not ring. Fix settings". |
| Missed session | Home | `note-inline` "Your {time} alarm stopped after 30 minutes. Logged as missed." until dismissed. |
| Fallback 3 times in 7 days | Home | `banner-warning` info variant with "Re-register". |
| Session active | Home, whole app | `panel-session-in-progress` only. |
| Weakening under lock | Alarm editor, Settings | `note-inline` "Saved. Takes effect after tomorrow's {time} alarm." |
| No check selected | Alarm editor | Save blocked, inline error "Pick at least one check." under the Wake-up check row (value "None") and in its sub-screen. |
| One-time alarm time already passed today | Alarm editor | Schedules the next day; `note-inline` "Rings tomorrow at {time}." |
| Custom sound missing | Sound picker, editor | "File missing. Default sound will play." (FR-SND-5). |
| No recordings / mic denied | Recordings | "Record a message for your morning self." / "Microphone is off. Turn it on in Settings." with "Fix". |
| Empty | Progress | "Your first morning shows up here." |
| Test or skipped day | Day detail | Outcome label only; excluded from rates and streaks. |
| Empty | Purchase history | "No snoozes paid. Keep it that way." |
| All OK | Reliability checklist | Every row "OK"; `button-outlined` "Ring a test alarm" stays. |
| Session active | Settings | Not reachable (session lock). |
| Large font (200%) and TalkBack | All | See Accessibility Floor. |

## Interaction Primitives

- **Tap to act.** Long-press only on `card-alarm` (Duplicate / Delete), always duplicated in a menu.
- **Progressive disclosure (owner decision 2026-09-27).** A screen never shows every option: rows show their value and open a sub-screen. The same grouped-card, rows to sub-screen pattern applies to Settings (round 2), onboarding and check setup (round 3).
- **Back:** on wake screens Back does nothing (Home and Recents still work). In the confirm sheet, Back = "I'll get up" path.
- **Anti-double-tap:** `sheet-snooze-confirm` ignores input for 500 ms after opening; "I'll get up" sits under the thumb position of the Snooze tap.
- **Motion** (motion intensity 4/10):

| Pattern | Spec |
|---|---|
| Standard transition | 250 ms, Material emphasized easing |
| Countdown ring | Linear, exact to the second |
| Memory tiles | 350 ms highlight, 150 ms gap |
| Success (zero-snooze) | One 1.5 s celebration, linear: at 20% the number steps from n−1 to n and bounces (scale up to 1.2 and back over 25%); seeded confetti bursts from behind the number, falls and fades out from 70%; one success haptic at the start. Then calm. Compose drawing only, no GIF, no library. |
| Wrong answer | 200 ms horizontal shake + error haptic |
| Screens and sub-screens (owner decision 2026-09-27) | Slide in from the end and out the other way, 250 ms emphasized; the outgoing screen moves a quarter width |
| "Custom" repeat | Day chips expand and collapse |
| Switches and chips | Animate their state change (Material 3 switch, chip colour fade) |
| Time wheel | Momentum scroll with snapping; light haptic tick and a quiet tick sound per value |
| Home header (owner decision 2026-09-28) | Scroll-linked collapse: title chip fades in over 16 dp; hero fades out by 60%, shrinks 10% and lags; the compact streak chip cross-fades in from 50% and rises 8 dp |
| Alarm cards | Animate in and out when added or removed |
| "I'm up" pulse (Ringing) | Scale 1 to 1.03 and back, 1.2 s, repeating; drawn only |

- **Reduced motion:** when the animator duration scale is 0, every motion becomes an instant state change and the "I'm up" pulse does not run; the Home header switches between its rest and collapsed states at the halfway point instead of following the scroll; the countdown still counts as numbers.
- **Haptics and sounds:** light tick on each digit or tile tap and on each time-wheel value, plus the bundled wheel tick sound (12 ms, played quietly on the sonification stream, never while the phone is on silent or vibrate; not the system click that "Touch sounds" controls); success pattern on completion; strong buzz when the alarm returns after grace; a short tick every 5 s during grace (countdown cue that works without sound or sight).
- **Volume keys:** captured only while the wake screen is in the foreground (FR-SES-6). The accessibility shortcut (both volume keys held) always passes through.
- **Banned:** decorative motion, pre-selected payment buttons, disguised or hidden snooze, confirm-shaming copy, carousels, streak-loss threats, badge counts.

## Session Integrity and Device Safety

The alarm must be hard to escape without holding the phone hostage (Google Play policy).

- **The phone stays usable during a session.** Home, Recents, other apps, calls and the lock-screen emergency call all work. The alarm sound continues on the alarm stream.
- **No relaunching screens from the background.** The app never starts an activity from the background to force the wake screen back. `notification-ringing` (ongoing, full-screen intent, heads-up on unlocked Android 14+) is the way back: tapping it returns to the wake screen.
- **Volume keys** are captured only while the wake screen is foreground. Elsewhere they behave normally. The set volume on the alarm stream is re-applied at the start of each ring and when a grace window ends (FR-SES-6), never continuously.
- **Backup alarm and restore** (FR-SES-1/2) bring the sound back after a kill or reboot through a scheduled alarm, not a background activity start.
- **Force-stop and uninstall** remain accepted escapes.
- The alarm behaviour disclosure (FR-ONB-5) states these rules before the first alarm is saved.

## Accessibility Floor

Behavioural. Visual contrast lives in `DESIGN.md > Colors`.

- **Waking up is always free, for everyone.** Camera checks offer the fallback check immediately when the camera or its permission is unavailable, otherwise after 5 failed attempts. The Fallback check picker lists only non-camera checks as selectable `check-type-card`s, with **Math always first and always available**; its TalkBack-friendly input reads the problem as words ("47 plus 38"), announces each `number-pad-key`, announces the current answer, and needs no timing or visual matching. Memory Sequence is offered in its numbered, announced variant. Count and difficulty follow PRD FR-PWK-11 (v0.2).
- **Check picker warnings:** camera checks say "Needs the camera. If it can't be used, you'll get a fallback check."; with TalkBack on, Memory Sequence says "Uses numbered tiles with TalkBack."
- **TalkBack:** every control labelled with role and state; icon-only controls have content descriptions; `button-snooze-disabled` reads its reason; day chips read full day names ("Tuesday", "Thursday"); `clock-xl` reads the full time.
- **Countdown:** announced every 10 s and at 5 s while the alarm is muted; haptic tick every 5 s as the non-audio cue.
- **Outcomes** never rely on colour: `outcome-marker` glyphs (filled check, clock, cross, hollow ring) plus labels.
- **Targets:** ≥ 48 dp everywhere; every wake action ≥ 64 dp (`{spacing.target-wake}`), in the thumb zone, never scrolled off screen.
- **Font scale 200%:** layouts reflow, never clip; `clock-xl` caps at 1.3× so wake actions stay visible.
- **Reduced motion** honoured (see Interaction Primitives).
- **Brightness:** "Bright wake screen" is explained in onboarding and respects the system Extra dim setting.
- **Volume keys:** the accessibility shortcut is never captured.
- Focus order follows reading order; on wake screens initial focus is the clock, then "I'm up".

## Inspiration & Anti-patterns

- **Lifted from Alarmy:** missions as the way to dismiss; a menu of check types instead of one.
- **Lifted from Taste Skill:** the three locks, dials, anti-slop visual and copy rules (adapted, see `DESIGN.md`).
- **Lifted from Material 3:** components, sheets and accessibility behaviour as the base.
- **Rejected: single-mechanism pay-to-snooze apps** (Unsnooze, Paid Alarm Clock, Slooze/Looze, Nuj, WAKEorDONATE): one fixed way to wake and payment framed as the product.
- **Rejected: category palettes.** Sleep-app navy/purple and hard-alarm red/black.
- **Rejected: casino and shaming tones.** No celebration of payments, no guilt copy, no confirm-shaming.
- **Rejected: hostage alarms** that relaunch themselves over other apps or block the phone.

## Key Flows

Protagonists: **Linh**, 24, junior developer, the chronic snoozer (three or four snoozes most mornings); **Marco**, 31, nurse, the routine builder, shares a bedroom with **Ana** (affected bystander); **Dara**, 29, low vision, TalkBack user.

### F1 — First-run setup and test alarm (Linh, 23:40, the night before a 9:00 stand-up)

1. Linh opens the app. Mission: "This app makes money only when you snooze. We hope you never pay us." She taps "Let's set it up".
2. The alarm behaviour disclosure says the alarm keeps ringing until the check or a paid snooze, that her phone stays usable, and lists the other ways to stop it. She taps "I understand".
3. Base fee `stepper` shows local prices from Google Play; she keeps $1 and sees "Snooze 1: $1 · 2: $2 · 3: $3".
4. She sets 7:30, weekdays, with `time-picker` and `chip-day`.
5. She selects Math and QR/Barcode (mode Random) and taps "Try it" on Math.
6. Reliability checklist: she taps "Fix" on battery optimization and full-screen alarm; both turn "OK".
7. She leaves usage stats off. "Lock your phone. We'll ring in 10 seconds." She locks it.
8. The Sunrise ringing screen appears; Snooze shows "Test · no charge". She taps "I'm up" and solves one problem.
9. **Climax:** Success appears and she sees the alarm really rings over a locked phone, for free.

Failure: offline on first launch → base fee shows USD tiers with "Approximate. Your local price shows when you're online." and onboarding continues. She skips the test alarm → Home shows a `note-inline` recommending it (FR-ONB-4).

### F2 — On-time morning with the grace window (Marco, 5:45, Ana asleep)

1. Alarm rings at 5:45; volume ramps; brightness goes up.
2. Marco taps "I'm up" (72 dp, bottom of screen).
3. Alarm goes silent; `countdown-ring` shows "Quiet for 20s".
4. He solves two Math problems in 14 s.
5. **Climax:** "Up on time. 12 days in a row." The streak number scales once; Ana never heard a second ring.
6. He taps "Done"; brightness returns to normal.

Failure: see F3.

### F3 — Grace window expiry (Linh, 7:30, partner Sam asleep)

1. Linh taps "I'm up"; 20 s countdown starts.
2. She fumbles the first Word Unscramble.
3. The ring reaches 0: "Time's up. Alarm's back on until you finish.", strong haptic, full volume.
4. Her progress (one word of two) is kept.
5. **Climax:** she finishes the second word and the alarm stops. "You're up. That's what counts."

Failure: she taps Snooze during grace → the mute continues only until the countdown ends; see F4.

### F4 — Paid snooze with rising fee, and a failed payment (Linh, 7:30, phone locked)

1. Ringing shows "Snooze · $1". She taps it.
2. `sheet-snooze-confirm` rises; for 500 ms taps do nothing. "Snooze for 9 min?" · "$1" · "The next one costs $2." · "Is 9 more minutes worth $1? You've got this."
3. Her thumb is on "I'll get up" (bottom). She reaches up to "Pay $1 and snooze".
4. The phone is locked: the sheet shows "Unlock to pay $1"; she unlocks with her fingerprint.
5. The Google Play sheet opens; the alarm keeps ringing at full volume.
6. Purchase completes: "Snoozed. Next ring at 7:39." Screen off.
7. At 7:39 it re-rings: "Snooze 1 of 5 · $1 paid this morning", button "Snooze · $2".
8. **Climax:** she looks at "$2", hears "You've got this" in her head, and taps "I'm up".

Failure: Play reports a pending payment → "Payment not confirmed yet. If it goes through before you finish, your snooze starts. Otherwise, finish the check to stop the alarm."; Snooze becomes "Snooze unavailable: payment pending"; if Play confirms it while she is still on the check, the snooze starts automatically (PRD §6.3 recovery); otherwise she does the check and Success says "Your pending payment wasn't used. Google refunds it automatically." Other failures (unlock cancelled, cancelled, error, offline) follow the Payment outcomes table; every one says "No charge." and returns to ringing at full volume. If a payment cleared too late to be used, her next "Pay" at that price opens the "already paid" state: "You already paid $1 earlier that wasn't used. Use it for this snooze?"

### F5 — Fallback check (Dara, 6:15, TalkBack on, camera permission revoked by a system cleanup)

1. Alarm rings; TalkBack reads "6:15. I'm up, button."
2. Dara taps "I'm up"; her check is QR/Barcode.
3. The camera can't start: "Camera isn't available. Pick a fallback check." with "Can't do this check?" available immediately.
4. She opens the Fallback check picker; Math is first.
5. TalkBack reads "47 plus 38". She types 85 on the announced number pad and double-taps "Check".
6. **Climax:** the alarm stops without a single payment or sighted step. Day detail later shows the fallback badge.

Failure: with the camera working, after 5 failed scans the link appears. A second fallback in the same session is not offered (once per session). After 3 fallbacks in 7 days, Home suggests re-registering the code.

### F6 — Commitment lock change (Linh, 23:40, alarm at 7:30)

1. Linh opens Settings and lowers the base fee from $3 to $1.
2. `note-inline`: "Saved. Takes effect after tomorrow's 7:30 alarm."
3. She goes to Home and toggles the 7:30 alarm off.
4. `dialog-confirm`: "Turn off your 7:30 alarm? It rings in 8 h. This is logged." She picks "Keep it on".
5. **Climax:** the plan made by her daytime self holds; nothing blocked her, but nothing let her quietly weaken it.

Failure: she confirms "Turn off" → alarm off, entry logged in Day detail history; raising the fee or making checks harder always applies immediately.

### F7 — Reliability warning (Marco, after a phone update)

1. A system update turns battery optimization back on.
2. Marco opens the app: `banner-warning` "Alarms may not ring. Fix settings".
3. He taps "Fix"; the checklist shows "Alarm may not ring: battery optimization turned back on".
4. "Fix" deep-links to system settings; he allows it and comes back.
5. **Climax:** the row turns "OK" and the banner disappears by itself.
6. He taps "Ring a test alarm" to be sure.

Failure: he ignores it → the banner stays on Home (not dismissible) until fixed.

### F8 — Overnight reboot before first unlock (Linh, 7:30, phone restarted for an update at 3:00)

1. The alarm rings on the lock screen before Linh has ever unlocked since the reboot.
2. Wake screen shows "Your phone restarted, so today's check is Math."; default sound plays.
3. Snooze is disabled with a lock icon: "Unlock your phone to snooze".
4. She taps "I'm up" instead; grace starts.
5. **Climax:** two Math problems and she is up; the alarm she relied on survived a reboot.

Failure: she swipes up and enters her PIN on the lock screen; the wake screen stays on top, the button becomes "Snooze · $1", the Math check stays for this ring, and F4 continues.

### F9 — Review progress (Marco, Sunday evening)

1. The weekly summary notification says "6 on-time mornings, nothing paid. Nice." He taps it.
2. Progress: streak 12 (best 12) on the ring, on-time 86% (30 d), average 3 min to up, 2 snoozes.
3. He taps a calendar day with a clock glyph: Day detail shows one snooze, $2, Math, 7 min.
4. Purchase history lists the $2 charge.
5. **Climax:** he sees 12 on the ring and one snooze all week; nothing to fix.

Failure: nothing logged yet → the empty ring with "Your first morning shows up here." (CSV export removed, owner decision 2026-10-01.)

### F10 — Record a motivation message (Linh, evening)

1. In the editor "Motivation" section, Linh opens Recordings.
2. First use asks for the microphone; she allows it.
3. She taps `recorder`, says "Stand-up is at nine. You like being early.", stops at 0:09.
4. She plays it back, saves, and picks "After I'm up".
5. **Climax:** next morning, Success plays her own voice.

Failure: microphone denied → "Microphone is off. Turn it on in Settings." with "Fix"; the alarm itself is unaffected.

## Open Questions

- **D1** App name and icon concept (PRD Q8).
- **D2** Should "Bright wake screen" also flash the torch on the first ring? Off by default if added; weigh photosensitivity.
- **D3** Onboarding illustration style: none (type-only, current plan) or simple line illustrations?
- **D4** Fallback check count and difficulty per option; spines assume Math is always offered.
- **D5** Resolved: PRD FR-PWK-11 adopted the selectable non-camera fallback picker (Math first).
