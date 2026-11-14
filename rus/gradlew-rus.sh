#!/usr/bin/env bash
# JugglucoNG-RUS: the only way to run Gradle in this fork.
# Adds the init script that defines build type `rus` (upstream build files stay untouched).
# Usage: bash rus/gradlew-rus.sh :Common:assembleMobileRus :Common:assembleWearRus -PrusRelease=0
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
cd "$root"
exec bash ./gradlew -I "$root/rus/rus-init.gradle" "$@"
