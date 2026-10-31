---
title: Yawn & Pawn
permalink: /
layout: home
---

{% comment %}
  The home page, ported from the design the owner approved on 2026-10-29. It is raw HTML (kramdown's nomarkdown
  block) so the layout stays exact; it lives in this .md file so checkSitePages applies the copy rules to it. The
  "hero-end" marker splits the hero (rendered inside the header by _layouts/home.html) from the sections. The motion
  (assets/css/site.css, assets/js/site.js) only adds classes: everything here is readable without JavaScript.
{% endcomment %}

{::nomarkdown}
<div id="top" class="hero">
<div class="hero-copy">
<p class="hero-eyebrow">An alarm clock for Android</p>
<h1 class="hero-title">Every snooze costs real money.</h1>
<p class="hero-free">Waking up is free.</p>
<p class="hero-lead">Snooze, and you pay a little, a bit more each time. Get up instead, finish a quick check you chose, and the alarm stops for free. We hope you never pay us.</p>
<div class="hero-actions">
<a class="btn btn--sun" href="#test">Join the test</a>
<a class="btn btn--ghost" href="#how">How it works</a>
</div>
<p class="hero-note">Coming to Google Play. Android 8.0 or later.</p>
</div>
<div class="hero-phone-wrap">
<div class="hero-glow" aria-hidden="true"></div>
<div class="hero-float">
<div class="phone phone--hero">
<div class="phone-screens">
<img class="screen screen--1" src="{{ "/assets/img/ringing.webp" | relative_url }}" width="600" height="1333" alt="The ringing alarm: I'm up, or Snooze for $1.00" fetchpriority="high">
<img class="screen screen--2" src="{{ "/assets/img/check-math.webp" | relative_url }}" width="600" height="1333" alt="" aria-hidden="true" decoding="async">
<img class="screen screen--3" src="{{ "/assets/img/success.webp" | relative_url }}" width="600" height="1333" alt="" aria-hidden="true" decoding="async">
<div class="screen-fx" aria-hidden="true"><i style="--x:-96px;--y:-70px;--r:200deg;--c:#F5A04E"></i><i style="--x:-60px;--y:-104px;--r:-150deg;--c:#0F5534"></i><i style="--x:-20px;--y:-120px;--r:260deg;--c:#D96F14"></i><i style="--x:24px;--y:-112px;--r:-220deg;--c:#FFE3B8"></i><i style="--x:66px;--y:-96px;--r:170deg;--c:#A8520A"></i><i style="--x:100px;--y:-60px;--r:-240deg;--c:#F5A04E"></i><i style="--x:-110px;--y:-20px;--r:120deg;--c:#A8520A"></i><i style="--x:112px;--y:-14px;--r:-130deg;--c:#0F5534"></i><i style="--x:-76px;--y:-40px;--r:300deg;--c:#FFE3B8"></i><i style="--x:80px;--y:-34px;--r:-280deg;--c:#D96F14"></i><i style="--x:-36px;--y:-70px;--r:190deg;--c:#0F5534"></i><i style="--x:40px;--y:-66px;--r:-200deg;--c:#F5A04E"></i><i class="fx-late" style="--x:-88px;--y:-92px;--r:-210deg;--c:#D96F14"></i><i class="fx-late" style="--x:-8px;--y:-130px;--r:240deg;--c:#A8520A"></i><i class="fx-late" style="--x:90px;--y:-86px;--r:-170deg;--c:#FFE3B8"></i><i class="fx-late" style="--x:-104px;--y:-48px;--r:150deg;--c:#0F5534"></i><i class="fx-late" style="--x:104px;--y:-44px;--r:-260deg;--c:#F5A04E"></i><i class="fx-late" style="--x:50px;--y:-118px;--r:220deg;--c:#D96F14"></i></div>
</div>
</div>
</div>
</div>
</div>
<!-- hero-end -->
<section id="how" class="section section--stack" aria-labelledby="how-title">
<div class="section-head" data-reveal>
<h2 id="how-title" class="section-title">How it works</h2>
<p class="section-lead">Three steps, every morning. The choice is always yours.</p>
</div>
<div class="grid grid--steps">
<div class="card step" data-reveal>
<div class="step-num" aria-hidden="true">1</div>
<h3 class="card-title"><span class="visually-hidden">Step 1: </span>Set your alarm</h3>
<p class="card-body">Pick a time, a sound and the check that will prove you are up. Do it at night, while you still mean it.</p>
</div>
<div class="card step" data-reveal>
<div class="step-num" aria-hidden="true">2</div>
<h3 class="card-title"><span class="visually-hidden">Step 2: </span>It rings</h3>
<p class="card-body">Snooze and pay, a little more each time. Or tap "I’m up" and the alarm goes quiet for a moment.</p>
</div>
<div class="card step" data-reveal>
<div class="step-num" aria-hidden="true">3</div>
<h3 class="card-title"><span class="visually-hidden">Step 3: </span>Finish your check</h3>
<p class="card-body">Solve it before the quiet time runs out and the alarm is done for the day. Free, every time.</p>
</div>
</div>
</section>

<section id="prices" class="section section--split" aria-labelledby="prices-title">
<div class="prices-copy" data-reveal>
<h2 id="prices-title" class="section-title">Each snooze costs more than the last</h2>
<p class="section-lead">You pick a base fee, from $1. Your first snooze costs the base fee, the second twice that, and so on, up to 5 snoozes a morning. Your fee is locked for the morning, so there's no lowering it at 6 a.m.</p>
<p class="prices-note">Payments go through Google Play, in your own currency, with tax. Unused payments are refunded automatically.</p>
<a class="text-link" href="{{ "/payments/" | relative_url }}">How payments and refunds work</a>
</div>
<div class="ladder" data-reveal data-ladder>
<p class="ladder-caption">With a $1 base fee</p>
<ul class="ladder-rows">
<li class="ladder-row" style="--i:0"><span class="ladder-label">1st snooze</span><span class="ladder-track" aria-hidden="true"><span class="ladder-bar ladder-bar--low" style="width:20%"></span></span><span class="ladder-price" data-count="1">$1</span></li>
<li class="ladder-row" style="--i:1"><span class="ladder-label">2nd snooze</span><span class="ladder-track" aria-hidden="true"><span class="ladder-bar ladder-bar--low" style="width:40%"></span></span><span class="ladder-price" data-count="2">$2</span></li>
<li class="ladder-row" style="--i:2"><span class="ladder-label">3rd snooze</span><span class="ladder-track" aria-hidden="true"><span class="ladder-bar ladder-bar--mid" style="width:60%"></span></span><span class="ladder-price" data-count="3">$3</span></li>
<li class="ladder-row" style="--i:3"><span class="ladder-label">4th snooze</span><span class="ladder-track" aria-hidden="true"><span class="ladder-bar ladder-bar--mid" style="width:80%"></span></span><span class="ladder-price" data-count="4">$4</span></li>
<li class="ladder-row" style="--i:4"><span class="ladder-label">5th snooze</span><span class="ladder-track" aria-hidden="true"><span class="ladder-bar ladder-bar--high" style="width:100%"></span></span><span class="ladder-price" data-count="5">$5</span></li>
</ul>
<div class="ladder-up">
<span class="ladder-label ladder-up-label">I'm up</span>
<span class="ladder-up-text">Finish your check</span>
<span class="ladder-price ladder-up-price">Free</span>
</div>
</div>
</section>

<section class="section section--stack" aria-labelledby="checks-title">
<div class="section-head section-head--wide" data-reveal>
<h2 id="checks-title" class="section-title">Prove you're up, your way</h2>
<p class="section-lead">Tap "I'm up" and the alarm goes quiet while you do your check. Pick one, or mix them.</p>
</div>
<div class="grid grid--checks">
<div class="card check" data-reveal>
<div class="check-art art-math" aria-hidden="true"><span>7</span><span>+</span><span>5</span><span>=</span><span class="roller"><span class="roller-strip"><span>9</span><span>3</span><span>8</span><span>12</span></span></span></div>
<h3 class="card-title">Math</h3>
<p class="card-body">A few sums, from easy to hard. Hard enough to wake your brain.</p>
</div>
<div class="card check" data-reveal>
<div class="check-art art-word" aria-hidden="true"><span style="--from:2">W</span><span style="--from:2">A</span><span style="--from:-2">K</span><span style="--from:-2">E</span></div>
<h3 class="card-title">Word unscramble</h3>
<p class="card-body">Put the letters back in order before the quiet time ends.</p>
</div>
<div class="card check" data-reveal>
<div class="check-art art-memory" aria-hidden="true"><span class="lit" style="--t:0">1</span><span></span><span></span><span></span><span class="lit" style="--t:1">2</span><span></span><span class="lit" style="--t:3">4</span><span></span><span class="lit" style="--t:2">3</span></div>
<h3 class="card-title">Memory sequence</h3>
<p class="card-body">Watch a pattern of tiles, then repeat it.</p>
</div>
<div class="card check" data-reveal>
<div class="check-art art-scan" aria-hidden="true"><span class="scan-code"></span><span class="scan-line"></span></div>
<h3 class="card-title">QR or barcode</h3>
<p class="card-body">Scan your toothpaste, or a code we print for you, in another room. You have to get out of bed.</p>
</div>
</div>
</section>

<section class="section" aria-label="Screens from the app">
<div class="shots">
<figure class="shot" data-reveal data-parallax>
<div class="phone phone--shot"><img src="{{ "/assets/img/home.webp" | relative_url }}" width="600" height="1333" alt="Home screen with alarms and a streak" loading="lazy" decoding="async"></div>
<figcaption>Your alarms and your streak</figcaption>
</figure>
<figure class="shot" data-reveal data-parallax>
<div class="phone phone--shot"><img src="{{ "/assets/img/check-math.webp" | relative_url }}" width="600" height="1333" alt="A math check with the quiet time counting down" loading="lazy" decoding="async"></div>
<figcaption>A quick check gets you up</figcaption>
</figure>
<figure class="shot" data-reveal data-parallax>
<div class="phone phone--shot"><img src="{{ "/assets/img/progress.webp" | relative_url }}" width="600" height="1333" alt="Progress: 30 mornings in a ring and a calendar" loading="lazy" decoding="async"></div>
<figcaption>Every morning, on your calendar</figcaption>
</figure>
</div>
</section>

<section class="section section--stack" aria-labelledby="features-title">
<h2 id="features-title" class="section-title" data-reveal>Made to actually get you up</h2>
<div class="grid grid--features">
<div class="feature" data-reveal><span class="feature-dot" aria-hidden="true"></span><div class="feature-text"><h3 class="feature-title">Rings even after a restart</h3><p class="card-body">If your phone restarts overnight, the alarm still rings, even before you unlock it.</p></div></div>
<div class="feature" data-reveal><span class="feature-dot" aria-hidden="true"></span><div class="feature-text"><h3 class="feature-title">Wakes the screen, bright</h3><p class="card-body">The alarm takes over the screen and brightens it, like a sunrise.</p></div></div>
<div class="feature" data-reveal><span class="feature-dot" aria-hidden="true"></span><div class="feature-text"><h3 class="feature-title">Your own voice</h3><p class="card-body">Record a message to yourself and hear it once you are up, or mixed into the alarm.</p></div></div>
<div class="feature" data-reveal><span class="feature-dot" aria-hidden="true"></span><div class="feature-text"><h3 class="feature-title">Your own sound</h3><p class="card-body">Pick a song or sound from your phone. If the file goes missing, a built-in sound plays instead.</p></div></div>
<div class="feature" data-reveal><span class="feature-dot" aria-hidden="true"></span><div class="feature-text"><h3 class="feature-title">Streaks and progress</h3><p class="card-body">See your mornings on a calendar and keep your streak of on-time days going.</p></div></div>
<div class="feature" data-reveal><span class="feature-dot" aria-hidden="true"></span><div class="feature-text"><h3 class="feature-title">Skip one morning</h3><p class="card-body">Up early? A reminder 2 hours before your first alarm lets you skip the day's alarms, for free.</p></div></div>
</div>
</section>

<section class="section" aria-labelledby="privacy-title">
<div class="band" data-reveal>
<div class="band-copy">
<h2 id="privacy-title" class="band-title">No account. Your mornings stay on your phone.</h2>
<p class="band-body">Your alarms, history and recordings never leave your phone. Crash reports are sent so we can fix alarms that fail. Usage stats only if you say yes.</p>
</div>
<a class="btn btn--ghost btn--band" href="{{ "/privacy/" | relative_url }}">Read the privacy policy</a>
</div>
</section>

<section id="faq" class="section section--faq" aria-labelledby="faq-title">
<h2 id="faq-title" class="section-title" data-reveal>Questions</h2>
<div class="faq">
<details class="faq-item" data-reveal><summary><h3 class="faq-q">Who gets the money?</h3></summary><p class="faq-a">We do, through Google Play. It is how the app pays for itself. We would rather you got up.</p></details>
<details class="faq-item" data-reveal><summary><h3 class="faq-q">Can I get a refund?</h3></summary><p class="faq-a">Payments that weren’t used are refunded automatically by Google. You can also ask Google for a refund yourself within 48 hours of a charge.</p></details>
<details class="faq-item" data-reveal><summary><h3 class="faq-q">Is there a way out without paying?</h3></summary><p class="faq-a">Yes. Finish your check, and it is free. You can also force-stop or uninstall the app, or turn off the phone. If nobody touches it, it stops after 30 minutes.</p></details>
<details class="faq-item" data-reveal><summary><h3 class="faq-q">Do I need an account?</h3></summary><p class="faq-a">No. There is no sign-in. Your alarms, history and recordings stay on your phone.</p></details>
<details class="faq-item" data-reveal><summary><h3 class="faq-q">Which phones does it work on?</h3></summary><p class="faq-a">Android phones with Android 8.0 or later. Some brands stop apps in the background, so the app walks you through the settings that keep your alarm reliable.</p></details>
</div>
</section>

<section id="test" class="section section--test" aria-labelledby="test-title">
<div class="cta" data-reveal>
<h2 id="test-title" class="section-title">Try it before everyone else</h2>
<p class="section-lead cta-lead">Yawn &amp; Pawn is in a closed test on Google Play. Send us your Gmail address and we'll add you.</p>
<a class="btn btn--accent" href="mailto:{{ site.support_email }}" data-confetti-hover>Email&#32;<span class="nowrap">{{ site.support_email }}</span></a>
</div>
</section>
{:/}
