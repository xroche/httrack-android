#!/usr/bin/env bash
#
# Exercise check-so-alignment.sh on both archive layouts. CI builds no .aab, so
# without this the AAB branch is first exercised by a real release.
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
SUT="$HERE/check-so-alignment.sh"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

# Stand in for llvm-readelf so the fixtures need no toolchain and no real ELF.
# Alignment comes from the file name, one LOAD line per requested value.
mkdir -p "$tmp/bin"
cat >"$tmp/bin/llvm-readelf" <<'STUB'
#!/usr/bin/env bash
for a in $(basename "${!#}" .so | tr '-' ' '); do
    case "$a" in
    [0-9]*) echo "  LOAD           0x000000 0x0 0x0 0x10 0x10 R   0x$a" ;;
    esac
done
STUB
chmod +x "$tmp/bin/llvm-readelf"
export PATH="$tmp/bin:$PATH"

# Name each .so after the p_align values its LOAD segments report, in hex.
mkzip() {
    local out="$1" dir="$2"
    shift 2
    rm -rf "$tmp/stage"
    if [ "$#" -gt 0 ]; then
        mkdir -p "$tmp/stage/$dir/arm64-v8a"
        local n
        for n in "$@"; do touch "$tmp/stage/$dir/arm64-v8a/$n.so"; done
    else
        mkdir -p "$tmp/stage"
        touch "$tmp/stage/readme.txt"
    fi
    (cd "$tmp/stage" && zip -qr "$out" .)
}

fail=0
check() {
    local label="$1" want="$2" archive="$3"
    local got=0
    "$SUT" "$archive" >/dev/null 2>&1 || got=$?
    if [ "$got" -ne "$want" ]; then
        echo "FAIL $label: want exit $want, got $got"
        fail=1
    else
        echo "ok   $label"
    fi
}

mkzip "$tmp/aligned.apk" lib 4000
check "apk layout, 16 KB" 0 "$tmp/aligned.apk"

mkzip "$tmp/aligned.aab" base/lib 4000
check "aab layout, 16 KB" 0 "$tmp/aligned.aab"

mkzip "$tmp/low.apk" lib 1000
check "apk below 16 KB" 1 "$tmp/low.apk"

# The regression the inline AAB check could not see: only a later LOAD is short.
mkzip "$tmp/mixed.aab" base/lib 4000-1000
check "aab, later LOAD below 16 KB" 1 "$tmp/mixed.aab"

mkzip "$tmp/empty.apk" lib
check "no .so at all" 1 "$tmp/empty.apk"

# An .aab read as an apk finds nothing, so layout detection must not pass it.
cp "$tmp/aligned.aab" "$tmp/mislabelled.apk"
check "aab named .apk" 1 "$tmp/mislabelled.apk"

check "missing file" 1 "$tmp/nope.apk"

[ "$fail" -eq 0 ] && echo "check-so-alignment: all cases pass"
exit "$fail"
