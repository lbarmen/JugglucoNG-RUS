#!/usr/bin/env bash
# JugglucoNG-RUS: things that must never reach this public repository (keys, tokens, passwords,
# Nightscout addresses, key files). Exit 1 and a list of findings if anything matches.
# Usage:
#   bash rus/check-secrets.sh                      staged changes: run before every commit
#   bash rus/check-secrets.sh --ours [THEIR_POINT] our own commits only: every non-merge commit of HEAD that
#                                                  THEIR_POINT (their code in HEAD, default
#                                                  `git merge-base HEAD upstream/main`) does not have. Upstream
#                                                  commits are not ours and are never scanned: in a sync branch
#                                                  main..HEAD would hold all their new commits (plan of stage 2, A3).
set -euo pipefail
cd "$(dirname "$0")/.."

status=0
patterns=(
  'gh[pousr]_[A-Za-z0-9]{30,}'
  'github_pat_[A-Za-z0-9_]{30,}'
  '-----BEGIN [A-Z ]*PRIVATE KEY-----'
  'AKIA[0-9A-Z]{16}'
  '(thepassword|thekeypassword|storePassword|keyPassword|api[_-]?secret|apiSecret|API_SECRET)["'"'"']?[[:space:]]*[=:][[:space:]]*["'"'"']?[^"'"'"'[:space:]$]{4,}'
  # quoted value without "=" (Gradle: storePassword "x") and generic names, quoted values only
  '(thepassword|thekeypassword|storePassword|keyPassword|api[_-]?secret|apiSecret|API_SECRET|[Pp]assword|PASSWORD|[Tt]oken|TOKEN)["'"'"']?[[:space:]]*[=:]?[[:space:]]*["'"'"'][^"'"'"'[:space:]$]{6,}["'"'"']'
  'https?://[A-Za-z0-9.-]+\.(herokuapp\.com|fly\.dev|ns\.10be\.de|nightscoutpro\.com|t1pal\.com)'
  '[?&]token=[A-Za-z0-9-]{8,}'
)

# $1 = label, stdin = list of file names, $2 = added lines
scan() {
  local label="$1" added="$2" f p hits
  while IFS= read -r f; do
    case "$f" in
      *.jks|*.keystore|*.p12|*.pem|*.key|local.properties|*.env)
        echo "FAIL  key or secret file ($label): $f"; status=1 ;;
    esac
  done
  for p in "${patterns[@]}"; do
    hits=$(printf '%s\n' "$added" | grep -nE -- "$p" || true)
    # Upstream's public test key is known and allowed in their own files only; ours never use it.
    hits=$(printf '%s\n' "$hits" | grep -v 'IDontKnow' || true)
    if [ -n "$hits" ]; then
      echo "FAIL  pattern ($label): $p"
      printf '%s\n' "$hits" | cut -c1-160
      status=1
    fi
  done
}

if [ "${1:-}" = "--ours" ]; then
  point="${2:-}"
  if [ -z "$point" ]; then
    git rev-parse -q --verify upstream/main > /dev/null || {
      echo "FAIL  upstream/main is not fetched: git fetch upstream main (or pass their point)"; exit 1; }
    point=$(git merge-base HEAD upstream/main)
  fi
  git rev-parse -q --verify "$point^{commit}" > /dev/null || { echo "FAIL  unknown point: $point"; exit 1; }
  commits=$(git rev-list --no-merges HEAD --not "$point")
  n=0
  for c in $commits; do
    n=$((n + 1))
    scan "commit ${c:0:9}" "$(git show --format= -U0 --no-color "$c" | grep -E '^\+[^+]' || true)" \
      < <(git show --format= --name-only --diff-filter=AM "$c")
  done
  # merge commits are skipped above: a conflict resolved by hand lives only there, so the whole tree against
  # their code is scanned too
  scan "tree vs their ${point:0:9}" "$(git diff -U0 --no-color "$point" HEAD | grep -E '^\+[^+]' || true)" \
    < <(git diff --name-only --diff-filter=AM "$point" HEAD)
  [ $status -eq 0 ] && echo "OK    no secrets in our $n commit(s) and in the tree on top of their ${point:0:9}"
  exit $status
fi

files=$(git diff --cached --name-only --diff-filter=AM)
scan staged "$(git diff --cached -U0 --no-color | grep -E '^\+[^+]' || true)" < <(printf '%s\n' "$files" | grep . || true)
[ $status -eq 0 ] && echo "OK    no secrets in staged changes ($(echo "$files" | grep -c . || true) files)"
exit $status
