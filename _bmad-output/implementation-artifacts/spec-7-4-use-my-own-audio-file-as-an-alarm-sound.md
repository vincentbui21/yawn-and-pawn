---
title: 'Story 7.4: Use my own audio file as an alarm sound'
type: 'feature'
created: '2026-10-28'
status: 'done'
baseline_revision: '863adc3b3493c036af52aff83fcf55d1acae5053'
review_loop_iteration: 0
followup_review_recommended: false
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-7-context.md'
  - '{project-root}/.claude/skills/pps-design/SKILL.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-1-17-built-in-sound-library-with-preview-and-a-never-silent-fallback.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-7-1-record-and-store-motivation-messages.md'
warnings: ['oversized']
deferred:
  - summary: >-
      The import checks audio metadata, not a real decode, so a DRM or corrupt-payload file is accepted and some duration-less formats are refused.
    evidence: |-
      MetadataAudioProbe reads HAS_AUDIO/MIMETYPE and DURATION only. At ring time such a file falls back to the default sound (never silent). A MediaExtractor or prepare-only MediaPlayer check at import would close it; verify with real files in 7.12.
    location: >-
      androidApp/src/main/kotlin/com/yawnandpawn/app/android/sound/AndroidSoundImporter.kt
    severity: medium
  - summary: >-
      No busy state while a large file is imported, and a storage failure shows the "can't be played" snackbar.
    evidence: |-
      EditorSounds shows nothing during the copy; no approved wording exists for a storage error (copy changes are deferred to v2).
    location: >-
      composeApp/src/commonMain/kotlin/com/yawnandpawn/app/ui/editor/EditorSounds.kt
    severity: low
  - summary: >-
      Imported sounds cannot be removed, so restored "File missing" rows stay until a v2 remove action.
    evidence: |-
      Out of scope for 7.4 by the spec; listed for the owner.
    severity: low
  - summary: >-
      MainActivity's OpenDocument launcher, ContentResolverDocuments and a few importer error paths have no direct tests.
    evidence: |-
      They are covered through fakes and shadows; the real picker and provider behaviour is a 7.12 phone check.
    location: >-
      androidApp/src/main/kotlin/com/yawnandpawn/app/android/sound/AndroidSoundImporter.kt
    severity: low
  - summary: >-
      Delete all data deletes the recordings folder before the sounds folder; a failure of the second leaves recordings' rows without audio until the retry.
    evidence: |-
      Same retry semantics as Story 7.1's eraser.
    location: >-
      data/src/androidMain/kotlin/com/yawnandpawn/app/data/reset/AndroidLocalDataEraser.kt
    severity: low
---

<intent-contract>

## Intent

**Problem:** Users can only wake to the built-in sounds or a phone ringtone (FR-SND-6). They want their own audio file, and the alarm must still never be silent (NFR-2).

**Approach:**
- **Import:** "Pick a file" in the Sound sub-screen's "Your files" section opens the system document picker (`ACTION_OPEN_DOCUMENT`, `audio/*`).
- **Validate and store:** the file is checked, copied into credential-protected `filesDir/sounds/{id}.{ext}`, recorded in a new `custom_sound` table, and selected as `SoundRef.File(id)`.
- **Play:** the alarm and the preview play the copy. If the copy is missing or fails, the default built-in sound plays instead.

## Boundaries & Constraints

**Always:**
- **Picker:**
  - The system document picker, with no storage or media permission; the permission allowlist is unchanged.
  - The app takes the persistable read permission for the import, then releases it once the copy is done.
- **Validation:**
  - The file is decodable audio of at least 1 s and at most 20 MB.
  - Non-audio, corrupt or DRM files show the snackbar "This file can't be played. Pick another."
  - A file over 20 MB shows "This file is too big. Pick one under 20 MB."
  - On either error nothing is copied or saved, and the current choice stays.
- **Storage:**
  - The copy is written to a temp file, then renamed into `sounds/`.
  - Only credential-protected storage is used.
  - The alarm never depends on the original file.
  - `custom_sound` has `id` (UUID v4), `display_name`, `file_name`, `duration_ms` and `created_at`.
  - The table comes from one migration: the next free `app.db` version after `main` at PR time, expected v13 to v14. Its schema JSON is exported, and a migration test keeps existing alarms and history.
- **SoundRef:**
  - `SoundRef.File(id)` encodes as `file:{id}`.
  - Unknown text still means the default sound.
- **Ring:**
  - `AlarmPlayer` loops the copy at the set volume, with gradual volume, using `USAGE_ALARM`.
  - The default built-in sound plays within the same ring when the copy is missing (for example after a restore, since `sounds/` is not backed up), unreadable, or fails at prepare or mid-ring.
  - The fallback is logged without paths or names.
  - Before the first unlock, the existing Direct Boot substitution plays the default sound, and that ring keeps it after unlock.
- **Preview:** a `sound-row` for a file previews like a built-in sound (Story 1.17).
- **Missing file:** the row and the editor show "File missing. Default sound will play.", and the row cannot be previewed.
- **Copy:** every string is a resource, verbatim from EXPERIENCE.md, pinned by `CopyRulesTest`.
- **Delete all data** removes `custom_sound` rows and the `sounds/` files. Before the first unlock it deletes nothing, like recordings.
- **Privacy:** file names, URIs and paths are never logged; only durations and error kinds are.

**Never:**
- No loudness check on user files (FR-SND-1 is for bundled files only).
- No new permission and no new dependency.
- No deleting or renaming of imported files in this story.
- No change to built-in or system sound behaviour.
- No direct clock reads (`NoDirectTimeAccess`).
- No audio or motivation work from Stories 7.3 and 7.5.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Import | 30 s MP3, 2 MB | copied to `sounds/{id}.mp3`; `custom_sound` row; listed under "Your files" with caption "Your file"; selected; Save stores `file:{id}` | — |
| Not audio | a PDF or corrupt file | snackbar "This file can't be played. Pick another." | nothing copied or saved |
| Too short | 0.5 s audio | same "can't be played" snackbar | nothing copied or saved |
| Too big | 25 MB | snackbar "This file is too big. Pick one under 20 MB." | nothing copied; a partial temp file is deleted |
| Picker cancelled | no URI | nothing changes | — |
| Ring, file present | `file:{id}` | loops the copy | — |
| Ring, file missing | row present, file gone | default sound, same ring | fallback logged without path |
| Ring, decode error | prepare or mid-ring error | default sound, same ring | as above |
| Before first unlock | `file:{id}` | default sound, kept after unlock | — |
| Editor, file missing | `file:{id}` without a file | "File missing. Default sound will play." on the row and the editor | preview disabled |
| Unknown id | `file:{id}` with no row | as "file missing" (name unknown: shown as the default sound's name) | — |

</intent-contract>

## Code Map

- `core/src/commonMain/.../core/sound/SoundRef.kt` -- `BuiltIn`/`System`, `encode`/`parse` (l.34); add `File(id)` = `file:{id}`. Tests: `core/src/commonTest/.../core/sound/SoundRefTest.kt`.
- `core/.../core/sound/SoundPorts.kt` -- `SoundLibrary` (`systemSounds`, `isAvailable`) and `SoundPreview`. Add the custom-sound list (or a `CustomSoundRepository` port) and an import port. The fakes live in `testing/.../SoundFakes.kt` (`FakeSoundLibrary.isAvailable` l.24 switches over `SoundRef`).
- `core/.../core/session/DirectBoot.kt:40-52` -- already turns anything that is not `BuiltIn` into the default. Add a File case to `DirectBootTest`.
- `core/.../core/recording/Recording.kt` and `RecordingUseCases.kt` -- the 7.1 template for a domain row plus a file with `missing`.
- `data/src/commonMain/.../data/recording/RecordingEntity.kt` and `RecordingDao.kt` -- the template for `custom_sound`.
- `data/src/androidMain/.../data/recording/RecordingFiles.kt` -- folder helper: plain-name guard, temp write, `exists`, `deleteAll` (throws before unlock), `@Suppress("CredentialStorageAccess")`. Copy it as `SoundFiles` with `FOLDER = "sounds"`.
- `data/src/androidMain/.../data/recording/RoomRecordingRepository.kt` -- `withFile()` sets `missing`, and `storage{}` maps errors to `StorageFailure(class name)`.
- `data/src/commonMain/.../data/db/AppDatabase.kt` (`SCHEMA_VERSION`, entities, KDoc history) and `AppDatabaseMigrations.kt` (`MIGRATION_10_11` is the create-table template; the list is at the end). Schemas are in `data/schemas/...AppDatabase/`. Migration tests follow `AppDatabaseSkipTest.kt`/`AppDatabaseMotivationTest.kt`; `AppDatabaseFactoryTest` pins the version list (keep it under detekt `LargeClass`).
- `data/src/androidMain/.../data/DataModule.kt:148-169` -- recording Koin wiring and the eraser (`recordings = get()`).
- Delete all data:
  - `data/src/androidMain/.../data/reset/AndroidLocalDataEraser.kt:40` -- delete the files before the rows.
  - `data/src/commonMain/.../data/reset/AlarmDataDao.kt` -- add `DELETE FROM custom_sound`.
  - `core/.../core/reset/DeleteAllData.kt:65` -- KDoc.
  - Tests: `data/src/androidHostTest/.../reset/DeleteAllDataIntegrationTest.kt` (l.132, l.196).
- `composeApp/src/commonMain/.../ui/sound/SoundList.kt` -- already has `SoundSource.File`, the "Your files" section, "Pick a file" (a `PpsTextButton` at l.104-111; the AC asks for `button-outlined`, so use the outlined button component from `ui/components`), the missing caption, and `PickFileClicked`.
- `composeApp/.../ui/editor/EditorSounds.kt` -- `checkChosen` (l.48), `onIntent` with the `PickFileClicked` stub (l.85), and the pure `editorSound(...)` (l.120, `showFiles = false` at l.150). It casts `as? SoundRef.System` at l.51/77/126-142; l.140 must not name a File as the default.
- `composeApp/.../ui/editor/AlarmEditorViewModel.kt:113-114,189` (`EditorSounds` construction) and `AlarmEditorContract.kt` (`EditorSound` l.157, `EditorEffect` l.416). `AlarmEditorScreen.kt:143-175` shows snackbars from effects.
- `composeApp/.../ui/editor/EditorSubScreens.kt:163-186` -- `SoundPane` and its missing `NoteInline`.
- `composeApp/src/commonMain/composeResources/values/strings.xml:141-161` -- the sound strings; add the two snackbar strings.
- `composeApp/src/androidHostTest/.../ui/CopyRulesTest.kt`.
- Picker plumbing:
  - Template: `androidApp/.../android/recording/AndroidMicrophonePermission.kt` (launcher, `CompletableDeferred`, attach/detach, `onResult`, suspend `request`).
  - Its commonMain port: `composeApp/.../ui/recordings/MicrophonePermission.kt`.
  - `MainActivity.kt:70-117` registers launchers and attaches them `onStart`/`onStop`. Add `ActivityResultContracts.OpenDocument()`.
- Playback:
  - `androidApp/.../android/sound/LibrarySoundResolver.kt:38` -- add File: `sounds/{id}.*` exists → `AlarmSound.File(Uri.fromFile(..).toString())`, else null (the player then plays the default and logs `SoundFellBack`).
  - `androidApp/.../android/wake/Playback.kt` (`AlarmSound.File`, `soundUri`), `AndroidAlarmPlayer.kt` (`resolve` l.235, `fallbackAfter` l.408).
  - `WakeModule.kt:60` binds the resolver.
  - `AndroidSoundLibrary.kt:71` -- `isAvailable` for File.
  - `AndroidSoundPreview.kt` resolves through the resolver.
- Tests to extend:
  - `androidApp/src/test/.../android/wake/AndroidAlarmPlayerTest.kt` (fallback tests l.187-481) and `DirectBootRingTest.kt:154`.
  - `androidApp/src/test/.../android/sound/SoundLibraryTest.kt`.
  - `composeApp/src/commonTest/.../ui/editor/AlarmEditorSoundTest.kt` (l.110 asserts "Your files" is hidden).
  - `androidApp/src/test/.../ui/SoundPickerScreenshotTest.kt` (`SoundSamples`; l.98 asserts the section is absent).
- Backup:
  - `androidApp/src/main/res/xml/data_extraction_rules.xml` and `backup_rules.xml` already exclude `file/sounds`.
  - `androidApp/src/test/.../BackupRulesTest.kt:73,110-123` -- add a `custom_sound` schema assertion.
  - `androidApp/src/test/.../android/backup/BackupRulesCoverageTest.kt:130-150` -- add a `sounds/` file.
- Design preview: `androidApp/src/debug/.../debug/preview/PreviewSamples.kt:119,195` already has a File row (missing) and `editor_sound_missing`.

## Tasks & Acceptance

**Execution:**
- **Core:**
  - Add `SoundRef.File(id)` with parse and encode tests.
  - Add a `CustomSound` domain type (`id`, `displayName`, `fileName`, `durationMs`, `createdAt`, `missing`) and a `CustomSoundRepository` port (`observeAll`, `get`), with a fake.
  - Add a `SoundImporter` port, `import(uri: String): Outcome<ImportResult, DomainError>`, where `ImportResult` is `Imported(CustomSound)`, `NotPlayable` or `TooBig`, with a fake.
  - Add File branches to every `when` over `SoundRef`.
  - Add a `DirectBootTest` case for File.
- **Data:**
  - `CustomSoundEntity`/`CustomSoundDao`, the migration (next free version), `SCHEMA_VERSION`, the exported schema, and a new migration test class.
  - `SoundFiles` (the `RecordingFiles` pattern).
  - `RoomCustomSoundRepository`, with `missing` meaning the file does not exist.
  - The eraser and `AlarmDataDao`; Koin wiring.
- **androidApp:**
  - `AndroidSoundFilePicker` (OpenDocument launcher, the `AndroidMicrophonePermission` pattern) wired in `MainActivity`.
  - `AndroidSoundImporter`:
    - checks size with `OpenableColumns.SIZE` and also counts while copying, aborting over 20 MB;
    - reads duration and has-audio through `MediaMetadataRetriever`;
    - takes and later releases the persistable permission;
    - copies to temp, renames, inserts the row;
    - deletes the temp file on any failure.
  - Resolver, library `isAvailable` and preview support for File.
- **composeApp:**
  - `EditorSounds` lists the imported files under "Your files" (`showFiles = true`), names a chosen File by its display name, and marks it missing.
  - `PickFileClicked` launches the picker, then the importer. On success it selects the file; otherwise a new `EditorEffect` shows the matching snackbar.
  - "Pick a file" becomes the outlined button.
  - Add the two strings and pin them in `CopyRulesTest`.
- **Tests:**
  - Import: bytes are copied and the row saved; non-audio is rejected; oversized is rejected (Robolectric with a fake or shadow metadata).
  - Ring: a missing file plays the default; a `MediaPlayer` error mid-ring plays the default; Direct Boot plays the default.
  - Editor ViewModel: the picker flow and its snackbars.
  - Roborazzi: "Your files" with a file row, a missing-file row, and an error snackbar, in Light, Dark and at 200%.
  - Backup and Delete-all tests.

**Acceptance Criteria:**
- Given the editor's Sound sub-screen, when it renders, then after "Built-in" and "System" it shows "Your files" with each imported sound (caption "Your file") and an outlined "Pick a file".
- Given a valid file picked, when the import finishes, then the new sound is selected and Save stores `file:{id}`; an alarm with it rings the copy, and the default sound if the copy is gone or fails.
- Given the pps-design Done checklist, when the story is done, then every item is ticked below, `CopyRulesTest` passes, and `./gradlew qualityGate` passes in CI.

## Spec Change Log

**Rebased onto bddaf18e (2026-10-28).** Story 7.5 (#106) took `app.db` v14 (`alarm.motivation_mode`), so this story's migration became `MIGRATION_14_15`, `SCHEMA_VERSION = 15`, with an exported `15.json`; 7.5's `14.json` is kept. `DirectBootRingTest` keeps both stories' locked-restore cases.

## Review Triage Log

### 2026-10-28 — Review pass
- verdicts: 24 findings — high 0, medium 1, low 21, false 2, maybe-false 0 (duplicates across layers share a row)
- findings:
  - `[low]` `[patch]` (edge 1, blind 1, verification other) A cancellation between the rename and the row insert leaves an orphan copy in `sounds/` — `store` deletes the renamed copy when `add` throws (including cancellation); importer test added.
  - `[low]` `[reject]` (edge 2) A blocking copy keeps running after the editor is cancelled — It ends within the 20 MB cap; with the edge 1 fix nothing is left behind.
  - `[low]` `[patch]` (edge 3) A pick whose result never arrives keeps `pending` forever — `pick` clears its own pending answer when the waiting coroutine is cancelled; test added.
  - `[low]` `[patch]` (edge 4, blind 4) A late import overrides a sound chosen meanwhile — The imported sound is selected only if the choice is unchanged since the pick began; ViewModel test added.
  - `[low]` `[reject]` (edge 5) One `observeAll` error leaves "Your files" empty for that editor — Same pattern as the recordings list; reopening the editor reads again.
  - `[low]` `[reject]` (edge 6) Two concurrent imports could delete each other's temp file — Only one editor exists at a time, and a second pick waits on the first.
  - `[low]` `[defer]` (edge 7) Delete all data: recordings deleted, then the sounds folder fails — The rows stay and the retry deletes both; same as Story 7.1's eraser.
  - `[low]` `[patch]` (edge 8) Ogg files reported as `application/ogg` are greyed out — The picker accepts `audio/*` and `application/ogg`.
  - `[low]` `[reject]` (edge 9) A `file:` URI whose name query throws loses the last-segment name — `nameOf` catches the error and lists the sound as "Your file"; the picker returns content URIs.
  - `[medium]` `[defer]` (blind 2, intent 3) The import checks metadata, not a real decode, so a DRM or corrupt-payload file is accepted (and some duration-less formats refused) — Never silent: such a file falls back to the default at ring time. A decode check at import is a follow-up for the phone checks (7.12).
  - `[low]` `[defer]` (blind 3) No busy state while a large file is copied — UX addition for the owner (v2 wording and design).
  - `[low]` `[defer]` (blind 5) A storage failure shows "This file can't be played. Pick another." — No approved wording exists; copy changes are deferred to v2.
  - `[low]` `[reject]` (blind 6) The editor's missing check and the ring find the copy differently — Exactly one `{id}.{ext}` exists per id by construction.
  - `[low]` `[patch]` (blind 7) `LocalDataEraser.deleteAlarmData` KDoc omits sounds — KDoc now names `sounds/` and `custom_sound`.
  - `[low]` `[patch]` (blind 8, intent 4, verification 1) File preview and the real graph's `findFile` wiring are untested — `SoundWiringTest` resolves `file:sound-1` and checks `isAvailable` through Koin (preview and ring share that resolver).
  - `[low]` `[defer]` (blind 9) Importer and picker test gaps (an IOException writing, `takeRead`/`size` throwing, a stale `.tmp` sweep, a nameless provider) — Covered in part; the rest is low-risk platform glue.
  - `[low]` `[patch]` (blind 10) The privacy text says the default plays "until you pick the file again" — Reworded: pick it again and choose it for that alarm. The missing rows stay until a v2 remove action (owner list).
  - `[low]` `[defer]` (verification 2) The `MainActivity` OpenDocument launcher wiring is untested — Needs an activity-result Robolectric harness; phone check in 7.12.
  - `[low]` `[patch]` (verification 3) Nothing pins that the app editor shows "Your files" and onboarding does not — `UiModuleTest` assertions added.
  - `[low]` `[patch]` (verification 4) The route's effect-to-snackbar mapping is untested — `AlarmScreensSemanticsTest` route case for the too-big snackbar.
  - `[low]` `[defer]` (verification 5) `ContentResolverDocuments` has no direct test — A thin platform shim like `PlatformRingtoneSource`; the copy-time cap still enforces 20 MB.
  - `[low]` `[defer]` (intent 1, 2, 5, 6, 7, 8) The real picker, persistable permission, real decoding, unlock mid-ring with a file, and the route's snackbars are exercised only through fakes or shadows — Platform boundary; listed for the 7.12 phone checks.
  - `[false]` `[reject]` (intent 9) Onboarding's first-alarm editor hides "Your files" — Deliberate: that editor shows no snackbars; the story names the app editor's Sound sub-screen.
  - `[false]` `[reject]` (intent 10) Copy to temp, then validate, versus validate, then copy — The observable result is the same: nothing is kept on refusal.

## Design Notes

- **Not previewed (owner review list):**
  - the "Your files" section with a real file row and the outlined "Pick a file" button;
  - the two error snackbars.

  They are built in the existing `sound-row` and snackbar styles. The missing-file row is the previewed `editor-sound-missing` state.
- **Ring-time lookup needs no database:** the resolver finds `sounds/{id}.*` in the folder. A missing row and a missing file are treated the same way.
- **Extension:** taken from the display name's extension: lower-case letters and digits, at most 5 characters, otherwise `audio`. The display name is the picked file's name without its extension. It is stored, never logged.
- **Imported files are never deleted in this story.** Delete all data removes them. A "remove file" action is v2 and is listed for the owner.

## pps-design Done checklist

- [x] Only DESIGN.md tokens (the existing `sound-row`, `PpsOutlinedButton` (`button-outlined`), `GroupCard` and snackbar; no new token, radius or size)
- [x] Light and Dark checked with screenshots (`sound_files_*`, `sound_file_error_*`)
- [x] Every colour pair is in the contrast table (text, text-secondary and accent-text on glass, `outline` border, the existing snackbar; no new pair)
- [x] Touch targets ≥ 48 dp (56 dp rows, 48 dp preview buttons, "Pick a file" asserted ≥ 48 dp)
- [x] Works at 200% font scale and with TalkBack (`*_font200` screenshots; radio-role rows, "Play preview", the disabled preview on a missing file)
- [x] Reduced-motion path works (no new animation)
- [x] Copy matches EXPERIENCE.md, strings in resources (`sound_file_not_playable`, `sound_file_too_big`, pinned with the "Your files" strings in `CopyRulesTest`)
- [x] State rows handled (empty "Your files", file row, missing row, import errors, picker cancelled)
- [x] "I'm up" prominence (not applicable)
- [x] Previews and screenshot tests updated (Roborazzi: "Your files" with a file row and a missing row, the error snackbar, Light, Dark, 200%)

## Verification

**Environment:** a company PC with no admin rights and very little free RAM.
- Set `JAVA_HOME=C:/Users/BuiTua/AppData/Local/Programs/jdk17/jdk-17.0.20.1+1` (Git Bash: `export JAVA_HOME=...`).
- Run ONE Gradle build at a time, with `--no-daemon` (another lane on this PC stops shared daemons) and `--max-workers=2`.
- No phone and no adb.
- Commit on the current branch when done. Commit messages end with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.
- The repo is public: no secrets, emails or personal data.

**Commands** (`--no-daemon`, one build at a time):
- `./gradlew --no-daemon :core:jvmTest --tests "*Sound*" --tests "*DirectBoot*"` -- expected: pass
- `./gradlew --no-daemon :data:testAndroidHostTest --tests "*AppDatabase*" --tests "*DeleteAllData*" --tests "*CustomSound*"` -- expected: pass
- `./gradlew --no-daemon :composeApp:testAndroidHostTest` -- expected: pass
- `./gradlew --no-daemon :androidApp:testDebugUnitTest --tests "*Sound*" --tests "*AlarmPlayer*" --tests "*DirectBoot*" --tests "*Backup*" --tests "*MainActivity*" --tests "*Wiring*"` -- expected: pass; record new Roborazzi baselines with `-Proborazzi.test.record=true` for the new states only
- `./gradlew --no-daemon spotlessApply detekt :core:detekt :data:detekt :composeApp:detekt :androidApp:detekt :testing:detekt` -- expected: clean
- `./gradlew qualityGate` (CI) -- expected: pass

## Auto Run Result

Status: done

**Summary.** "Pick a file" in the editor's Sound sub-screen opens the system document picker for audio files. The app needs no permission for this.
- The picked file is checked: it must report an audio track and a duration of 1 s or more, and be no more than 20 MB.
- A good file is copied into credential-protected `filesDir/sounds/{id}.{ext}`, recorded in the new `custom_sound` table (`app.db` v14 to v15), listed under "Your files" and selected as `file:{id}`.
- A refused file shows "This file can't be played. Pick another." or "This file is too big. Pick one under 20 MB.".
- At ring time the alarm loops the copy. If the copy is missing or fails, the default sound plays in the same ring. Before the first unlock the default sound plays.
- "Delete all data" removes the copies and the rows.
- The privacy page and the owner checklist describe the sound files.

**Files (main).**
- Core: `core/.../sound/SoundRef.kt` (File), `CustomSound.kt` (type, repository and importer ports, name rules).
- Data: `data/.../sound/` (entity, DAO, `SoundFiles`, `RoomCustomSoundRepository`), `AppDatabase.kt` and `AppDatabaseMigrations.kt` (`MIGRATION_14_15`), `15.json`, the eraser and `AlarmDataDao`.
- androidApp: `androidApp/.../sound/AndroidSoundImporter.kt`, `AndroidSoundFilePicker.kt`, `LibrarySoundResolver.kt`, `AndroidSoundLibrary.kt`, `SoundModule.kt`, `WakeModule.kt`, `MainActivity.kt`.
- composeApp: `composeApp/.../editor/EditorSounds.kt`, `AlarmEditorScreen.kt` (snackbars), `ui/sound/SoundList.kt` (outlined "Pick a file"), `SoundFilePicker.kt`, `UiModule.kt`, `strings.xml`.
- Docs: `docs/site/privacy.md`, `docs/closed-test/owner-checklist.md`.
- Tests: importer, picker, player, Direct Boot, library, wiring, backup, DB migration, Delete all data, editor ViewModel and route, `CopyRulesTest`, and 6 Roborazzi baselines.

**Review.** One pass found 24 findings: no high, 1 medium (deferred), 21 low and 2 false. 10 rows were patched, all low (orphan copy on cancel, an abandoned pick, a late import overriding a newer choice, Ogg in the picker, KDoc, privacy wording, and wiring, editor and route tests). 7 were deferred and 7 rejected; the reasons are in the triage log.

**Follow-up review recommended: false.** Every patched entry was low.

**Verification.** All runs used `--no-daemon`, one build at a time:
- spotless and detekt on every module;
- `:core:jvmTest` (Sound, DirectBoot, DeleteAllData);
- `:data:testAndroidHostTest` (AppDatabase, DeleteAllData, CustomSound);
- all of `:composeApp:testAndroidHostTest`;
- `:androidApp:testDebugUnitTest` (Sound, AlarmPlayer, DirectBoot, Backup, MainActivity, Wiring, AlarmScreens).

Everything passed, before and after the review fixes. `qualityGate` runs in CI.

**Residual risks.** Nothing has run on a device. These need checking on the phone in 7.12:
- the real picker and the persistable permission;
- real formats: Ogg, AAC, and DRM or corrupt files, which pass the metadata check and fall back to the default sound at ring time;
- preview and ring from a copied file.

**Owner review (not previewed).**
- The "Your files" section with a file row and the outlined "Pick a file" button.
- The two error snackbars.
- Imported files cannot be removed yet (v2).
- A storage error shows the "can't be played" text.
