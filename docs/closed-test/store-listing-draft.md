# Store listing draft (closed test)

Story 5.14 pulls the listing text forward from Story 8.1, because Play needs a main store listing before a closed track can be published. Story 8.1 later moves the final text to `docs/store/en-US/` with `StoreListingTest`. This draft already follows that story's rules: no em dash, no emoji, no hard-coded price or currency symbol, no "best", "#1" or "top", no percentages or "x times" claims, and no content word more than 5 times.

Paste each block exactly as it is inside the box (the copy button on GitHub copies it).

## App name (30 characters max)

```text
Yawn & Pawn
```

11 characters.

## Short description (80 characters max)

```text
Every snooze costs real money. Waking up with a quick check is always free.
```

75 characters. A second option, if the owner prefers the word "alarm" in it:

```text
An alarm clock where each snooze costs real money. Waking up stays free.
```

72 characters.

## Full description (4,000 characters max)

```text
Yawn & Pawn is an alarm clock for people who hit snooze too often. Here, every snooze costs real money, so getting up is the cheaper choice. We hope you never pay us.

HOW IT WORKS
When it rings, you have two choices:
• Tap "I'm up" and finish a short check you chose in advance. Waking up this way is always free, and it works offline.
• Or pay to snooze. The first one in a morning costs the base fee you set, and each one after costs one base fee more than the last.

You confirm every payment, and the price is shown first. Payments go through Google Play, in your local currency, and are real charges to your Google Play account.

THE CHECKS
• Math: solve a few quick sums.
• Word Unscramble: unscramble a few words.
• Memory Sequence: repeat a pattern of tiles.
• QR/Barcode: scan a code you placed away from your bed, or print one from the app.
While you work on it, the sound pauses for a short quiet time.

YOU STAY IN CONTROL
• Choose your base fee from ten price tiers, and how often you can pay per morning, from 1 to 5. After that, only the check is left.
• Raise your fee or make checks harder at any time. In the 8 hours before an alarm, changes that make it easier wait until it has rung, so your sleepy self can't undo your plan.
• Purchase history lists every charge. A charge that never gave you more sleep is refunded automatically by Google.

WHEN IT RINGS
It keeps ringing until you finish your check or pay. Your phone stays usable: calls, other apps and emergency calls all work. Other ways to stop it: force-stop or uninstall the app, turn off the phone, or leave it for 30 minutes (that morning counts as missed). Paying is not offered offline, before the first unlock after a restart, or during a test.

MADE TO RING
A reliability checklist walks you through notifications, full-screen display, exact timing, Do Not Disturb, battery settings and your phone maker's background settings. A test alarm on the lock screen shows it works on your phone, and never charges you.

MAKE IT YOURS
• Record a short message to yourself. Hear it once you are up, or mixed into the ringing.
• Wake up to your own song or sound file. If the file goes missing, a built-in sound plays instead.
• Need a day off? An optional reminder before the alarm lets you skip just that morning, for free.

SEE YOUR PROGRESS
Your streak of on-time mornings, on-time rate, a calendar of each morning and the money you paid.

PRIVACY
No account and no ads. Your alarms, history, recordings and purchase records stay on your phone. Crash reports are sent so we can fix failures. Anonymous usage stats are sent only if you say yes.

FOR ADULTS
Yawn & Pawn is for adults (18+). We recommend turning on purchase authentication in Google Play, so every purchase needs your fingerprint or password.
```

## Other listing fields

| Field | Value |
|---|---|
| Tags | Pick the closest offered, such as "Alarm clock" |
| App category | Tools (owner decision 2026-10-27) |
| Contact email | support@yawnandpawn.com |
| Website | https://yawnandpawn.com/ |
| Privacy policy | https://yawnandpawn.com/privacy/ |

## Honesty notes (why each claim is safe)

- **Real money:** "real charges to your Google Play payment method" matches the terms ("Each snooze is a one-time in-app purchase through Google Play").
- **Price ladder:** "each one after costs one base fee more than the last" is the fee formula base fee × N (PRD §6.2, terms "Snooze fees").
- **Ten price tiers, 1 to 5 snoozes:** terms "Snooze fees" (base fee 1 to 10 in steps of 1; max snoozes 1 to 5).
- **8 hours:** terms "Commitment lock".
- **Free and offline:** checks run on the phone; terms "What the app does" and the payments page.
- **Phone stays usable, other ways to stop it, 30 minutes:** the payments page "How the alarm behaves" and the terms, word for word in meaning (FR-ONB-5 disclosure).
- **Refunded automatically:** terms "Unused payments".
- **Checks listed:** only the four checks a user can pick in this build. House Hunt moved to v2 (owner 2026-10-27) and is not mentioned.
- **Make it yours (Epic 7, built 2026-10-28):** recordings play after "I'm up" (Story 7.3) or mixed into the ring (7.5); own sound file with a built-in fallback (7.4); printable QR (7.10); the skip reminder is optional and skipping is free (7.11, FR-ALM-10). Recordings stay on the phone (privacy policy, 7.1).
- **Progress:** Stories 6.3 to 6.7 are on `main`. The Sunday weekly summary (Story 6.8) is not built, so it is not mentioned.
- **Privacy paragraph:** matches the privacy policy's short version. It does not say "nothing leaves your phone", because crash reports always do.
- **No reliability promise:** the text says the checklist and test alarm help, never that the alarm always rings (terms "No guarantee that an alarm rings").

## Checks

Counted with a script when this draft was written; re-check after any edit.

- App name: 11 characters (limit 30).
- Short description: both options are under the 80 limit (counts above).
- Full description: about 2,790 characters (limit 4,000; recounted 2026-10-29 after adding Epic 7).
- No content word is used more than 5 times. Word forms were counted together ("snooze", "snoozes"; "alarm", "alarms"; "payment", "payments").
- No em dash, emoji, currency symbol, price, percentage, "x times" claim or superlative.
