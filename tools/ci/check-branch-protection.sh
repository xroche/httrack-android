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
# find's status is lost through a process substitution, so a directory it could
# not read would silently shrink the walk to the ones it could.
listing="$(mktemp)"
trap 'rm -f "$listing"' EXIT
find "$LIBS/$ABI" -type f -name '*.so' >"$listing" || {
    echo "check-branch-protection: cannot walk $LIBS/$ABI" >&2
    exit 1
}
mapfile -t sos <"$listing"
# An empty walk would pass every check below, so name that case rather than report success.
[ "${#sos[@]}" -gt 0 ] || {
    echo "check-branch-protection: no .so under $LIBS/$ABI" >&2
    exit 1
}

# Count a mnemonic in the instruction column only. objdump prints the file path
# and every branch target, so an unanchored match counts a directory name or a
# symbol called paciasp as if it were a signed function.
count_insn() {
    printf '%s\n' "$2" | grep -cE "^[[:space:]]*[0-9a-f]+:.*[[:space:]]$1([[:space:]]|$)" || true
}

rc=0
for so in "${sos[@]}"; do
    name="$(basename "$so")"

    # Capture once so a failed objdump does not read as a zero-match pass.
    dis="$("$OBJDUMP" -d "$so")" || {
        echo "check-branch-protection: cannot disassemble $name" >&2
        exit 1
    }

    # paciasp and bti are hints, so an ARMv8.0 core runs them as a NOP. retaa and
    # retab are not, and fault there. Clang emits them once -march reaches armv8.3-a.
    bad=$(($(count_insn retaa "$dis") + $(count_insn retab "$dis")))
    if [ "$bad" -ne 0 ]; then
        echo "FAIL $name: $bad retaa/retab, which fault on an ARMv8.0 core"
        rc=1
    fi

    # Without this the note check passes a library that carries the note and signs
    # nothing, which is the regression it exists to catch.
    if [ "$(count_insn paciasp "$dis")" -eq 0 ]; then
        echo "FAIL $name: no paciasp, so no return address is signed"
        rc=1
    fi

    # The linker ANDs this note over every input, so its absence means some input
    # skipped the flag.
    mapfile -t feat < <("$READELF" -n "$so" | grep -i 'aarch64 feature' || true)
    if [ "${#feat[@]}" -eq 0 ]; then
        echo "FAIL $name: no AArch64 feature note, so BTI is not enforced"
        rc=1
    elif [ "${#feat[@]}" -ne 1 ]; then
        # Two notes would let a good line cover for a bad one under one match.
        echo "FAIL $name: ${#feat[@]} AArch64 feature notes, want exactly one"
        rc=1
    else
        case "${feat[0]}" in
        *BTI*PAC* | *PAC*BTI*) ;;
        *)
            echo "FAIL $name: feature note lacks BTI or PAC (${feat[0]})"
            rc=1
            ;;
        esac
    fi
done

[ "$rc" -eq 0 ] && echo "branch protection: ${#sos[@]} $ABI .so carry BTI and PAC, none use retaa"
exit "$rc"
