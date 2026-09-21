#!/usr/bin/env bash
#
# Exercise check-branch-protection.sh against stub tools, so the failure cases run
# without a toolchain. A real build only ever produces the passing case.
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
SUT="$HERE/check-branch-protection.sh"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

# Both stubs read the fixture's name. Suffixes: "unsigned" emits no paciasp,
# "retaa" and "retab" add that instruction, "symref" names a symbol paciasp
# without signing anything, "nonote", "paconly", "btionly" and "twonotes" set
# the feature note, "objfail" makes the disassembler exit non-zero. A name
# matching none of them is the clean baseline. Both stubs copy the real tools'
# layout, down to the path header and the lowercase "aarch64 feature", because
# a stub that prints what the script expects cannot catch the script expecting
# the wrong thing.
mkdir -p "$tmp/bin"
cat >"$tmp/bin/llvm-objdump" <<'STUB'
#!/usr/bin/env bash
so="${!#}"
case "$(basename "$so")" in
*objfail*) echo "llvm-objdump: error: unknown format" >&2; exit 1 ;;
esac
printf '\n%s:\tfile format elf64-littleaarch64\n\nDisassembly of section .text:\n\n' "$so"
echo "0000000000001000 <leaf>:"
case "$(basename "$so")" in
*unsigned* | *symref*) ;;
*) echo "    1000: d503233f    	paciasp" ;;
esac
case "$(basename "$so")" in
*retaa*) echo "    1004: d65f0bff    	retaa" ;;
*retab*) echo "    1004: d65f0fff    	retab" ;;
*) echo "    1004: d65f03c0    	ret" ;;
esac
# A call to a symbol named paciasp, which an unanchored match would count.
case "$(basename "$so")" in
*symref*) echo "    1008: 97fffffe    	bl	0x0 <paciasp>" ;;
esac
STUB
cat >"$tmp/bin/llvm-readelf" <<'STUB'
#!/usr/bin/env bash
echo "Displaying notes found in: .note.gnu.property"
echo "  Owner                Data size 	Description"
echo "  GNU                  0x00000010	NT_GNU_PROPERTY_TYPE_0 (property note)"
case "$(basename "${!#}")" in
*-nonote.so) ;;
*-paconly.so) echo "    Properties:    aarch64 feature: PAC" ;;
*-btionly.so) echo "    Properties:    aarch64 feature: BTI" ;;
*-twonotes.so)
    echo "    Properties:    aarch64 feature: BTI, PAC"
    echo "    Properties:    aarch64 feature: PAC"
    ;;
*) echo "    Properties:    aarch64 feature: BTI, PAC" ;;
esac
STUB
chmod +x "$tmp/bin/llvm-objdump" "$tmp/bin/llvm-readelf"
export PATH="$tmp/bin:$PATH"

fail=0
run() { # run <want-rc> <case-name> <so-name...>
    local want="$1" name="$2" out rc=0
    shift 2
    rm -rf "$tmp/libs"
    mkdir -p "$tmp/libs/arm64-v8a"
    for so in "$@"; do : >"$tmp/libs/arm64-v8a/$so"; done
    out="$(bash "$SUT" "$tmp/libs" 2>&1)" || rc=$?
    if [ "$rc" -ne "$want" ]; then
        echo "FAIL $name: want rc=$want got rc=$rc"
        echo "$out"
        fail=1
    fi
}

run 0 "marked, no retaa" good-ok.so other-ok.so
run 1 "retaa faults on ARMv8.0" good-ok.so bad-retaa-ok.so
run 1 "retab faults too" bad-retab-ok.so
run 1 "no feature note" good-ok.so bad-nonote.so
run 1 "note without BTI" bad-paconly.so
run 1 "note without PAC" bad-btionly.so
run 1 "marked but nothing signed" bad-unsigned.so
# objdump prints the path and every branch target, so an unanchored match would
# count a symbol named paciasp as a signed function.
run 1 "paciasp only as a symbol name" bad-symref.so
# One good note must not cover for a bad one.
run 1 "two feature notes" bad-twonotes.so
# A tool that fails must stop the run for that reason, not slide into the
# paciasp check and fail there by accident.
run 1 "disassembler fails" bad-objfail.so
rm -rf "$tmp/libs"
mkdir -p "$tmp/libs/arm64-v8a"
: >"$tmp/libs/arm64-v8a/bad-objfail.so"
case "$(bash "$SUT" "$tmp/libs" 2>&1)" in
*"cannot disassemble"*) ;;
*)
    echo "FAIL disassembler message: want the run to stop on the tool"
    fail=1
    ;;
esac

# The pass line says how many libraries were checked, so a silently shrinking
# walk cannot read as a clean run.
rm -rf "$tmp/libs"
mkdir -p "$tmp/libs/arm64-v8a"
: >"$tmp/libs/arm64-v8a/a-ok.so"
: >"$tmp/libs/arm64-v8a/b-ok.so"
case "$(bash "$SUT" "$tmp/libs" 2>&1)" in
*"2 arm64-v8a .so"*) ;;
*)
    echo "FAIL pass line: want the count of libraries checked"
    fail=1
    ;;
esac

# An empty or absent directory must fail, or the check reports success on a build
# that produced no libraries at all.
rm -rf "$tmp/libs"
mkdir -p "$tmp/libs/arm64-v8a"
bash "$SUT" "$tmp/libs" >/dev/null 2>&1 && {
    echo "FAIL empty dir: want failure"
    fail=1
}
rm -rf "$tmp/libs"
mkdir -p "$tmp/libs"
bash "$SUT" "$tmp/libs" >/dev/null 2>&1 && {
    echo "FAIL absent abi dir: want failure"
    fail=1
}

# A missing tool must stop the run rather than skip the library.
rm -rf "$tmp/libs"
mkdir -p "$tmp/libs/arm64-v8a"
: >"$tmp/libs/arm64-v8a/a-ok.so"
PATH="/nonexistent" ANDROID_NDK_ROOT=/nonexistent bash "$SUT" "$tmp/libs" >/dev/null 2>&1 && {
    echo "FAIL missing tools: want failure"
    fail=1
}

[ "$fail" -eq 0 ] && echo "check-branch-protection-test: all cases pass"
exit "$fail"
