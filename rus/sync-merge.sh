#!/usr/bin/env bash
# JugglucoNG-RUS: merge an upstream release into main through a PR sync/<release> (plan of stage 2, D1).
# Run from the computer (Git Bash) by the sync chat, after reading the morning report on GitHub.
#
#   bash rus/sync-merge.sh prepare --decision "<text>" --minutes N [--point <tag|commit>]
#       rerere on; their latest release without the pre-release mark (another point only by the owner's word);
#       branch sync/<release> from origin/main; merge (rerere replays known resolutions; a conflict left -> stop
#       with the list of files); check-hooks, check-secrets --ours, sync-report; a line in rus/SYNC-LOG.md;
#       push the branch and open the PR with the report. Prints the PR number.
#   bash rus/sync-merge.sh finish <PR>
#       waits for the PR checks; all green -> merge with a merge commit (never squash/rebase: main's history is
#       not rewritten, ТЗ 3.2); switches off their new workflows named in the report; updates local main.
#       A red check -> stop: the chat decides (an owner-approved red merge goes through the ruleset bypass).
# RUS_SYNC_DRY=1 with prepare: everything except push and PR (probe git, rerere check).
# Needs: git, gh (signed in), python 3. The point and upstream main are fetched; origin is our fork.
set -euo pipefail
cd "$(dirname "$0")/.."
REPO=lbarmen/JugglucoNG-RUS
UP=ctqvva/JugglucoNG
STATE=.git/rus-sync
PY=python3; "$PY" -c 'import sys' 2>/dev/null || PY=python
py() { "$PY" -X utf8 "$@"; }
die() { echo "STOP  $*" >&2; exit 1; }

cmd="${1:-}"; shift || true
case "$cmd" in
prepare)
  decision=""; minutes=""; point=""
  while [ $# -gt 0 ]; do
    case "$1" in
      --decision) decision="$2"; shift 2 ;;
      --minutes) minutes="$2"; shift 2 ;;
      --point) point="$2"; shift 2 ;;
      *) die "unknown argument $1" ;;
    esac
  done
  [ -n "$decision" ] || die "--decision is required (what the chat decided after the report)"
  [[ "$minutes" =~ ^[0-9]+$ ]] || die "--minutes N is required (time spent on the review)"
  [ -z "$(git status --porcelain --untracked-files=no)" ] || die "working tree has changes: commit or stash first"
  git config rerere.enabled true
  git config rerere.autoupdate true
  git fetch -q origin main
  git remote get-url upstream > /dev/null 2>&1 || git remote add upstream "https://github.com/$UP.git"
  git fetch -q --no-tags upstream main
  if [ -z "$point" ]; then
    point=$(gh api "repos/$UP/releases?per_page=50" --jq \
      '[.[] | select(.draft | not) | select(.prerelease | not)] | sort_by(.published_at) | last | .tag_name')
    [ -n "$point" ] && [ "$point" != null ] || die "no upstream release found"
  else
    echo "NOTE  point $point given by hand: only by the owner's word (plan of stage 2, D1.2)"
  fi
  if git fetch -q --no-tags upstream "+refs/tags/$point:refs/tags/$point" 2>/dev/null; then
    theirs=$(git rev-parse "refs/tags/$point^{commit}")
  elif git fetch -q upstream "$point" 2>/dev/null; then
    theirs=$(git rev-parse FETCH_HEAD)
  else
    die "$point is neither their tag nor their commit"
  fi
  if git merge-base --is-ancestor "$theirs" origin/main; then die "$point is already in main: nothing to merge"; fi
  old=$(git merge-base origin/main upstream/main)
  branch="sync/$point"
  git switch -q -C "$branch" origin/main
  # conflicts git sees before rerere (for SYNC-LOG); rerere may then resolve them from memory
  conflicts=0
  if ! out=$(git merge-tree --write-tree --name-only --no-messages origin/main "$theirs"); then
    conflicts=$(printf '%s\n' "$out" | tail -n +2 | grep -c . || true)
  fi
  if ! git merge --no-ff --no-edit -m "RUS sync: merge their $point into main" "$theirs"; then
    left=$(git diff --name-only --diff-filter=U)
    if [ -n "$left" ]; then
      echo "CONFLICT in:"; printf '  %s\n' $left
      die "resolve these files by hand (rerere remembers the resolution), git add them, git commit --no-edit,
      then: bash rus/sync-merge.sh prepare-after-conflict --decision \"...\" --minutes N --conflicts $conflicts"
    fi
    echo "OK    rerere resolved all $conflicts conflict(s) from an earlier resolution"
    git commit -q --no-edit
  fi
  exec bash rus/sync-merge.sh checks-and-pr "$point" "$old" "$theirs" "$decision" "$minutes" "$conflicts"
  ;;
prepare-after-conflict)
  # the chat resolved the conflicts by hand and committed the merge on sync/<release>
  decision=""; minutes=""; conflicts="?"
  while [ $# -gt 0 ]; do
    case "$1" in
      --decision) decision="$2"; shift 2 ;;
      --minutes) minutes="$2"; shift 2 ;;
      --conflicts) conflicts="$2"; shift 2 ;;
      *) die "unknown argument $1" ;;
    esac
  done
  [ -n "$decision" ] && [[ "$minutes" =~ ^[0-9]+$ ]] || die "--decision and --minutes N are required"
  branch=$(git rev-parse --abbrev-ref HEAD)
  [[ "$branch" == sync/* ]] || die "not on a sync/<release> branch"
  git rev-parse -q --verify HEAD^2 > /dev/null || die "HEAD is not the merge commit (commit the resolved merge first)"
  [ -z "$(git diff --name-only --diff-filter=U)" ] || die "unresolved files left"
  point="${branch#sync/}"
  theirs=$(git rev-parse HEAD^2)
  old=$(git merge-base origin/main upstream/main)
  exec bash rus/sync-merge.sh checks-and-pr "$point" "$old" "$theirs" "$decision" "$minutes" "$conflicts (вручную)"
  ;;
checks-and-pr)
  point="$1"; old="$2"; theirs="$3"; decision="$4"; minutes="$5"; conflicts="$6"
  mkdir -p "$STATE"
  fail=0
  py rus/check-hooks.py --ref HEAD --base "$theirs" || fail=1
  bash rus/check-secrets.sh --ours "$theirs" || fail=1
  py rus/sync-report.py "$old" "$theirs" --ours origin/main --out "$STATE/report.md" --json "$STATE/report.json"
  [ $fail = 0 ] || die "checks are red: fix on this branch first (nothing pushed)"
  read -r version commits flags < <(py -c "
import json,sys; d=json.load(open(sys.argv[1],encoding='utf-8'))
f=', '.join(f'{k} {v}' for k,v in (d.get('flags') or {}).items()) or 'нет'
print(d.get('version_new','?'), d.get('commits',0), f)" "$STATE/report.json")
  log=rus/SYNC-LOG.md
  if [ ! -f "$log" ]; then
    printf '%s\n' "# Журнал вливаний JugglucoNG (rus/sync-merge.sh)" "" \
      "| дата | их выпуск | версия | коммитов | конфликтов | флаги риска | решение | разбор, мин |" \
      "|---|---|---|---|---|---|---|---|" > "$log"
  fi
  echo "| $(date +%Y-%m-%d) | $point | $version | $commits | $conflicts | $flags | ${decision//|//} | $minutes |" >> "$log"
  git add "$log"
  git commit -q -m "RUS sync: SYNC-LOG line for $point"
  branch=$(git rev-parse --abbrev-ref HEAD)
  if [ "${RUS_SYNC_DRY:-}" = 1 ]; then
    echo "DRY   $branch ready ($(git log --oneline -1)); not pushed, no PR (RUS_SYNC_DRY=1); report: $STATE/report.md"
    exit 0
  fi
  git push -q -u origin "$branch"
  body="$STATE/pr-body.md"
  { echo "Вливание их выпуска **$point** в \`main\` (rus/sync-merge.sh). Решение чата: $decision"
    echo; echo "Проверка этого PR — задача \`RUS sync\` (report). Отчёт до слияния:"; echo
    cat "$STATE/report.md"; } | head -c 60000 > "$body"
  url=$(gh pr create -R "$REPO" --base main --head "$branch" --title "sync: their $point" --body-file "$body")
  echo "$url"
  echo "PR    ${url##*/}  -> next: bash rus/sync-merge.sh finish ${url##*/}"
  ;;
finish)
  pr="${1:-}"; [[ "$pr" =~ ^[0-9]+$ ]] || die "usage: finish <PR number>"
  echo "wait  checks of PR #$pr (RUS sync takes 20-30 min)..."
  sleep 20
  if ! gh pr checks "$pr" -R "$REPO" --watch --interval 60; then
    gh pr checks "$pr" -R "$REPO" || true
    die "a check of PR #$pr is red: read the report on the run page; merge only by the owner's decision"
  fi
  gh pr merge "$pr" -R "$REPO" --merge
  new_wf=$(py -c "
import json; d=json.load(open('$STATE/report.json',encoding='utf-8')); print(' '.join(d.get('workflows_new') or []))" 2>/dev/null || true)
  for wf in $new_wf; do
    sleep 5
    gh workflow disable "$(basename "$wf")" -R "$REPO" && echo "OK    their new workflow $(basename "$wf") switched off"
  done
  git switch -q main
  git pull -q --ff-only origin main
  echo "OK    PR #$pr merged into main; local main = $(git rev-parse --short HEAD). RUS build runs on main now."
  ;;
*)
  sed -n '2,14p' "$0"; exit 1 ;;
esac
