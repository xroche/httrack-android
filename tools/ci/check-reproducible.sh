#!/usr/bin/env bash
#
# Fail if a built library could not be rebuilt byte for byte elsewhere. Three
# things carry a build into its output: a path in a string literal, a path in the
# debug info, and OpenSSL's configured prefix and build date. Every one of them is
# silent, because the build stays green and the libraries still work.
#
# Point this at the UNSTRIPPED libraries. Stripping deletes the debug info, so a
# stripped library hides the DW_AT_comp_dir half, which still reaches the shipped
# file through the build-id that lld computes before the strip.
set -euo pipefail

LIBS="${1:?usage: $0 <unstripped-libs-dir> [openssl-staging-dir]}"
SSL="${2:-}"
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
RECIPE="$ROOT/docker/build-openssl.sh"

# Read both constants from the script that bakes them, so they cannot drift.
field() {
    local value
    value="$(sed -n "s/^$1=//p" "$RECIPE")"
    [ -n "$value" ] || {
        echo "check-reproducible: no $1 in docker/build-openssl.sh" >&2
        exit 1
    }
    echo "$value"
}
PREFIX="$(field SSL_PREFIX)"
# OpenSSL renders the epoch with perl's scalar(gmtime) plus a UTC suffix, so the
# weekday leads and the zone trails: "Tue Aug 25 00:00:00 2026 UTC".
# LC_ALL=C because this host runs a French locale, where %a and %b render as
# "mar. août" and would never match what OpenSSL wrote.
WANT_DATE="$(LC_ALL=C date -u -d "@$(field SOURCE_DATE_EPOCH)" '+%a %b %e %H:%M:%S %Y') UTC"

listing="$(mktemp)"
trap 'rm -f "$listing"' EXIT
rc=0

# find's status is lost through a process substitution, so a directory it could
# not read would silently shrink the walk.
find "$LIBS" -type f -name '*.so' >"$listing" || {
    echo "check-reproducible: cannot walk $LIBS" >&2
    exit 1
}
[ -s "$listing" ] || {
    echo "check-reproducible: no .so under $LIBS, so this check would see nothing" >&2
    exit 1
}
# find walks in directory order, so sort: a failure then names the same library on
# every machine.
sort -o "$listing" "$listing"
libs=0
while read -r so; do
    libs=$((libs + 1))
    # -F throughout: a dot in a build path is a regex wildcard, and a bracket
    # makes grep exit 2.
    if LC_ALL=C grep -qF -- "$ROOT" "$so"; then
        echo "check-reproducible: $so embeds its build path" >&2
        LC_ALL=C strings -a "$so" | grep -m3 -F -- "$ROOT" >&2 || true
        rc=1
    fi
    # libcrypto is linked in statically, so its baked prefix ships inside the .so.
    if LC_ALL=C grep -qF -- 'OPENSSLDIR: "' "$so" &&
        ! LC_ALL=C grep -qF -- "OPENSSLDIR: \"$PREFIX" "$so"; then
        echo "check-reproducible: $so carries a non-constant OpenSSL prefix" >&2
        LC_ALL=C strings -a "$so" | grep -m1 'OPENSSLDIR' >&2 || true
        rc=1
    fi
done <"$listing"

if [ -n "$SSL" ]; then
    find "$SSL" -type f -name 'lib*.a' >"$listing" || {
        echo "check-reproducible: cannot walk $SSL" >&2
        exit 1
    }
    [ -s "$listing" ] || {
        echo "check-reproducible: no static libs under $SSL" >&2
        exit 1
    }
    sort -o "$listing" "$listing"
    # Counted, because only some members carry each field. Without this the whole
    # OpenSSL half passes on a tree where no member carries anything.
    prefixes=0
    dates=0
    while read -r ar; do
        if LC_ALL=C grep -qF -- 'OPENSSLDIR: "' "$ar"; then
            prefixes=$((prefixes + 1))
            if ! LC_ALL=C grep -qF -- "OPENSSLDIR: \"$PREFIX" "$ar"; then
                echo "check-reproducible: $ar was built with a non-constant prefix" >&2
                LC_ALL=C strings -a "$ar" | grep -m1 'OPENSSLDIR' >&2 || true
                rc=1
            fi
        fi
        if LC_ALL=C grep -qF -- "$SSL" "$ar"; then
            echo "check-reproducible: $ar embeds its staging path $SSL" >&2
            rc=1
        fi
        if LC_ALL=C grep -qF -- 'built on: ' "$ar"; then
            dates=$((dates + 1))
            if ! LC_ALL=C grep -qF -- "built on: $WANT_DATE" "$ar"; then
                echo "check-reproducible: $ar was not built at the pinned epoch" >&2
                LC_ALL=C strings -a "$ar" | grep -m1 'built on: ' >&2 || true
                rc=1
            fi
        fi
    done <"$listing"
    [ "$prefixes" != 0 ] || {
        echo "check-reproducible: no static under $SSL carries OPENSSLDIR" >&2
        rc=1
    }
    [ "$dates" != 0 ] || {
        echo "check-reproducible: no static under $SSL carries a build date" >&2
        rc=1
    }
fi

[ "$rc" = 0 ] && echo "check-reproducible: $libs libraries clean, OpenSSL at $PREFIX"
exit "$rc"