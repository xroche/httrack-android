#!/usr/bin/env bash
#
# Fail if any packaged .so has a LOAD segment aligned below 16 KB. Play rejects those
# at targetSdk 35+, and nothing in a normal build says so — the regression is silent.
set -euo pipefail

APK="${1:?usage: $0 <apk>}"
WANT=16384

# The toolchain image carries the NDK but no binutils, so fall back to its llvm-readelf.
READELF="$(command -v llvm-readelf || command -v readelf || true)"
if [ -z "$READELF" ]; then
    for cand in "${ANDROID_NDK_ROOT:-${ANDROID_NDK_HOME:-/nonexistent}}"/toolchains/llvm/prebuilt/*/bin/llvm-readelf; do
        [ -x "$cand" ] && READELF="$cand" && break
    done
fi
[ -n "$READELF" ] || {
    echo "check-so-alignment: no readelf/llvm-readelf found" >&2
    exit 1
}

tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
unzip -q -o "$APK" 'lib/*' -d "$tmp"

mapfile -t sos < <(find "$tmp/lib" -name '*.so' | sort)
# An empty list would otherwise "pass" every check below.
[ "${#sos[@]}" -gt 0 ] || {
    echo "check-so-alignment: no .so inside $APK" >&2
    exit 1
}

rc=0
for so in "${sos[@]}"; do
    name="${so#"$tmp"/}"
    aligns="$("$READELF" -lW "$so" | awk '$1 == "LOAD" { print $NF }')"
    [ -n "$aligns" ] || {
        echo "check-so-alignment: no LOAD segments in $name" >&2
        exit 1
    }
    for a in $aligns; do
        if [ "$((a))" -lt "$WANT" ]; then
            echo "FAIL $name: LOAD p_align $a < $WANT"
            rc=1
        fi
    done
done

[ "$rc" -eq 0 ] && echo "16 KB aligned: ${#sos[@]} .so in $(basename "$APK")"
exit "$rc"
