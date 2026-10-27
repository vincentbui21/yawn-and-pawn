---
title: Privacy policy
permalink: /privacy/
---

{% comment %} Draft for the owner to review; not legal advice. Rewritten 2026-10-26 to match the code on main (docs/closed-test/owner-checklist.md, PRIVACY MISMATCH 1 to 7). Updated 2026-10-27 for Story 7.1: recorded messages and the microphone. House Hunt photos (moved to v2) and custom sounds are not built; add them here when they ship. {% endcomment %}

# Privacy policy

**Effective date:** 27 October 2026

Yawn & Pawn ("the app") is an alarm clock for Android, published on Google Play by the developer Yawn & Pawn ("we", "us"). This policy explains what the app keeps, what leaves your phone and how to delete it.

The short version:

- There is no account and no login, and we run no server of our own. Your alarms and wake-up history are never sent to us, and we never see your card or bank details.
- Your data stays on your phone. The only things that leave it are the backup copy in your own Google account, crash reports (with a record of when the app is opened), usage stats if you turn them on, and what Google Play needs to take a payment. Google's libraries in the app (the barcode scanner, Play Billing and Firebase) can also send Google their own diagnostic logs. Each is explained below.
- The app has no ads, and we never sell your data or use it for advertising.

## Data on your phone

The app keeps these on your phone:

- **Your alarms:** time, repeat days, label, sound (one of your phone's alarm sounds), volume, vibration, snooze length, grace window and the checks you chose. For a QR/Barcode check, the app keeps a one-way fingerprint (a hash) of the code you registered, never the code's text or a picture of it.
- **Your wake-up history:** when each alarm rang, how many times you snoozed, which checks you did and how each morning ended.
- **Your settings:** such as your base fee, max snoozes and theme.
- **Your recorded messages:** short voice messages (up to 1 minute each) that you record yourself to hear when you wake up, with the name and length of each.
- **Your purchase records:** for each snooze payment, Google's order ID, the product, the price and currency, the time, its status and which alarm and morning it was for. The app also keeps a one-way fingerprint (a hash) of Google's purchase token. The token itself is kept in a separate store on the phone that is never backed up.
- **Two random ids:** an install id, made when you first use the app, and a session id for each morning. They say nothing about who you are.

The app never asks for your name, email address or contacts, and has no location permission, so it never knows your precise location.

## Backup

Android's Auto Backup copies part of the app's data to your own Google account, so a new or reset phone can get your alarms back. Google stores this copy, and we can't read it. The same rules apply when you move to a new phone with Android's transfer tool.

- **Backed up:** your alarms, wake-up history, settings and purchase records, and the name and length of each recorded message.
- **Not backed up:** your recorded messages themselves (the sound), the morning in progress, payments that are not finished yet, Google's purchase tokens, the install id, your usage stats choice and the cached Google Play prices.

Because the sound of your recorded messages isn't in the backup, a phone restored from it lists your messages as missing, and you can record them again.

Backup follows your phone's settings, and you can turn it off there. The copy stays in your Google account after you uninstall the app; to remove it, delete your phone's backup in Google Drive (under Storage, then Backups) or turn off Google backup in your phone's settings.

## Crash reports

When the app crashes, it sends a crash report through Firebase Crashlytics, a Google service. This is always on, whatever your usage stats choice: a crash in an alarm app can mean an alarm that doesn't ring, and the reports are how we find and fix those.

A crash report contains where in the app's code the crash happened (class names and a stack trace), the phone model, the Android version, the app version and a random installation id made by Firebase. For errors the app reports itself, it removes the error message first. A crash that closes the app is reported as it happened, and the app's code is written so error messages don't hold your alarm labels, history or payments. A report also includes device state at the time, such as free memory and storage. The app adds no logs or notes of its own to a report. With usage stats on, a report also lists the usage stats events from just before the crash (see "Usage stats").

To count how many people a crash affects, Crashlytics also records when the app is opened (through Firebase Sessions, a part of Crashlytics), with the phone's maker and model, the Android version, the app's package name and the network type (such as Wi-Fi or mobile data). This happens even when usage stats are off.

Crashlytics can also send its own diagnostic logs about how it runs to Google.

## Usage stats

Usage stats are off until you say yes. The app asks once, during setup, and you can change your answer anytime in Settings.

With usage stats on, the app sends them through Firebase Analytics, a Google service, linked only to a random analytics id that Firebase makes (not to your name or email):

- **Three app events:** how a morning ended (on time, snoozed or missed), how many snoozes it took, and which check types were used. Each holds only a category or a number.
- **Firebase's automatic events,** which Firebase records by itself:
  - the app was opened for the first time, and each time it was opened (a session started);
  - how long the app was on screen;
  - the app or Android was updated (with the previous version number), the app's data was cleared, and the app was removed from the phone;
  - a crash happened (whether it closed the app, and when);
  - **each snooze you buy:** the product, its price and currency; and each Google Play refund of a snooze, with the product, amount and currency. Your card or bank details are never included.

With each event, Firebase records basic facts such as the phone model, Android version, app version and language. Google also works out an approximate location (country, region and city) from the network address and keeps it with the stats. Google doesn't keep the network address itself. The app never sets a user id, and the stats hold no name, email address or alarm labels. The app never collects the advertising ID, turns off ad storage and ad personalisation, and doesn't record which screens you open.

Test alarms send none of the three app events, and nothing from before you said yes is ever sent. Turning usage stats off stops them at once and deletes the analytics data on your phone, including its analytics id, so later stats can't be linked to earlier ones.

## Payments

Snooze payments go through Google Play Billing. Google takes the payment and handles your payment details and your Google account, under Google's own privacy policy. The app never sees your card or bank details.

To link each payment to the right morning, the app gives Google two random ids: the install id and the session id. They contain nothing about you. The app keeps a record of each payment on your phone (the order ID, price and time; see "Data on your phone") so it can show your purchase history and help with a charge question. Google Play also gives us a record of each order in our Play Console, which we use only to answer charge questions and handle refunds. With usage stats on, Firebase Analytics also records each snooze you buy (see "Usage stats"). Play Billing can also send its own diagnostic logs to Google.

## Camera and microphone

**Camera.** The app uses the camera only for the QR/Barcode check, and asks for permission only when you choose that check or register a code. The camera picture is read on your phone by Google's ML Kit barcode scanner, which is built into the app. Nothing is recorded, stored or uploaded; the app keeps only the fingerprint of the code you register.

ML Kit can send diagnostic logs to Google about how the scanner runs. These never contain camera pictures.

**Microphone.** The app uses the microphone only to record your own messages, and only while you are recording one. It asks for permission the first time you tap record, never when you open the app. A recording stops after 1 minute, or sooner when you leave the app, the screen turns off or a call comes in. Your recordings stay in the app's private storage on your phone. They are never uploaded, never sent to us or to Google, and never included in crash reports or usage stats. They are not in the backup either (see "Backup"). The app doesn't keep photos; if a later version adds a feature that does, we will update this page first.

## Keeping your data safe

- **Encrypted connections:** we run no server, and the app makes no connections of its own. Everything the app sends goes through Google's libraries in the app (Firebase, Play Billing and the ML Kit barcode scanner) over encrypted connections, and the app keeps Android's default of blocking unencrypted connections. The backup copy is made by Android itself (see "Backup").
- **Private storage:** everything the app keeps is in its own private storage on your phone, which other apps can't read. The app writes nothing to shared storage.
- **Purchase tokens:** the records that are backed up hold only a one-way fingerprint (a hash) of each Google purchase token, never the token itself.

## How long data is kept

- **On your phone:** data on your phone is kept until you delete it or uninstall the app. The exceptions are the app's own working records, which it clears by itself: the morning in progress when it ends, payment attempts after 7 days, and a payment's purchase token 30 days after the payment is settled.
- **Crash reports:** Google keeps them, and the ids sent with them, for 90 days.
- **Usage stats:** Google Analytics keeps stats linked to an analytics id for 2 months (Google's default, which we keep), and after that keeps only totals that aren't linked to any id.
- **Order records:** Google Play keeps the records of your payments, under Google's privacy policy.

## Who the app is for

The app is for adults (18 and over) and is not directed at children. Because snoozes cost real money, we recommend turning on purchase authentication in Google Play, so every snooze needs your fingerprint or password.

## Deleting your data

- **Delete all data:** in the app, open You, then "Delete all data". It deletes everything the app stores on your phone: alarms, history, purchase records, settings and your recorded messages. It also turns usage stats off and deletes the analytics data on your phone, deletes crash reports not sent yet, and makes a new install id. If a snooze payment is still being finished with Google Play, the app keeps only what it needs to finish it. "Delete all data" isn't available while an alarm is ringing or snoozed.
- **Uninstall the app:** this removes all of the app's data from your phone.
- **The backup copy** in your Google account: "Delete all data" does not remove it. If you restore a phone from that backup before Android makes a new one, your old alarms, history, purchase records and settings come back, with the names of your recorded messages (but not their sound). To remove it, see "Backup" above.
- **Crash reports and usage stats already sent:** email us at <vincentbui2108@gmail.com> and ask us to delete them. They aren't linked to your name or email, so tell us your phone model and roughly when you used the app, and we will delete what we can match to you. Google deletes the rest by itself: crash reports after 90 days, and usage stats linked to an analytics id after 2 months.
- **Google's own records:** Google Play keeps its own record of your payments (your Google Play order history), under Google's privacy policy.

## Changes to this policy

If the app changes what it does with data, we update this page and its effective date. Every change is visible in the app's public source repository.

## Contact

Questions about privacy or your data: <vincentbui2108@gmail.com>. More help is on the [support page]({{ "/support/" | relative_url }}).
