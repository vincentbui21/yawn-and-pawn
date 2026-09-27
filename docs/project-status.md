# Yawn & Pawn — project status

_Last updated: 2026-09-26_

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
- **Company laptop set up without admin rights**: Python, GitHub CLI, JDK 17, Android Studio and the Android SDK in the user folder.

## Waiting on
- **Google identity verification** (passport + Helen electricity invoice as proof of address), submitted 2026-09-26. Usually 1–3 business days. A rejection can be fixed by re-uploading, no new fee.
- **Phone verification**: unlocks only after identity approval (Play Console → Account details → Verify).

## Next
1. Stories 1.1–1.3 are done, so the foundation for the automatic loop is in place. Next automatable stories: 1.8 (create and edit an alarm), 1.9 (alarm list with countdown), 1.10 (exact scheduling). 1.4 and 1.5 are owner/human-verify stories waiting on Play Console.
2. **bmad-loop cannot run on the company laptop** (it needs WSL, which needs admin rights). Options: run bmad-loop on the personal computer (docs/dev-setup.md §6), or use a simple PowerShell loop that runs `claude -p "/bmad-build-auto"` one story at a time on the company laptop.
3. Story 1.4 remainder (app record, internal track, license testers) after identity verification. The release workflow needs the GitHub secrets in `docs/ci-release.md`, and the very first upload must be done by hand in Play Console.

## Open items (not blocking Epic 1)
- Support email placeholder vincentbui2108@gmail.com (change later in config/app-links.properties).
- OEM guidance text for Samsung, Huawei, Oppo/Realme, Vivo, OnePlus (Epic 5).
- EU/UK withdrawal-right wording in terms, legal check (Epic 8).
- EU DSA trader details: decide whether to exclude EU at launch (Epic 8).
