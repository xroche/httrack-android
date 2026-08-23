#!/usr/bin/env bash
#
# Exercise check-so-alignment.sh on both archive layouts. CI builds no .aab, so
# without this the AAB branch is first exercised by a real release.
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
SUT="$HERE/check-so-alignment.sh"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

OK=4000   # 16 KB, the minimum Play accepts
LOW=1000  # 4 KB
NEAR=2000 # 8 KB: still short, and pins the threshold against a halved WANT

# Stand in for llvm-readelf so the fixtures need no toolchain and no real ELF.
# Alignment comes from the file name; the non-LOAD segments are what real output
# carries and what makes the LOAD filter load-bearing.
mkdir -p "$tmp/bin"
cat >"$tmp/bin/llvm-readelf" <<'STUB'
#!/usr/bin/env bash
echo "  DYNAMIC        0x000000 0x0 0x0 0x10 0x10 RW  0x8"
echo "  NOTE           0x000000 0x0 0x0 0x10 0x10 R   0x4"
for a in $(basename "${!#}" .so | tr '-' ' '); do
    case "$a" in
    [0-9a-f]*) echo "  LOAD           0x000000 0x0 0x0 0x10 0x10 R E 0x$a" ;;
    esac
done
echo "  GNU_EH_FRAME   0x000000 0x0 0x0 0x10 0x10 R   0x4"
STUB
chmod +x "$tmp/bin/llvm-readelf"
export PATH="$tmp/bin:$PATH"

# Each .so is named for the p_align values its LOAD segments report, in hex,
# separated by '-'. A name with no hex digits reports no LOAD segments at all.
mkzip() {
    local out="$1" dir="$2"
    shift 2
    rm -rf "$tmp/stage"
    mkdir -p "$tmp/stage/${dir:-.}"
    if [ "$#" -eq 0 ]; then
        touch "$tmp/stage/readme.txt"
    else
        local n i=0
        for n in "$@"; do
            # One ABI directory each, so two libs of equal alignment stay two files.
            mkdir -p "$tmp/stage/$dir/abi$i"
            touch "$tmp/stage/$dir/abi$i/$n.so"
            i=$((i + 1))
        done
    fi
    (cd "$tmp/stage" && zip -qr "$out" .)
}

fail=0
# want: expected exit code. pattern: text the run must print, so a case cannot
# pass merely by failing for some unrelated reason.
check() {
    local label="$1" want="$2" pattern="$3" archive="$4"
    local got=0 out
    out="$("$SUT" "$archive" 2>&1)" || got=$?
    if [ "$got" -ne "$want" ]; then
        echo "FAIL $label: want exit $want, got $got"
        fail=1
    elif ! printf '%s' "$out" | grep -q -- "$pattern"; then
        echo "FAIL $label: output did not match '$pattern'"
        printf '   got: %s\n' "$out"
        fail=1
    else
        echo "ok   $label"
    fi
}

mkzip "$tmp/apk-ok.apk" lib $OK
check "apk layout, aligned" 0 "1 .so" "$tmp/apk-ok.apk"

mkzip "$tmp/aab-ok.aab" base/lib $OK
check "aab layout, aligned" 0 "1 .so" "$tmp/aab-ok.aab"

mkzip "$tmp/apk-low.apk" lib $LOW
check "apk below 16 KB" 1 "p_align" "$tmp/apk-low.apk"

mkzip "$tmp/near.aab" base/lib $NEAR
check "8 KB is still short" 1 "p_align" "$tmp/near.aab"

# The regression the deleted inline check could not see, in both orderings.
mkzip "$tmp/hi-lo.aab" base/lib "$OK-$LOW"
check "aab, later LOAD short" 1 "p_align" "$tmp/hi-lo.aab"
mkzip "$tmp/lo-hi.aab" base/lib "$LOW-$OK"
check "aab, earlier LOAD short" 1 "p_align" "$tmp/lo-hi.aab"

# One bad lib among several must fail: the check is per-.so, not per-archive.
mkzip "$tmp/two.aab" base/lib $OK $LOW
check "second of two libs short" 1 "p_align" "$tmp/two.aab"
mkzip "$tmp/two-ok.aab" base/lib $OK $OK
check "both libs aligned" 0 "2 .so" "$tmp/two-ok.aab"

mkzip "$tmp/noload.aab" base/lib "none"
check "no LOAD segments" 1 "no LOAD segments" "$tmp/noload.aab"

mkzip "$tmp/empty.apk" lib
check "apk with no .so" 1 "no lib/" "$tmp/empty.apk"
mkzip "$tmp/empty.aab" base/lib
check "aab with no .so" 1 "no base/lib/" "$tmp/empty.aab"

# An .aab read as an apk finds nothing, so layout detection must not pass it.
cp "$tmp/aab-ok.aab" "$tmp/mislabelled.apk"
check "aab named .apk" 1 "no lib/" "$tmp/mislabelled.apk"

check "missing file" 1 "cannot extract" "$tmp/nope.apk"

# The listing-versus-walk cross-check: two entries, one file on disk.
if command -v python3 >/dev/null; then
    python3 - "$tmp/dup.aab" <<'PY'
import sys, zipfile
with zipfile.ZipFile(sys.argv[1], "w") as z:
    for _ in range(2):
        z.writestr("base/lib/arm64-v8a/4000.so", "x")
PY
    check "listed more .so than walked" 1 "walked" "$tmp/dup.aab"
else
    echo "FAIL duplicate-entry case needs python3"
    fail=1
fi

[ "$fail" -eq 0 ] && echo "check-so-alignment: all cases pass"
exit "$fail"
