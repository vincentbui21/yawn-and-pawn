---
title: 'Story 3.5: Choose the checks for each alarm'
type: 'feature'
created: '2026-10-06'
status: 'done'
baseline_revision: 'ad9736b'
review_loop_iteration: 0
followup_review_recommended: false
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-3-context.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-3-1-check-plugin-contract-and-the-math-generator-in-core.md'
  - '{project-root}/.claude/skills/pps-design/SKILL.md'
warnings:
  - 'depends-on-3.2: stacked on 3.1 (ad9736b). Merge only after Story 3.2, which brings the Math check screen. Without it, a ring that runs a configured Math plan cannot be answered on the wake screen.'
deferred: []
---

<intent-contract>

## Intent

**Problem:** Every alarm rings the same built-in plan. The user cannot pick checks, difficulty, count or the Random/All mode (FR-PWK-1..3), and Home shows no check icons.

**Approach:**
- **Storage:** add per-alarm check configuration in `:core` and `:data`. `app.db` goes from v4 to v5 with a `check_config` table and `alarm.check_mode`, and the migration gives every existing alarm one Math · Medium · 3 row.
- **Use cases:** a `CheckConfigRepository` port. `SaveAlarm` stores the alarm and its checks in one transaction, `DuplicateAlarm` copies them and `DeleteAlarm` removes them, all under the session guard.
- **Validation:** zero checks, the same type twice, a count outside the type's range, or a non-pickable type gives `InvalidAlarm(Checks)`.
- **Plan:** `ConfigResolver` builds the frozen `CheckPlan` from the alarm's mode and checks.
- **UI:** wire the approved Wake-up check sub-screen (`CheckPickerContent`), Check setup (`CheckSetup`) and the Home check icons to the editor ViewModel.

## Boundaries & Constraints

**Always:**
- **Pickable types:** a type with a core plugin that a user may pick, which is only `Math` (`Placeholder` never). Story 3.2's `CheckRegistry` (wake composables) narrows this list when it lands.
- **Defaults:** a new alarm starts with Random · Math · Medium · 3, the epic default. A newly ticked check starts at Medium with the core `defaultCount`.
- **Count range:** the stepper uses the core `countRange` (Math 1–10, owner decision q10). EXPERIENCE.md's "(1 to 5)" is the preview's generic range and is listed for the owner.
- **Strings:** existing resources only. "Move up", "Move down", "Pick at least one check.", "Problems", "Checks", "Mode", "Your checks", "Random" and "All" are all in EXPERIENCE.md.
- **Reordering:** reuse the preview composables. In All mode, "Move up" and "Move down" stay the preview's 48 dp row buttons and are also TalkBack custom actions on the row.
- **Unsaved changes:** check edits are part of the form, so "Discard changes?" covers them. The editor's Back from Check setup returns to the Wake-up check sub-screen.
- **Session lock:** every config write goes through a guarded use case (`whenIdle`). `SessionLockGuardScanTest` sees them.

**Never:**
- No new strings or screen states, and no new design.
- No "Try it" wiring (Story 3.6).
- No other check types (3.7+), and no camera, QR or House Hunt rows in production.
- No wake-screen change.
- No change to the test alarm's plan: the test ring keeps the default plan, and using the draft's checks is deferred.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Migrate | v4 db with 2 alarms | v5. Each alarm has `check_mode` Random and one Math · Medium · 3 row at position 0 | — |
| Save new | draft with Math · Hard · 5, All | the alarm and 1 config row, written together | storage error → `StorageFailure`, nothing stored |
| Save edit | Math kept, difficulty changed | the row keeps its id and `created_at`; `updated_at` = now | — |
| Invalid | 0 checks / Math twice / count 11 / Placeholder | `InvalidAlarm(Checks)`, nothing stored | — |
| Duplicate | alarm with configs | the copy has new config ids in the same order | — |
| Delete | alarm with configs | configs gone (the FK cascade and an explicit delete) | — |
| Identical new alarm | same settings and the same checks | the stored one is switched on. Different checks: stored as new | — |
| Resolve | mode All, configs at positions 1, 0 | `CheckPlan(All, [pos0, pos1])`. No configs: the default plan | a read failure in the wake service: the default plan, logged |
| Editor | remove the last check, Save | "Pick at least one check." under the row and in the sub-screen; no save | — |

</intent-contract>

## Code Map

- **Core (`core/.../core/alarm/`):**
  - `Alarm.kt`: `checkMode`; `AlarmField.Checks`; `hasSameSettingsAs` includes the mode.
  - `CheckConfig.kt` (new): `CheckConfig`, `CheckConfigRepository`, `validateChecks` and the default checks.
  - `AlarmUseCases.kt`: the draft gets checks and a mode; Save, Duplicate and Delete handle the configs.
- **Resolver:** `core/.../core/session/SessionConfig.kt` -- `ConfigResolver.resolve(alarm, checks, ...)`.
- **Data (`data/.../data/`):**
  - `alarm/`: `CheckConfigEntity`, `CheckConfigDao` (transactional save), `RoomCheckConfigRepository`, plus the `AlarmEntity` and mapping changes.
  - `db/`: `AppDatabase` v5 and `MIGRATION_4_5`; `schemas/.../5.json`; `DataModule`.
- **Testing:** `testing/.../FakeCheckConfigRepository`.
- **App wiring:** `androidApp`'s `YawnAndPawnApp` (Koin) and `WakeService` (reads the configs and resolves).
- **UI (`composeApp/.../ui/`):**
  - `checks/CheckTypes.kt`: UI↔core mapping and the pickable types.
  - `checkpicker/CheckPicker.kt`: the listed types and the TalkBack move actions.
  - `checksetup/CheckSetup.kt`: `CheckSetupContent` and the count range.
  - `editor/*`: the form checks, the `CheckSetup` pane and the per-row visibility of the full editor.
  - `home/HomeViewModel.kt`: check icons.

## Tasks & Acceptance

**Execution:**
- Core model, validation, use cases and resolver, with tests (`AlarmUseCasesTest`, `CheckConfigTest`, `ConfigResolverTest`).
- Data: entity, DAO, repository, the v5 migration and exported schema, with tests (`RoomCheckConfigRepositoryTest`, `AppDatabaseFactoryTest` v4→v5).
- Wiring: Koin, `WakeService`, fakes and the scan test.
- UI: the editor ViewModel (tested: add, remove, reorder, mode, validation, discard, load, save), the panes and Home icons.
- Screenshots (Light, Dark and 200%): the Wake-up check sub-screen (one check, several in All mode, none with the error), the picker and Check setup.
- Semantics: targets ≥ 48 dp and the move custom actions.

**Acceptance Criteria:**
- Given `./gradlew qualityGate`, then BUILD SUCCESSFUL, and `git status --porcelain androidApp/src/test/screenshots/preview` is empty.
- Given an alarm saved with checks, then `WakeService` freezes `CheckPlan(mode, entries by position)` into the session.

## pps-design Done checklist

- [x] Only tokens from `DESIGN.md` used (no raw hex, no new radii, no new font sizes): only the approved `CheckPickerContent`, `CheckSetup` and editor rows are reused, and no new styling is added.
- [x] Light, Dark (and Sunrise where relevant) checked with previews / screenshots: `EditorChecksScreenshotTest` covers Light, Dark and 200% (app screens; not Sunrise).
- [x] Every colour pair used is in the `DESIGN.md` contrast table: no new pairs (the preview's picker and setup pairs).
- [x] Touch targets ≥ 48 dp; wake actions ≥ 64 dp: `EditorChecksSemanticsTest` checks that every clickable node is ≥ 48 dp, at 100% and 200%. There are no wake actions here.
- [x] Works at 200% font scale and with TalkBack; outcome glyphs present: the 200% screenshots; check rows are checkboxes; "Move up" / "Move down" are TalkBack custom actions (test); the stepper bounds follow the type's range (test). No outcome glyphs on this surface.
- [x] Reduced-motion path works: only the existing sub-screen slide (`subScreenTransition`), now running backward for Check setup → Wake-up check.
- [x] Copy matches `EXPERIENCE.md > Voice and Tone` (no em dashes, no filler, strings in resources): no new strings, and `CopyRulesTest` passes.
- [x] Every state row in `EXPERIENCE.md > State Patterns` for this surface is handled: "No check selected" (the row value "None", the inline error under the row and in the sub-screen, Save blocked). The camera-unavailable and TalkBack Memory rows belong to those checks' stories.
- [x] "I'm up" is the most prominent wake action; snooze is visible, plain and priced: not a wake surface.
- [x] Compose `@Preview`s for each state (light/dark/Sunrise, empty/error) exist; screenshot tests updated (Paparazzi or Roborazzi): the preview catalogue's `editor-wake-check*` and `check-setup-*` states, unchanged, plus the 16 new production-state screenshots.

## Spec Change Log

## Review Triage Log

## Design Notes

- **Why the configs are a separate port:** the AC asks for it, and the plan is not part of `Alarm`'s scheduling fields. The one transaction lives in `CheckConfigDao.saveAlarmWithChecks` (update or insert the alarm, then replace its rows), so `SaveAlarm` and `DuplicateAlarm` store through `CheckConfigRepository.saveWithAlarm`.
- **Row ids:** `CheckConfig.idFor(alarmId, type)` = `<alarm id>:<type id>`, the same form the v5 migration writes. It is unique (a type appears once per alarm) and stable on edits, and it takes nothing from the `IdGenerator`, so alarm ids stay as they were. An edit keeps a staying type's `created_at`.
- **Several checks:** only Math is pickable, so production cannot show several checks yet. The ViewModel takes a `pickable` list (default `PickableCheckTypes`) so that its tests cover add, reorder and All mode with two types. The "several" screenshot uses Word Unscramble the way 3.7 will show it.
- **"Try it":** it stays visible and does nothing until Story 3.6 wires it (story AC).
- **Test heap:** the androidApp unit tests get a 1 GB heap, the same change as `fix(3.3): review fixes`, since the default 512 MB ran out at the end of the gate.
- **Full editor rows:** the production editor turns on only the Wake-up check row of `FullEditorSections` (`rows`), so quiet time, motivation and the fee ladder stay hidden until their stories. The preview keeps every row.
- **For the owner:** EXPERIENCE.md says the count stepper goes "1 to 5", but q10 says Math counts 1–10 (core `countRange`). This story follows the story AC and uses the type's range.

- **Before 3.2 (merge order):** on this base, a saved alarm rings its Math plan, which the placeholder wake screen cannot answer. `BackupRulesCoverageTest` therefore deletes its alarm's rows before the ring, and a comment says to remove that once 3.2 answers Math. An alarm stored without rows counts as having the default checks, both in the editor and in the identical-alarm check.
- **Deferred:** the test alarm rings `ConfigResolver.defaultPlan()`, not the draft's checks (a follow-up once 3.2/3.6 can ring Math).

## Verification

Results from the run (2026-10-06):
- New tests: `CheckConfigUseCasesTest` (15), `ConfigResolverTest` (+2), `RoomCheckConfigRepositoryTest` (8), `AppDatabaseFactoryTest` (v5 schema and v4→v5 migration), `AlarmEditorChecksTest` (8), `HomeViewModelTest` (+1 icons), `WakeServiceTest` (+2 frozen plan), `EditorChecksSemanticsTest` (6), and `EditorChecksScreenshotTest` (16 new `editor_check*` baselines).
- The session-guard test also checks that no check row changes during a session.

**Commands:**
- `./gradlew qualityGate` -- expected: BUILD SUCCESSFUL.
- `git status --porcelain androidApp/src/test/screenshots/preview` -- expected: empty.
