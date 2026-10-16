---
title: Privacy policy
permalink: /privacy/
---

<!-- Draft for the owner to review; not legal advice. Story 5.7 lists the statements about features not built yet. -->

# Privacy policy

**Effective date:** 15 October 2026

Yawn & Pawn ("the app") is an alarm clock for Android, made by an independent developer ("we", "us"). This policy explains what the app keeps, what leaves your phone and how to delete it.

The short version:

- There is no account and no login, and we run no server of our own. Your alarms, history and payments are never sent to us.
- Your data stays on your phone. The only things that leave it are the backup copy in your own Google account, crash reports, usage stats if you turn them on, and what Google Play needs to take a payment. Each is explained below.
- The app has no ads, and we never sell your data or use it for advertising.

## Data on your phone

The app keeps these on your phone:

- **Your alarms:** time, repeat days, label, sound, volume, vibration, snooze length, grace window and the checks you chose. For a QR/Barcode check, the app keeps the text of the code you registered, not a picture of it.
- **Your wake-up history:** when each alarm rang, how many times you snoozed, which checks you did and how each morning ended.
- **Your settings:** such as your base fee, max snoozes and theme.
- **Your purchase records:** for each snooze payment, Google's order ID, the product, the price and currency, the time, its status and which alarm and morning it was for. The app also keeps a one-way fingerprint (a hash) of Google's purchase token. The token itself is kept in a separate store on the phone that is never backed up.
- **Your photos and recordings:** if you use House Hunt, the reference photos you take; if you record motivation messages, those recordings; if you pick your own sound file, a reference to it.
- **Two random ids:** an install id, made when you first use the app, and a session id for each morning. They say nothing about who you are.

The app never collects your name, email address, contacts or location. It has no location permission.

## Backup

Android's Auto Backup copies part of the app's data to your own Google account, so a new or reset phone can get your alarms back. Google stores this copy, and we can't read it. The same rules apply when you move to a new phone with Android's transfer tool.

- **Backed up:** your alarms, wake-up history, settings and purchase records.
- **Not backed up:** House Hunt photos, motivation recordings, custom sounds, the morning in progress, payments that are not finished yet, Google's purchase tokens, the install id and the cached Google Play prices.

Backup follows your phone's settings, and you can turn it off there. The copy stays in your Google account after you uninstall the app; to remove it, delete your phone's backup in Google Drive (under Storage, then Backups) or turn off Google backup in your phone's settings.

## Crash reports

When the app crashes, it sends a crash report through Firebase Crashlytics, a Google service. This is always on: a crash in an alarm app can mean an alarm that doesn't ring, and the reports are how we find and fix those.

A crash report contains where in the app's code the crash happened (class names and a stack trace), the phone model, the Android version, the app version and a random installation id made by Firebase. The app removes every error message before a report is sent and adds no logs or notes of its own. Crash reports never contain your alarms, labels, history, payments, photos or recordings.

Crashlytics can also send its own diagnostic logs about how it runs to Google. Google keeps crash reports for 90 days.

## Usage stats

Usage stats are off until you say yes. The app asks once, during setup, and you can change your answer anytime in Settings.

With usage stats on, the app sends anonymous stats through Firebase Analytics, a Google service:

- **Three app events:** how a morning ended (on time, snoozed or missed), how many snoozes it took, and which check types were used. Each holds only a category or a number.
- **Firebase's automatic events:** the app was opened for the first time, the app was opened (a session started), and the app was removed from the phone.

With each event, Firebase records basic facts such as the phone model, Android version, app version, language and an approximate country based on the network. The stats have no user id, no name, no alarm labels and no amounts paid. The app never collects the advertising ID and turns off ad storage and ad personalisation.

Test alarms send no app events, and nothing from before you said yes is ever sent. Turning usage stats off stops them at once and deletes the analytics data on your phone, including its analytics id, so later stats can't be linked to earlier ones.

## Payments

Snooze payments go through Google Play Billing. Google takes the payment and handles your payment details and your Google account, under Google's own privacy policy. The app never sees your card or bank details.

To link each payment to the right morning, the app gives Google two random ids: the install id and the session id. They contain nothing about you. The app keeps a record of each payment on your phone (the order ID, price and time; see "Data on your phone") so it can show your purchase history and help with a charge question. Play Billing can also send its own diagnostic logs to Google.

## Camera and microphone

**Camera.** The app uses the camera only for the QR/Barcode and House Hunt checks, and asks for permission only when you choose one of them.

- **QR/Barcode:** the camera picture is read on your phone by Google's ML Kit barcode scanner, which is built into the app. Nothing is recorded, stored or uploaded; the app keeps only the text of the code you register.
- **House Hunt:** your reference photos stay on your phone, are not backed up and are never uploaded. The photo you take to stop the alarm is compared on your phone.

ML Kit can send diagnostic logs to Google about how the scanner runs. These never contain camera pictures.

**Microphone.** The app uses the microphone only while you record a motivation message, and asks for permission only then. Recordings stay on your phone, are not backed up and are never uploaded.

## Who the app is for

The app is for adults (18 and over) and is not directed at children. Because snoozes cost real money, we recommend turning on purchase authentication in Google Play, so every snooze needs your fingerprint or password.

## Deleting your data

- **Delete all data:** in the app, open You, then "Delete all data". It deletes everything the app stores on your phone: alarms, history, purchase records, settings, photos and recordings. It also turns usage stats off and deletes the analytics data on your phone, deletes crash reports not sent yet, and makes a new install id. If a snooze payment is still being finished with Google Play, the app keeps only what it needs to finish it. "Delete all data" isn't available while an alarm is ringing or snoozed.
- **Uninstall the app:** this removes all of the app's data from your phone.
- **The backup copy** in your Google account: see "Backup" above.
- **Google's own records:** Google Play keeps its own record of your payments (your Google Play order history), under Google's privacy policy.

## Changes to this policy

If the app changes what it does with data, we update this page and its effective date. Every change is visible in the app's public source repository.

## Contact

Questions about privacy or your data: <vincentbui2108@gmail.com>. More help is on the [support page]({{ "/support/" | relative_url }}).
