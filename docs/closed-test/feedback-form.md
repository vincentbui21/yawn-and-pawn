# Closed-test feedback form (Google Form)

The questions for the tester feedback form that `docs/closed-test.md` links to (Story 5.14). Build it at forms.google.com, then put its link into `docs/closed-test.md` and into the closed track's feedback field (see `owner-checklist.md`).

Answers stay in the owner's Google account. Never commit them, or tester names and emails, to this public repo.

## Form settings

- **Title:** Yawn & Pawn closed test: report a problem
- **Description:** paste the text under "Form description" below.
- **Settings > Responses > Collect email addresses:** Do not collect. (Question 15 asks for an email only if the tester wants a reply.)
- **Limit to 1 response:** off. Testers send one form per problem.
- **Allow response editing:** off.
- **No file upload question.** A file upload makes every tester sign in and stores the files in the owner's Drive. Testers email screenshots instead.
- **Responses tab > Link to Sheets:** on, so triage (twice a week) can sort by date and type.

## Form description

```text
Thank you for testing Yawn & Pawn. One form per problem, please. It takes about 2 minutes.
If an alarm didn't ring, or you were asked to pay with a real card, please also email us right away (the address is on the support page).
Never write card details or passwords here.
```

## Questions

Mark a question required only where it says **Required**.

1. **What are you reporting?** (Multiple choice, Required)
   - The alarm didn't ring
   - The alarm rang late, or stopped by itself
   - I couldn't stop the alarm, or my phone felt stuck
   - A snooze or payment problem
   - I was asked to pay with my real card, or charged real money
   - The app crashed or froze
   - A check didn't work (Math, Word Unscramble, Memory Sequence, QR/Barcode)
   - Something was confusing, or the text was wrong
   - Weekly check-in: everything is fine
   - Other

2. **When did it happen?** (Date, Required; turn on "Include time")

3. **What time should the alarm have rung?** (Time, optional) Help text: "Only for alarm problems."

4. **Phone brand** (Dropdown, Required)
   - Samsung
   - Xiaomi, Redmi or POCO
   - Google Pixel
   - Oppo
   - OnePlus
   - Realme
   - Vivo or iQOO
   - Huawei
   - Honor
   - Motorola
   - Nokia
   - Sony
   - Other

5. **Phone model** (Short answer, Required) Help text: "For example Galaxy A55. It's in Settings > About phone."

6. **Android version** (Dropdown, Required) Help text: "Settings > About phone > Android version."
   - 16
   - 15
   - 14
   - 13
   - 12
   - 11
   - 10
   - 9
   - 8
   - I don't know

7. **App version** (Short answer, optional) Help text: "Optional. In the Google Play page for the app, under About this app."

8. **Was your phone locked when it happened?** (Multiple choice, optional)
   - Yes
   - No
   - Not sure

9. **What was on at the time?** (Checkboxes, optional)
   - Battery saver
   - Do Not Disturb
   - Charging
   - Headphones or a Bluetooth speaker
   - Wi-Fi or mobile data was off
   - The phone had restarted during the night
   - None of these
   - Not sure

10. **Did you try to snooze?** (Multiple choice, optional)
    - No
    - Yes, and Google Play showed a test card
    - Yes, and Google Play showed my real card or balance (I didn't pay)
    - Yes, and I paid with my real card
    - Yes, but the payment sheet didn't open

11. **Have you done everything in Settings > Make sure alarms ring?** (Multiple choice, optional)
    - Yes, every item
    - Some items
    - No
    - I don't know where it is

12. **What happened?** (Paragraph, Required) Help text: "What did you do, and what did you see? Step by step if you can."

13. **What did you expect to happen?** (Paragraph, optional)

14. **How was your morning with the app?** (Linear scale 1 to 5, optional; 1 = "Bad", 5 = "Great") Help text: "Optional. Tell us how waking up with it feels."

15. **Can we email you about this?** (Short answer, optional) Help text: "Optional. Your email, only if you want a reply. We use it only for this report."

## Confirmation message

```text
Thank you. We read every report at least twice a week. If the alarm didn't ring, keep using your second alarm until we send a fix.
```

## Triage (owner)

- Read new answers at least twice a week (Story 5.14).
- Each real problem becomes a bug story that names Story 5.14, with the phone brand, Android version and steps, but never the tester's email or name.
- "I was asked to pay with my real card" means that tester is missing from licence testing: add their Gmail address under Setup > License testing the same day (see `owner-checklist.md`).
- "I paid with my real card": refund the order in Play Console > Order management, then add the tester to licence testing.
