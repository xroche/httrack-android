#!/usr/bin/env bash
#
# Vendor GNU libiconv into app/src/main/jni/libiconv-1.17/ and generate the
# headers the ndk-build needs (iconv.h from iconv.h.in, config.h). bionic has no
# iconv, so httrack bundles it (built with -DLIBICONV_PLUG). Only 3 source files
# are compiled by Android.mk; iconv.c is a mega-TU that #includes every converter,
# so it needs configure-generated headers + the tarball's pre-generated aliases.h.
#
# configure runs with the NDK and not with the host, because a host run bakes the
# build machine's compiler and libc into libiconv.so, and F-Droid could not then
# reproduce our build.
#
# Idempotent: re-running with the tree already present is a no-op. Needs wget, tar
# and $ANDROID_NDK_ROOT. ndk-build does the cross-build itself.
set -euo pipefail

VERSION="${LIBICONV_VERSION:-1.17}"
SHA256="${LIBICONV_SHA256:-8f74213b56238c85a50a5329f77e06198771e70dd9a739779f4c02f65d971313}"
JNI_DIR="$(cd "$(dirname "$0")/../../app/src/main/jni" && pwd)"
DEST="$JNI_DIR/libiconv-${VERSION}"
: "${ANDROID_NDK_ROOT:?set ANDROID_NDK_ROOT}"

if [ -f "$DEST/include/iconv.h" ] && [ -f "$DEST/config.h" ]; then
  echo "libiconv ${VERSION} already vendored + configured at $DEST"
  exit 0
fi

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
cd "$work"
echo "=== fetching libiconv ${VERSION} ==="
# ftp.gnu.org on its own is a single point of failure: when it stalls, wget exits 4 and
# every job that needs libiconv goes red. Try the GNU redirector and a mirror as well.
# Fetching from a mirror is only safe because of the sha256 gate below.
tarball="libiconv-${VERSION}.tar.gz"
fetched=0
for base in "https://ftpmirror.gnu.org/libiconv" \
  "https://ftp.gnu.org/gnu/libiconv" \
  "https://mirrors.kernel.org/gnu/libiconv"; do
  if wget -q --tries=3 --timeout=30 --retry-connrefused -O "$tarball" "${base}/${tarball}"; then
    echo "  fetched from ${base}"
    fetched=1
    break
  fi
  echo "  ${base} failed, trying next mirror"
  rm -f "$tarball"
done
if [ "$fetched" -ne 1 ]; then
  echo "all libiconv mirrors failed" >&2
  exit 1
fi
echo "${SHA256}  ${tarball}" | sha256sum -c -
tar xf "$tarball"

# Both ABIs answer the same, so one stands for both.
HOST=aarch64-linux-android
API="$(sed -n 's/^APP_PLATFORM *:= *android-//p' "$JNI_DIR/Application.mk")"
CLANG="$ANDROID_NDK_ROOT/toolchains/llvm/prebuilt/linux-x86_64/bin/clang"
echo "=== configure for ${HOST}${API} to generate iconv.h + config.h ==="
(cd "libiconv-${VERSION}" &&
  CC="$CLANG --target=${HOST}${API}" \
    ./configure --host="$HOST" --enable-static --disable-shared >/dev/null)

rm -rf "$DEST"
mkdir -p "$(dirname "$DEST")"
mv "libiconv-${VERSION}" "$DEST"

echo "=== vendored at $DEST ==="
ls "$DEST/include/iconv.h" "$DEST/config.h" "$DEST/lib/aliases.h" 2>&1
