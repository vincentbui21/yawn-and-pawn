# Yawn & Pawn — project status

_Last updated: 2026-09-26_

## Done
- Planning complete (BMAD): PRD v0.2, DESIGN.md + EXPERIENCE.md v0.2, architecture spine v0.3, 8 epics / 114 stories, sprint-status.yaml generated (readiness gate PASS).
- App name **Yawn & Pawn**, package `com.yawnandpawn.app`, English only.
- GitHub repo: https://github.com/vincentbui21/yawn-and-pawn (private).
- **Google Play Console developer account created** (personal, owner vincentbui2108@gmail.com, developer name "Yawn & Pawn", USD 25 paid).

## Waiting on
- **Google identity verification** (passport + Helen electricity invoice as proof of address), submitted 2026-09-26. Usually 1–3 business days. A rejection can be fixed by re-uploading, no new fee.
- **Phone verification**: unlocks only after identity approval (Play Console → Account details → Verify).

## Next
1. Push the local folder to GitHub (docs/dev-setup.md §3).
2. Story 1.1 (scaffold) → 1.2 (CI) → 1.3 (design tokens), built interactively with `bmad-build` in Claude Code (VS Code) on the laptop.
3. Then run the automatic loop with **bmad-loop** (official BMAD orchestrator: dev → review → verify → commit per story, driven by sprint-status.yaml). Runs in WSL + tmux on Windows. BMAD must be reinstalled with `--shims` so the `bmad-dev-auto` name bmad-loop calls maps to `bmad-build-auto`. First run with `--dry-run`, then per epic with `gates.mode = "per-epic"`; `human-verify` stories are done by the owner.
4. Story 1.4 remainder (app record, internal track, license testers) once 1.1 produces a signed build.

## Open items (not blocking Epic 1)
- Support email placeholder vincentbui2108@gmail.com (change later in config/app-links.properties).
- OEM guidance text for Samsung, Huawei, Oppo/Realme, Vivo, OnePlus (Epic 5).
- EU/UK withdrawal-right wording in terms, legal check (Epic 8).
- EU DSA trader details: decide whether to exclude EU at launch (Epic 8).
