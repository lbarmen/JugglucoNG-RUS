#!/usr/bin/env bash
# JugglucoNG-RUS: merge an upstream release into main through a PR sync/<release> (plan of stage 2, D1).
# Run from the computer (Git Bash) by the sync chat, after reading the morning report on GitHub.
#
#   bash rus/sync-merge.sh prepare --decision "<text>" --minutes N [--point <tag|commit>]
#       clean tree needed; rerere on; their latest release without the pre-release mark (another point only by
#       the owner's word); branch sync/<release> from origin/main (an existing one is never reset); merge (rerere
#       replays known resolutions; a conflict left -> stop with the list of files); check-hooks, check-secrets
#       --ours, sync-report; a line in rus/SYNC-LOG.md; push the branch and open the PR with the report.
#   bash rus/sync-merge.sh continue --decision "<text>" --minutes N [--conflicts N]
#       the same from the checks on, on sync/<release>: after conflicts resolved by hand or a fix committed for
#       red checks (alias: prepare-after-conflict). Not after the push: then fix, commit, git push, finish.
#   bash rus/sync-merge.sh finish <PR>
#       waits for the PR checks, report green -> switches off their new workflows, merges with a merge commit
#       (never squash/rebase, ТЗ 3.2), switches them off again and cancels their runs; updates local main.
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
  # never reset an existing branch: it may hold a resolution or a fix (DRY probe: delete it, git branch -D)
  git rev-parse -q --verify "refs/heads/$branch" > /dev/null &&
    die "branch $branch already exists: continue on it (bash rus/sync-merge.sh continue ...) or delete it: git branch -D $branch"
  git switch -q -c "$branch" origin/main
  # conflicts git sees before rerere (for SYNC-LOG); rerere may then resolve them from memory
  conflicts=0
  if ! out=$(git merge-tree --write-tree --name-only --no-messages origin/main "$theirs"); then
    conflicts=$(printf '%s\n' "$out" | tail -n +2 | grep -c . || true)
  fi
  if ! git merge --no-ff --no-edit -m "RUS sync: merge their $point into main" "$theirs"; then
    git rev-parse -q --verify MERGE_HEAD > /dev/null || die "the merge failed before any conflict (see git above)"
    left=$(git diff --name-only --diff-filter=U)
    if [ -n "$left" ]; then
      echo "CONFLICT in:"; printf '%s\n' "$left" | sed 's/^/  /'
      die "resolve these files by hand (rerere remembers the resolution), git add them, git commit --no-edit,
      then: bash rus/sync-merge.sh continue --decision \"...\" --minutes N --conflicts $conflicts"
    fi
    echo "OK    rerere resolved all $conflicts conflict(s) from an earlier resolution"
    git commit -q --no-edit
  fi
  exec bash rus/sync-merge.sh checks-and-pr "$point" "$old" "$theirs" "$decision" "$minutes" "$conflicts"
  ;;
continue|prepare-after-conflict)
  # on sync/<release>: the merge is committed (by hand after a conflict), maybe fix commits on top of it
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
  [ -z "$(git diff --name-only --diff-filter=U)" ] || die "unresolved files left"
  [ -z "$(git status --porcelain --untracked-files=no)" ] || die "working tree has changes: commit them first"
  git ls-remote --exit-code --heads origin "refs/heads/$branch" > /dev/null 2>&1 &&
    die "$branch is already on GitHub (PR open): fix, commit, git push, then finish <PR>"
  git fetch -q origin main
  # the sync merge = the oldest merge commit on the first-parent line above main
  m=$(git rev-list --merges --first-parent origin/main..HEAD | tail -1)
  [ -n "$m" ] || die "no merge commit on this branch above main (commit the resolved merge first)"
  point="${branch#sync/}"
  theirs=$(git rev-parse "$m^2")
  old=$(git merge-base origin/main upstream/main)
  [ "$conflicts" = "?" ] || conflicts="$conflicts (вручную)"
  exec bash rus/sync-merge.sh checks-and-pr "$point" "$old" "$theirs" "$decision" "$minutes" "$conflicts"
  ;;
checks-and-pr)
  point="$1"; old="$2"; theirs="$3"; decision="$4"; minutes="$5"; conflicts="$6"
  mkdir -p "$STATE"
  fail=0
  py rus/check-hooks.py --ref HEAD --base "$theirs" || fail=1
  bash rus/check-secrets.sh --ours "$theirs" || fail=1
  py rus/sync-report.py "$old" "$theirs" --ours origin/main --out "$STATE/report.md" --json "$STATE/report.json"
  [ $fail = 0 ] || die "checks are red: fix on this branch, commit, then bash rus/sync-merge.sh continue ... (nothing pushed)"
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
    echo "      delete the probe branch before the real prepare: git switch main && git branch -D $branch"
    exit 0
  fi
  body="$STATE/pr-body.md"
  { echo "Вливание их выпуска **$point** в \`main\` (rus/sync-merge.sh). Решение чата: $decision"
    echo; echo "Проверка этого PR — задача \`RUS sync\` (report). Отчёт до слияния:"; echo
    cat "$STATE/report.md"; } > "$body.full"
  # a PR body holds 65536 characters: cut at a whole line, in Python (no pipe, no cut inside a letter)
  py -c "import sys; t = open(sys.argv[1], encoding='utf-8').read(); n = 60000; t = t if len(t) <= n else t[:t.rfind(chr(10), 0, n) + 1] + '… (отчёт обрезан: полный — на странице запуска RUS sync)' + chr(10); open(sys.argv[2], 'w', encoding='utf-8', newline='').write(t)" "$body.full" "$body"
  git push -q -u origin "$branch"
  url=$(gh pr create -R "$REPO" --base main --head "$branch" --title "sync: their $point" --body-file "$body")
  echo "$url"
  echo "PR    ${url##*/}  -> next: bash rus/sync-merge.sh finish ${url##*/}"
  ;;
finish)
  pr="${1:-}"; [[ "$pr" =~ ^[0-9]+$ ]] || die "usage: finish <PR number>"
  read -r head sha < <(gh pr view "$pr" -R "$REPO" --json headRefName,headRefOid --jq '"\(.headRefName) \(.headRefOid)"')
  [[ "$head" == sync/* ]] || die "PR #$pr is not a sync/<release> PR (its branch: $head)"
  # their new workflows = workflow files the branch adds (not ours): straight from git, no saved state
  git fetch -q origin main "+refs/heads/$head:refs/remotes/origin/$head"
  new_wf=$(git diff --name-only --diff-filter=A "origin/main...$sha" -- .github/workflows | grep -v '^\.github/workflows/rus-' || true)
  echo "wait  checks of PR #$pr (RUS sync takes 20-30 min)..."
  n=0
  for i in $(seq 1 30); do
    n=$(gh pr view "$pr" -R "$REPO" --json statusCheckRollup --jq '.statusCheckRollup | length')
    [ "$n" -gt 0 ] && break
    sleep 20
  done
  [ "$n" -gt 0 ] || die "no checks on PR #$pr after 10 min: open the PR page"
  if ! gh pr checks "$pr" -R "$REPO" --watch --interval 60; then
    gh pr checks "$pr" -R "$REPO" || true
    die "a check of PR #$pr is red: read the report on the run page; merge only by the owner's decision"
  fi
  r=$(gh pr view "$pr" -R "$REPO" --json statusCheckRollup --jq \
    '[.statusCheckRollup[] | select(.name == "report") | .conclusion] | if length > 0 and all(. == "SUCCESS") then "SUCCESS" else (join(" ") | if . == "" then "none" else . end) end')
  [ "$r" = SUCCESS ] || die "the required check report of PR #$pr is '$r', not SUCCESS"
  # before the merge: GitHub may already know the file from the branch push (if not, after the merge below)
  for wf in $new_wf; do
    gh workflow disable "$(basename "$wf")" -R "$REPO" > /dev/null 2>&1 && echo "OK    their new workflow $(basename "$wf") switched off before the merge" || true
  done
  gh pr merge "$pr" -R "$REPO" --merge --match-head-commit "$sha"
  for wf in $new_wf; do
    w=$(basename "$wf")
    for i in 1 2 3 4 5 6; do
      [ "$(gh api "repos/$REPO/actions/workflows/$w" --jq .state 2>/dev/null || true)" = disabled_manually ] && break
      gh workflow disable "$w" -R "$REPO" > /dev/null 2>&1 || sleep 10
    done
    [ "$(gh api "repos/$REPO/actions/workflows/$w" --jq .state 2>/dev/null || true)" = disabled_manually ] ||
      die "their new workflow $w is NOT switched off: gh workflow disable $w -R $REPO, then cancel its runs"
    for id in $(gh run list -w "$w" -R "$REPO" --json databaseId,status --jq '.[] | select(.status != "completed") | .databaseId'); do
      gh run cancel "$id" -R "$REPO" > /dev/null && echo "OK    run $id of their $w cancelled"
    done
    echo "OK    their new workflow $w switched off"
  done
  git switch -q main
  git pull -q --ff-only origin main
  echo "OK    PR #$pr merged into main; local main = $(git rev-parse --short HEAD). RUS build runs on main now."
  ;;
*)
  sed -n '2,18p' "$0"; exit 1 ;;
esac
