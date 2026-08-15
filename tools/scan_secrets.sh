#!/usr/bin/env bash
# Fails if anything that looks like a credential is tracked by git.
# Run before every release; also runs in CI on every push.
set -uo pipefail

cd "$(dirname "$0")/.."

patterns=(
  'BEGIN [A-Z ]*PRIVATE KEY'
  'AKIA[0-9A-Z]{16}'
  'sk-[A-Za-z0-9]{20,}'
  'ghp_[A-Za-z0-9]{30,}'
  'xox[baprs]-[A-Za-z0-9-]{10,}'
  'eyJ[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}'
  '(password|passwd|secret|api[_-]?key|access[_-]?token|bearer)[[:space:]]*[:=][[:space:]]*["'"'"'][^"'"'"']{8,}'
)

# Files that legitimately discuss credentials without containing any.
allowlist='^(SECURITY\.md|README\.md|TESTING\.md|ARCHITECTURE\.md|tools/scan_secrets\.sh|\.github/workflows/secret-scan\.yml)$'

status=0
tracked=$(git ls-files | grep -Ev "$allowlist")

for pattern in "${patterns[@]}"; do
  # shellcheck disable=SC2086
  hits=$(printf '%s\n' $tracked | xargs -r grep -InE "$pattern" 2>/dev/null || true)
  if [ -n "$hits" ]; then
    echo "POSSIBLE SECRET (pattern: $pattern)"
    echo "$hits"
    status=1
  fi
done

# Environment files must never be tracked at all.
env_files=$(git ls-files | grep -E '(^|/)\.env($|\.)' | grep -v '\.env\.example$' || true)
if [ -n "$env_files" ]; then
  echo "TRACKED ENV FILE(S):"
  echo "$env_files"
  status=1
fi

# Keystores and private keys must never be tracked.
key_files=$(git ls-files | grep -E '\.(jks|keystore|p12|pem|key)$' || true)
if [ -n "$key_files" ]; then
  echo "TRACKED KEY MATERIAL:"
  echo "$key_files"
  status=1
fi

if [ "$status" -eq 0 ]; then
  echo "Secret scan clean: $(printf '%s\n' $tracked | wc -l) tracked files checked."
fi
exit "$status"
