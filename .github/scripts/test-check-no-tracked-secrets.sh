#!/usr/bin/env bash
# Tests check-no-tracked-secrets.sh against throwaway git repos: every forbidden name fails and is
# listed; a clean repo passes. Run in CI before the guard itself.
set -euo pipefail

guard="$(cd "$(dirname "$0")" && pwd)/check-no-tracked-secrets.sh"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
failures=0

new_repo() {
  local dir="$work/repo-$RANDOM$RANDOM"
  mkdir -p "$dir"
  git -C "$dir" init -q
  git -C "$dir" config core.autocrlf false
  echo "$dir"
}

track() {
  local dir="$1" path="$2"
  mkdir -p "$dir/$(dirname "$path")"
  echo x > "$dir/$path"
  git -C "$dir" add -f -- "$path"
}

forbidden=(
  "release.jks"
  "app/upload.keystore"
  "cert.p12"
  "key.pem"
  "platform.pk8"
  "AuthKey.p8"
  "keystore.properties"
  "local.properties"
  "sub/local.properties"
  ".env"
  ".env.local"
  "config/prod.env"
  "google-services.json"
  "androidApp/google-services.json"
  "play-service-account.json"
  "ci/my-service-account-key.json"
  "dir with space/release.jks"
  "clés/ünïcode.keystore"
)

for path in "${forbidden[@]}"; do
  repo="$(new_repo)"
  track "$repo" "README.md"
  track "$repo" "$path"
  if output="$(bash "$guard" "$repo" 2>&1)"; then
    echo "FAIL: '$path' tracked but the guard passed"
    failures=$((failures + 1))
  elif ! grep -qF -- "  $path" <<<"$output"; then
    echo "FAIL: '$path' tracked but not listed. Output:"
    echo "$output"
    failures=$((failures + 1))
  else
    echo "ok: '$path' fails and is listed"
  fi
done

clean="$(new_repo)"
for path in README.md build.gradle.kts gradle.properties config/permission-allowlist.txt docs/environment.md app/src/main/AndroidManifest.xml; do
  track "$clean" "$path"
done
if bash "$guard" "$clean" >/dev/null 2>&1; then
  echo "ok: clean repo passes"
else
  echo "FAIL: clean repo failed the guard"
  failures=$((failures + 1))
fi

if [ "$failures" -gt 0 ]; then
  echo "$failures guard test(s) failed"
  exit 1
fi
echo "All guard tests passed."
