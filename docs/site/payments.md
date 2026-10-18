---
title: How payments & refunds work
permalink: /payments/
---

{% comment %}
  Mirrors the app's "How payments & refunds work" screen, in its order. checkSitePages fails unless every mirrored
  in-app sentence (the mirroredKeys in the root build.gradle.kts) appears here word for word. A placeholder in the
  app's text is filled with the value mirroredKeys gives it: the cap sentence is the app's tier wording
  (payments_cap_tier_body) with "$50.00", because a local price, with any tax, is only known on the phone.
{% endcomment %}

# How payments & refunds work

## Snooze fees

Each snooze costs your base fee times the snooze number. Google Play shows the price in your local currency.

You can snooze up to your max snoozes per session. A single snooze never costs more than the $50.00 price tier.

Google Play sets the local price, including any tax.

Your fee is locked for the morning. You can raise it anytime. Lowering it waits until after your next alarm.

## Pending payments and refunds

A pending payment only starts your snooze if it goes through before you finish. Otherwise, finish the check to stop the alarm.

Payments that weren't used are refunded automatically by Google.

Google lets you request a refund yourself within 48 hours of a charge.

## How the alarm behaves

Your alarm keeps ringing until you finish your check or pay to snooze.

Your phone stays usable: calls, other apps and emergency calls all work.

Other ways to stop it: force-stop or uninstall the app, turn off the phone, or leave it 30 minutes.

## A tip

Turn on purchase authentication in Google Play so every snooze needs your fingerprint or password.

## Problem with a charge?

In the app, "Problem with a charge?" opens your Google Play order history and writes a support email with the order filled in. You can also see every charge in the app's purchase history. More answers are on the [support page]({{ "/support/" | relative_url }}), and the full rules are in the [terms]({{ "/terms/" | relative_url }}).
