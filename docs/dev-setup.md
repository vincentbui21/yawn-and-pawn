# Developer setup and two-computer workflow

## 1. Install the tools (each Windows computer)

Open **PowerShell** (not as Administrator) and run:

```powershell
winget install Git.Git
winget install GitHub.cli
winget install Google.AndroidStudio
irm https://claude.ai/install.ps1 | iex
```

Then close and reopen PowerShell and check:

```powershell
git --version
gh --version
claude --version
```

**Android Studio first run:** open it once and let the setup wizard install the Android SDK, platform tools and an emulator image. It bundles the JDK, so nothing else is needed. In *SDK Manager*, also install **Android 16 (API 36)** and **Android 17 (API 37)** platforms.

**Git identity** (once per computer):

```powershell
git config --global user.name "Vincent Bui"
git config --global user.email "<the email on your GitHub account>"
gh auth login
```

**BMAD** needs Node.js 20.12+ and `uv` for its helper scripts:

```powershell
winget install OpenJS.NodeJS.LTS
winget install astral-sh.uv
```

## 2. Your Samsung Galaxy A57 for testing

1. Settings → About phone → Software information → tap **Build number** 7 times (turns on Developer options).
2. Settings → Developer options → turn on **USB debugging**.
3. Plug it in with a USB cable, accept the "Allow USB debugging" prompt on the phone.
4. In PowerShell: `adb devices` should list the phone.

Emulators (created in Android Studio's Device Manager) cover everything else; the build also runs emulator tests automatically on GitHub Actions.

## 3. Put the project on GitHub (once, on the first computer)

```powershell
cd "D:\Code Source\pay-per-snooze"
git init -b main
git add .
git commit -m "chore: planning artifacts, BMAD setup and sprint status"
git remote add origin https://github.com/vincentbui21/yawn-and-pawn.git
git push -u origin main
```

On the second computer:

```powershell
gh repo clone vincentbui21/yawn-and-pawn
cd yawn-and-pawn
```

## 4. Rules for two computers (two Claude accounts)

Git is the only thing the two computers share, so these rules stop them overwriting each other's work:

1. **Always start with `git pull`** on `main` before choosing work.
2. **Claim before you build.** Pick the next `backlog` story in `_bmad-output/implementation-artifacts/sprint-status.yaml`, set it to `in-progress`, add a comment `# claimed by laptop-A` (or B), commit and push **immediately**. If the push is rejected, someone else changed the file: pull and pick again.
3. **One story = one branch** named `story/<story-key>` (for example `story/1-3-generated-design-tokens-and-ppstheme`). Never let both computers work on the same branch.
4. **Never run two automatic loops on the same story**, and don't run loops on both computers on stories that touch the same core files (the session state machine in Epic 1–2, billing in Epic 4). The safe default: one computer runs the loop, the other reviews and tests on the phone.
5. **Merge through pull requests** after `./gradlew qualityGate` passes and CI is green. Then set the story to `done` on `main`.
6. `human-verify` stories are done only after you tested on the phone and wrote the result in the story file.
7. Secrets (signing keys, `google-services.json`, Play service-account file) never go into git. Keep them in each computer's `local.properties`/files ignored by `.gitignore`, and in GitHub Actions secrets.

## 5. Accounts still to create

- **Google Play Console developer account** (personal, USD 25 one-time, identity verification can take a few days): Story 1.4. Start early.
- **Support email:** placeholder `vincentbui2108@gmail.com` for now; change it later in `config/app-links.properties` (one line).

## 6. Automatic build loop: bmad-loop (after stories 1.1–1.3)

bmad-loop is the official BMAD orchestrator (https://github.com/bmad-code-org/bmad-loop). It reads `sprint-status.yaml`, picks the next story, and runs dev → review → verify → commit in fresh Claude Code sessions. It needs **WSL + tmux** on Windows.

```powershell
wsl --install -d Ubuntu        # once, then reboot and open "Ubuntu"
```

Inside Ubuntu (WSL):

```bash
sudo apt update && sudo apt install -y tmux git unzip openjdk-17-jdk
curl -LsSf https://astral.sh/uv/install.sh | sh
curl -fsSL https://claude.ai/install.sh | bash          # Claude Code for Linux, then run `claude` once to log in
# Android command-line SDK for Gradle builds inside WSL (emulators stay on Windows / CI)
mkdir -p ~/android-sdk/cmdline-tools && cd ~/android-sdk/cmdline-tools
# download "Command line tools only" for Linux from developer.android.com/studio, unzip to ./latest, then:
~/android-sdk/cmdline-tools/latest/bin/sdkmanager "platform-tools" "platforms;android-37" "build-tools;36.0.0"
echo 'export ANDROID_HOME=$HOME/android-sdk' >> ~/.bashrc

# clone the repo inside the Linux filesystem (much faster than /mnt/d)
cd ~ && gh repo clone vincentbui21/yawn-and-pawn && cd yawn-and-pawn
npx bmad-method install --yes --modules bmm --tools claude-code --shims   # --shims adds bmad-dev-auto → bmad-build-auto
uv tool install "bmad-loop[tui] @ git+https://github.com/bmad-code-org/bmad-loop.git"
bmad-loop init
bmad-loop validate
bmad-loop run --epic 1 --dry-run     # preview which stories it would run
```

Settings live in `.bmad-loop/policy.toml`. Recommended for this project:

```toml
[verify]
commands = ["./gradlew qualityGate"]

[gates]
mode = "per-epic"          # pause at the end of each epic for your device check

[scm]
isolation = "worktree"
branch_per = "story"
merge_strategy = "squash"
```

`human-verify` stories (device checklists, Play Console tasks) are done by you, not the loop. Use `bmad-loop tui` to watch progress and `bmad-loop attach` to look at a live session.
