#!/usr/bin/env bash
# JugglucoNG-RUS: run before every commit. Scans lines added in the staged diff for
# things that must never reach this public repository (keys, tokens, passwords,
# Nightscout addresses, key files). Exit 1 and a list of findings if anything matches.
# Usage: bash rus/check-secrets.sh
set -euo pipefail
cd "$(dirname "$0")/.."

status=0
files=$(git diff --cached --name-only --diff-filter=AM)
for f in $files; do
  case "$f" in
    *.jks|*.keystore|*.p12|*.pem|*.key|local.properties|*.env)
      echo "FAIL  key or secret file staged: $f"; status=1 ;;
  esac
done

patterns=(
  'gh[pousr]_[A-Za-z0-9]{30,}'
  'github_pat_[A-Za-z0-9_]{30,}'
  '-----BEGIN [A-Z ]*PRIVATE KEY-----'
  'AKIA[0-9A-Z]{16}'
  '(thepassword|thekeypassword|storePassword|keyPassword|api[_-]?secret|API_SECRET)[[:space:]]*[=:][[:space:]]*["'"'"']?[^"'"'"'[:space:]$]{4,}'
  'https?://[A-Za-z0-9.-]+\.(herokuapp\.com|fly\.dev|ns\.10be\.de|nightscoutpro\.com|t1pal\.com)'
  '[?&]token=[A-Za-z0-9-]{8,}'
)
added=$(git diff --cached -U0 --no-color | grep -E '^\+[^+]' || true)
for p in "${patterns[@]}"; do
  hits=$(printf '%s\n' "$added" | grep -nE -- "$p" || true)
  # Upstream's public test key is known and allowed in their own files only; ours never use it.
  hits=$(printf '%s\n' "$hits" | grep -v 'IDontKnow' || true)
  if [ -n "$hits" ]; then
    echo "FAIL  pattern: $p"
    printf '%s\n' "$hits" | cut -c1-160
    status=1
  fi
done

[ $status -eq 0 ] && echo "OK    no secrets in staged changes ($(echo "$files" | grep -c . || true) files)"
exit $status
