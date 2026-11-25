#!/usr/bin/env bash
#
# Exercise check-reproducible.sh against fixtures. A clean build only ever
# produces the passing case, so each failure needs building by hand.
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
SUT="$HERE/check-reproducible.sh"
ROOT="$(cd "$HERE/../.." && pwd)"
PREFIX="$(sed -n 's/^SSL_PREFIX=//p' "$HERE/../../docker/build-openssl.sh")"
# Written out, not derived the way the script derives it. When the two disagree the
# fixtures stop matching and these cases fail, which is the point: a shared
# derivation would let the script and the test be wrong together.
DATE='Tue Aug 25 00:00:00 2026 UTC'
EPOCH="$(sed -n 's/^SOURCE_DATE_EPOCH=//p' "$HERE/../../docker/build-openssl.sh")"
[ "$EPOCH" = 1787616000 ] || {
    echo "FAIL the pinned epoch moved to $EPOCH, so update DATE in this test"
    exit 1
}
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
fail=0

# check <name> <wanted-status> <wanted-message-fragment> <libs-dir> [ssl-dir]
# The message is asserted too, so a failure for the wrong reason cannot pass.
check() {
    local name="$1" want="$2" msg="$3"
    shift 3
    local got=0
    "$SUT" "$@" >"$tmp/out" 2>&1 || got=$?
    if [ "$got" != "$want" ] || ! grep -qF -- "$msg" "$tmp/out"; then
        echo "FAIL $name: wanted exit $want with \"$msg\", got $got"
        sed 's/^/     /' "$tmp/out"
        fail=1
    else
        echo "ok   $name"
    fi
}

# The scripts only read these files for strings, so plain files are faithful.
mklib() { mkdir -p "$(dirname "$1")" && printf '%b\0' "$2" >"$1"; }

GOOD_LIBS="$tmp/libs-clean"
GOOD_SSL="$tmp/ssl-clean"
mklib "$GOOD_LIBS/libclean.so" 'jni/htslibjni.c and nothing else'
mklib "$GOOD_SSL/libcrypto.a" "OPENSSLDIR: \"$PREFIX/ssl\"\nbuilt on: $DATE"
mklib "$GOOD_SSL/libssl.a" 'no fields in this one, as libssl.a'
check "control: clean libraries and clean statics" 0 "libraries clean" "$GOOD_LIBS" "$GOOD_SSL"

# Two libraries, the second dirty: a loop reading only the first entry passes the
# single-file fixtures, so every later case keeps a clean library beside the bad one.
mklib "$tmp/libs-2nd/libclean.so" 'jni/htslibjni.c'
mklib "$tmp/libs-2nd/libdirty.so" "assertion failed at $ROOT/app/src/main/jni/x.c"
check "the second library embeds its build path" 1 "embeds its build path" \
    "$tmp/libs-2nd" "$GOOD_SSL"

# A bare ROOT is what DW_AT_comp_dir leaves when only the second map is missing.
mklib "$tmp/libs-compdir/libclean.so" 'jni/htslibjni.c'
mklib "$tmp/libs-compdir/libcompdir.so" "$ROOT/app"
check "a library whose comp_dir is the build root" 1 "embeds its build path" \
    "$tmp/libs-compdir" "$GOOD_SSL"

mklib "$tmp/libs-sslprefix/libclean.so" 'jni/htslibjni.c'
mklib "$tmp/libs-sslprefix/libbad.so" 'OPENSSLDIR: "/opt/openssl-android/arm64-v8a/ssl"'
check "a library carrying a staged OpenSSL prefix" 1 "non-constant OpenSSL prefix" \
    "$tmp/libs-sslprefix" "$GOOD_SSL"

mkdir -p "$tmp/libs-empty"
check "a directory holding no libraries" 1 "would see nothing" "$tmp/libs-empty"
check "a libraries directory that does not exist" 1 "cannot walk" "$tmp/absent"

# The OpenSSL half. Each fixture keeps one good member, so a loop reading only the
# first entry cannot pass.
mklib "$tmp/ssl-prefix/libcrypto.a" "OPENSSLDIR: \"/tmp/openssl-android/ssl\"\nbuilt on: $DATE"
check "a static built with a non-constant prefix" 1 "non-constant prefix" \
    "$GOOD_LIBS" "$tmp/ssl-prefix"

mklib "$tmp/ssl-date/libcrypto.a" "OPENSSLDIR: \"$PREFIX/ssl\"\nbuilt on: Mon Jan  1 00:00:00 2001"
check "a static built at an unpinned epoch" 1 "not built at the pinned epoch" \
    "$GOOD_LIBS" "$tmp/ssl-date"

mklib "$tmp/ssl-nofields/libssl.a" 'carries neither field'
check "statics carrying neither field at all" 1 "carries OPENSSLDIR" \
    "$GOOD_LIBS" "$tmp/ssl-nofields"

# A static that carries a prefix but no date: without the date counter the whole
# date half would pass on a build that stamped nothing.
mklib "$tmp/ssl-nodate/libcrypto.a" "OPENSSLDIR: \"$PREFIX/ssl\""
check "a static carrying no build date" 1 "carries a build date" \
    "$GOOD_LIBS" "$tmp/ssl-nodate"

# A staging path holding a regex metacharacter, and a string that matches it only
# as a regex. Without grep -F the "." matches the X and a clean static reads dirty.
mklib "$tmp/ssl.dot/libcrypto.a" "OPENSSLDIR: \"$PREFIX/ssl\"\nbuilt on: $DATE\n$tmp/sslXdot/libcrypto.a"
check "control: a dotted staging path does not false-positive" 0 "libraries clean" \
    "$GOOD_LIBS" "$tmp/ssl.dot"

# The same for the build root, which the script derives from its own location, so
# this case runs a copy of it from a directory whose name holds a dot.
fake="$tmp/r.oot"
mkdir -p "$fake/tools/ci" "$fake/docker"
cp "$SUT" "$fake/tools/ci/"
cp "$HERE/../../docker/build-openssl.sh" "$fake/docker/"
mklib "$tmp/libs-dotroot/libclean.so" "$tmp/rXoot/app/src/main/jni/x.c"
got=0
"$fake/tools/ci/check-reproducible.sh" "$tmp/libs-dotroot" >"$tmp/out" 2>&1 || got=$?
if [ "$got" = 0 ]; then
    echo "ok   control: a dotted build root does not false-positive"
else
    echo "FAIL control: a dotted build root does not false-positive: exit $got"
    sed 's/^/     /' "$tmp/out"
    fail=1
fi

mklib "$tmp/ssl-staged/libcrypto.a" "OPENSSLDIR: \"$PREFIX/ssl\"\nbuilt on: $DATE\n$tmp/ssl-staged/x"
check "a static embedding its staging path" 1 "embeds its staging path" \
    "$GOOD_LIBS" "$tmp/ssl-staged"

# A clean member sorting before a dirty one, so a loop reading only the first
# entry passes every single-member fixture above and fails here.
mklib "$tmp/ssl-2nd/libcrypto.a" "OPENSSLDIR: \"$PREFIX/ssl\"\nbuilt on: $DATE"
mklib "$tmp/ssl-2nd/libzz.a" "OPENSSLDIR: \"/tmp/openssl-android/ssl\"\nbuilt on: $DATE"
check "the second static was built with a non-constant prefix" 1 "non-constant prefix" \
    "$GOOD_LIBS" "$tmp/ssl-2nd"

mkdir -p "$tmp/ssl-empty"
check "an OpenSSL directory holding no statics" 1 "no static libs" "$GOOD_LIBS" "$tmp/ssl-empty"
check "an OpenSSL directory that does not exist" 1 "cannot walk" "$GOOD_LIBS" "$tmp/absent-ssl"

[ "$fail" = 0 ] && echo "all check-reproducible cases pass"
exit "$fail"
