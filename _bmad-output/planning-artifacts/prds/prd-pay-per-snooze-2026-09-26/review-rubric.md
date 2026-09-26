# PRD Quality Review — Pay Per Snooze (v0.1 draft, 2026-09-26)

## Overall verdict
This is an unusually concrete, technically literate PRD: the product principles actually drive the FRs, the session-integrity and billing rules are specific enough to build against, and the fixed owner decisions (snooze-only revenue, B×N fee from $1, user-chosen checks, 15–30 s muted grace, Android first) are stated plainly and applied consistently. What puts it at risk as the top of an autonomous build chain is a contradiction in the billing recovery rules (§6.3), a primary success metric that can't be measured under the no-backend, on-device-only design, no priority tiers inside a large MVP, and no Glossary or User Journeys. Stories generated from it will inherit those gaps, and the Ralph loop can't settle them on its own.

## Decision-readiness — adequate

Most decisions are stated as decisions, and the text says what was given up: "Force-stop and uninstall are accepted escapes" (§7.1b); "the app **cannot** detect refunds … history shows what was charged, not refunds" (§6.3); "someone who hacks the app gets a free snooze — they only cheat themselves" (§6.3); the commitment lock is openly "a nudge, not a wall" (§6.2). The Open Questions (§14) are genuinely open and each one names the default the FRs currently assume, which lets building start. Q5 is shown as decided and struck through, which is honest bookkeeping.

The weak spots: one open question is already written as settled elsewhere in the document, and the PRD never says what to cut if the 7–10 week goal (G4) slips. That second point is a decision the owner will face, and the document is silent on it.

### Findings
- **[high]** No cut line inside the MVP (§7, §13 "Draft epic list", G4). All ~70 FRs sit at the same priority. That includes five check types (House Hunt carries its own ML spike), motivation recordings with a "Mix into alarm" mode, CSV export (FR-PRG-6), a calendar view (FR-PRG-3) and a weekly notification (FR-PRG-5), all against "Build with Ralph loop (≈3–4 weeks)". Principle 4 ("Reliability over features") gives a way to rank features, but the FRs never use it to rank. *Fix:* Tag each FR (or FR group) Must / Should / Could for launch, and name the first things to drop if the build runs past 4 weeks (e.g., FR-SND-4 "Mix into alarm", FR-PRG-3, FR-PRG-6, House Hunt if S3 comes back marginal).
- **[medium]** Q6 is treated as open and decided at the same time (§14 Q6 vs NFR-5 and §12). Q6 asks "Set Play target audience to 18+?", but NFR-5 says "set **Target audience = 18+**" and the Risks table lists "18+ rating" as a mitigation. Downstream steps will read NFR-5 as binding. *Fix:* Either close Q6 as decided or reword NFR-5 as "pending Q6".
- **[low]** §6.2 says the $50 cap "only matters if Q1/Q3 change", yet the §6.3 catalogue ships 50 products, many of which B×N can never reach at current limits (e.g., $11, $13, $17). That's fine as future-proofing, but the choice isn't written down. *Fix:* Add one line saying the full 1–50 catalogue is deliberate headroom for Q1/Q3.

## Substance over theater — strong

Very little of this is furniture. The personas (§4) are lean, and at least one earns its place: "Affected bystanders … The grace window exists for them." The product principles (§5) turn directly into testable FRs (Principle 3 → FR-RNG-2's "No pre-selection" and FR-RNG-8's "no guilt, no celebration"). The NFRs have product-specific thresholds: NFR-1 "within 2 s … on the device test matrix (Pixel, Samsung, Xiaomi, one budget device)", NFR-7 "≤ 1 s", NFR-11 "≥ 90% line coverage, enforced by Kover". None of it is scalable/secure boilerplate.

### Findings
- **[low]** Two differentiation items aren't differentiators (§2, items 4–5). "Wake-up progress tracking, recorded motivation messages, and custom alarm sounds" are table stakes (Alarmy has most of them), and "Android first" is a distribution choice, not a product feature. The competitor claims ("Most are new (2026), iOS-only, low traction") have no source in §15. *Fix:* Cut §2 down to items 1–3, move Android-first to the go-to-market text, and cite or tag the competitor claims as assumptions.
- **[low]** The "Routine builder" persona (§4) doesn't drive any requirement that the chronic snoozer doesn't already drive (streaks serve both). *Fix:* Either tie it to a specific decision (e.g., defaults for users who rarely snooze) or fold it into the primary persona.

## Strategic coherence — adequate

The thesis is clear and holds up: a rising money stake plus a free, self-chosen way to wake up beats single-mechanism or no-stake competitors, and the PRD bets on behaviour change as the outcome (G1; §11 "Behaviour change (primary)"). Features serve that arc: fee escalation, grace window, fallback, anti-escape, and progress screens that frame money as a cost. Trust metrics (refund rate, rating) and "Revenue: tracked, but **not** optimized" work as counter-metrics. The MVP scope kind is experience/behaviour-change, with reliability as a hard gate. That matches.

The gap is that the metrics meant to validate the thesis can't be collected under the architecture the PRD itself prescribes.

### Findings
- **[critical]** The primary success metric can't be measured (§11 vs §5 Principle 5, NFR-4, §9 "No backend in MVP"). "Snoozes per session in week 4 are ≥ 40% lower than week 1" and "alarm-failure reports < 0.1% of sessions" both need per-session data across users. But "All data stays on the device", there's no analytics SDK, and crash reporting is only "if added". Play Console can supply purchase counts, D30 retention, refunds and rating, but it can't supply sessions or non-paid snooze behaviour. So the thesis the PRD bets on can't be checked after launch. *Fix:* Choose one route and write it into the PRD: (a) opt-in, anonymous, aggregate-only telemetry (session outcome, snooze count), with a new NFR and a Data safety disclosure; (b) proxy metrics Play Console can supply (e.g., purchases per active user in week 1 vs week 4); or (c) an opt-in in-app share of FR-PRG stats from closed testers. Then restate each SM with its data source.
- **[medium]** The behaviour-change metric suffers from survivorship bias (§11). Counting only "users active 4+ weeks" leaves out anyone who quit because they kept paying, which is exactly the failure mode the "Users feel exploited" risk (§12) worries about. *Fix:* Pair it with a churn-by-snooze-level counter-metric (e.g., share of week-1 heavy payers still active at D30).

## Done-ness clarity — adequate

Most FRs are testable and specific: FR-ALM-6 "over ≤ 30 s", FR-ALM-8 "disabled with the label 'Test · no charge'", FR-ALM-9 "30 minutes … `elapsedRealtime`", FR-PWK-4 "Length 4 / 6 / 8", FR-PWK-9's three sub-rules, FR-PWK-11's fallback limits. The billing grant rules in §6.3 are among the best-specified parts. Being unforgiving, though, there's a cluster of undefined behaviour exactly where an autonomous loop will guess: at state boundaries, in multi-alarm cases, and in money arithmetic. One of these is an outright contradiction in the payment core.

### Findings
- **[high]** The pending-purchase rule contradicts the recovery rule (§6.3 "Pending purchases" vs "Recovery"). The pending rule says that if a pending purchase "clears after the session, it is **never consumed/acknowledged**, so Google auto-refunds it". The recovery rule says any purchased-but-unconsumed token not from the current session is handled "otherwise consume and record it in history". A pending purchase that clears after the session is exactly such a token, so the recovery path would consume it and charge the user for a snooze they never got. That breaks G3 and Principle 3. *Fix:* Split recovery into two cases: (i) tokens granted but not yet consumed → consume; (ii) tokens never granted from a past session → leave them unacknowledged (auto-refund) and log them as "Not charged — refunded automatically". Add this as an explicit acceptance case.
- **[high]** Money totals can't be computed from what the PRD tells the app to store (§6.2, FR-PRG-2, FR-PRG-4, FR-PRG-5, FR-MSG-1/2). §6.2 says "always displays the localized price string … never a hard-coded USD value". But FR-PRG-2 needs sums ("Money paid this week / month / all-time"), and the example copy hard-codes dollars: "$0 paid. Nice." (FR-PRG-5), "$0 this week" (FR-MSG-2). You can't add up display strings, and a user whose currency changes (travel, account move) gets mixed totals. *Fix:* Require storing `priceAmountMicros` + currency code per purchase. Say how totals behave across currencies (e.g., one total per currency). Show zero-amount copy in local format, or phrase it without a currency ("Nothing paid").
- **[high]** "No interaction" in FR-ALM-9 is undefined, and so is the timeout as an escape route. What resets the 30-minute timer: a screen tap, starting a check, a wrong answer, opening the Play sheet? After "I'm up" and an expired grace window, FR-PWK-9 says the alarm "stays on until the check is completed". If working on the check counts as interaction, a slow user could ring for more than 30 minutes; if it doesn't, the alarm could stop mid-check. The 30-minute timeout is also a no-payment exit that §7.1b ("Everything else must lead back to the ringing alarm") doesn't list among the accepted escapes. *Fix:* Define interaction precisely (e.g., "any check input or ringing-screen tap resets the timer"), and add "30-minute unattended timeout → Missed" to the accepted-escapes list in §7.1b.
- **[medium]** The multi-session and interruption edge cases are underspecified.
  - FR-SES-7 merges overlapping alarms, but which alarm's checks, sound and grace length apply? Does the fee sequence continue?
  - FR-SES-8 pauses sound for calls, but do the grace-window countdown, the 30-minute timer and the backup alarm (FR-SES-2) pause too?
  - FR-PRG-3 colours "each day … by outcome", but which outcome wins when a day has several sessions?
  - FR-PRG-1 has no outcome for an alarm disabled or deleted within the 8-hour lock window (§6.2 says it "is logged"), and "Skipped" depends on FR-ALM-10, which isn't in the MVP.
  *Fix:* Add a one-line rule for each.
- **[medium]** Check definitions are incomplete for generator and validator stories (FR-PWK-2/3/5/8). Count ranges are only given for Math ("1–10 problems"). Memory Sequence rounds, Word Unscramble "N words" and House Hunt count have no range. Math Hard ("multi-step with multiplication") has no operand bounds. The "All" mode says "in order" without saying whose order. Word Unscramble has no word-list source or offensive-word filter. *Fix:* Give each check type min/max/default count, exact difficulty parameters, and the word-list source.
- **[medium]** FR-SES-4 is garbled and partly infeasible. "relaunches it / keeps the ringing notification non-dismissible" reads as two alternatives. Since Android 10, background activity starts are blocked outside full-screen intents, and since Android 14 users can dismiss ongoing notifications on unlocked devices. The FR half-acknowledges this but never states what the pass condition is. *Fix:* State the testable outcome ("sound never stops; a tap on the heads-up or notification returns to the ringing screen within 1 s") and put relaunch behaviour under Spike S2.
- **[medium]** NFR-9's accessibility clause is vague and the fallback doesn't satisfy it. "each check type has an accessible alternative or the fallback check" — but the fallback (FR-PWK-11: Hard Math ×2 plus Memory Sequence Hard) depends on visual tile sequences and doesn't work for TalkBack users. *Fix:* Name the accessible alternative for each check (e.g., Memory Sequence with audio tones, Math with spoken problems), or state which checks can't be selected with TalkBack on.
- **[low]** Several adjectives stand in for acceptance criteria:
  - FR-RNG-4 "a clear message"
  - FR-SND-1 "loud and tested"
  - FR-MSG-3 "a small celebration"
  - FR-ONB-4 "A strong recommendation"
  - FR-SES-2 "~1 minute ahead"
  - FR-PRG-5 gives no day or time for the weekly summary.
  *Fix:* Either point each at a design.md section or give a bound (e.g., FR-SND-1 minimum loudness in LUFS; FR-SES-2 "≤ 60 s").

## Scope honesty — adequate

The non-goals (§3) do real work, especially "Subscriptions, ads, paid unlocks — **never**, not only in MVP". Accepted escapes, undetected refunds, client-only verification and the "downgrade the feature" option for House Hunt (S3) are all declared openly. FR-ALM-10 is honestly marked "*(Proposed — pending Q9, not in MVP unless approved)*".

What's missing is any assumption tagging. The PRD has no `[ASSUMPTION]` tags, no `[NOTE FOR PM]` callouts and no Assumptions Index. Several load-bearing inferences go untagged:
- that users will pay at least $1 to snooze (the market-validation premise);
- that Play policy accepts pay-to-snooze consumables;
- the 3–4 week Ralph build estimate;
- that an owner who is the only person able to do `human-verify` device checks (NFR-11) can keep up with an autonomous loop.

Open-items density is 8 open questions and 0 tagged assumptions. That's fine for a draft, but on a PRD that authorizes building it should be the other way round: fewer open questions, and every assumption visible.

### Findings
- **[medium]** Untagged schedule and throughput assumption (G4, §13, NFR-11). E2 (OEM reliability), E4 (House Hunt), E5 (billing) and much of E8 are mostly `human-verify`, and "human device test after each epic" puts the owner's device time on the critical path. Neither is costed. *Fix:* Tag the 3–4 week estimate as an assumption, and state the expected owner device-test hours per epic.
- **[low]** Consumer-law exposure isn't addressed (§12). Immediate-delivery digital consumables in the EU/UK need express consent to waive the right of withdrawal, and Google handles only part of this. *Fix:* Add a Risk row or an open question to confirm with Play policy and the privacy/terms author.

## Downstream usability — thin

This is a chain-top PRD feeding UX → architecture → Ralph-generated stories, so this dimension matters most. IDs are stable and contiguous within each group (ALM 1–12, SES 1–8, RNG 1–8, PWK 1–12, SND 1–5, PRG 1–6, MSG 1–4, ONB 1–4, SET 1–4), and "IDs are stable so stories can reference them" shows intent. But there is no Glossary, no User Journeys, no IDs on success metrics, and the draft epic map doesn't cover every FR group.

### Findings
- **[high]** There's no Glossary, and core terms drift. Examples:
  - "alarm session" / "session" versus "ring" / "single ring" (FR-ALM-9) / "re-ring" (FR-PWK-9) — the ring-vs-session distinction carries weight in the timeout and grace rules;
  - "proof-of-wake check" / "check" / "check type";
  - "backup check" (FR-PWK-11) versus "backup alarm" (FR-SES-2), which are unrelated despite the shared word;
  - "Fallback" is used both for the backup check (FR-PWK-11) and for the default-sound fallback (FR-SND-5, NFR-2).
  A story-writing agent will conflate these. *Fix:* Add a Glossary (session, ring, snooze, grace window, check, backup check, backup alarm, commitment lock, outcome states) and use its terms exactly.
- **[medium]** The draft epic map (§13) leaves FR groups orphaned. Nothing clearly maps to SET (Settings) or MSG (Messaging), and SES (session integrity) is only folded into E2 by parenthesis. The FR-ALM-11/12 platform items and the §9 "Play Console declarations" could fall between E2 and E9. *Fix:* Add an FR-group → epic mapping line under each epic.
- **[low]** Success metrics and NFRs aren't linked to FRs. The §11 SMs have no IDs, and nothing ties NFR-1/2 to FR-SES or Spike S2 beyond prose. *Fix:* Add SM IDs, and add "Verified by" references (e.g., NFR-1 → S2, device matrix).

## Shape fit — thin

This is a consumer product with meaningful UX, so User Journeys with named protagonists are load-bearing, and there are none. The morning flow (ring → "I'm up" → grace → check / snooze → pay → re-ring → success) has to be pieced together from §6.1, FR-RNG-*, FR-PWK-9/10/11 and FR-SES-*. That forces the UX and architecture steps to rebuild the core journey themselves. Onboarding (FR-ONB-1) is the only flow written in sequence.

In the other direction, §9 and several FRs carry implementation detail (FR-ALM-11 `LOCKED_BOOT_COMPLETED`, FR-ALM-12 manifest attributes, the §9 Gradle gate command). For a solo owner feeding an autonomous loop that's defensible, since it prevents known Android pitfalls. It's labelled "to be finalized by the Architect workflow", so I'm not flagging it.

### Findings
- **[high]** The core flows have no User Journeys. At minimum the PRD needs:
  - UJ1 "chronic snooze, pays twice then wakes" (protagonist from §4 primary persona);
  - UJ2 "zero-snooze morning with grace window" (with the bystander);
  - UJ3 "check can't be done → fallback";
  - UJ4 "phone rebooted overnight, locked, Direct Boot";
  - UJ5 "payment pending / cancelled while ringing".
  Each should end in a stated outcome and the FR IDs it exercises. These are where the state-machine gaps listed under Done-ness would surface. *Fix:* Add a §6.4 "User journeys" with named protagonists and FR cross-references.

## Mechanical notes
- **Required sections:** A Glossary and User Journeys are missing for a chain-top consumer PRD (see above). There's also no Assumptions Index. It has nothing to roundtrip against because the PRD has no inline `[ASSUMPTION]` tags.
- **ID continuity:** FR groups have no gaps or duplicates. Section numbering "7.1b" is non-standard; renumber to 7.2 and cascade. There's a stray blank line between FR-ALM-10 and FR-ALM-11.
- **Cross-references:**
  - FR-RNG-2's "design.md §7, §9.6" resolves: `_bmad-output/planning-artifacts/ux-designs/ux-pay-per-snooze-2026-09-26/design.md` has §7 Voice & copy and §9.6 Snooze confirm. Still, the PRD should give the relative path, and the upstream document depending on downstream section numbers is fragile.
  - FR-MSG-4 defers to "the UX design doc" without naming the file.
  - FR-SET-2's "(ONB-3)" drops the "FR-" prefix.
  - §6.1's "see FR-ALM-9" resolves.
  - §14 Q4 references §6.2 correctly.
- **Glossary drift:**
  - "Snooze" is used as a noun, a verb and an outcome state ("Snoozed").
  - "Missed" is both an outcome and plain English.
  - "Grace window" appears consistently. Good.
- **UJ protagonists:** N/A, since there are no UJs.
- **Frontmatter:** `status: Draft — awaiting owner review` is accurate. Bump the version when the §14 questions close.
