#!/usr/bin/env bash
#
# Vendor GNU libiconv into app/src/main/jni/libiconv-1.17/ and generate the
# headers the ndk-build needs (iconv.h from iconv.h.in, config.h). bionic has no
# iconv, so httrack bundles it (built with -DLIBICONV_PLUG). Only 3 source files
# are compiled by Android.mk; iconv.c is a mega-TU that #includes every converter,
# so it needs configure-generated headers + the tarball's pre-generated aliases.h.
#
# Idempotent: re-running with the tree already present is a no-op. Needs a host C
# compiler + make for ./configure (header generation only; the actual cross-build
# is done by ndk-build).
set -euo pipefail

VERSION="${LIBICONV_VERSION:-1.17}"
SHA256="${LIBICONV_SHA256:-8f74213b56238c85a50a5329f77e06198771e70dd9a739779f4c02f65d971313}"
JNI_DIR="$(cd "$(dirname "$0")/../../app/src/main/jni" && pwd)"
DEST="$JNI_DIR/libiconv-${VERSION}"

if [ -f "$DEST/include/iconv.h" ] && [ -f "$DEST/config.h" ]; then
  echo "libiconv ${VERSION} already vendored + configured at $DEST"
  exit 0
fi

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
cd "$work"
echo "=== fetching libiconv ${VERSION} ==="
wget -q "https://ftp.gnu.org/gnu/libiconv/libiconv-${VERSION}.tar.gz"
echo "${SHA256}  libiconv-${VERSION}.tar.gz" | sha256sum -c -
tar xf "libiconv-${VERSION}.tar.gz"

echo "=== configure (host) to generate iconv.h + config.h ==="
( cd "libiconv-${VERSION}" && ./configure --enable-static --disable-shared >/dev/null )

rm -rf "$DEST"
mkdir -p "$(dirname "$DEST")"
mv "libiconv-${VERSION}" "$DEST"

# The host ./configure detects HAVE_LANGINFO_CODESET (true on Linux), but bionic
# only introduces nl_langinfo() at API 26. localcharset.c is built without
# -DHAVE_CONFIG_H and reads whichever config.h is on the include path, so neutralize
# the define in ALL generated config.h. Android is always UTF-8, so the non-
# nl_langinfo path is correct. (Cross-config skew; a proper NDK cross-configure
# would set this automatically.)
for f in "$DEST/config.h" "$DEST/libcharset/config.h" "$DEST/lib/config.h"; do
  [ -f "$f" ] && sed -i \
    's|^#define HAVE_LANGINFO_CODESET 1|/* #undef HAVE_LANGINFO_CODESET (bionic nl_langinfo is API 26+) */|' "$f"
done

echo "=== vendored at $DEST ==="
ls "$DEST/include/iconv.h" "$DEST/config.h" "$DEST/lib/aliases.h" 2>&1
