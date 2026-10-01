---
title: 'Story 1.17: Built-in sound library with preview and a never-silent fallback'
type: 'feature'
created: '2026-10-01'
status: 'done'
baseline_revision: 'da4b52d3025b6155bd3717c2e19dadcaadb71d1a'
review_loop_iteration: 0
followup_review_recommended: true
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-1-context.md'
  - '{project-root}/.claude/skills/pps-design/SKILL.md'
warnings: ['oversized']
deferred: []
---

<intent-contract>

## Intent

**Problem:** Every alarm rings the one bundled default. The Sound sub-screen only has the volume card: there is no sound list, no preview, and the player maps every sound reference to the default. Nothing checks that bundled alarm sounds are loud enough. `MediaPlayer.prepare()` runs on the main thread (deferred from Story 1.14).

**Approach:**
- Bundle at least 10 generated, CC0 alarm sounds, listed in a core `SoundCatalog` with exactly one default.
- List the phone's alarm ringtones through `RingtoneManager.TYPE_ALARM`.
- Wire the preview's `ui/sound` list into the editor's Sound sub-screen, with a one-at-a-time `USAGE_ALARM` preview.
- Make the alarm player resolve the chosen sound. A missing or broken sound falls back to the default in the same ring.
- Add `checkSoundLoudness` to `qualityGate`.

## Boundaries & Constraints

**Always:**
- **Sound references** (core `sound/SoundRef`), stored in `Alarm.soundRef` as text:
  - `SoundRef.BuiltIn(id)` is `builtin:<id>`. `Alarm.DEFAULT_SOUND_REF` stays `builtin:default`.
  - `SoundRef.System(uri, title)` is `system:<title>|<uri>`, with `%` and `|` in the title percent-escaped. The title is kept so a ringtone that disappears can still be named.
  - Anything else parses to `null`, which means missing.
- **SoundCatalog** (core): an ordered list of `BuiltInSound(id, resourceName)`, where `resourceName` is the `res/raw` file name without extension. It has at least 10 entries. Exactly one is the default: `id = "default"`, `alarm_default`, named "Sunrise". Every `androidApp/src/main/res/raw/alarm_*.ogg` file is in the catalog, and every catalog entry has a file (tested).
- **Bundled sounds:**
  - OGG Vorbis, mono, 48 kHz, 2 to 4 s seamless loops.
  - Generated in the repo by `tools/sounds/` scripts (uv + soundfile + numpy, user scope).
  - Each has a row in `docs/sounds/LICENSES.md` (source, author "Yawn & Pawn contributors", CC0 1.0, the generator command).
  - Each passes the loudness rule: sample peak ≥ −3 dBFS and integrated loudness ≥ −14 LUFS (BS.1770).
  - Display names are composeResources strings (`sound_name_<id>`). They are drafts in `docs/design-preview/copy-to-approve.md`.
- **checkSoundLoudness** (build-logic plugin, a `qualityGate` dependency):
  - It measures every `alarm_*` file under `androidApp/src/main/res/raw` with `ffmpeg -af ebur128=peak=sample`.
  - It fails, naming each file and the rule it misses.
  - It prints the measured values. It lists the exempt UI sounds (`composeApp/src/androidMain/res/raw/wheel_tick.wav`) and does not measure them.
  - ffmpeg comes from Gradle property `yawnandpawn.ffmpeg`, or else `ffmpeg` on `PATH`. If ffmpeg can't start, the task fails with an install hint (apt / brew / a portable build, and the property).
  - On machines that cannot run ffmpeg (this company PC blocks unsigned executables), `yawnandpawn.loudnessMeasurer=python` measures with `tools/sounds/measure_loudness.py`, run through uv (`yawnandpawn.uv`, or `uv` on PATH). It prints the same ebur128 summary format, so the same parser and rules apply. The default stays ffmpeg.
  - CI installs ffmpeg with apt before the quality gate.
- **Fixture test** (build-logic): generated WAV fixtures. A compliant tone passes. A quiet tone fails, naming the file. A peak-only and a loudness-only failure each name their rule. The ebur128 summary parser is tested on captured ffmpeg output. A missing ffmpeg fails with the install hint (TestKit).
- **System sounds:** an Android `SoundLibrary` adapter lists `RingtoneManager(TYPE_ALARM)` entries as `SoundRef.System(uri, title)`, off the main thread. A failing query gives an empty list and is logged. `isAvailable(ref)`:
  - a known built-in is true;
  - a system URI is true when its file descriptor opens;
  - anything else is false.
- **Player resolution:**
  - `builtin:default` resolves to `AlarmSound.Default`.
  - Another catalog id resolves to `AlarmSound.BuiltIn(rawRes)`, through an androidApp map from resource name to `R.raw` id.
  - `system:` resolves to `AlarmSound.File(uri)`.
  - Unknown refs or ids resolve to `null`, so the default plays, logged as "unknown sound reference".
  - The existing fallback chain stays: chosen sound → default → phone alarm → default. Logs carry no URI, path or ref.
- **Async prepare:** `MediaPlayerPlaybackFactory` uses `prepareAsync`.
  - A `start()` or gain before prepared is applied when it is prepared.
  - An async prepare error goes through the same `onError` path, so the next sound in the chain plays.
  - `setDataSource` failures (a missing URI) still throw synchronously, so the fallback happens in the same call.
- **Editor wiring** (design baseline, no redesign):
  - `EditorForm.soundRef` holds the chosen sound. It is loaded from the stored alarm, saved on "Save", and changing it counts as an unsaved change.
  - The three sound fields move out of `FullEditorSections` into `EditorUiState.sound: EditorSound?` (name, name resource, missing, picker). The production ViewModel fills it. The preview app and its baselines render unchanged.
  - The Sound sub-screen shows the volume card, then "Built-in" and "System". "Your files" and "Pick a file" are hidden (`SoundPickerUiState.showFiles = false`, Story 7.4).
  - Selecting a row and going back updates the editor's Sound row.
  - A chosen sound that is unavailable gets:
    - `missing = true`;
    - the note "File missing. Default sound will play." in the Sound sub-screen and under the editor's Sound row;
    - a row in its section with the stored title, the missing caption and a disabled preview button.
- **Preview** (core `SoundPreview` port, Android `AndroidSoundPreview`):
  - It plays the sound once, non-looping, `USAGE_ALARM`, with the alarm stream at the alarm's volume. It reuses `AlarmVolume` save and restore.
  - It plays one at a time: a second preview stops the first, and tapping the playing row stops it.
  - `previewing: StateFlow<String?>` clears when playback completes or fails.
  - It stops when the user leaves the Sound sub-screen (Back, or another pane), when the ViewModel is cleared, and when the editor's lifecycle reaches `ON_STOP` (backgrounding).
  - A volume change while previewing updates the stream.
  - It never plays while a ring is active, and it never restores the volume while a ring is active.
  - TalkBack reads "Play preview" / "Stop preview" (existing strings).
- Strings are in resources and match EXPERIENCE.md verbatim. `CopyRulesTest` passes. The pps-design Done checklist is ticked below.

**Never:**
- No "Your files" / file picker (Story 7.4), no Direct Boot sound swap (Epic 2), no new permission (no storage or media permission), no new runtime dependency.
- No change to the approved layout. No change to `androidApp/src/test/screenshots/preview` baselines.
- No bypass of the PC's application-control policy. No admin-rights install.
- No file paths, URIs or refs in logs.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Built-in ring | `builtin:chimes` | Player opens the `alarm_chimes` raw resource | No error expected |
| System ring | `system:Argon\|content://…` that opens | Player opens that URI | No error expected |
| Missing URI | System URI whose `setDataSource` throws | Default plays in the same ring | Logged without the URI |
| Mid-ring error | `MediaPlayer` error callback while playing | Default plays | Logged |
| Async prepare error | Error callback before prepared | Default plays | Logged |
| Unknown ref | `builtin:gone` / `foo` | Default plays | Logged "unknown sound reference" |
| Default resolves | `alarm_default` | Raw resource exists and opens | Test |
| Picker select | Tap a row, Back | Editor Sound row shows its name; Save stores its ref | No error expected |
| Missing choice | Stored system ref no longer opens | Row with the title, missing caption, preview disabled; note in sub-screen and editor | No error expected |
| Preview | Tap play on A, then on B | A stops, B plays; `previewingId` = B; completes → null | Open failure → null, logged |
| Leave / background | Back from Sound, or `ON_STOP` | Preview stops, volume restored | No error expected |
| Ringing | Preview requested during a ring | Ignored; the volume is not touched | Logged |
| Loud file | Compliant tone | Task passes, values printed | No error expected |
| Quiet file | −20 dBFS tone | Task fails naming the file and the rules | Gradle failure |
| No ffmpeg | `yawnandpawn.ffmpeg` invalid | Task fails with the install hint | Gradle failure |

</intent-contract>

## Code Map

- `androidApp/src/main/kotlin/com/yawnandpawn/app/android/wake/Playback.kt` -- `AlarmSound` (Default, SystemAlarm, File), `SoundResolver`, `DefaultOnlySoundResolver` (to replace), `PlaybackFactory`, `MediaPlayerPlaybackFactory.open` (sync `prepare()`, so switch it to async), `MediaPlayerPlayback`.
- `androidApp/.../wake/AndroidAlarmPlayer.kt` -- `resolve()` at line 171 (log text), `fallbackAfter` (add BuiltIn), `onPlaybackError` (reused for async prepare errors). Add `isRinging`.
- `androidApp/.../wake/AlarmVolume.kt` -- `setForRing` / `restore` / `saved`, reused by the preview.
- `androidApp/.../wake/WakeModule.kt` -- binds `SoundResolver`. `YawnAndPawnApp.kt` `appModule` (add the SoundLibrary and SoundPreview bindings).
- `androidApp/src/test/.../wake/AndroidAlarmPlayerTest.kt` and `WakeTestDoubles.kt` (`FakePlaybackFactory`) -- extend these. The MediaPlayer adapter tests need a looper idle after `prepareAsync`.
- `core/.../alarm/Alarm.kt:38` `DEFAULT_SOUND_REF`. Add a new package `core/.../sound/` (SoundRef, SoundCatalog, SoundLibrary and SoundPreview ports). The fakes go in `testing/.../SoundFakes.kt`.
- `composeApp/.../ui/sound/SoundList.kt` -- `SoundOption` (add `nameRes`), `SoundPickerUiState` (add `showFiles = true`), `SoundRow`.
- `composeApp/.../ui/editor/AlarmEditorContract.kt` -- move `soundName` / `soundMissing` / `sounds` from `FullEditorSections` into `EditorSound`. Add `EditorForm.soundRef` and an `EditorIntent.Backgrounded` intent.
- `composeApp/.../ui/editor/AlarmEditorViewModel.kt` -- the new ports, the sound section, preview control and `toDraft` soundRef. `AlarmEditorScreen.kt:286` `NameSoundCard`, `EditorSubScreens.kt:90` `SoundPane`, and the route (`LifecycleEventEffect(ON_STOP)`).
- `composeApp/.../ui/UiModule.kt` -- the ViewModel params. Call sites: `AlarmEditorViewModelTest.kt:76`, `AlarmScreensSemanticsTest.kt:246,287`.
- `androidApp/src/debug/.../preview/PreviewSamples.kt:113-190` and `PreviewEditorReducer.kt:139` -- move them to `EditorUiState.sound`, rendering the same.
- `androidApp/src/test/kotlin/com/yawnandpawn/app/ui/AlarmScreensScreenshotTest.kt`, `EditorSamples.kt` -- add the sound list, selected and missing screenshots in Light, Dark and 200%.
- `composeApp/src/commonMain/composeResources/values/strings.xml:140-147` -- the existing sound strings. Add `sound_name_*`. `editor_sound_default` ("Sunrise") stays for a null sound section.
- `build.gradle.kts` `qualityGate`, `build-logic/build.gradle.kts` (register the plugin and pass properties to the tests), `.github/workflows/ci.yml` (apt ffmpeg).
- `tools/sounds/generate_default_alarm.py` (BS.1770 helper to share), `docs/sounds/LICENSES.md`.

## Tasks & Acceptance

**Execution:**
- `core/.../sound/SoundRef.kt`, `SoundCatalog.kt`, `SoundPorts.kt` (+ tests) -- the refs, the catalog and the ports.
- `testing/.../SoundFakes.kt` (+ test) -- `FakeSoundLibrary`, `FakeSoundPreview`.
- `tools/sounds/loudness.py`, `generate_alarm_sounds.py`, `measure_loudness.py` and the refactored `generate_default_alarm.py` -- generate 11 more alarm sounds, and measure.
- `androidApp/src/main/res/raw/alarm_*.ogg`, `docs/sounds/LICENSES.md` -- the bundled files and their licences.
- `build-logic/.../SoundLoudness*.kt` (+ tests), root build and CI -- the gate.
- `androidApp/.../sound/` (`BuiltInSoundFiles`, `LibrarySoundResolver`, `AndroidSoundLibrary`, `AndroidSoundPreview`), `Playback.kt` (async prepare), the player and Koin -- the Android side.
- composeApp contract, ViewModel, screens and strings; the preview samples and reducer -- the UI.
- Tests: the player (missing URI, mid-ring error, async prepare error, built-in and system resolution, default resolves, catalog ↔ raw files), the preview, the library adapter, the ViewModel (select, save, missing, preview lifecycle) and the screenshots.

**Acceptance Criteria:**
- Given the Sound sub-screen in production, when it opens, then it shows the volume card, then "Built-in" with every catalog sound and "System" with the phone's alarm ringtones, and no "Your files" section.
- Given a chosen built-in, when saved and rung, then the player opens that raw resource on `USAGE_ALARM`.
- Given `./gradlew qualityGate`, when run (ffmpeg in CI, the python measurer on this PC), then it is BUILD SUCCESSFUL and `git status --porcelain androidApp/src/test/screenshots/preview` is empty.

## Design Notes

- **Missing names:** a system ref carries its title, so a vanished ringtone still shows "Argon" with the missing caption. A built-in id that no longer exists (not reachable today) shows the default sound's name in the editor and gets no picker row. The note still shows.
- **Preview vs ring volume:** the preview saves the user volume through the same `AlarmVolume` as the ring. If a ring starts during a preview, the ring keeps the saved user value, and the preview's stop skips the restore because the ring is active. The ring's end restores the user value. A crash mid-preview is healed by the app-start `restoreVolumeIfIdle`.

## Verification

**Commands:**
- `./gradlew qualityGate` (with `yawnandpawn.loudnessMeasurer=python` and `yawnandpawn.uv` in `~/.gradle/gradle.properties` on this PC) -- expected: BUILD SUCCESSFUL.
- `git status --porcelain androidApp/src/test/screenshots/preview` -- expected: empty.

## pps-design Done checklist

- [x] Only tokens from `DESIGN.md` are used (no raw hex, no new radii, no new font sizes). The existing `SoundList` / `SoundPane` composables are reused.
- [x] Light and Dark are checked with screenshots (the list, selected and missing-file row). Sunrise doesn't apply (not a wake screen).
- [x] Every colour pair used is in the `DESIGN.md` contrast table (text, text-secondary, accent-text on glass, as the preview).
- [x] Touch targets are ≥ 48 dp: 56 dp rows and the 48 dp preview button. No wake actions on this surface.
- [x] It works at 200% font scale (screenshots) and with TalkBack: radio role rows, "Play preview" / "Stop preview". No outcome glyphs on this surface.
- [x] The reduced-motion path works: there are no new animations, and the sub-screen slide is existing.
- [x] Copy matches `EXPERIENCE.md > Voice and Tone`. The sound names are drafts in copy-to-approve. All strings are in resources.
- [x] Every state row for this surface is handled: "Custom sound missing" → "File missing. Default sound will play.".
- [x] "I'm up" / snooze: not applicable (not a wake screen).
- [x] Previews and screenshot tests are updated (Roborazzi: the list, selected and missing rows in Light, Dark and 200%).

## Spec Change Log

## Review Triage Log

### Review Triage Log (fast mode)

2026-10-01: two review layers ran (fast mode, high and medium findings patched in `fix(1.17): review fixes`).

**Patched:**
- **Prepare watchdog:** a sound that never reports prepared (a stalled content provider) now counts as failed after 5 s and falls back, so the ring is never silent. Healthy play is timed from prepared, not from open. Callbacks are matched to their own open.
- **Ring vs preview:** a ring start stops a playing preview without restoring the user volume. The preview is thread-safe and reads the ring state without the player's lock. A production Koin wiring test covers it.
- **Editor preview stops:** opening another pane from Sound stops the preview. Backgrounding stops it even while a save runs. An `AlarmEditorRoute` lifecycle test covers `ON_STOP`.
- **Async adapter:** a test for start, then pause, then prepared.
- **checkSoundLoudness timeout:** the measurer's output is read on its own thread, so the 5-minute timeout fires and fails with a clear message.
- **Screenshot tests:** the discarded semantics checks are now real assertions. A main-editor case checks that "Chimes" is shown for `builtin:chimes`.
- **Picker:**
  - A selection re-checks availability.
  - A chosen ringtone that isn't in the `TYPE_ALARM` list keeps its own row.
  - Ringtones with a blank title are left out.

**Rejected:**
- The generator-refactor claim. `generate_default_alarm.py` keeps its own BS.1770 copy on purpose: it is the Story 1.14 generator, its file is not regenerated, and the new library generator shares `tools/sounds/loudness.py`.

After the rebase onto main (1.14 squashed, plus Story 1.15), `./gradlew qualityGate` passed with no fix-up needed. Preview baselines are unchanged.

## Auto Run Result

**Summary:**
- **Library:** 11 new generated CC0 alarm sounds, so 12 with the default "Sunrise". They live in `androidApp/src/main/res/raw/alarm_*.ogg`, come from `tools/sounds/generate_alarm_sounds.py` and the shared BS.1770 module `tools/sounds/loudness.py`, and each is listed in `docs/sounds/LICENSES.md`.
- **Core:** the core `sound` package has `SoundRef` (`builtin:<id>`, `system:<title>|<uri>`), `SoundCatalog` (one default), and the `SoundLibrary` and `SoundPreview` ports. Their fakes are in `:testing`.
- **Player:**
  - `LibrarySoundResolver` resolves built-ins to their raw resources and ringtones to their URIs.
  - `MediaPlayerPlaybackFactory` now uses `prepareAsync`, which closes the Story 1.14 deferred item. A start or gain asked for before prepare is applied once prepared, and a prepare error falls back like a playback error.
  - Unknown refs, missing URIs and errors (at prepare or mid-ring) all play the default in the same ring. The logs carry no URIs.
- **Picker:**
  - The editor's Sound sub-screen shows the production list: "Built-in" and the phone's alarm ringtones (`RingtoneManager.TYPE_ALARM`) under "System", with "Your files" hidden.
  - The choice is saved with the alarm.
  - A missing choice shows "File missing. Default sound will play." in the sub-screen and the editor.
  - Preview plays one sound at a time on `USAGE_ALARM` at the alarm volume. It stops on Back, on `ON_STOP` and when the editor closes, and it keeps out of a ring.
- **Gate:** `checkSoundLoudness` (build-logic plugin `yawnandpawn.sound-loudness`) is a `qualityGate` dependency. It uses ffmpeg ebur128 by default and in CI (`ci.yml` installs ffmpeg). It falls back to a python measurer where ffmpeg can't run. It lists `wheel_tick.wav` as exempt.

**Environment finding:** this company PC's application-control policy blocks unsigned executables. A portable ffmpeg build under `%LOCALAPPDATA%\Programs\ffmpeg` fails with "Access is denied", even after `Unblock-File`. No bypass was attempted. Instead, `~/.gradle/gradle.properties` (user scope) sets `yawnandpawn.loudnessMeasurer=python` and `yawnandpawn.uv`. The python measurer gives the same values as recorded for the default (−0.5 dBFS, −8.1 LUFS). ffmpeg itself has not been run against these files on this PC: the first CI run is the first real ffmpeg measurement.

**Verification:**
- `./gradlew qualityGate --continue`: BUILD SUCCESSFUL.
- `git status --porcelain androidApp/src/test/screenshots/preview`: empty. No existing baseline changed.
- New baselines: `sound_list_*`, `sound_selected_*`, `sound_missing_*` (Light, Dark, 200%) and `alarm_editor_sound_missing_*`.
- New and updated suites:
  - `SoundLoudnessTest` (6) and `SoundLoudnessPluginTest` (3, TestKit);
  - `SoundRefTest`, `SoundCatalogTest` and `SoundFakesTest`;
  - `AlarmEditorSoundTest` (10);
  - `SoundLibraryTest` (5) and `AndroidSoundPreviewTest` (7);
  - `AndroidAlarmPlayerTest` (23);
  - `SoundPickerScreenshotTest` (11, which also checks the radio role, "Play preview" / "Stop preview" and the disabled preview on the missing row).

**Residual risks:**
- The ffmpeg path of the gate runs only in CI (see the environment finding).
- No device check has been done for `prepareAsync`, ringtone listing or the preview volume handling. The Story 1.21 checklist on the Oppo A96 covers it.
- The sound names are drafts (`docs/design-preview/copy-to-approve.md`).
- The `ON_STOP` hook in `AlarmEditorRoute` is covered only through the ViewModel intent, not by an activity-lifecycle test.
