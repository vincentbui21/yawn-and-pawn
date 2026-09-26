## Epic 7: Make it yours

Personal touches and flexibility: the user can record motivation messages and hear one after getting up (or mixed into the alarm), use their own audio file as an alarm sound, dismiss the alarm by photographing a spot far from bed (House Hunt, after Spike S3 decides it is accurate enough), print a QR code for the QR/Barcode check, and skip the next alarm from a pre-alarm notification. All media (recordings, custom sounds, House Hunt photos and embeddings) lives in credential-protected storage, is excluded from backup, and is replaced by the default built-in sound or Math before the first unlock after a reboot (AD-6, FR-ALM-11, Direct Boot substitution). Every chosen sound or recording that cannot play falls back to the default built-in sound during a ring; the alarm is never silent (FR-SND-5, NFR-2).

This epic is mostly Should and Could and is the first bucket to cut if time runs short. PRD cut order within it: all [Could] first (Stories 7.5, 7.10, 7.11), then House Hunt (Stories 7.6 to 7.9), then custom audio file (Story 7.4), then recordings (Stories 7.1 to 7.3). Each story works without the later ones.

Every UI story in this epic carries the two standing acceptance criteria from Epic 1, repeated in the story so the build loop can check them: (1) the `pps-design` Done checklist is copied into the story file with every item ticked; (2) all new user-facing strings live in Compose Multiplatform resources, match `EXPERIENCE.md > Voice and Tone > Key strings` verbatim where a key string exists, and pass `CopyRulesTest` (FR-MSG-4). A string that EXPERIENCE.md does not define is marked `[ASSUMPTION: add to EXPERIENCE.md Key strings]` and listed for the owner.

### Story 7.1: Record and store motivation messages

As a user,
I want the app to record a short message in my own voice and keep it safely on my phone,
So that my evening self can talk to my morning self.
**Refs:** FR-SND-3, NFR-4, NFR-14, AD-1, AD-6, AD-12, AD-13 · **Priority:** Should · **Verify:** auto

**Acceptance Criteria:**

**Given** `:core`
**When** the recording domain is added
**Then** `Recording` holds `id` (UUID v4), `name`, `durationMs`, `fileName` and `createdAt`, and a `RecordingRepository` port (`observeAll(): Flow`, `get`, `save`, `delete`) and an `AudioRecorder` port (`start(): Outcome<RecordingSession, DomainError>`, `stop()`, `cancel()`, `level(): Flow<Float>`, `elapsed(): Flow<Duration>`) return `Outcome<T, DomainError>`, with `FakeRecordingRepository` and `FakeAudioRecorder` in `:testing`
**And** use cases `SaveRecording` (auto-name "Message {n}" [ASSUMPTION: add to EXPERIENCE.md Key strings], n = highest existing number + 1), `DeleteRecording` and `ReRecord` (replaces the file of an existing recording, keeps its id so alarms that use it keep working) validate input and never throw

**Given** `:data`
**When** the table is added
**Then** `app.db` migrates to the next version adding `recording` (`id` primary key, `name`, `duration_ms`, `file_name`, `created_at`) with exported schema and a migration test that preserves existing alarms and history
**And** the audio files live only in the credential-protected `filesDir/recordings/{id}.m4a` (never the device-protected context), written to a temporary file first and renamed on success so a crash never leaves a half-written file in place

**Given** `AndroidAudioRecorder` (`MediaRecorder`, AAC in MPEG-4, mono, 44.1 kHz, 64 kbit/s)
**When** a recording runs
**Then** it stops automatically at exactly 60 s (FR-SND-3), stops and keeps what was captured when the app goes to the background, audio focus is lost to a call, or the screen turns off, and discards captures shorter than 1 s with `DomainError.RecordingTooShort`
**And** a `MediaRecorder` error, missing microphone or storage-full condition maps to a `DomainError` and deletes the temporary file (Robolectric tests with a shadowed recorder)
**And** recordings are never logged, uploaded or sent to Crashlytics; only a duration and an error code may be logged

**Given** Android Auto Backup (NFR-14)
**When** the backup rules are updated
**Then** `dataExtractionRules` and `fullBackupContent` explicitly exclude the credential-protected `recordings/` folder (as well as `sounds/` and `househunt/`, created in later stories) while the `recording` table in `app.db` is still backed up, and the Robolectric XML test asserts these entries
**And** a restored `recording` row whose file does not exist is reported by `RecordingRepository` as `missing = true` (test), so later stories can show it and fall back
**And** `RECORD_AUDIO` is already in the permission allowlist and no new permission is added
**And** `./gradlew qualityGate` passes

### Story 7.2: Recordings screen and choosing a message per alarm

As a user,
I want to record, play back, re-record and delete messages, and choose one (or a random one) for each alarm,
So that the right voice greets me each morning.
**Refs:** FR-SND-3, FR-ALM-2, FR-ONB-2, FR-MSG-4, NFR-9, AD-11, AD-16, UX-DR54, UX-DR55, UX-DR56, UX-DR60, UX-DR64, UX-DR66, UX-DR67, UX-DR80, UX-DR84, UX-DR95 · **Priority:** Should · **Verify:** auto

**Acceptance Criteria:**

**Given** the Alarm editor
**When** it renders
**Then** a "Motivation" section [ASSUMPTION: add to EXPERIENCE.md Key strings] appears after Sound with a row "Message" [ASSUMPTION: add to EXPERIENCE.md Key strings] whose value is "None", the recording's name, or "Random" [ASSUMPTION: add "None" and "Random" to EXPERIENCE.md Key strings], and tapping it opens the Recordings screen in selection mode
**And** the alarm stores `motivation` = `None` | `Recording(id)` | `Random` (default `None`) in the `alarm` table through a migration with test, saved only on "Save" like every editor field, and `ConfigResolver` copies it into `SessionConfig`

**Given** the Recordings screen (pushed, `top-app-bar` "Recordings" per the EXPERIENCE.md IA)
**When** there are no recordings
**Then** it shows "Record a message for your morning self." (EXPERIENCE.md Key strings) above the `recorder`

**Given** the `recorder` (72 dp round accent record button with on-accent mic icon, "0:12 / 1:00" in `display` with tabular figures, level meter in `text-secondary`)
**When** the user taps record for the first time
**Then** `RECORD_AUDIO` is requested from this screen only (never at app start or in onboarding), and recording starts only after it is granted
**And** tapping again stops; at 60 s it stops by itself; afterwards "Play", "Re-record", "Save" and "Delete" (EXPERIENCE.md Component Patterns) are offered; "Save" adds the recording to the list; a capture under 1 s shows the snackbar "Too short. Try again." [ASSUMPTION: add to EXPERIENCE.md Key strings]
**And** the record button's TalkBack label is "Start recording" / "Stop recording" [ASSUMPTION: add to EXPERIENCE.md Key strings] and elapsed time is announced every 10 s while recording

**Given** the microphone permission is denied (once or permanently)
**When** the screen renders
**Then** it shows "Microphone is off. Turn it on in Settings." with a `button-text` "Fix" (EXPERIENCE.md Key strings) that opens the app's system settings page, the state is re-checked on return, and alarms, playback and existing recordings keep working
**And** the Epic 5 reliability checklist shows the microphone row only after the user has tapped record at least once (FR-ONB-2 "only when recording"), with status from the permission

**Given** the recordings list
**When** it renders
**Then** each row (56 dp) shows the name, duration in tabular figures, a 48 dp play/stop preview button (media usage, stops on leaving the screen or when another preview starts) and, in selection mode, a radio; a "Random" row sits at the top when there are at least two recordings
**And** a restored recording whose file is missing shows "File missing. Default sound will play." (EXPERIENCE.md State Patterns) and cannot be previewed

**Given** a recording used by one or more alarms
**When** the user deletes it
**Then** `dialog-confirm` asks "Delete this message? Alarms using it will play no message." with "Delete" (in `error` colour) and "Keep it" as the default dismiss [ASSUMPTION: add to EXPERIENCE.md Key strings]; confirming deletes the file and row and sets those alarms' motivation to `None` (or leaves `Random`, which falls back to `None` when fewer than one recording remains)
**And** editing motivation is not a weakening change under the commitment lock (it applies immediately) and the screen is unreachable during an active session (session lock)

**Given** Roborazzi, semantic and ViewModel tests with the Story 7.1 fakes
**When** they run
**Then** screenshots cover empty, recording in progress, after stop, list with selection and Random, missing file and microphone denied, in Light and Dark and at 200% font scale, with every target ≥ 48 dp
**And** the `pps-design` Done checklist is ticked in the story file, all strings are resources, key strings match EXPERIENCE.md verbatim and `CopyRulesTest` passes (FR-MSG-4)
**And** `./gradlew qualityGate` passes

### Story 7.3: Play my message "After I'm up"

As a user,
I want my chosen message to play on the Success screen once I finish the check,
So that the first thing I hear after the alarm is my own encouragement.
**Refs:** FR-SND-4, FR-SND-5, FR-ALM-11, FR-MSG-4, NFR-2, NFR-9, AD-2, AD-5, AD-6, AD-16, UX-DR23, UX-DR64, UX-DR78, UX-DR95 · **Priority:** Should · **Verify:** auto, plus (human-verify) audio on device in Story 7.12

**Acceptance Criteria:**

**Given** an alarm with motivation `Recording(id)` or `Random` and playback mode "After I'm up" (EXPERIENCE.md IA; the default mode, and the only one until Story 7.5)
**When** `ConfigResolver` freezes `SessionConfig` at `AlarmFired`
**Then** `Random` is resolved once to a concrete recording id using the session seed, so a restored session plays the same message (test); test sessions resolve and play the message too, so the user can hear it before a real morning
**And** before the first unlock (Direct Boot) the resolved motivation is `None` because credential-protected storage is unavailable (Direct Boot substitution), and it stays `None` for this session even after unlock

**Given** the AD-2 `Completed` one-shot effect "play motivation"
**When** `EffectRunner` executes it with a recording
**Then** the Success screen shows a `motivation-player` (`surface-sunrise` card, `rounded.md`, 48 dp play/pause and replay, accent progress bar) that starts playing automatically through a `MotivationPlayer` port (fake in `:testing`) with `USAGE_ALARM` at the user's alarm-stream volume, once, with no loop
**And** play/pause/replay have TalkBack labels "Play message", "Pause message" and "Replay message" [ASSUMPTION: add to EXPERIENCE.md Key strings]
**And** "Done", Back-less exit via Home, or the activity stopping stops playback immediately; the zero-snooze celebration (Story 6.4) runs at the same time, unchanged

**Given** the recording file is missing, unreadable or errors during playback
**When** the effect runs
**Then** no player is shown or the player hides, nothing plays, the failure is logged without file paths, and the Success screen is otherwise unchanged (the alarm has already stopped, so the default-sound fallback does not apply after the check; it applies to "Mix into alarm" in Story 7.5)

**Given** a session that ended Missed or by a paid snooze
**When** it ends
**Then** no message plays (only `Completed` plays it)

**Given** Robolectric, Roborazzi and engine tests with fakes
**When** they run
**Then** they cover: recording plays on `Completed`; `Random` resolves to the same id after `ProcessRestored`; Direct Boot session plays nothing; missing file hides the player; Done stops playback; screenshots of Success with the player (zero-snooze and after-snooze) in Sunrise at 100% and 200% font scale
**And** the `pps-design` Done checklist is ticked in the story file, all strings are resources, key strings match EXPERIENCE.md verbatim and `CopyRulesTest` passes (FR-MSG-4)
**And** `./gradlew qualityGate` passes

### Story 7.4: Use my own audio file as an alarm sound

As a user,
I want to pick an audio file from my phone as my alarm sound,
So that I wake to something I chose, with the default sound as a safety net.
**Refs:** FR-SND-6, FR-SND-5, FR-SND-2, FR-ALM-11, FR-MSG-4, NFR-2, NFR-4, NFR-14, AD-5, AD-6, AD-12, UX-DR53, UX-DR64, UX-DR66, UX-DR67, UX-DR80 · **Priority:** Should · **Verify:** auto

**Acceptance Criteria:**

**Given** the Sound picker from Story 1.17
**When** it renders
**Then** after the built-in and system sections it shows a "Your files" section of imported sounds with source caption "Your file" and a `button-outlined` "Pick a file" [ASSUMPTION: add "Your files", "Your file" and "Pick a file" to EXPERIENCE.md Key strings]

**Given** the user taps "Pick a file"
**When** the system document picker (`ACTION_OPEN_DOCUMENT`, `audio/*`) returns a URI
**Then** the app takes the persistable read permission, validates that the file is decodable audio with a duration of at least 1 s and a size of at most 20 MB [ASSUMPTION: size limit to be confirmed by owner], and copies it into credential-protected `filesDir/sounds/{id}.{ext}` so the alarm never depends on the original file (AD-6: custom sounds live in credential-protected storage); the persisted permission is then released
**And** the import records `custom_sound` (`id`, `display_name`, `file_name`, `duration_ms`, `created_at`) in `app.db` via a migration with test, and the new sound is selected as `SoundRef.File(id)`
**And** a non-audio, corrupt or DRM file shows the snackbar "This file can't be played. Pick another." and an oversized file "This file is too big. Pick one under 20 MB." [ASSUMPTION: add both to EXPERIENCE.md Key strings]; nothing is copied or saved
**And** no storage or media permission is requested and the permission allowlist still passes (the picker needs none)

**Given** a `sound-row` for an imported file
**When** the user previews it
**Then** it plays with `USAGE_ALARM` at the alarm's volume and stops on leaving, exactly like built-in sounds (Story 1.17)

**Given** an alarm whose sound is `SoundRef.File(id)`
**When** it rings
**Then** `AlarmPlayer` loops the copied file; if the file is missing (for example after a backup restore, since `sounds/` is excluded from backup), unreadable, or errors at prepare or mid-ring, it switches to the default built-in sound within the same ring and logs the fallback without paths (FR-SND-5, NFR-2)
**And** before the first unlock the Direct Boot substitution plays the default built-in sound instead (credential storage unavailable), and the ring keeps the default sound after unlock
**And** the Sound picker and editor show "File missing. Default sound will play." (EXPERIENCE.md State Patterns) for a missing file

**Given** loudness
**When** a user file is imported
**Then** the FR-SND-1 loudness script does not apply (it checks bundled files only), and gradual volume and the set volume still apply to it

**Given** Robolectric and Roborazzi tests
**When** they run
**Then** they cover: successful import copies bytes and saves the row; non-audio rejected; oversized rejected; missing file at ring → default sound; `MediaPlayer` error mid-ring → default sound; Direct Boot → default sound; picker screenshots with the "Your files" section, a missing file row and an error snackbar, in Light and Dark and at 200% font scale
**And** the `pps-design` Done checklist is ticked in the story file, all strings are resources, key strings match EXPERIENCE.md verbatim and `CopyRulesTest` passes (FR-MSG-4)
**And** `./gradlew qualityGate` passes

### Story 7.5: "Mix into alarm" motivation playback

As a user,
I want my message to alternate with the alarm sound while it rings,
So that my own voice pushes me out of bed.
**Refs:** FR-SND-7, FR-SND-5, FR-ALM-6, FR-ALM-11, FR-MSG-4, NFR-2, AD-2, AD-5, UX-DR38, UX-DR64 · **Priority:** Could · **Verify:** auto, plus (human-verify) audio on device in Story 7.12

**Acceptance Criteria:**

**Given** the editor "Motivation" section with a message selected
**When** it renders
**Then** a `segmented-control` offers "After I'm up" / "Mix into alarm" (EXPERIENCE.md IA labels), default "After I'm up", stored with the alarm and frozen into `SessionConfig`

**Given** a pure `mixSchedule(elapsedInRing, soundBlock = 30 s, recordingDuration)` in core
**When** it is evaluated
**Then** it returns which source plays: the alarm sound for the first 30 s of each ring (so the volume ramp from FR-ALM-6 completes), then the recording once, then 30 s of alarm sound, then the recording, repeating until the ring ends (unit tests at 0 s, 29 s, 30 s, 30 s + duration, and after a grace window ends)

**Given** a ring with mode "Mix into alarm"
**When** `AlarmPlayer` runs it
**Then** it switches between the alarm sound and the recording per `mixSchedule`, both with `USAGE_ALARM` at the set volume, never leaving a silent gap longer than 250 ms between sources
**And** during a grace window everything is muted as usual; when grace ends, the schedule resumes with the alarm sound at full set volume (FR-SES-6)
**And** if the recording is missing, unreadable or errors, the player plays only the alarm sound for the rest of the session (never silent, FR-SND-5), and before the first unlock the Direct Boot substitution plays only the default sound
**And** "After I'm up" playback on Success does not also play when the mode is "Mix into alarm"

**Given** Robolectric tests with fake players and `FakeMonotonicClock`
**When** they run
**Then** they cover the alternation order, mute in grace, resume after grace, a recording error mid-ring switching to alarm sound only, and a Direct Boot session with no recording
**And** Roborazzi screenshots cover the editor with the mode control in Light and Dark and at 200% font scale
**And** the `pps-design` Done checklist is ticked in the story file, all strings are resources, key strings match EXPERIENCE.md verbatim and `CopyRulesTest` passes (FR-MSG-4)
**And** `./gradlew qualityGate` passes

### Story 7.6: Spike S3: House Hunt image matching

As the owner,
I want measured evidence of how well on-device image matching recognises the same spot and rejects others,
So that House Hunt ships only with a threshold that wakes people reliably, or is cut.
**Refs:** FR-PWK-6, FR-PWK-11, NFR-4, AD-9, AD-15; PRD §10 S3, §12 (House Hunt false rejects), Q10, Q11 · **Priority:** Should · **Verify:** human-verify

**Acceptance Criteria:**

**Given** MediaPipe tasks-vision 1.0.0 `ImageEmbedder` with a bundled model (MobileNet-V3 small and large embedder `.tflite` compared, loaded from assets, no network)
**When** the spike starts
**Then** the prototype and evaluation harness live only on branch `spike/s3-house-hunt` and are never merged to `main`
**And** the test photo set stays on the owner's machine and is never committed; only aggregate numbers go into the report

**Given** a photo set of at least 10 spots in at least 3 homes, each with 3 reference photos and test photos under 3 lighting conditions (daylight, lamp, dim morning), 3 angles (same, ±15°, ±30°) and with small changes (a towel moved, a person's hand in frame)
**And** impostor photos: other spots in the same room, other rooms, random internet-style photos, and a photo of the reference photo shown on a second phone and printed on paper
**When** the harness computes cosine similarity between each test photo and the best of the 3 references for each model
**Then** `docs/spikes/S3.md` records per model: the false-reject rate (genuine photos below threshold) and false-accept rate (impostors at or above threshold) across thresholds 0.50 to 0.95 in 0.05 steps, embedding time and model size on the budget device and a Pixel, and memory use
**And** it reports separately the false-accept rate for photo-of-a-photo attempts (a known limitation; PRD Q11)

**Given** the targets [ASSUMPTION: owner to confirm]: false-reject rate per attempt ≤ 10% (so 5 attempts before the fallback link almost always succeed), false-accept rate for other rooms and random photos ≤ 1%, match time ≤ 500 ms on the budget device
**When** the owner writes the Decision section
**Then** it states Go or No-go, the chosen model, one threshold per difficulty (Easy, Medium, Hard) as core config values, the number of reference photos (1 to 3 of one spot) and a count of 1 spot per check (resolving the House Hunt part of PRD Q10)
**And** on No-go, FR-PWK-6 is cut or downgraded through `bmad-correct-course`, Stories 7.7 to 7.9 are dropped, and House Hunt stays hidden from check pickers
**And** any MediaPipe usage logging or network behaviour found is recorded for the Data safety form (AD-15)

**Given** the spike checklist
**When** it is complete
**Then** the story file records pass/fail per question, device, Android version and date; automation never marks this story done
**And** `./gradlew qualityGate` passes on `main` with `docs/spikes/S3.md` committed

### Story 7.7: House Hunt check plugin and on-device matcher

As a user,
I want the app to decide on the phone whether my photo shows my registered spot,
So that House Hunt works offline and my photos never leave my phone.
**Refs:** FR-PWK-6, FR-PWK-1, FR-PWK-3, FR-PWK-11, NFR-4, NFR-11, NFR-12, AD-2, AD-6, AD-9, AD-12, AD-15 · **Priority:** Should · **Verify:** auto

**Acceptance Criteria:**

**Given** Spike S3 decided Go
**When** `CheckType.HouseHunt` is added to the sealed `CheckType` in core with the Epic 3 AD-9 contract
**Then** `generate(seed, difficulty, count)` returns a `Puzzle` naming the alarm's reference set and the threshold for the difficulty from core config (values from `docs/spikes/S3.md`), count fixed at 1, and `validate(puzzle, answer)` returns Valid when the answer's best similarity ≥ the threshold and Invalid otherwise (table tests at threshold − 0.01, exactly threshold, + 0.01)
**And** the thresholds and model name are constants in `core.checks.househunt` with a test that they match the S3 Decision values

**Given** the AD-9 sensor-check path
**When** the House Hunt check screen submits a capture
**Then** `CheckAnswerSubmitted(PhotoCapture(tempRef))` produces the effect `MatchImage(tempRef, referenceSetId)`; the `ImageMatcher` port returns `ImageMatchCompleted(similarity)` or `ImageMatchFailed(reason)` as events, and the engine validates the similarity through `validate`
**And** if the Epic 3 contract did not already define these two events, this story adds them and their AD-2 rows (Grace/Loud + `ImageMatchCompleted` → same state, valid/invalid/last-step effects as `CheckAnswerSubmitted`; Grace/Loud + `ImageMatchFailed` → same state, attempts++), updates the transition-coverage test, and records the table change in the Architecture Spine through `bmad-correct-course`
**And** each non-match counts as a failed attempt for the Epic 3 `FallbackPolicy` (link after 5 failed attempts); `ImageMatchFailed` because the model cannot load counts as "camera or check unavailable" and allows the fallback immediately (FR-PWK-11)

**Given** `:data` and `:androidApp`
**When** storage and the adapter are added
**Then** `app.db` migrates adding `reference_media` (`id`, `check_config_id`, `file_name`, `embedding_file_name`, `created_at`, `needs_retake`) linked to the alarm's House Hunt `CHECK_CONFIG` row, with exported schema and migration test
**And** photos (JPEG, longest side 1024 px) and their embeddings live only in credential-protected `filesDir/househunt/`, excluded from backup (rules from Story 7.1), and wake-time captures are written to the cache, deleted right after matching, and never logged, uploaded or sent to Crashlytics (test asserts the capture file is gone after `ImageMatchCompleted`)
**And** `MediaPipeImageMatcher` embeds with the S3 model bundled in assets, computes cosine similarity against stored reference embeddings (best of 1 to 3), runs off the main thread, and maps every MediaPipe exception to `ImageMatchFailed` (AD-12); `FakeImageMatcher` returns programmable similarities
**And** the MediaPipe coordinates are already in `config/dependency-allowlist.txt` (or added in this change after review), and a test asserts the model is loaded from assets with no network call

**Given** Direct Boot (before first unlock)
**When** `ConfigResolver` freezes a plan containing House Hunt
**Then** House Hunt is replaced by Math (Direct Boot substitution from Epic 2) because the photos are in credential-protected storage (test)

**Given** engine tests with `FakeImageMatcher`
**When** they run
**Then** they cover: match on first try completes the step; 5 non-matches unlock the fallback link; a matcher failure allows the fallback immediately; a purchase granted while matching discards the pending match result (it arrives in `Snoozed` and is ignored and logged)
**And** Kover shows `core.checks` ≥ 90% line coverage
**And** `./gradlew qualityGate` passes

### Story 7.8: House Hunt registration and re-take after restore

As a user,
I want to photograph one to three reference shots of a spot far from my bed and test a match before saving,
So that I know the check will accept me tomorrow morning.
**Refs:** FR-PWK-6, FR-PWK-2, FR-PWK-12, FR-ONB-2, NFR-4, NFR-14, FR-MSG-4, AD-6, AD-9, AD-16, UX-DR20, UX-DR21, UX-DR29, UX-DR33, UX-DR60, UX-DR64, UX-DR66, UX-DR67 · **Priority:** Should · **Verify:** auto, plus (human-verify) camera on device in Story 7.12

**Acceptance Criteria:**

**Given** the Epic 3 Check picker
**When** Spike S3 decided Go
**Then** a House Hunt `check-type-card` appears with the name "House Hunt", the line "Photograph a spot far from your bed." [ASSUMPTION: add to EXPERIENCE.md Key strings] and the camera warning "Needs the camera. If it can't be used, you'll get a fallback check." (EXPERIENCE.md Component Patterns)
**And** selecting it requests `CAMERA` only if not granted (the reliability checklist camera row from Epic 5 then appears), and its Check setup offers difficulty Easy / Medium / Hard

**Given** the House Hunt registration screen (from Check setup, `top-app-bar` "House Hunt photos" [ASSUMPTION: add to EXPERIENCE.md Key strings])
**When** it opens
**Then** it shows the instruction "Take 1 to 3 photos of one spot far from your bed." [ASSUMPTION: add to EXPERIENCE.md Key strings], the `viewfinder` (starts on open), the 72 dp `shutter` with TalkBack label "Take photo" [ASSUMPTION: add to EXPERIENCE.md Key strings], and thumbnails of taken photos (`rounded.sm`) each with a 48 dp "Remove" action [ASSUMPTION: add to EXPERIENCE.md Key strings]
**And** after 3 photos the shutter is disabled; each photo is saved and embedded through the Story 7.7 adapter; an embedding failure shows "Couldn't use that photo. Try again." [ASSUMPTION: add to EXPERIENCE.md Key strings] and keeps the photo out of the set

**Given** at least one reference photo
**When** the user taps "Test match" [ASSUMPTION: add to EXPERIENCE.md Key strings] and takes a new photo
**Then** it is matched with the Medium threshold (or the chosen difficulty) and shows "Matched" or "Doesn't match yet. Try the same angle." (EXPERIENCE.md Component Patterns); the test photo is deleted after matching (FR-PWK-12)

**Given** no reference photo
**When** the user tries to save the check
**Then** Save is blocked with "Take at least one photo." [ASSUMPTION: add to EXPERIENCE.md Key strings]

**Given** camera permission is denied or the camera fails to start
**When** the registration screen opens
**Then** it shows "Camera isn't available." with a "Fix" `button-text` to the app's settings [ASSUMPTION: add to EXPERIENCE.md Key strings; the wake-screen copy mentions a fallback check, which does not apply at setup], and House Hunt cannot be saved until photos exist

**Given** a backup restore on a new phone (the `reference_media` rows come back, the photos do not, NFR-14)
**When** the app starts or `rescheduleAll()` runs after the restore
**Then** a `ReferenceMediaIntegrity` check sets `needs_retake` on every row whose photo or embedding file is missing (test)
**And** Home shows the info variant of `banner-warning` "House Hunt photos weren't restored. Retake them." with "Retake" [ASSUMPTION: add both to EXPERIENCE.md Key strings], dismissible, which opens the registration screen for the first affected alarm and clears once every House Hunt check has photos
**And** until photos are retaken, `ConfigResolver` replaces House Hunt with Math at session start and the wake screen shows `note-inline` "Your House Hunt photos are missing, so today's check is Math." [ASSUMPTION: add to EXPERIENCE.md Key strings]

**Given** commitment lock (AD-16)
**When** the user removes House Hunt or lowers its difficulty within 8 h of the alarm
**Then** it is stored as a weakening `PendingChange` per Epic 4; adding photos or raising difficulty applies immediately; the screen is unreachable during a session

**Given** Roborazzi, semantic and ViewModel tests with `FakeImageMatcher` and a fake camera
**When** they run
**Then** screenshots cover the picker card, registration with 0, 1 and 3 photos, a test match success and failure, camera unavailable, and the Home restore banner, in Light and Dark and at 200% font scale, with every target ≥ 48 dp
**And** the `pps-design` Done checklist is ticked in the story file, all strings are resources, key strings match EXPERIENCE.md verbatim and `CopyRulesTest` passes (FR-MSG-4)
**And** `./gradlew qualityGate` passes

### Story 7.9: House Hunt check on the wake screen

As a user,
I want to dismiss my alarm by walking to my spot and taking its photo,
So that I'm out of bed before the alarm stops.
**Refs:** FR-PWK-6, FR-PWK-9, FR-PWK-10, FR-PWK-11, FR-MSG-4, NFR-2, NFR-4, NFR-9, AD-2, AD-5, AD-9, UX-DR16, UX-DR20, UX-DR21, UX-DR22, UX-DR64, UX-DR66, UX-DR67, UX-DR78, UX-DR84 · **Priority:** Should · **Verify:** auto, plus (human-verify) in Story 7.12

**Acceptance Criteria:**

**Given** a session whose current check step is House Hunt
**When** the check screen renders (Sunrise tokens)
**Then** it shows the `countdown-ring` while grace runs, the `viewfinder` with the 72 dp ghost thumbnail of the first reference photo top-left (`rounded.sm`), a 48 dp torch toggle, the 72 dp `shutter` centred in the thumb zone, and the check footer with `button-snooze` (Epic 4 rules) at the bottom
**And** the camera starts on screen open, and the screen never shows a loading state before the first frame (the viewfinder area shows its frame colour until the preview arrives)

**Given** the user taps the shutter
**When** the capture is matched
**Then** input is ignored until the result arrives, the shutter shows a progress state announced as "Checking" [ASSUMPTION: add to EXPERIENCE.md Key strings], and the result reads "Matched" (step completes) or "Doesn't match yet. Try the same angle." (EXPERIENCE.md Component Patterns) with an error haptic, counting a failed attempt
**And** a match result that arrives after the step already ended (snooze granted, grace expired into Loud, or a restore) is handled by the engine rules, never by the UI

**Given** 5 failed matches in this session, or the camera permission is denied, the camera fails (CameraX error or no frame within 5 s) or the matcher cannot load
**When** the condition occurs
**Then** the `fallback-link` "Can't do this check?" appears (after 5 failures), or immediately with "Camera isn't available. Pick a fallback check." (EXPERIENCE.md Key strings), opening the Epic 3 Fallback check picker; the alarm keeps ringing with no new grace window, and the fallback is offered once per session (FR-PWK-11)

**Given** grace ends while the user is framing the photo
**When** `GraceElapsed` fires
**Then** the alarm returns at full set volume, "Time's up. Alarm's back on until you finish." shows, and the viewfinder keeps running with progress kept (FR-PWK-9)

**Given** a snooze is granted during the check
**When** `PurchaseGranted` arrives
**Then** the camera stops, pending captures are deleted, and the re-ring starts a fresh House Hunt step (FR-PWK-10)

**Given** TalkBack
**When** the House Hunt screen is focused
**Then** initial focus is the instruction, the shutter reads "Take photo", results are announced politely, and the fallback link is reachable in one swipe from the shutter

**Given** Robolectric, Roborazzi and instrumented tests with a fake camera provider and `FakeImageMatcher`
**When** they run
**Then** they cover grace running, grace expired, matching, matched, not matched, fallback after 5, camera unavailable immediately, and snooze granted mid-check, with Sunrise screenshots at 100% and 200% font scale showing the shutter and snooze in the thumb zone
**And** an instrumented Gradle Managed Device test with the emulator camera completes a House Hunt step using `FakeImageMatcher` returning a match
**And** the `pps-design` Done checklist is ticked in the story file, all strings are resources, key strings match EXPERIENCE.md verbatim and `CopyRulesTest` passes (FR-MSG-4)
**And** `./gradlew qualityGate` passes

### Story 7.10: Printable QR code for the QR/Barcode check

As a user without a handy barcode,
I want the app to make a QR code I can print and stick far from my bed,
So that I can use the QR check without hunting for a product.
**Refs:** FR-PWK-13, FR-PWK-7, FR-MSG-4, NFR-4, AD-9, AD-15, UX-DR60, UX-DR64, UX-DR66, UX-DR67 · **Priority:** Could · **Verify:** auto, plus (human-verify) print and scan in Story 7.12

**Acceptance Criteria:**

**Given** the Epic 3 QR registration screen
**When** it renders
**Then** besides scanning, it offers a `button-outlined` "Make a printable QR" [ASSUMPTION: add to EXPERIENCE.md Key strings]

**Given** the user taps it
**When** the code is generated
**Then** core creates a payload `pps:{random UUID v4}` (no personal data, no alarm details), it is registered as that alarm's QR/Barcode code exactly as a scanned code would be, and a QR image (error correction M, at least 4-module quiet zone) is rendered with ZXing core [ASSUMPTION: add `com.google.zxing:core` 3.5.3 to the Architecture Stack and `config/dependency-allowlist.txt` after owner review; it has no network access]
**And** a preview shows the code with the hint "Stick it somewhere far from your bed." [ASSUMPTION: add to EXPERIENCE.md Key strings]

**Given** the preview
**When** the user taps "Print or save as PDF" [ASSUMPTION: add to EXPERIENCE.md Key strings]
**Then** an A4 / Letter PDF built with `PdfDocument` (QR 6 cm square, centred, with the line "Scan this to stop your alarm." [ASSUMPTION: add to EXPERIENCE.md Key strings]) is sent to Android `PrintManager`, where the user can print or save as PDF; no file is kept by the app afterwards

**Given** a generated code
**When** it is scanned by the Epic 3 QR check (ML Kit) on screen and on a 6 cm print
**Then** it validates as the registered code (instrumented test renders the bitmap and decodes it with ML Kit; unit test round-trips the payload with ZXing)
**And** generating a new code replaces the old one after a `dialog-confirm` "Replace your QR code? The old one stops working." / "Replace" / "Keep it" [ASSUMPTION: add to EXPERIENCE.md Key strings]; replacing a code is not a weakening change

**Given** Roborazzi and semantic tests
**When** they run
**Then** screenshots cover the QR registration with the new button, the preview and the replace dialog in Light and Dark and at 200% font scale, and the QR image has a content description "QR code for your alarm" [ASSUMPTION: add to EXPERIENCE.md Key strings]
**And** the `pps-design` Done checklist is ticked in the story file, all strings are resources, key strings match EXPERIENCE.md verbatim and `CopyRulesTest` passes (FR-MSG-4)
**And** `./gradlew qualityGate` passes

### Story 7.11: Skip the next alarm from a pre-alarm notification

As a user whose plans changed,
I want an optional notification before my alarm that lets me skip that one morning,
So that I don't have to turn the alarm off and remember to turn it back on.
**Refs:** FR-ALM-10, FR-PRG-1, FR-SES-3, FR-MSG-4, NFR-8, AD-3, AD-4, AD-16, AD-17, AD-18, UX-DR31, UX-DR55, UX-DR84 · **Priority:** Could · **Verify:** auto, plus (human-verify) in Story 7.12

**Acceptance Criteria:**

**Given** the Alarm editor
**When** it renders
**Then** it has a `switch` "Offer to skip 2 h before" [ASSUMPTION: add to EXPERIENCE.md Key strings], default off, stored per alarm (migration with test)
**And** turning it on is a weakening change under the commitment lock: within 8 h of that alarm it is saved as a `PendingChange` effective after the next occurrence, with the Epic 4 note "Saved. Takes effect after tomorrow's {time} alarm." (EXPERIENCE.md Key strings); turning it off applies immediately

**Given** an enabled alarm with the switch on (effective)
**When** its next occurrence is computed by `rescheduleAll()` or a save
**Then** a `BackgroundWork` one-time request `pre-alarm-{alarmId}` is enqueued for occurrence − 2 h (REPLACE); if that time has already passed but the occurrence has not, the notification is posted at once
**And** the notification (channel "Upcoming alarms" [ASSUMPTION: add to EXPERIENCE.md Key strings], default importance, monochrome icon) has title "{time} alarm" and text "Rings in {hours} h {minutes} min" (EXPERIENCE.md Key strings), with one action "Skip this one" [ASSUMPTION: add to EXPERIENCE.md Key strings]
**And** it is cancelled when the occurrence starts, the alarm is edited, disabled or deleted, or the switch is turned off; a late worker run after the occurrence posts nothing

**Given** the user taps "Skip this one"
**When** no session is active
**Then** the app opens a `dialog-confirm` "Skip your {time} alarm? This is logged." with "Skip" and "Keep it" as the default dismiss [ASSUMPTION: add to EXPERIENCE.md Key strings] (skipping is allowed inside the lock window like disabling, confirmed and logged, PRD §6.2)
**And** confirming stores the skipped local date on the alarm, cancels that occurrence's system alarm, schedules the following occurrence (a one-time alarm is disabled instead), and `SessionRecorder` writes one history row with a new session id, `scheduled_at` = the skipped occurrence, `first_ring_at` null and outcome Skipped (migration making `first_ring_at` nullable if Story 1.13 did not, with test); the snackbar reads "Skipped. No charge." [ASSUMPTION: add to EXPERIENCE.md Key strings]
**And** skipping is free and the Skipped row never counts toward rates or streaks (Story 6.1)

**Given** a skipped occurrence that has not happened yet
**When** Home renders the alarm
**Then** its `card-alarm` caption reads "Next ring skipped" [ASSUMPTION: add to EXPERIENCE.md Key strings], the countdown uses the following occurrence, and the card menu offers "Don't skip" [ASSUMPTION: add to EXPERIENCE.md Key strings], which re-arms the occurrence and asks `SessionRecorder` to discard that Skipped row (strengthening, always allowed)

**Given** a session is active when the action is tapped
**When** the app opens
**Then** only `panel-session-in-progress` shows (FR-SES-3) and nothing is skipped; the notification stays until the occurrence

**Given** edge cases
**When** tests run with `FakeClock`, `FakeTimeZoneProvider`, `FakeAlarmScheduler` and `FakeBackgroundWork`
**Then** they cover: notification time at occurrence − 2 h; enqueue when the window already started; a time-zone change after skipping still skips the occurrence on the same local date; a DST day; skipping a one-time alarm disables it; the same occurrence cannot be skipped twice (idempotent); "Don't skip" restores the system alarm and removes the row; skip during a session is ignored
**And** Roborazzi screenshots cover the editor switch, the confirm dialog and a card with "Next ring skipped" in Light and Dark and at 200% font scale
**And** the `pps-design` Done checklist is ticked in the story file, all strings are resources, key strings match EXPERIENCE.md verbatim and `CopyRulesTest` passes (FR-MSG-4)
**And** `./gradlew qualityGate` passes

### Story 7.12: Epic 7 device verification checklist

As the owner,
I want to confirm on real phones the recordings, custom sounds, House Hunt, printed QR and skip features,
So that the personal touches work and never make an alarm silent.
**Refs:** FR-SND-3, FR-SND-4, FR-SND-5, FR-SND-6, FR-SND-7, FR-PWK-6, FR-PWK-11, FR-PWK-13, FR-ALM-10, FR-ALM-11, FR-MSG-4, NFR-2, NFR-4, NFR-9, NFR-14 · **Priority:** Must · **Verify:** human-verify

**Acceptance Criteria:**

**Given** the latest `main` debug build installed on each device of the matrix (Pixel, Samsung, Xiaomi, budget device); items for stories that were cut are marked "cut" instead of pass/fail
**When** the owner runs the checklist
**Then** for each item the story file records pass/fail, device, Android version and date:
1. The microphone permission is asked only on the first record tap; a 10 s message records, plays back, re-records and saves; recording stops by itself at 1:00; denying shows "Microphone is off. Turn it on in Settings." and "Fix" opens the right page.
2. An alarm with "After I'm up" plays the message on Success after the check, audible with media volume at 0, and "Done" stops it; "Random" picks among saved messages.
3. "Mix into alarm" alternates 30 s of alarm sound with the message, is muted during grace, and returns at full volume after grace.
4. An imported MP3 from Downloads rings as the alarm; after deleting the original from Downloads it still rings; a corrupted file is rejected at import.
5. After an overnight reboot before first unlock, an alarm set to a custom file and a message plays the default sound, shows the Direct Boot notice, and plays no message.
6. After a backup restore to a second phone (or `adb shell bmgr restore`), the custom sound and messages show "File missing. Default sound will play.", the alarm rings with the default sound, and House Hunt shows the retake banner and uses Math until photos are retaken.
7. House Hunt registration takes 3 photos of a bathroom sink; "Test match" says "Matched" from the same spot in lamp light and "Doesn't match yet. Try the same angle." from the bedroom.
8. A real House Hunt morning completes by photographing the spot within 3 tries on each device; covering the camera for 5 attempts shows "Can't do this check?", and revoking camera permission shows the fallback immediately.
9. Photographing the reference photo shown on another phone: result recorded (known limitation, PRD Q11).
10. A printed QR from "Make a printable QR" scans on the QR check from 30 cm in normal room light.
11. With "Offer to skip 2 h before" on, the notification arrives within 15 minutes of occurrence − 2 h (Doze allowed), "Skip this one" → "Skip" prevents the ring, Day detail and the calendar show Skipped, and streak and rates are unchanged; "Don't skip" restores it.
12. No recording, photo or file path appears in Crashlytics logs from these tests.
13. At 200% font size and with TalkBack on, the recorder, Recordings list, House Hunt registration and check screens are usable.
14. All copy seen matches EXPERIENCE.md and the owner-approved assumption strings (FR-MSG-4).

**Given** any failed item
**When** the owner records it
**Then** each failure becomes a new bug story referencing this item, and this story stays open until all items pass or are explicitly waived by the owner in the story file
**And** automation never marks this story done
