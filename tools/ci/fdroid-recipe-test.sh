#!/usr/bin/env bash
#
# Proves fdroid-recipe.sh fails on the mutations that matter, because a gate nobody
# has seen fail is not a gate.
set -euo pipefail

SCRIPT="$(cd "$(dirname "$0")" && pwd)/fdroid-recipe.sh"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
fail=0

check() { # name, expected status (0 or 1), command...
    local name="$1" want="$2" got=0
    shift 2
    "$@" >"$work/out" 2>&1 || got=1
    if [ "$got" != "$want" ]; then
        echo "FAIL $name: wanted exit $want, got $got"
        sed 's/^/    /' "$work/out"
        fail=1
    else
        echo "ok   $name"
    fi
}

# A tree shaped like the real one: the recipe, app/build.gradle, the submodule
# sentinel fdroid-recipe.sh looks for, and one file of every kind that decides a case.
tree="$work/tree"
mkdir -p "$tree/app/src/main/jni/httrack/src" "$tree/tools/fdroid" \
    "$tree/app/src/main/jni/httrack/tests/fixtures" "$tree/gradle/wrapper"
: >"$tree/app/src/main/jni/httrack/src/htsglobal.h"
: >"$tree/app/src/main/jni/httrack/tests/fixtures/deleted.zip"
printf '\000\001\002binary seed' >"$tree/app/src/main/jni/httrack/tests/fixtures/seed_raw"
: >"$tree/gradle/wrapper/gradle-wrapper.jar"
printf 'GNU GENERAL PUBLIC LICENSE\n' >"$tree/LICENSE"
printf '        ndkVersion "27.3.13750724"\n' >"$tree/app/build.gradle"
cat >"$tree/tools/fdroid/recipe.yml" <<'YAML'
Builds:
  - subdir: app
    ndk: 27.3.13750724
    scandelete:
      - app/src/main/jni/httrack/tests/fixtures
    sudo:
      - true
    build:
      - echo $$NDK$$ > ndk-seen.txt
YAML
git -C "$tree" init -q
git -C "$tree" add -A

export ROOT="$tree" RECIPE="$tree/tools/fdroid/recipe.yml"
# The control also proves what must NOT fire: the wrapper jar and a text file with
# no extension are both fine, and so is a binary one under a scandelete path.
check "control: the recipe matches build.gradle" 0 bash "$SCRIPT" check-ndk
check "control: nothing the scanner rejects" 0 bash "$SCRIPT" scan
# An asserted value, because a build list that runs proves nothing about $$NDK$$:
# the substitution was once broken while this case still passed.
export ANDROID_HOME="$work/sdk"
check "control: the build list runs" 0 bash "$SCRIPT" run-build
check "control: \$\$NDK\$\$ became the NDK path" 0 \
    grep -qxF "$work/sdk/ndk/27.3.13750724" "$tree/app/ndk-seen.txt"

sed -i 's/ndk: 27.3.13750724/ndk: 26.1.10909125/' "$RECIPE"
check "a stale ndk in the recipe" 1 bash "$SCRIPT" check-ndk
sed -i 's/ndk: 26.1.10909125/ndk: 27.3.13750724/' "$RECIPE"

: >"$tree/app/src/main/jni/stray.jar"
git -C "$tree" add -A
check "a binary extension outside scandelete" 1 bash "$SCRIPT" scan
git -C "$tree" rm -q --cached app/src/main/jni/stray.jar

printf '\000\001\002raw' >"$tree/app/src/main/jni/seed_stray"
git -C "$tree" add -A
check "a binary file with no extension, as coucal ships" 1 bash "$SCRIPT" scan
git -C "$tree" rm -q --cached app/src/main/jni/seed_stray

printf '    scandelete:\n      - app/src/main/jni/httrack/tests/fixtures\n      - app/src/main/jni/httrack/src\n' >"$work/dels"
sed -i '/scandelete:/,/sudo:/{/scandelete:/r '"$work"'/dels
/scandelete:/,/sudo:/{/sudo:/!d}}' "$RECIPE"
check "a scandelete path that deletes nothing" 1 bash "$SCRIPT" scan
sed -i 's|^      - app/src/main/jni/httrack/src$|      - app/src/main/jni/httrack/gone|' "$RECIPE"
check "a scandelete path that is not in the tree" 1 bash "$SCRIPT" scan
sed -i '\|^      - app/src/main/jni/httrack/gone$|d' "$RECIPE"

rm "$tree/app/src/main/jni/httrack/src/htsglobal.h"
check "a scan that would see nothing" 1 bash "$SCRIPT" scan

exit "$fail"
