# Design preview: states

Every state of the debug design preview, with its deep-link id. Generated from `PreviewCatalog` and the
tap-throughs; `PreviewMenuTest` fails when it is out of date. Rewrite it with
`PREVIEW_WRITE_STATES=true ./gradlew :androidApp:testDebugUnitTest --tests "*PreviewMenuTest"`.

Open one directly on the phone (debug build installed; Back returns to the menu):

```sh
adb -s 4d804fdd shell am start -S -n com.yawnandpawn.app/.debug.preview.PreviewActivity --es state progress-empty --es theme dark --ez font200 true
```

`--es theme light|dark` (default light) and `--ez font200 true` (default off) are optional. In Git Bash set
`MSYS_NO_PATHCONV=1` first. Wake screens are always Sunrise, whatever the theme.

## Round 1 · The daily loop

| Id | Screen | State |
|---|---|---|
| `tap-app` | Tap through | Tap through the app: tabs, the + for a new alarm, editor, test alarm |
| `tap-morning` | Tap through | Tap through a morning: Back to alarm, Ringing, Check, Success |
| `home-list` | Alarms (Home) | Streak, next alarm, alarm cards |
| `home-half-collapsed` | Alarms (Home) | Scrolled: header half collapsed |
| `home-collapsed` | Alarms (Home) | Scrolled: header collapsed, cards under it |
| `home-paid` | Alarms (Home) | Paid this week, rings in 45 min |
| `home-days-away` | Alarms (Home) | Next alarm in 2 d 3 h |
| `home-empty` | Alarms (Home) | Empty |
| `home-missed` | Alarms (Home) | Missed note and re-register banner |
| `home-reliability` | Alarms (Home) | Permission missing banner |
| `home-session` | Alarms (Home) | Session in progress |
| `home-disable-dialog` | Alarms (Home) | Turn off under the commitment lock |
| `editor-full-new` | Alarm editor | New alarm |
| `editor-full-edit` | Alarm editor | Edit alarm: weekdays, two checks, a message |
| `editor-custom-days` | Alarm editor | Custom repeat days |
| `editor-no-check` | Alarm editor | No check selected |
| `editor-weakening` | Alarm editor | Weakening under lock |
| `editor-sound-missing` | Alarm editor | Custom sound missing |
| `editor-rings-tomorrow` | Alarm editor | One-time alarm rings tomorrow |
| `editor-sound` | Alarm editor sub-screens | Sound: volume, gradual volume, sounds |
| `editor-sound-previewing` | Alarm editor sub-screens | Sound: preview playing |
| `editor-snooze` | Alarm editor sub-screens | Snooze: length and fee ladder |
| `editor-wake-check` | Alarm editor sub-screens | Wake-up check: checks, mode, difficulty |
| `editor-wake-check-none` | Alarm editor sub-screens | Wake-up check: none selected |
| `editor-quiet-time` | Alarm editor sub-screens | Quiet time |
| `editor-motivation` | Alarm editor sub-screens | Motivation |
| `ringing-first` | Ringing | First ring (Sunrise) |
| `ringing-after-snooze` | Ringing | After a snooze (Sunrise) |
| `ringing-test` | Ringing | Test alarm (Sunrise) |
| `ringing-locked` | Ringing | Before first unlock (phone restarted) (Sunrise) |
| `ringing-offline` | Ringing | Snooze unavailable: offline (Sunrise) |
| `ringing-max-snoozes` | Ringing | Snooze unavailable: max snoozes (Sunrise) |
| `ringing-stranded` | Ringing | Earlier payment being refunded (Sunrise) |
| `ringing-phone-call` | Ringing | Paused for a call (Sunrise) |
| `sheet-confirm` | Snooze confirm sheet | Confirm (Sunrise) |
| `sheet-last-snooze` | Snooze confirm sheet | Last snooze, tax note (Sunrise) |
| `sheet-unlocking` | Snooze confirm sheet | Unlock step (Sunrise) |
| `sheet-already-paid` | Snooze confirm sheet | Already paid (Sunrise) |
| `payment-unlock-failed` | Payment outcomes | Unlock cancelled (Sunrise) |
| `payment-cancelled` | Payment outcomes | Payment cancelled (Sunrise) |
| `payment-error` | Payment outcomes | Billing error (Sunrise) |
| `payment-offline` | Payment outcomes | No connection (Sunrise) |
| `payment-pending` | Payment outcomes | Pending (Sunrise) |
| `check-math` | Check | Math, grace running (Sunrise) |
| `check-math-wrong` | Check | Math, wrong answer, grace expired (Sunrise) |
| `check-word` | Check | Word Unscramble (Sunrise) |
| `check-memory-watch` | Check | Memory Sequence, watch (Sunrise) |
| `check-memory-turn` | Check | Memory Sequence, your turn (numbered) (Sunrise) |
| `check-qr` | Check | QR/Barcode (Sunrise) |
| `check-qr-wrong` | Check | QR/Barcode, wrong code (Sunrise) |
| `check-qr-camera-unavailable` | Check | Camera unavailable (Sunrise) |
| `check-house-hunt` | Check | House Hunt (Sunrise) |
| `check-house-hunt-no-match` | Check | House Hunt, no match yet (Sunrise) |
| `check-test` | Check | Test alarm check (Sunrise) |
| `check-sheet` | Check | Snooze tapped during grace (Sunrise) |
| `fallback-picker` | Fallback check picker | Pick a fallback check (Sunrise) |
| `success-on-time` | Success and Snoozed | Zero snooze, 12-day streak (Sunrise) |
| `success-first` | Success and Snoozed | Zero snooze, before streaks (Sunrise) |
| `success-after-snooze` | Success and Snoozed | After a snooze (Sunrise) |
| `success-pending` | Success and Snoozed | Pending payment not used (Sunrise) |
| `success-test` | Success and Snoozed | Test finished (Sunrise) |
| `snoozed` | Success and Snoozed | Snoozed (Sunrise) |

## Round 2 · Progress and settings

| Id | Screen | State |
|---|---|---|
| `tap-progress` | Tap through | Tap through Progress: ring, chart, calendar, day detail, purchase history |
| `tap-settings` | Tap through | Tap through Settings: sub-screens, reliability checklist |
| `tap-you` | Tap through | Tap through You: purchase history, payments, delete dialog |
| `progress-full` | Progress | Ring of 30 mornings, tiles, week chart, streak, calendar, money, insight |
| `progress-dot-chip` | Progress | A ring dot tapped: its label chip |
| `progress-calendar-chip` | Progress | A calendar day tapped: its label chip |
| `progress-empty` | Progress | Empty ring |
| `day-snoozed` | Day detail | Snoozed twice, paid: the morning's timeline |
| `day-fallback` | Day detail | Fallback check, merged alarm, alarm turned off |
| `day-before-unlock` | Day detail | Before first unlock, check switched, quiet time ran out |
| `day-two-sessions` | Day detail | Two sessions |
| `day-missed` | Day detail | Missed, alarm deleted |
| `day-test` | Day detail | Test day |
| `day-skipped` | Day detail | Skipped day |
| `purchases-list` | Purchase history | Charges by month, one refunded |
| `purchases-empty` | Purchase history | Empty |
| `settings-main` | Settings | All sections |
| `settings-reliability-banner` | Settings | Permission missing banner |
| `you-main` | You | Money, privacy and your data, help |
| `you-delete-dialog` | You | Delete all data |
| `you-no-browser` | You | Link without a browser |
| `settings-session` | Settings | During a session (session lock) |
| `settings-base-fee` | Settings sub-screens | Base fee |
| `settings-base-fee-weakening` | Settings sub-screens | Base fee lowered under lock |
| `settings-base-fee-approximate` | Settings sub-screens | Base fee, prices never loaded |
| `settings-max-snoozes` | Settings sub-screens | Max snoozes per session |
| `settings-snooze-length` | Settings sub-screens | Default snooze length |
| `settings-quiet-time` | Settings sub-screens | Default quiet time |
| `reliability-missing` | Reliability checklist | Items missing and revoked |
| `reliability-all-ok` | Reliability checklist | All OK |
| `reliability-manufacturer` | Reliability checklist | Manufacturer steps (Xiaomi) |
| `payments` | Payments & refunds | How payments & refunds work |
| `payments-problem` | Payments & refunds | Problem with a charge? |
| `payments-problem-no-browser` | Payments & refunds | Problem with a charge?, no browser |

## Round 3 · Setup flows

| Id | Screen | State |
|---|---|---|
| `tap-onboarding` | Tap through | Tap through onboarding: 8 steps, check setup, try it, test alarm, then Home |
| `tap-checks` | Tap through | Tap through checks: picker, setup, try it, QR and House Hunt registration |
| `tap-recordings` | Tap through | Tap through recordings: record, save, play, delete |
| `onboarding-mission` | Onboarding | 1 Mission |
| `onboarding-disclosure` | Onboarding | 2 Alarm behaviour disclosure |
| `onboarding-base-fee` | Onboarding | 3 Base fee |
| `onboarding-base-fee-approximate` | Onboarding | 3 Base fee, prices never loaded |
| `onboarding-first-alarm` | Onboarding | 4 First alarm |
| `onboarding-checks` | Onboarding | 5 Checks |
| `onboarding-checks-none` | Onboarding | 5 Checks, none selected |
| `onboarding-reliability` | Onboarding | 6 Reliability checklist |
| `onboarding-analytics` | Onboarding | 7 Analytics choice |
| `onboarding-test` | Onboarding | 8 Test alarm |
| `onboarding-test-not-locked` | Onboarding | 8 Test alarm, phone not locked |
| `home-test-skipped` | Onboarding | Home after skipping the test alarm |
| `check-picker` | Check picker | Math and QR/Barcode, Random |
| `check-picker-all` | Check picker | All mode: the order |
| `check-picker-talkback` | Check picker | Memory Sequence with TalkBack on |
| `check-picker-camera-unavailable` | Check picker | Camera unavailable |
| `check-setup-math` | Check setup | Math: difficulty, problems, Try it |
| `check-setup-memory-talkback` | Check setup | Memory Sequence with TalkBack on |
| `check-setup-qr` | Check setup | QR/Barcode, code saved |
| `check-setup-qr-none` | Check setup | QR/Barcode, no code yet |
| `check-setup-qr-camera-unavailable` | Check setup | QR/Barcode, camera unavailable |
| `check-setup-house-hunt` | Check setup | House Hunt, two photos |
| `check-setup-house-hunt-lost` | Check setup | House Hunt, photos not restored |
| `try-it-math` | Check setup: Try it | Math (Sunrise) |
| `try-it-word` | Check setup: Try it | Word Unscramble (Sunrise) |
| `try-it-memory` | Check setup: Try it | Memory Sequence (Sunrise) |
| `try-it-done` | Check setup: Try it | Solved: Nice. That's how it works. (Sunrise) |
| `qr-scanning` | QR registration | Scanning |
| `qr-detected` | QR registration | Code found |
| `qr-camera-unavailable` | QR registration | Camera unavailable |
| `qr-printable` | QR registration | Printable QR |
| `qr-replace-dialog` | QR registration | Printable QR, replace the old code |
| `house-hunt-empty` | House Hunt registration | No photos yet |
| `house-hunt-two-photos` | House Hunt registration | Two photos |
| `house-hunt-matched` | House Hunt registration | Three photos, test match matched |
| `house-hunt-no-match` | House Hunt registration | Test match, doesn't match yet |
| `house-hunt-photo-failed` | House Hunt registration | Couldn't use that photo |
| `house-hunt-need-photo` | House Hunt registration | Save without a photo |
| `house-hunt-camera-unavailable` | House Hunt registration | Camera unavailable |
| `recordings-empty` | Recordings | Empty |
| `recordings-recording` | Recordings | Recording |
| `recordings-take` | Recordings | A take: play, re-record, save, delete |
| `recordings-list` | Recordings | Saved messages, one playing |
| `recordings-mic-denied` | Recordings | Microphone off |
| `recordings-too-short` | Recordings | Too short |
| `recordings-delete-dialog` | Recordings | Delete a message |
