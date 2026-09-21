#!/usr/bin/env bash
#
# Exercise check-branch-protection.sh against stub tools, so the failure cases run
# without a toolchain. A real build only ever produces the passing case.
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
SUT="$HERE/check-branch-protection.sh"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

# Both stubs read the fixture's name, so a case needs no real ELF. "retaa" in the
# name means the disassembly carries one; the feature note comes from the suffix.
mkdir -p "$tmp/bin"
cat >"$tmp/bin/llvm-objdump" <<'STUB'
#!/usr/bin/env bash
echo "   0: d503233f     paciasp"
case "$(basename "${!#}")" in
*retaa*) echo "   4: d65f0bff     retaa" ;;
*) echo "   4: d65f03c0     ret" ;;
esac
STUB
cat >"$tmp/bin/llvm-readelf" <<'STUB'
#!/usr/bin/env bash
echo "Displaying notes found in: .note.gnu.property"
case "$(basename "${!#}")" in
*-nonote.so) ;;
*-paconly.so) echo "    AArch64 feature: PAC" ;;
*-btionly.so) echo "    AArch64 feature: BTI" ;;
*) echo "    AArch64 feature: BTI, PAC" ;;
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
run 1 "no feature note" good-ok.so bad-nonote.so
run 1 "note without BTI" bad-paconly.so
run 1 "note without PAC" bad-btionly.so

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

[ "$fail" -eq 0 ] && echo "check-branch-protection-test: all cases pass"
exit "$fail"
