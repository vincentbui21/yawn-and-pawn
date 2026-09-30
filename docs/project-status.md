# Yawn & Pawn — project status

_Last updated: 2026-09-28_

## ▶ Where we are (handoff, 2026-09-28)

**Current work: the whole-app design preview, not the sprint stories.** The owner decided to see and approve the UI of the entire app on the phone before any more stories are built, so later work follows one approved design.

- **Branch `design/preview-epic-1`**, draft PR #7 (for CI only; do not merge until the owner approves all rounds). CI is green. Pull this branch to continue.
- **Spec:** `_bmad-output/implementation-artifacts/spec-design-preview-whole-app.md` (status `in-progress`, route `dispatch`). Delivery is 3 rounds; the orchestrator runs one implementation per round and stops for owner feedback on the phone.
- **Round 1 (daily loop) is DONE and owner-approved** ("everything is looking good", 2026-09-28). It covers Home, the full editor with sub-screens, the sound picker, Ringing, Snooze confirm, the checks, Success and Snoozed.
- **Round 2 (Progress, day detail, purchase history, export, Settings with sub-screens, reliability checklist, Payments & refunds) is implemented (2026-09-30), waiting for the owner's feedback on the phone.** Device screenshots: `docs/design-preview/round-2/`; new drafts to approve under Round 2 in `docs/design-preview/copy-to-approve.md`.
- **Round 3** after that covers onboarding, check picker and setup, House Hunt and QR registration, and recordings.
- **Owner design decisions** live in `docs/design-preview/feedback.md` (items 1–20; read them before any UI work). They are also recorded in DESIGN.md v0.3, EXPERIENCE.md and `.claude/skills/pps-design/SKILL.md` (rules 11, 14, 15).
  - Stock Samsung/Oppo Clock style: grouped glass cards on gradient backgrounds, and rows with a value and › that open sub-screens.
  - A scrolling time wheel with haptic and tick; a Cancel | Save pill in its own bottom area.
  - Motion, including a collapsing Home header like Samsung Weather.
  - "Quiet time" instead of "Grace window"; no starting-volume slider.
  - Brand colours unchanged.
- **Wording to approve:** `docs/design-preview/copy-to-approve.md`. **Device screenshots:** `docs/design-preview/round-1/`. **References:** `docs/design-preview/reference/`.
- **After the preview is approved:** resume sprint stories at **1.9**, reusing the approved stateless screens (the preview feeds them fake data; stories connect ViewModels). Check `_bmad-output/implementation-artifacts/deferred-work.md` for items parked for 1.9 and later.
- **Test phone:** the owner's **Oppo A96** (Android 13, 360 dp, ColorOS), not a Galaxy A57. It is connected by USB with USB debugging on. The debug build installs a second launcher icon, "Yawn & Pawn Preview". For on-device walkthroughs use `adb` (screencap, uiautomator, input tap). In Git Bash set `MSYS_NO_PATHCONV=1`. The phone needs to be unlocked and awake (`adb shell svc power stayon usb` while working, `false` after).
- **How work runs:** `bmad-build` per story or feature (spec → owner approval → implementation subagent → 3 review subagents → fixes → `qualityGate` → PR → CI green → squash-merge → sprint-status `done`). Merging with `gh pr merge` is allowed by `.claude/settings.local.json` on the company laptop only. That file is git-ignored, so set the same permission on another computer if wanted.

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
