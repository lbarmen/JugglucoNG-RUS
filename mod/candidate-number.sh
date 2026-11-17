#!/usr/bin/env bash
# JugglucoNGmod: is candidate number N still free for upstream version VERSION? mod-build.yml calls it twice: in
# candidate-precheck and again first thing in candidate-build ("Re-run failed jobs" / "Re-run this job" run the
# build again with the precheck of an earlier attempt). Owner's word 15.11: one number never gives two different APKs
# with one version (versionCode = their code * 100 + N). So N must be above
#   - every tag mod-VERSION-M;
#   - every other candidate run of VERSION that built its APKs (some attempt succeeded) or may still do so (not
#     finished: queued, building, waiting for approval). Candidates are found by their run name "MOD candidate N
#     blocks: ..." (run-name in mod-build.yml);
#   - for 1.3.2 also 2: candidates 1 and 2 were built under the former name (their tag was removed from GitHub
#     17.11.2026; candidates 3 of 15.11 were refused without APKs), so one number stays one APK in the owner's notes.
#     The run list shows the latest attempt only: for a red run with several attempts the earlier ones are asked.
#     The list holds the last 1000 manual runs (GitHub limit with an event filter, from memory): years away.
# No artifact mod-candidate-VERSION-N may exist in any run (APKs uploaded, whatever the run's end). And no earlier
# attempt of this very run may have succeeded: a re-run would build N again.
# Usage: bash mod/candidate-number.sh VERSION N
#   env GITHUB_REPOSITORY, GITHUB_RUN_ID, GITHUB_RUN_ATTEMPT, GH_TOKEN (actions: read); full history and tags here.
# Exit 0 with "OK: ...", or 1 with ::error:: lines.
set -euo pipefail
ver="${1:-}"; rel="${2:-}"
[ -n "$ver" ] || { echo "::error::candidate-number.sh: no version"; exit 1; }
[[ "$rel" =~ ^[1-9][0-9]?$ ]] || { echo "::error::release '$rel' is not 1..99"; exit 1; }
repo="$GITHUB_REPOSITORY"; me="$GITHUB_RUN_ID"

# $1 = run id, $2 = how many attempts to ask (1..$2); true if one of them succeeded. An API failure stops the
# script itself: inside an if, set -e would not.
attempt_succeeded() {
  local a c
  for ((a = 1; a <= $2; a++)); do
    c=$(gh api "repos/$repo/actions/runs/$1/attempts/$a" --jq .conclusion) || {
      echo "::error::GitHub API: attempt $a of run $1 unreadable"; exit 1; }
    [ "$c" != success ] || return 0
  done
  return 1
}

max=0; why="no tag or candidate of $ver yet"
if [ "$ver" = 1.3.2 ]; then max=2; why="candidates 1-2 built under the former name"; fi
for t in $(git tag -l "mod-$ver-*"); do
  m="${t##*-}"
  if [[ "$m" =~ ^[0-9]+$ ]] && [ "$m" -gt "$max" ]; then max="$m"; why="tag $t"; fi
done

runs=$(gh api --paginate "repos/$repo/actions/workflows/mod-build.yml/runs?event=workflow_dispatch&per_page=100" \
  --jq '.workflow_runs[] | select(.display_title | test("^MOD candidate [0-9]+ blocks: ")) |
        "\(.display_title | capture("^MOD candidate (?<n>[0-9]+) ").n) \(.head_sha) \(.id) \(.status) \(.conclusion) \(.run_attempt)"')
while read -r n sha id st concl att; do
  [ -n "$n" ] && [ "$id" != "$me" ] || continue
  [ "$n" -gt "$max" ] || continue   # cannot raise the bar: no need to look closer
  # its version = appVersionName in its commit; unknown (commit missing here) counts as this one
  v=""
  if git cat-file -e "$sha^{commit}" 2> /dev/null; then
    v=$(git show "$sha:Common/build.gradle" | sed -n "s/.*appVersionName *= *'\([^']*\)'.*/\1/p") || v=""
    v="${v%%$'\n'*}"; v="${v%%-*}"
  fi
  [ -z "$v" ] || [ "$v" = "$ver" ] || continue
  if [ "$st" != completed ]; then why="candidate $n (run $id, $st)"
  elif [ "$concl" = success ]; then why="candidate $n (run $id)"
  elif [ "$att" -gt 1 ] && attempt_succeeded "$id" $((att - 1)); then why="candidate $n (run $id, an earlier attempt)"
  else continue
  fi
  max="$n"
done <<< "$runs"

[ "$rel" -gt "$max" ] || {
  echo "::error::release $rel must be above $why: the same number again gives a second APK with the same version (pick a higher number; an unfinished run can be cancelled)"; exit 1; }
# APKs of this very number uploaded by any run, whatever its conclusion (cancelled right after the upload, a red
# step after it): the artifact stays listed, expired too. This run uploads only after this check.
arts=$(gh api "repos/$repo/actions/artifacts?name=mod-candidate-$ver-$rel&per_page=100" \
  --jq '.artifacts[] | "run \(.workflow_run.id)"')
[ -z "$arts" ] || {
  echo "::error::candidate $ver-$rel already has APKs (artifact mod-candidate-$ver-$rel, ${arts%%$'\n'*}): pick a higher number"; exit 1; }
if [ "${GITHUB_RUN_ATTEMPT:-1}" -gt 1 ] && attempt_succeeded "$me" $((GITHUB_RUN_ATTEMPT - 1)); then
  echo "::error::an earlier attempt of this run already built candidate $rel: start a new run with a higher number"; exit 1
fi
echo "OK: release $rel is above $why"
