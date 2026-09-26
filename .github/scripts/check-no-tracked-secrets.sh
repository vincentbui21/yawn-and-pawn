#!/usr/bin/env bash
# Fails if git tracks a secret (AD-15: secrets live only in GitHub Actions secrets): keystores
# and key files (*.jks, *.keystore, *.p12, *.pem, *.pk8, *.p8), keystore.properties,
# local.properties, .env files, service-account JSON or google-services.json.
# Mirrors the "Local config and secrets" block in .gitignore, plus common key-file extensions.
# Tested by .github/scripts/test-check-no-tracked-secrets.sh.
# Usage: .github/scripts/check-no-tracked-secrets.sh [repo-dir]
set -euo pipefail

cd "${1:-.}"

pattern='(\.jks|\.keystore|\.p12|\.pem|\.pk8|\.p8|\.env)$|(^|/)\.env[^/]*$|(^|/)(keystore|local)\.properties$|(^|/)google-services\.json$|service-account[^/]*\.json$'

tracked=()
# core.quotePath=false and -z: paths with spaces or non-ASCII characters come out verbatim.
while IFS= read -r -d '' path; do
  if printf '%s' "$path" | grep -Eiq "$pattern"; then
    tracked+=("$path")
  fi
done < <(git -c core.quotePath=false ls-files -z)

if [ "${#tracked[@]}" -gt 0 ]; then
  echo "::error title=Secrets tracked by git::Remove these files from git (git rm --cached) and keep them in GitHub secrets"
  echo "These files must never be committed:"
  printf '  %s\n' "${tracked[@]}"
  if [ -n "${GITHUB_STEP_SUMMARY:-}" ]; then
    {
      echo "### Secrets tracked by git"
      echo
      printf -- '- `%s`\n' "${tracked[@]}"
    } >> "$GITHUB_STEP_SUMMARY"
  fi
  exit 1
fi

echo "No keystores, key files, local/keystore properties, .env files, service-account JSON or google-services.json tracked by git."
