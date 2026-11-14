#!/usr/bin/env bash
# JugglucoNG-RUS: the only way to run Gradle in this fork.
# Adds the init script that defines build type `rus` (upstream build files stay untouched).
# Usage: bash rus/gradlew-rus.sh :Common:assembleMobileRus -PrusRelease=0
#        bash rus/gradlew-rus.sh :Common:assembleWearRus -PrusRelease=0
#
# Wear tasks run in a call of their own with -Pandroid.nonFinalResIds=false: the watch face reaches its
# color names and icons via `private const val X = R.string...` (ColorStyleIdAndResourceIds.kt). With
# non-final R ids (AGP default) these constants compile to 0 -> "String resource ID #0x0" on the watch
# (upstream 1.3.2 wear has the same bug). Final ids put the real ids into the code; the resources
# themselves are kept by Common/src/wearRus/res/raw/rus_keep.xml. The phone build is not affected.
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
cd "$root"

wear=0; other=0
for a in "$@"; do
  case "$a" in
    -*) ;;
    *Wear*) wear=1 ;;
    *) other=1 ;;
  esac
done
extra=()
if [ "$wear" = 1 ]; then
  if [ "$other" = 1 ]; then
    echo "gradlew-rus.sh: run wear tasks in a separate call (they need -Pandroid.nonFinalResIds=false)" >&2
    exit 2
  fi
  extra=(-Pandroid.nonFinalResIds=false)
fi
exec bash ./gradlew -I "$root/rus/rus-init.gradle" "${extra[@]}" "$@"
