#!/usr/bin/env bash
#
# Fail if an arm64 .so lost its branch protection, or gained an instruction that
# faults on ARMv8.0. Both regressions are silent: the build stays green and the
# libraries still load.
set -euo pipefail

LIBS="${1:?usage: $0 <libs-dir>}"
ABI=arm64-v8a

# The toolchain image carries the NDK but no binutils, so fall back to its llvm tools.
find_tool() {
    local name="$1" found
    found="$(command -v "$name" || true)"
    if [ -z "$found" ]; then
        for cand in "${ANDROID_NDK_ROOT:-${ANDROID_NDK_HOME:-/nonexistent}}"/toolchains/llvm/prebuilt/*/bin/"$name"; do
            [ -x "$cand" ] && found="$cand" && break
        done
    fi
    [ -n "$found" ] || {
        echo "check-branch-protection: no $name found" >&2
        exit 1
    }
    echo "$found"
}
READELF="$(find_tool llvm-readelf)"
OBJDUMP="$(find_tool llvm-objdump)"

[ -d "$LIBS/$ABI" ] || {
    echo "check-branch-protection: no $LIBS/$ABI directory" >&2
    exit 1
}
mapfile -t sos < <(find "$LIBS/$ABI" -type f -name '*.so' | sort)
# An empty walk would pass every check below, so name that case rather than report success.
[ "${#sos[@]}" -gt 0 ] || {
    echo "check-branch-protection: no .so under $LIBS/$ABI" >&2
    exit 1
}

rc=0
for so in "${sos[@]}"; do
    name="$(basename "$so")"

    # paciasp and bti are hints, so an ARMv8.0 core runs them as a NOP. retaa and
    # retab are not, and fault there. Clang emits them once -march reaches armv8.3-a.
    bad="$("$OBJDUMP" -d "$so" | grep -cwE 'retaa|retab' || true)"
    if [ "$bad" -ne 0 ]; then
        echo "FAIL $name: $bad retaa/retab, which fault on an ARMv8.0 core"
        rc=1
    fi

    # The linker ANDs this note over every input, so its absence means some input
    # was built without the flag. Without it the loader never guards the pages.
    feat="$("$READELF" -n "$so" | grep -i 'aarch64 feature' || true)"
    case "$feat" in
    *BTI*PAC* | *PAC*BTI*) ;;
    "")
        echo "FAIL $name: no AArch64 feature note, so BTI is not enforced"
        rc=1
        ;;
    *)
        echo "FAIL $name: feature note lacks BTI or PAC ($feat)"
        rc=1
        ;;
    esac
done

[ "$rc" -eq 0 ] && echo "branch protection: ${#sos[@]} $ABI .so carry BTI and PAC, none use retaa"
exit "$rc"
