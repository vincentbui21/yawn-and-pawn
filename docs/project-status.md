# Yawn & Pawn — project status

_Last updated: 2026-10-01_

## ▶ Where we are (2026-10-01)

**The whole-app design preview is DONE and owner-approved: rounds 1, 2 and 3** (owner, 2026-10-01: "everything is looking good").
- The branch `design/preview-epic-1` (PR #7) is being merged into `main`. Every later UI story wires these approved stateless screens to real ViewModels and does not rebuild them.
- Spec: `_bmad-output/implementation-artifacts/spec-design-preview-whole-app.md`.
- Owner design decisions: `docs/design-preview/feedback.md` (items 1–26). Read them before any UI work. They are also recorded in DESIGN.md / EXPERIENCE.md v0.5 and `.claude/skills/pps-design/SKILL.md`.
- Drafted copy still to approve: `docs/design-preview/copy-to-approve.md`.
- Every preview state has a deep link; see `docs/design-preview/states.md`.

**Next steps, as agreed with the owner:**
1. Merge PR #7 into `main`.
2. **Correct-course** (`bmad-correct-course`): update the PRD, `epics.md` and the stories to the approved design. Several owner decisions go against the PRD: no Export CSV (FR-PRG-6), no snoozes chart (FR-PRG-2), the "You" tab, five-slot navigation, "Quiet time", no ramp-start slider, the wheel picker, and difficulty in Check setup. Assign the items in `_bmad-output/implementation-artifacts/deferred-work.md` to stories. The owner approves one sprint change proposal.
3. **Run the rest of Epic 1 automatically** with `bmad-build-auto`, one story after another: PR → CI green → merge. Stop only on real blockers. The owner reviews and tests the whole epic at the end, not per story. `human-verify` stories (1.4, 1.5, 1.20, 1.21) are done with the owner.
4. **Play Console is approved** (2026-10-01). The Story 1.4 remainder (app record, upload key, first manual internal-track upload, Play App Signing, license testers, payments profile) unblocks spike 1.5, which decides whether 1.11 needs unlock states.

**Test phone:** the owner's **Oppo A96** (Android 13, 360 dp, ColorOS), adb serial `4d804fdd`, USB debugging on.
- **Use it only when the owner says the phone is free.** Never run capture loops while they hold it.
- `am start -S` loops make the app look like it keeps restarting.
- Avoid `settings put` and `svc power`: on ColorOS they fail with a WRITE_SETTINGS error.
- The debug build has a second launcher icon, "Yawn & Pawn Preview". Deep link: `adb shell am start -n com.yawnandpawn.app/.debug.preview.PreviewActivity --es state <id>`. In Git Bash, set `MSYS_NO_PATHCONV=1`.

**How work runs:** `bmad-build` / `bmad-build-auto` per story. Spec → implementation subagent → 3 review subagents → fixes → `qualityGate` → PR → CI green → squash-merge → sprint-status `done`. Merging with `gh pr merge` is allowed by `.claude/settings.local.json` on the company laptop only; that file is git-ignored.

## Done
- Planning complete (BMAD): PRD v0.2, DESIGN.md + EXPERIENCE.md v0.2, architecture spine v0.3, 8 epics / 114 stories, sprint-status.yaml generated (readiness gate PASS).
- App name **Yawn & Pawn**, package `com.yawnandpawn.app`, English only.
- GitHub repo: https://github.com/vincentbui21/yawn-and-pawn (private).
- **Google Play Console developer account created** (personal, owner vincentbui2108@gmail.com, developer name "Yawn & Pawn", USD 25 paid).
- **Story 1.1** (KMP scaffold + `./gradlew qualityGate`) merged in PR #1.
- **Story 1.2** (CI on every PR and push to `main`, dependency and permission allowlists, tag-based release workflow) merged in PR #2. CI is green, including the emulator smoke test.
- **Story 1.3** (design tokens generated from DESIGN.md, PpsTheme with Light/Dark/Sunrise, Geist, raw-value detekt rules, contrast and copy-rule tests, debug theme showcase) merged in PR #3.
- **Story 1.6** (time ports, boot-aware deadlines, DST-safe next-occurrence math, direct-clock detekt ban) merged in PR #4.
- **Story 1.7** (alarm domain and use cases, Room 3 `app.db` in device-protected storage, backup rules) merged in PR #5.
- **Story 1.8** (Alarms route with empty state and FAB, Alarm editor with every Epic 1 field, discard dialog) merged in PR #6. Checked on the owner's Oppo A96 (Android 13) on 2026-09-27: add, save, reopen and the discard dialog work; Save hidden behind the keyboard and the grey status bar are logged for 1.9.
- **Design preview round 1** (whole-app preview, daily loop) owner-approved on branch `design/preview-epic-1` (draft PR #7).
- **Company laptop set up without admin rights**: Python, GitHub CLI, JDK 17, Android Studio and the Android SDK in the user folder.

## Waiting on
- **Google identity verification** (passport + Helen electricity invoice as proof of address), submitted 2026-09-26. Usually 1–3 business days. A rejection can be fixed by re-uploading, no new fee.
- **Phone verification**: unlocks only after identity approval (Play Console → Account details → Verify).

## Next
1. Stories 1.1–1.3 are done, so the foundation for the automatic loop is in place. Next automatable stories: 1.9 (alarm list with countdown), 1.10 (exact scheduling). 1.4 and 1.5 are owner/human-verify stories waiting on Play Console.
2. **bmad-loop cannot run on the company laptop** (it needs WSL, which needs admin rights). Options: run bmad-loop on the personal computer (docs/dev-setup.md §6), or use a simple PowerShell loop that runs `claude -p "/bmad-build-auto"` one story at a time on the company laptop.
3. Story 1.4 remainder (app record, internal track, license testers) after identity verification. The release workflow needs the GitHub secrets in `docs/ci-release.md`, and the very first upload must be done by hand in Play Console.

## Open items (not blocking Epic 1)
- Support email placeholder vincentbui2108@gmail.com (change later in config/app-links.properties).
- OEM guidance text for Samsung, Huawei, Oppo/Realme, Vivo, OnePlus (Epic 5).
- EU/UK withdrawal-right wording in terms, legal check (Epic 8).
- EU DSA trader details: decide whether to exclude EU at launch (Epic 8).
