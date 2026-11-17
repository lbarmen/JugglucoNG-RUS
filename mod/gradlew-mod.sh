#!/usr/bin/env bash
# JugglucoNGmod: the only way to run Gradle in this fork.
# Adds the init script that defines build type `mod` (upstream build files stay untouched).
# Usage: bash mod/gradlew-mod.sh :Common:assembleMobileMod -PmodRelease=0
#        bash mod/gradlew-mod.sh :Common:assembleWearMod -PmodRelease=0
#
# Wear tasks run in a call of their own with -Pandroid.nonFinalResIds=false (and the previous resource
# shrinker, below): the watch face reaches its
# color names and icons via `private const val X = R.string...` (ColorStyleIdAndResourceIds.kt). With
# AGP defaults these constants compile to 0 -> "String resource ID #0x0" on the watch (upstream 1.3.2
# wear has the same bug). Final ids of the real R class put the real ids into the code; the resources
# themselves are kept by Common/src/wearMod/res/raw/mod_keep.xml. The phone build is not affected.
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
    echo "gradlew-mod.sh: run wear tasks in a separate call (they need -Pandroid.nonFinalResIds=false)" >&2
    exit 2
  fi
  # Kotlin compiles against the compile-time R class (generateWearModRFile/R.jar), where every id is 0, and
  # inlines that 0 into the const vals; enableAppCompileTimeRClass=false compiles against the real R.jar.
  # AGP 9: final ids are allowed only with the previous resource shrinker ("Optimized resource shrinking
  # requires non-final IDs"); that shrinker also sees ids inlined in the code.
  extra=(-Pandroid.nonFinalResIds=false -Pandroid.enableAppCompileTimeRClass=false
         -Pandroid.r8.optimizedResourceShrinking=false)
fi
exec bash ./gradlew -I "$root/mod/mod-init.gradle" "${extra[@]}" "$@"
