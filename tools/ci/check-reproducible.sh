#!/usr/bin/env bash
#
# Fail if a built library carries the path it was built in. That path is what
# breaks a reproducible build, and the regression is silent: the build stays
# green and the libraries still work.
set -euo pipefail

LIBS="${1:?usage: $0 <libs-dir> [openssl-staging-dir]}"
SSL="${2:-}"
# Match the prefix Android.mk maps away, so a run from any checkout is a real check.
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
rc=0

mapfile -t sos < <(find "$LIBS" -name '*.so' | sort)
[ "${#sos[@]}" -gt 0 ] || {
    echo "check-reproducible: no .so under $LIBS, so this check would see nothing" >&2
    exit 1
}
for so in "${sos[@]}"; do
    # LC_ALL=C: a UTF-8 locale makes grep skip the undecodable bytes around a string.
    hits="$(LC_ALL=C grep -c -- "$ROOT" "$so" || true)"
    if [ "$hits" != 0 ]; then
        echo "check-reproducible: $so embeds its build path ($hits times)" >&2
        LC_ALL=C strings -a "$so" | grep -m3 -- "$ROOT" >&2 || true
        rc=1
    fi
done

# OpenSSL compiles its --prefix in, so the staged libs must carry the constant one.
if [ -n "$SSL" ]; then
    mapfile -t ars < <(find "$SSL" -name 'lib*.a' | sort)
    [ "${#ars[@]}" -gt 0 ] || {
        echo "check-reproducible: no static libs under $SSL" >&2
        exit 1
    }
    for ar in "${ars[@]}"; do
        # Anchor on the whole field: a bare "/openssl-android" also matches
        # "/tmp/openssl-android", so the wrong prefix would pass. Only the members
        # that carry the field are checked, because libssl.a has none.
        if LC_ALL=C grep -q -- 'OPENSSLDIR: "' "$ar" &&
            ! LC_ALL=C grep -q -- 'OPENSSLDIR: "/openssl-android' "$ar"; then
            echo "check-reproducible: $ar was built with a non-constant prefix" >&2
            LC_ALL=C strings -a "$ar" | grep -m1 'OPENSSLDIR' >&2 || true
            rc=1
        fi
        if LC_ALL=C grep -q -- "$SSL" "$ar"; then
            echo "check-reproducible: $ar embeds its staging path $SSL" >&2
            rc=1
        fi
    done
fi

[ "$rc" = 0 ] && echo "check-reproducible: ${#sos[@]} libraries carry no build path"
exit "$rc"
