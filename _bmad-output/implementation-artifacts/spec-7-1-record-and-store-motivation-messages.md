---
title: 'Story 7.1: Record and store motivation messages'
type: 'feature'
created: '2026-10-27'
status: 'done'
baseline_revision: 'bb75ee1396ff5d73eb3668137a730ee135a5ce5a'
review_loop_iteration: 0
followup_review_recommended: false
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-7-context.md'
warnings: ['oversized']
deferred:
  - summary: >-
      The production free-space reading (AndroidAudioRecorder.allocatableBytes) is only tested to be above 0, which its
      unknown-value fallback always passes.
    evidence: |-
      A regression that makes the StorageManager call throw would return Long.MAX_VALUE and the "storage full" refusal
      would never trigger; a meaningful test needs an injectable StorageManager seam.
    location: >-
      androidApp/src/main/kotlin/com/yawnandpawn/app/android/recording/AndroidAudioRecorder.kt
    severity: medium
  - summary: >-
      An orphan recordings/{id}.m4a with no row (process killed between the file move and the row upsert) is never
      swept; only *.m4a.tmp takes are cleaned.
    evidence: |-
      RecordingFiles cleans only the take suffix; a sweep of files not named by any recording row at start or unlock
      would remove them.
    location: >-
      data/src/androidMain/kotlin/com/yawnandpawn/app/data/recording/RecordingFiles.kt
    severity: medium
---

<intent-contract>

## Intent

**Problem:** The app cannot record a short voice message or keep one safely on the phone, so Story 7.2 (Recordings screen) and 7.3 (playback after "I'm up") have no domain, storage or recorder to build on.

**Approach:** Add the recording domain in `:core` (model, ports, three use cases, new `DomainError`s), the `recording` table in `app.db` (v10 to v11) with a Room repository that keeps audio files in credential-protected `filesDir/recordings/{id}.m4a`, an `AndroidAudioRecorder` over `MediaRecorder`, fakes in `:testing`, explicit backup exclusions, the `RECORD_AUDIO` manifest declaration (already allowlisted), and a truthful privacy policy update. No UI in this story.

## Boundaries & Constraints

**Always:**
- Ports return `Outcome<T, DomainError>`; use cases validate input and never throw (cancellation propagates).
- Audio files live only in the credential-protected `context.filesDir/recordings/` (never `createDeviceProtectedStorageContext()`); a take is written to a temp file in that folder and only renamed to `{id}.m4a` on save, so a crash never leaves a half-written `{id}.m4a`.
- `MediaRecorder`: MIC source, MPEG_4 output, AAC encoder, mono, 44.1 kHz, 64 kbit/s, `setMaxDuration(60_000)`.
- The recorder stops by itself and keeps the take at 60 s, when the app goes to the background (no started activity left), when audio focus is lost (a call), or when the screen turns off; takes under 1 s become `DomainError.RecordingTooShort` and their temp file is deleted.
- Recorder errors, no microphone, missing permission and storage-full map to `DomainError`s and delete the temp file.
- Never log, upload or report recordings, names or file paths: only a duration or an error code may appear in a `LogEvent`.
- Backup: the `recording` table in `app.db` stays backed up; both backup XML files gain explicit `<exclude domain="file" path="recordings" />` (cloud-backup, device-transfer and full-backup-content); `BackupRulesTest` lists the new entries.
- A row whose file is absent is returned with `missing = true` (also when credential storage is still locked before first unlock).
- "Delete all data" (Story 5.9) also deletes every `recording` row and the `recordings/` folder.
- `app.db` takes the next free version at merge time (v11 today); the migration SQL matches the exported schema JSON.

**Never:**
- No Recordings screen, ViewModel, permission request, editor field, playback or `SessionConfig` change (Stories 7.2, 7.3).
- No new entry in `config/permission-allowlist.txt` (RECORD_AUDIO is already there); no storage permission.
- No `househunt/` or `sounds/` backup rules (House Hunt moved to v2; `sounds/` belongs to Story 7.4's lane).
- No user-visible string except the auto name "Message {n}", which is built in the use case from a format the UI will localise later; keep it a plain constant in core for now.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Save first take | no recordings, 9 s take | row "Message 1", file `recordings/{uuid}.m4a`, temp gone | No error expected |
| Auto-name | rows "Message 1", "Message 4", "Morning" | new name "Message 5" | No error expected |
| Re-record | existing id, new 12 s take | same id, file replaced, duration 12000 | NotFound if id unknown; temp deleted |
| Too short | stop after 0.6 s | `Failure(RecordingTooShort)` | temp file deleted |
| 60 s cap | recording runs 60 s | stops itself, take of 60 000 ms offered via `autoStopped()` | No error expected |
| Background / call / screen off | while recording | stops, keeps the take (or too-short) | as above |
| No permission / no mic / recorder error | `start()` | `Failure(MicrophoneUnavailable / RecorderFailure)` | temp deleted, error code logged |
| Storage full | write fails | `Failure(StorageFailure)` | temp deleted |
| Restored row, no file | row exists, file absent | `missing = true` in `get` and `observeAll` | No error expected |
| Delete | existing id | row and file gone | NotFound if unknown id |

</intent-contract>

## Code Map

- `core/src/commonMain/kotlin/com/yawnandpawn/app/core/error/DomainError.kt` -- add `RecordingTooShort`, `MicrophoneUnavailable`, `RecorderFailure(cause)`; check `diagnostic()` (core `error` package) covers new types.
- `core/src/commonMain/kotlin/com/yawnandpawn/app/core/id/IdGenerator.kt` -- UUID v4 ids for new recordings.
- `core/src/commonMain/kotlin/com/yawnandpawn/app/core/log/Logger.kt` -- `LogEvent.OperationFailed.of(operation, error)` for error codes; never put names/paths in it.
- `core/src/commonMain/kotlin/com/yawnandpawn/app/core/time/TimePorts.kt` -- `Clock` (wall `now()`) for `createdAt`.
- `data/src/commonMain/kotlin/com/yawnandpawn/app/data/billing/{PurchaseRecordEntity,PurchaseRecordDao,RoomPurchaseRecordRepository}.kt` -- template for entity, DAO, Room repository and the `storage {}` exception boundary.
- `data/src/commonMain/kotlin/com/yawnandpawn/app/data/db/AppDatabase.kt` -- entities list, `SCHEMA_VERSION = 10`, KDoc version history.
- `data/src/commonMain/kotlin/com/yawnandpawn/app/data/db/AppDatabaseMigrations.kt` -- `MIGRATION_9_10` pattern; `APP_DATABASE_MIGRATIONS`.
- `data/schemas/com.yawnandpawn.app.data.db.AppDatabase/` -- exported schemas; Room KSP writes `11.json`.
- `data/src/androidHostTest/kotlin/com/yawnandpawn/app/data/db/AppDatabaseFactoryTest.kt` -- schema-version and migration tests (lines ~423-495 for v10 pattern; "migrations cover every version step").
- `data/src/androidMain/kotlin/com/yawnandpawn/app/data/DataModule.kt` -- Koin bindings for DAO and repository (needs `Context` for `filesDir`).
- `data/src/commonMain/kotlin/com/yawnandpawn/app/data/reset/AlarmDataDao.kt` + `data/src/androidMain/kotlin/com/yawnandpawn/app/data/reset/AndroidLocalDataEraser.kt` -- "Delete all data"; add recording rows and folder; `DeleteAllDataIntegrationTest` / `DeleteAllDataWriterScanTest` may list tables.
- `androidApp/src/main/AndroidManifest.xml` -- add `RECORD_AUDIO` and `uses-feature android.hardware.microphone required=false` next to CAMERA.
- `androidApp/src/main/res/xml/{backup_rules,data_extraction_rules}.xml` + `androidApp/src/test/kotlin/com/yawnandpawn/app/BackupRulesTest.kt` (`sectionEntries`) + `androidApp/src/test/kotlin/com/yawnandpawn/app/android/backup/BackupRulesCoverageTest.kt` (coverage of every app file).
- `androidApp/src/main/kotlin/com/yawnandpawn/app/android/sound/{SoundModule,AndroidSoundPreview}.kt` -- adapter and Koin style; add `AndroidAudioRecorder` here (or `android/recording/`) and bind it in a module included by `appModule` (`YawnAndPawnApp.kt` ~line 130).
- `testing/src/commonMain/kotlin/com/yawnandpawn/app/testing/SoundFakes.kt` -- fake style; add `RecordingFakes.kt`.
- `docs/site/privacy.md` -- "Data on your phone", "Backup", "Camera and microphone" (line ~81 says no microphone), "Deleting your data", the top comment; `checkSitePages` runs over it.

## Tasks & Acceptance

**Execution:**
- `core/.../core/recording/Recording.kt` (new package `core.recording`) -- `Recording(id, name, durationMs, fileName, createdAt: Instant, missing: Boolean = false)`; `RecordedTake(takeId: String, durationMs: Long)`; `RecordingSession(takeId)`; ports `RecordingRepository { observeAll(): Flow<List<Recording>>; get(id): Outcome<Recording?>; save(recording, take): Outcome<Unit> /* upsert row, rename take to {id}.m4a */; delete(id): Outcome<Unit> }` and `AudioRecorder { start(): Outcome<RecordingSession>; suspend stop(): Outcome<RecordedTake>; cancel(); level(): Flow<Float>; elapsed(): Flow<Duration>; autoStopped(): Flow<Outcome<RecordedTake, DomainError>>; suspend discard(take) }` -- the domain the next stories build on.
- `core/.../core/recording/RecordingUseCases.kt` -- `SaveRecording(take)` ("Message {n}", n = highest existing "Message k" + 1, new UUID, `createdAt` from Clock), `ReRecord(id, take)` (keeps id and name, new duration; NotFound discards take), `DeleteRecording(id)`; validate `durationMs` in 1 000..60 000 -- auto-naming and id stability.
- `core/.../core/error/DomainError.kt` -- new errors (above) with KDoc.
- `core/src/commonTest/.../core/recording/RecordingUseCasesTest.kt` -- matrix rows for naming, re-record, too short, NotFound.
- `testing/.../testing/RecordingFakes.kt` -- `FakeRecordingRepository` (in-memory, can mark files missing) and `FakeAudioRecorder` (scripted takes, errors, `emitAutoStop`).
- `data/.../data/recording/{RecordingEntity,RecordingDao,RoomRecordingRepository}.kt` -- table `recording(id PK, name, duration_ms, file_name, created_at)`; repository takes a `RecordingFiles` abstraction (folder `File`) so `missing` = `!File(folder, fileName).exists()`; rename temp → `{id}.m4a`, delete file on delete; exceptions → `StorageFailure`.
- `data/.../db/AppDatabase.kt`, `AppDatabaseMigrations.kt`, `data/schemas/.../11.json` -- v10→v11 `MIGRATION_10_11` creating the table; bump `SCHEMA_VERSION`; KDoc.
- `data/src/androidHostTest/.../db/AppDatabaseFactoryTest.kt` -- exported v11 schema test and a v10→v11 migration test keeping alarms and history.
- `data/src/androidHostTest/.../recording/RoomRecordingRepositoryTest.kt` -- save/rename, re-record replace, delete, missing file, observeAll.
- `data/.../DataModule.kt` -- bind DAO and `RecordingRepository` with `File(context.filesDir, "recordings")`.
- `data/.../reset/AlarmDataDao.kt`, `AndroidLocalDataEraser.kt` (+ their tests) -- delete recording rows in the same transaction and the recordings folder.
- `androidApp/.../android/recording/AndroidAudioRecorder.kt` + `RecordingModule.kt` -- MediaRecorder adapter: temp file `recordings/take-{takeId}.m4a.tmp`, monotonic elapsed, amplitude level 0..1 polled ~10 Hz, max-duration listener, audio-focus (`AUDIOFOCUS_GAIN_TRANSIENT`) loss, `ACTION_SCREEN_OFF` receiver, Application `ActivityLifecycleCallbacks` started-count to detect background; deletes stale `*.tmp` on `start()`; include module in `appModule`.
- `androidApp/src/test/.../android/recording/AndroidAudioRecorderTest.kt` -- Robolectric with an injectable recorder factory/fake `MediaRecorder` wrapper: too short, 60 s auto-stop, focus loss stop, screen-off stop, error deletes temp, no permission.
- `androidApp/src/main/AndroidManifest.xml` -- RECORD_AUDIO and optional microphone feature, with a comment (asked only on the first record tap, Story 7.2).
- `androidApp/src/main/res/xml/backup_rules.xml`, `data_extraction_rules.xml`, `BackupRulesTest.kt` -- explicit `recordings` exclusions and comment.
- `docs/site/privacy.md` -- microphone used only to record your own messages when you tap record; recordings stay on the phone, never uploaded, not in the backup (their names and lengths are in the backed-up `app.db`, so a restored phone lists them as missing); "Delete all data" deletes them; update the top comment.

**Acceptance Criteria:**
- Given a fresh install, when `app.db` opens, then it is at v11 with an empty `recording` table, and a v10 file migrates keeping every alarm and history row.
- Given the merged debug manifest, when `checkPermissionAllowlist` runs, then it passes with RECORD_AUDIO declared and the allowlist unchanged.
- Given the backup XMLs, when `BackupRulesTest` and `BackupRulesCoverageTest` run, then `recordings` is excluded in every section and `app.db` is still included.
- Given "Delete all data", when it completes, then no recording row or file remains.
- Given any recorder or repository failure, when it is logged, then the log event holds no file name, path or recording name.
- `./gradlew qualityGate` passes.

## Design Notes

`save(recording, take)` doubles as re-record: an upsert of the row plus an atomic `File.renameTo` of the take over `{id}.m4a` (on Android rename replaces the target). The temp file and the final file sit in the same folder, so the rename is atomic. `RecordingSession`/`RecordedTake` carry only a take id; the adapter owns the temp path.

Background detection uses `Application.registerActivityLifecycleCallbacks` (started-activity counter) to avoid a new `lifecycle-process` dependency.

## Verification

**Commands:**
- `./gradlew :core:jvmTest :testing:allTests` -- expected: green (use the module's actual test task names).
- `./gradlew :data:testAndroidHostTest` (or the module's host-test task) -- expected: green incl. migration tests.
- `./gradlew :androidApp:testDebugUnitTest --tests "*Recorder*" --tests "*Backup*"` -- expected: green.
- `./gradlew qualityGate` -- expected: green (run once at the end, alone).

## Spec Change Log

## Review Triage Log

### 2026-10-28 — Review pass
- verdicts: 31 findings — high 0, medium 18, low 11, false 2, maybe-false 0 (several rows are duplicates that share a root with another row and its route)
- findings:
  - `[medium]` `patch` (verification-gap) AppForeground binding only type-checked; the fallback StartedActivities is never registered — fallback registers on the Application; graph test asserts the same instance.
  - `[medium]` `patch` (verification-gap) no manifest test for RECORD_AUDIO and microphone required=false — manifest test added next to the camera one.
  - `[medium]` `defer` (verification-gap) allocatableBytes only tested `> 0` — needs a StorageManager seam; recorded in `deferred`.
  - `[medium]` `patch` (intent) explicit `sounds` backup exclusion missing (story AC) — added to both XMLs and BackupRulesTest; `househunt` skipped (v2).
  - `[low]` `reject` (intent) privacy page describes the record-tap permission ahead of the Story 7.2 UI — the owner asked for the policy update in the story that adds the microphone; 7.2 lands next in the same run, before any release tag.
  - `[medium]` `patch` (blind) audio focus: CAN_DUCK from a notification chime ends the take — GAIN_TRANSIENT_EXCLUSIVE; only LOSS and LOSS_TRANSIENT stop.
  - `[medium]` `patch` (blind) Delete all data before the first unlock orphans the audio — folder deleted first; locked storage returns StorageFailure so the deletion retries.
  - `[medium]` `patch` (blind) failed re-record upsert loses the old audio — old file moved aside and restored on failure.
  - `[medium]` `patch` (blind) every start() deletes unsaved finished takes — stale takes cleaned once per process.
  - `[low]` `patch` (blind) CONFLATED autoStops drops earlier takes — UNLIMITED.
  - `[low]` `reject` (blind) start() does file I/O and MediaRecorder.prepare on the main thread — with the once-per-process cleanup only prepare() remains, the platform's normal use; not worth a threading change.
  - `[low]` `patch` (blind) auto name overflows at Int.MAX_VALUE — parsed as Long.
  - `[low]` `reject` (blind) `missing` stays true after unlock until a row changes — no Recordings UI can be open before the first unlock; Story 7.3 reads `get()` at fire time.
  - `[medium]` `patch` (blind) same root as the first verification-gap row (unregistered counter) — shared fix.
  - `[low]` `reject` (blind) privacy page and manifest ahead of the UI — duplicate of the intent row, same reason.
  - `[medium]` `patch` (blind) owner-checklist item 6 and the Data safety table still say recordings and the microphone are not built — updated; owner flagged to re-check Data safety.
  - `[low]` `reject` (blind) fakes are stickier than the real recorder — tests set what they need; no named failing caller.
  - `[low]` `reject` (blind) SaveRecording reads every file for names; two quick saves could both pick Message n — saves are serialized by the single Recordings screen; the cost is a few `exists()` calls.
  - `[medium]` `patch` (edge) stop, screen off or background after the 60 s self-stop but before the info event deletes the full take — treated as at-limit within a 500 ms slack.
  - `[low]` `reject` (edge) lock held across MediaRecorder.stop blocks the pollers briefly — stop takes milliseconds; no visible effect.
  - `[medium]` `patch` (edge) a configuration change briefly counts 0 started activities and auto-stops — isChangingConfigurations ignored.
  - `[medium]` `patch` (edge) duplicate of the unregistered-counter row — shared fix.
  - `[medium]` `patch` (edge) duplicate of the CAN_DUCK focus row — shared fix.
  - `[low]` `patch` (edge) startedAt read before engine.start — read after start returns.
  - `[medium]` `defer` (edge) an orphan `{id}.m4a` with no row (process killed between move and upsert) is never swept — needs an orphan sweep; recorded in `deferred`.
  - `[medium]` `patch` (edge) eraser deletes rows before the folder — duplicate of the Delete-all row, shared fix.
  - `[medium]` `patch` (edge) duplicate of the re-record row — shared fix.
  - `[low]` `reject` (edge) stop() right after an auto-stop returns "not recording" while the take arrives on autoStopped — the UI takes auto-stops from the flow; one outcome reaches the user.
  - `[low]` `patch` (edge) duplicate of the name overflow row — shared fix.
  - `[false]` `reject` (edge) NotFound logs the recording id, the stem of its file name — the id is a random UUID with no personal data; the rule bans names and paths, not ids.
  - `[false]` `reject` (intent) Crashlytics path untested for recordings — AndroidLogger is the only log sink and the tests assert no path or name in any LogEvent; nothing else reports recordings.

## Auto Run Result

Status: done

**Summary:** Recording domain in `:core` (`Recording`, `RecordedTake`, `RecordingRepository`, `AudioRecorder`, `SaveRecording` with "Message {n}", `ReRecord`, `DeleteRecording`, three new `DomainError`s, all refused during a wake session), the `recording` table (`app.db` v10 to v11, `11.json`, migration tests), `RoomRecordingRepository` over credential-protected `filesDir/recordings/{id}.m4a` (temp take, atomic move, `.bak` restore on a failed re-record, `missing` flag), `AndroidAudioRecorder` over `MediaRecorder` (AAC/M4A mono 44.1 kHz 64 kbit/s, 60 s cap, stops and keeps the take on background, focus loss and screen off), fakes in `:testing`, `RECORD_AUDIO` and an optional microphone feature in the manifest (allowlist unchanged), explicit `recordings` and `sounds` backup exclusions, "Delete all data" removing recordings, and the privacy policy and owner checklist updated.

**Files:** `core/.../core/recording/*` (domain and use cases), `core/.../error/DomainError.kt`, `core/.../log/Logger.kt`, `core/.../reset/DeleteAllData.kt` (KDoc); `data/.../recording/*` (entity, DAO, files helper, repository), `data/.../db/*` (v11), `data/.../reset/*` (erase order), `data/.../DataModule.kt`; `androidApp/.../android/recording/*` (recorder, engine, focus, foreground counter, module), `YawnAndPawnApp.kt`, `AndroidManifest.xml`, backup XMLs; `testing/.../RecordingFakes.kt`; tests for each; `docs/site/privacy.md`, `docs/closed-test/owner-checklist.md`.

**Review:** 31 findings. 13 patch groups applied (focus CAN_DUCK, delete-all before unlock and order, re-record restore, stale-take cleanup once per process, unlimited auto-stop channel, 60 s slack, start time, configuration changes, Koin fallback and its test, manifest test, name overflow, `sounds` rule, owner checklist). 2 deferred (free-space seam, orphan sweep). Rejected: privacy ahead of 7.2 UI (owner asked for it in this story; 7.2 follows in the same run), main-thread prepare, `missing` re-check after unlock, sticky fakes, name race, lock during stop, stop-after-auto-stop, NotFound id in logs (false), Crashlytics path (false).

**Follow-up review recommended:** false (no high patched; medium patches are covered by new tests).

**Verification:** full `qualityGate` before review: everything passed except two order-dependent `:androidApp` tests (`PaymentOutcomeMessagesTest`, `PpsThemeSelectionTest`: WorkManager leftovers from earlier classes) that pass when re-run alone; after the patches the targeted tests listed by the implementer passed (recorder 26, repository 12, delete-all 4, database 30, use cases 12, manifest, backup), plus spotless, detekt and `checkSitePages`. CI runs the full gate on the PR.

**Residual risks:** real `MediaRecorder` timing at 60 s, screen-off delivery and the free-space check are device-only (Story 7.12 item 1); the privacy page describes the record tap that Story 7.2 adds.
