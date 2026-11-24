#!/usr/bin/env bash
#
# Performs the parts of an F-Droid build this repository can perform itself, reading
# them out of tools/fdroid/com.httrack.android.yml. The `fdroid` CI job calls this
# rather than restating the recipe, because a job that restates it goes green on a
# recipe F-Droid cannot build.
set -euo pipefail

ROOT="${ROOT:-$(cd "$(dirname "$0")/../.." && pwd)}"
RECIPE="${RECIPE:-$ROOT/tools/fdroid/com.httrack.android.yml}"

usage() {
    echo "usage: $0 {ndk|subdir|check-ndk|scan|run-sudo|run-build}" >&2
    exit 2
}

# One Builds[0] field, a list printing one item per line.
field() {
    python3 -c '
import sys, yaml
build = yaml.safe_load(open(sys.argv[1]))["Builds"][0]
value = build.get(sys.argv[2], "")
print("\n".join(value) if isinstance(value, list) else value)
' "$RECIPE" "$1"
}

# What app/build.gradle says, so the recipe can be checked against it.
gradle_field() {
    sed -n "s/^ *$1 \"\(.*\)\".*/\1/p" "$ROOT/app/build.gradle"
}

check_ndk() {
    local want found
    want="$(gradle_field ndkVersion)"
    found="$(field ndk)"
    if [ -z "$want" ]; then
        echo "no ndkVersion in app/build.gradle" >&2
        exit 1
    fi
    if [ "$want" != "$found" ]; then
        echo "the recipe says ndk: $found, app/build.gradle says ndkVersion $want" >&2
        exit 1
    fi
    echo "ndk $found"
}

# fdroidserver scans a fresh clone before it builds, so read the clone from git and
# never the working tree, where our own build output would hide a real offender. The
# rules below mirror fdroidserver/scanner.py, including its three error sources: a
# file it rejects, a scandelete path that is missing, and one that deletes nothing.
scan() {
    if [ ! -r "$ROOT/app/src/main/jni/httrack/src/htsglobal.h" ]; then
        echo "the httrack submodule is not checked out, so this scan would see nothing" >&2
        exit 1
    fi
    field scandelete | python3 -c '
import os, re, subprocess, sys

ROOT = sys.argv[1]
# scanner.py: fatal extensions, versioned shared objects, and any extensionless,
# .bin, .out or .exe file whose first 1024 bytes are not text.
FATAL = (".a", ".aar", ".class", ".dex", ".gz", ".tgz", ".zip", ".jar", ".wasm")
SO = re.compile(r".*\.so(\..+)*$")
MAYBE_BINARY = ("", ".bin", ".out", ".exe")
TEXT = bytearray({7, 8, 9, 10, 12, 13, 27} | set(range(0x20, 0x100)) - {0x7F})
# scanner.py removes these itself rather than counting them.
IGNORED = ("gradle/wrapper/gradle-wrapper.jar", "gradlew", "gradlew.bat")

def is_binary(path):
    with open(path, "rb") as f:
        return bool(f.read(1024).translate(None, TEXT))

def rejected(rel):
    if rel in IGNORED:
        return False
    if rel.endswith(FATAL) or SO.match(rel):
        return True
    if os.path.splitext(rel)[1] in MAYBE_BINARY:
        full = os.path.join(ROOT, rel)
        return os.path.isfile(full) and is_binary(full)
    return False

deletes = [line.strip() for line in sys.stdin if line.strip()]
listing = subprocess.run(
    ["git", "-C", ROOT, "ls-files", "--recurse-submodules"],
    capture_output=True, text=True)
if listing.returncode:
    print(listing.stderr.strip(), file=sys.stderr)
    sys.exit(1)
tracked = listing.stdout.splitlines()

used, offenders = set(), []
for rel in tracked:
    if not rejected(rel):
        continue
    covered = [d for d in deletes if rel.startswith(d)]
    if covered:
        used.update(covered)
    else:
        offenders.append(rel)

errors = ["the scanner rejects %s, and scandelete does not cover it" % o for o in offenders]
errors += ["scandelete names %s, which is not in the tree" % d
           for d in deletes if not os.path.exists(os.path.join(ROOT, d))]
errors += ["scandelete names %s, which deletes nothing, and fdroidserver counts that as an error" % d
           for d in deletes if d not in used and os.path.exists(os.path.join(ROOT, d))]
if errors:
    print("\n".join(errors), file=sys.stderr)
    sys.exit(1)
print("nothing the scanner rejects, and every scandelete path earns its place")
' "$ROOT"
}

# $$NDK$$ is fdroidserver's own substitution, so the commands run as F-Droid runs them.
run_list() {
    local cmds
    cmds="$(field "$1" | sed "s|\$\$NDK\$\$|${ANDROID_HOME:-}/ndk/$(field ndk)|g")"
    [ -n "$cmds" ] || return 0
    cd "$2"
    while IFS= read -r cmd; do
        [ -n "$cmd" ] || continue
        echo "+ $cmd"
        bash -euo pipefail -c "$cmd"
    done <<<"$cmds"
}

case "${1-}" in
ndk | subdir) field "$1" ;;
check-ndk) check_ndk ;;
scan) scan ;;
run-sudo) run_list sudo "$ROOT" ;;
run-build) run_list build "$ROOT/$(field subdir)" ;;
*) usage ;;
esac
