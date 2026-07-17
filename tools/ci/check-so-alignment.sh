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

# Cross-check the walk against the archive listing. find's exit status is invisible to set -e
# from inside a process substitution, so a truncated walk would silently check a subset and
# still report success; comparing counts asserts completeness, not merely non-emptiness.
listed="$(unzip -Z1 "$APK" 'lib/*.so' | wc -l)" ||
    {
        echo "check-so-alignment: cannot list $APK" >&2
        exit 1
    }
mapfile -t sos < <(find "$tmp/lib" -name '*.so' | sort)
if [ "$listed" -le 0 ] || [ "${#sos[@]}" -ne "$listed" ]; then
    echo "check-so-alignment: $APK lists $listed .so, walked ${#sos[@]}" >&2
    exit 1
fi

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
