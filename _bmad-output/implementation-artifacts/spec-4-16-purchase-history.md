---
title: 'Story 4.16: Purchase history'
type: 'feature'
created: '2026-10-09'
status: 'review'
baseline_revision: '8a38a112'
review_loop_iteration: 0
followup_review_recommended: true
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-4-context.md (Q5, owner decision 5: month cards)'
  - '{project-root}/_bmad-output/planning-artifacts/epics.md (Story 4.16; boundary with 4.17)'
  - '{project-root}/_bmad-output/planning-artifacts/ux-designs/ux-pay-per-snooze-2026-09-26/EXPERIENCE.md (Purchase history, purchase-row, skeleton, App screens states)'
  - '{project-root}/_bmad-output/planning-artifacts/ux-designs/ux-pay-per-snooze-2026-09-26/DESIGN.md (purchase-row, skeleton)'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-4-10-grant-ledger-purchase-records-and-consume-with-retry.md (price_source deferral)'
  - '{project-root}/androidApp/src/debug/kotlin/com/yawnandpawn/app/debug/preview (purchases_list, purchases_empty)'
deferred:
  - 'Story 4.17: turn on the "Problem with a charge?" row of Purchase history (showProblemWithCharge) and the You row "How payments & refunds work"; both lead nowhere until it is built.'
  - 'Epic 6: the Progress "Money paid" card links to the same Route.PurchaseHistory.'
---

<intent-contract>

## Intent

**Problem:** every charge must be visible to the user (FR-PRG-4). Story 4.10 writes `purchase_record` rows to `app.db`, but nothing shows them: the You tab has no rows and the approved Purchase history screen is only in the design preview.

**Approach:**
- **Core:** `PurchaseRecordRepository.observeAll(): Flow<List<PurchaseRecord>>`, newest purchase first (a read only; `PurchaseLedger` stays the only writer).
- **`:data`:** `PurchaseRecordDao.observeAll()` (Room flow, same order as `all()`), mapped by `RoomPurchaseRecordRepository`; an unreadable row is left out, as in `all()`.
- **`:testing`:** `FakePurchaseRecordRepository.observeAll()` over a `MutableStateFlow`, with an observe failure switch.
- **`:composeApp`:**
  - `PurchaseHistoryViewModel` combines the records with the alarms (labels) and the session history (the occurrence time of each session), maps each record with the pure `purchaseOf(record, …)`, and exposes `PurchaseHistoryUiState` (loading, loaded, load failed with "Try again").
  - `Purchase` gains nullable fields: `alarmTime`, `snoozeNumber`, `price` (null = not known), plus `alarmLabel`.
  - `PurchaseHistoryScreen` keeps the approved month cards, newest first; each month title shows that month's total per currency ("{amount1} + {amount2}") counting only known amounts of used payments. A row with no amount shows none. Loading shows a `skeleton` after 300 ms; a read failure shows "Couldn't load your purchases." with "Try again", never the empty state.
  - `Route.PurchaseHistory` pushed from the You tab's Money card ("Purchase history" row, now shown in production), Back returns to You.

## Boundaries & Constraints

- Read-only screen. No writes to `purchase_record`; the ledger writer scan test stays green.
- Money is neutral: prices and totals in `text`, tabular figures, formatted only through `MoneyFormatter` (`formatMoney`).
- Amount shown only when `priceSource == Intent` (`PriceSource.isAmountPaid`). Snapshot, tier and unknown estimates are never shown or totalled. EXPERIENCE.md has no "amount unknown" copy, so such a row shows no amount.
- Nothing sums across currencies: a month total is one amount per currency, joined with " + " (EXPERIENCE.md "Money, mixed currencies").
- "Problem with a charge?" belongs to 4.17: hidden in production until then, still shown in the design preview.
- Preview baselines: `purchases_list_*` change on purpose (the month totals); every other preview baseline is unchanged.

## Decisions (fast mode: default taken, owner can change)

1. **Month cards** (owner decision 2026-10-08, Q5), not the epic's flat list.
2. **Month totals in the card title row**, right-aligned in `text` (the coordinator's "totals per currency"). The epic's "nothing sums across currencies" holds: one total per currency.
3. **Totals leave out** payments that were not used (stranded, refunded automatically by Google) and payments with no known amount.
4. **Row date** is the purchase time in the phone's current zone. **Alarm** is the alarm's label when the alarm still exists and has one; otherwise the time the session rang for (`session_history.scheduled_at`), otherwise the alarm's current time; with none of them (a stranded payment with no profile id) the row shows the date only.
5. **Stranded rows** show their amount when it came from the intent (what was charged, then refunded); preview shows the same.
6. **Read failure** shows "Couldn't load your purchases." with "Try again" (new string, the Home pattern; added to EXPERIENCE.md App screens states). An alarm-label or session-time read failure only drops that detail (logged), it never hides the charges.
7. **Granted, consumed and reused** all read as normal paid snoozes ("Snooze {n}").

## I/O & Edge-Case Matrix

| Input | Shown |
|---|---|
| No records | "No snoozes paid. Keep it that way." |
| Loading under 300 ms | nothing |
| Loading over 300 ms | `skeleton` card (decorative, hidden from TalkBack) |
| Records read throws | "Couldn't load your purchases." + "Try again" (resubscribes) |
| Consumed / granted / reused, intent price | "{date} · {alarm}", "Snooze {n}", price |
| Stranded with alarm | "{date} · {alarm}", "Not used, refunded automatically by Google", price if intent |
| Stranded, no alarm / snooze number | "{date}", "Not used, refunded automatically by Google" |
| Price source snapshot / tier / unknown | row without amount, not in the total |
| Alarm deleted | the session's ring time, else no alarm part |
| Alarm with label | "{date} · {label}" |
| Two currencies in a month | each row its own currency; title "$3.00 + €2.00" |
| Month with only refunded / unknown amounts | no total |
| Records in two months | two cards, newest month first, rows newest first |

</intent-contract>

## Tasks

1. Core port `observeAll()`; Room DAO flow and repository mapping; fakes (`:testing`, core test double).
2. `PurchaseHistoryContract` (UI state, mapping), `PurchaseHistoryViewModel`, `PurchaseHistoryRoute`, Koin binding.
3. Screen: nullable fields, month totals, skeleton, load failure, `showProblemWithCharge`.
4. Navigation: `Route.PurchaseHistory`, `openPushed` helper, You tab row.
5. Strings and EXPERIENCE.md state row.
6. Tests: repository flow (Room host test), ViewModel (ordering, status mapping, amounts, totals inputs, alarm fallbacks, failure and retry), navigation unit test, Robolectric semantics (one TalkBack item per row, 64 dp rows, month heading, 48 dp targets, end to end from You on the real database), Roborazzi (empty, mixed statuses, two currencies, unknown amount, skeleton, load failure; Light, Dark, 200%).

## pps-design Done checklist

- [x] Only tokens from `DESIGN.md`: `purchase-row`, `skeleton` (`surface-variant`, `rounded.sm`), `label` and `body` type; no raw hex, no new radii or font sizes.
- [x] Light and Dark checked with screenshots (`purchases_*_light`, `purchases_*_dark`), and Light at 200%. No wake surface, so no Sunrise.
- [x] Every colour pair is in the contrast table: `text` and `text-secondary` on `glass+gradient-top` and on `gradient-top` (Light and Dark rows). The skeleton is decorative and carries no information.
- [x] Targets: rows are 64 dp (asserted), the back arrow, "Try again" and the Purchase history row ≥ 48 dp (asserted). No wake actions.
- [x] Works at 200% (screenshots; the row moves the price under the title instead of wrapping) and with TalkBack: one merged item per row, month titles are headings with their total, the skeleton is hidden. No outcome glyphs on this surface (statuses are words).
- [x] Reduced motion: no animation added (the skeleton has no shimmer; the pushed screen uses the existing slide, instant with reduced motion).
- [x] Copy from EXPERIENCE.md verbatim ("Purchase history", "No snoozes paid. Keep it that way.", "Snooze {n}", "Not used, refunded automatically by Google", mixed currencies "{amount1} + {amount2}"); one new string "Couldn't load your purchases." added to EXPERIENCE.md; strings in resources, `CopyRulesTest` passes.
- [x] State rows: Purchase history empty, Loading (skeleton after 300 ms), and the new storage read failure row are handled.
- [x] Wake rules: not a wake screen.
- [x] Previews: `purchases_list` and `purchases_empty` stay in the design preview; `purchases_list_*` re-recorded on purpose for the totals, every other preview baseline unchanged; new Roborazzi baselines `purchase_history_*`.

## Verification

**Commands:**
- `./gradlew qualityGate :androidApp:assembleDebugAndroidTest --no-daemon` -- expected: BUILD SUCCESSFUL.
