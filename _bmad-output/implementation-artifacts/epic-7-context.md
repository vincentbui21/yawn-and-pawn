# Epic 7 Context: Make it yours

<!-- Generated from planning artifacts. Regenerate with compile-epic-context if planning docs change. -->

## Goal

Personal touches that never put the alarm's reliability at risk. The user records short motivation messages in their own voice and hears one on the Success screen after the check (or mixed into the ring), picks their own audio file as an alarm sound, prints an app-made QR code for the QR/Barcode check, and can skip one morning from an optional pre-alarm notification. Every piece of media lives on the phone only, is left out of backup, is replaced by the default built-in sound before the first unlock after a reboot, and falls back to the default sound whenever it can't play: the alarm is never silent. The epic is mostly Should/Could and is the first to cut if time runs short (cut order: the Could stories 7.5, 7.10, 7.11 first, then 7.4, then 7.1 to 7.3). Each story must work without the later ones.

## Stories

- Story 7.1: Record and store motivation messages
- Story 7.2: Recordings screen and choosing a message per alarm
- Story 7.3: Play my message "After I'm up"
- Story 7.4: Use my own audio file as an alarm sound
- Story 7.5: "Mix into alarm" motivation playback
- Story 7.6: Spike S3: House Hunt image matching (done: write-up only)
- ~~Story 7.7 to 7.9: House Hunt check, registration, wake screen~~: moved to v2 by the owner on 2026-10-27; build nothing for House Hunt in this epic.
- Story 7.10: Printable QR code for the QR/Barcode check
- Story 7.11: Skip the next alarm from a pre-alarm notification
- Story 7.12: Epic 7 device verification checklist (owner, human-verify; House Hunt items are marked "cut")

## Requirements & Constraints

- **Never silent:** a missing, unreadable or failing custom sound switches to the default built-in sound within the same ring, and the failure is logged without file paths. In "Mix into alarm", a failing recording leaves the alarm sound playing alone for the rest of the session.
- **Before the first unlock:** credential-protected storage is not available, so custom sounds become the default sound and the motivation resolves to None. Both choices stay in place for that session, even after the phone is unlocked.
- **Privacy:** recordings and imported files never leave the phone. They are never logged, uploaded or sent to Crashlytics; only durations and error codes may be logged. The printable QR payload is `pps:{UUID v4}`, with no personal data and no alarm details.
- **Backup:** the `recording` and `custom_sound` rows are backed up with `app.db`, but the media files are not. A restored row whose file is missing must report `missing` and show "File missing. Default sound will play.". It can't be previewed and falls back at ring time.
- **Recording limits:** up to 60 s (stops by itself), captures under 1 s are rejected, AAC/M4A mono. Recording stops and keeps the take when the app goes to the background, the screen turns off or a call takes audio focus.
- **Imported sound limits:** must be decodable audio of at least 1 s and at most 20 MB. It is picked with the system document picker, with no storage permission.
- **Permissions:** no new permissions. `RECORD_AUDIO` is already allowlisted; ask for it only on the first record tap, never at app start or in onboarding. The reliability checklist shows the Microphone row only after the user has tapped record once.
- **Commitment lock:** changing the motivation and replacing a QR code are not weakening changes. Turning "Offer to skip 2 h before" on is a weakening change (stored as a `PendingChange` inside the 8 h window); turning it off applies at once. Skipping a morning is free and logged, and a Skipped session never counts toward streaks or rates.
- **Standing UI acceptance criteria:** the `pps-design` Done checklist is copied into the story with every item ticked. Every string is a resource, matches the EXPERIENCE.md key strings word for word and passes `CopyRulesTest`. Roborazzi screenshots cover Light, Dark and 200% font scale, every touch target is at least 48 dp, and `./gradlew qualityGate` passes.

## Technical Decisions

- **Storage:** media lives only in credential-protected `filesDir/recordings/{id}.m4a` and `filesDir/sounds/{id}.{ext}`, never in the device-protected context. Write to a temporary file, then rename. Imported files are copied in so the alarm never depends on the original, and the persisted URI permission is released afterwards. The backup rules already exclude the whole credential `file` domain; 7.1 adds explicit entries for `recordings/` and `sounds/`, and the XML test asserts them.
- **Schema renumbering:** `app.db` is already at v10, because Epic 4's purchase records used it. The version numbers in `epics.md` are out of date. Each Epic 7 schema story takes the next free version in merge order: planned 7.1 `recording` (v11), 7.2 `alarm.motivation` (v12), 7.4 `custom_sound` (v13), 7.5 `alarm.motivation_mode` (v14), 7.11 `alarm.offer_skip`, `alarm.skipped_date` and a nullable `session_history.first_ring_at` (v15). Every migration ships an exported schema and a test that keeps existing alarms and history.
- **Core ports** return `Outcome<T, DomainError>` and have fakes in `:testing`: `RecordingRepository`, `AudioRecorder` and `MotivationPlayer`. Use cases (`SaveRecording` names takes "Message {n}", `ReRecord` keeps the id) never throw.
- **Session engine:** `ConfigResolver` freezes `motivation` and the mode into `SessionConfig` at `AlarmFired`. `Random` is resolved once from the session seed, so a restored session plays the same message. `SessionEffect.PlayMotivation` already exists on `Completed`; only `Completed` plays a message, never Missed or a paid snooze. Test sessions play it too.
- **Audio:** `WakeService` owns the only `AlarmPlayer`. Everything plays with `USAGE_ALARM` at the alarm's set volume. `SoundRef` gains `File(id)` (today it parses only `builtin:` and `system:`; unknown text means the default sound). "Mix into alarm" uses a pure `mixSchedule` in core: 30 s of alarm sound (so the volume ramp completes), then the recording once, repeating, with no gap between sources longer than 250 ms. Everything is muted during grace and resumes with the alarm sound when grace ends. "After I'm up" does not also play when the mode is Mix.
- **Printable QR:** ZXing core 3.5.3 is already in the version catalog; add it to `config/dependency-allowlist.txt`. Use error correction M with a quiet zone of at least 4 modules. The PDF (A4/Letter, 6 cm QR) is built with `PdfDocument` and sent to `PrintManager`; the app keeps no file. The generated code registers exactly like a scanned code.
- **Pre-alarm notification (7.11):** the `BackgroundWork` port currently has only weekly-summary-specific delay and cancel calls. 7.11 needs a general delayed, keyed (`pre-alarm-{alarmId}`), replaceable and cancellable one-time job. Use the "Upcoming alarms" channel. A late run after the occurrence posts nothing. Skipping goes through `SessionRecorder`, the only history writer: it writes one Skipped row with `first_ring_at` null, and "Don't skip" discards that row.

## UX & Interaction Patterns

- Design-preview code already exists in `:composeApp` (`ui/recordings`, `ui/qr` printable QR and replace dialog, editor `MotivationPane`). Wire it up rather than redrawing it. Approved states: `recordings-*`, `tap-recordings`, `qr-printable`, `qr-replace-dialog`, `editor-motivation`, `editor-sound-missing`.
- The approved editor Motivation pane lists None / Random / each recording as radio rows plus "Record a message" ›. "When it plays" is a radio group (After I'm up / Mix into alarm), shown only when a message is chosen. `epics.md` says a "Message" row and a segmented control; follow the approved preview and EXPERIENCE.md.
- Recordings screen: the `recorder` sits in a glass card (72 dp accent button, "0:09 / 1:00" with tabular figures, decorative level meter). After a take: Play, "Re-record", "Delete" (error colour), "Save". Then "Your messages" rows. Mic denied: a banner with "Fix" that opens the app's settings, and the record button disabled. TalkBack announces elapsed time every 10 s.
- `motivation-player` on Success: a `surface-sunrise` card with play/pause and replay that starts by itself. "Done" or leaving stops it. It runs alongside the Epic 6 celebration, and hides silently if the file fails.
- Sound picker: sections "Built-in" / "System" / "Your files", with "Pick a file" and snackbars for bad or oversized files.
- No preview exists yet for: Success with the player, the "Your files" section, the skip switch and confirm dialog, the "Next ring skipped" card with its "Don't skip" menu item, and Recordings in selection mode. The owner approves screens before the build, so show these states first. Small wording waits for v2.

## Cross-Story Dependencies

- 7.1 → 7.2 → 7.3 → 7.5 (domain, then selection and storage, then playback, then mode). 7.5 also needs 7.3's frozen motivation.
- 7.4 is independent of 7.1 to 7.3 except for the shared `app.db` version order and the backup-rules test. 7.1's `missing` pattern is reused for custom sounds.
- 7.10 builds on the Epic 3 QR registration and scan (ML Kit). 7.11 builds on the Epic 4 commitment lock and `PendingChange`, the Epic 6 Skipped handling in stats, Day detail and calendar, and the `BackgroundWork` port from Story 4.3/6.8.
- Making `session_history.first_ring_at` nullable in 7.11 affects the core `SessionHistoryRow.firstRingAt` (non-null today) and the Epic 6 stats and Day detail that read it.
- Shared hotspots: `AlarmEditorViewModel`/`EditorSubScreens`, `strings.xml`, the `app.db` migrations list, `AlarmPlayer`/`WakeService` (7.3, 7.4, 7.5), and the Koin modules. Rebase before review.
- 7.12 (owner) runs after all the shipped stories. House Hunt items 6 (part), 7, 8 and 9 are marked "cut".
