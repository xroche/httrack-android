#!/usr/bin/env bash
#
# Exercise check-reproducible.sh against fixtures. A clean build only ever
# produces the passing case, so the failures need building by hand.
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
SUT="$HERE/check-reproducible.sh"
ROOT="$(cd "$HERE/../.." && pwd)"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

fail=0
check() { # name expected-status args...
    local name="$1" want="$2"
    shift 2
    local got=0
    "$SUT" "$@" >"$tmp/out" 2>&1 || got=$?
    if [ "$got" != "$want" ]; then
        echo "FAIL $name: wanted exit $want, got $got"
        sed 's/^/     /' "$tmp/out"
        fail=1
    else
        echo "ok   $name"
    fi
}

# A .so is only read for strings here, so a plain file is a faithful fixture.
mkdir -p "$tmp/clean"
printf 'jni/htslibjni.c\0some other string\0' >"$tmp/clean/libclean.so"
check "control: a library with no build path" 0 "$tmp/clean"

mkdir -p "$tmp/dirty"
printf 'assertion failed at %s/app/src/main/jni/htslibjni.c\0' "$ROOT" >"$tmp/dirty/libdirty.so"
check "a library embedding its build path" 1 "$tmp/dirty"

mkdir -p "$tmp/empty"
check "a directory with no libraries at all" 1 "$tmp/empty"

# The OpenSSL half.
mkdir -p "$tmp/clean" "$tmp/ssl-good"
printf 'OPENSSLDIR: "/openssl-android/ssl"\0' >"$tmp/ssl-good/libcrypto.a"
check "control: statics with the constant prefix" 0 "$tmp/clean" "$tmp/ssl-good"

mkdir -p "$tmp/ssl-nofield"
printf 'no openssldir field here\0' >"$tmp/ssl-nofield/libssl.a"
check "control: a static with no OPENSSLDIR field, as libssl.a" 0 "$tmp/clean" "$tmp/ssl-nofield"

mkdir -p "$tmp/ssl-noprefix"
printf 'OPENSSLDIR: "/tmp/openssl-android/ssl"\0' >"$tmp/ssl-noprefix/libcrypto.a"
check "statics missing the constant prefix" 1 "$tmp/clean" "$tmp/ssl-noprefix"

mkdir -p "$tmp/ssl-staged"
printf 'OPENSSLDIR: "/openssl-android/ssl"\0also %s/ssl-staged/x\0' "$tmp" >"$tmp/ssl-staged/libcrypto.a"
check "statics embedding their staging path" 1 "$tmp/clean" "$tmp/ssl-staged"

mkdir -p "$tmp/ssl-empty"
check "an OpenSSL dir with no statics" 1 "$tmp/clean" "$tmp/ssl-empty"

[ "$fail" = 0 ] && echo "all check-reproducible cases pass"
exit "$fail"
