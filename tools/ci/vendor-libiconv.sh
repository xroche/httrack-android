#!/usr/bin/env bash
#
# Vendor GNU libiconv into app/src/main/jni/libiconv-1.17/ and install the headers
# its configure generates (iconv.h, config.h) from libiconv-headers/<version>/.
# bionic has no iconv, so httrack bundles it (built with -DLIBICONV_PLUG). Only 3
# source files are compiled by Android.mk; iconv.c is a mega-TU that #includes
# every converter, so it needs those headers plus the tarball's pre-generated
# aliases.h.
#
# They are checked in because a ./configure run answers for the machine it runs
# on, so F-Droid's buildserver would get a different libiconv.so from this very
# commit and could not reproduce our build. --regen-headers rewrites them after a
# libiconv bump, cross-configuring with the NDK so the answers are Android's.
#
# Idempotent: re-running with the tree already present is a no-op. Needs wget and
# tar; --regen-headers also needs $ANDROID_NDK_ROOT.
set -euo pipefail

VERSION="${LIBICONV_VERSION:-1.17}"
SHA256="${LIBICONV_SHA256:-8f74213b56238c85a50a5329f77e06198771e70dd9a739779f4c02f65d971313}"
HERE="$(cd "$(dirname "$0")" && pwd)"
JNI_DIR="$(cd "$HERE/../../app/src/main/jni" && pwd)"
DEST="$JNI_DIR/libiconv-${VERSION}"
HEADERS="$HERE/libiconv-headers/${VERSION}"

# What configure generates that Android.mk's include path reaches. Both config.h
# are read: lib/iconv.c takes the one beside it, the other two sources take
# libcharset/config.h, which comes first on that path.
GENERATED=(
  include/iconv.h
  lib/config.h
  libcharset/config.h
  libcharset/include/libcharset.h
  libcharset/include/localcharset.h
)

# fetch_and_unpack <dir> — leaves libiconv-$VERSION/ under <dir>.
fetch_and_unpack() {
  local into="$1" tarball="libiconv-${VERSION}.tar.gz" base fetched=0
  # ftp.gnu.org on its own is a single point of failure: when it stalls, wget exits 4 and
  # every job that needs libiconv goes red. Try the GNU redirector and a mirror as well.
  # Fetching from a mirror is only safe because of the sha256 gate below.
  for base in "https://ftpmirror.gnu.org/libiconv" \
    "https://ftp.gnu.org/gnu/libiconv" \
    "https://mirrors.kernel.org/gnu/libiconv"; do
    if wget -q --tries=3 --timeout=30 --retry-connrefused -O "$into/$tarball" "${base}/${tarball}"; then
      echo "  fetched from ${base}"
      fetched=1
      break
    fi
    echo "  ${base} failed, trying next mirror"
    rm -f "$into/$tarball"
  done
  if [ "$fetched" -ne 1 ]; then
    echo "all libiconv mirrors failed" >&2
    exit 1
  fi
  (cd "$into" && echo "${SHA256}  ${tarball}" | sha256sum -c - && tar xf "$tarball")
}

# Cross-configure for each ABI the app ships and rewrite $HEADERS from the result.
# Running every ABI proves the one checked-in set serves them all.
regen_headers() {
  : "${ANDROID_NDK_ROOT:?set ANDROID_NDK_ROOT}"
  local bin="$ANDROID_NDK_ROOT/toolchains/llvm/prebuilt/linux-x86_64/bin"
  # Taken from Application.mk, so the headers answer for what ndk-build compiles.
  local api abis abi triple first=""
  api="$(sed -n 's/^APP_PLATFORM *:= *android-//p' "$JNI_DIR/Application.mk")"
  abis="$(sed -n 's/^APP_ABI *:= *//p' "$JNI_DIR/Application.mk")"
  if [ -z "$api" ] || [ -z "$abis" ]; then
    echo "no APP_PLATFORM or APP_ABI in $JNI_DIR/Application.mk" >&2
    exit 1
  fi
  # Not local: the EXIT trap fires after the function returns, when a local would
  # be out of scope and set -u would abort instead of cleaning up.
  work="$(mktemp -d)"
  trap 'rm -rf "$work"' EXIT
  echo "=== fetching libiconv ${VERSION} ==="
  fetch_and_unpack "$work"
  for abi in $abis; do
    case "$abi" in
    arm64-v8a) triple=aarch64-linux-android ;;
    x86_64) triple=x86_64-linux-android ;;
    armeabi-v7a) triple=armv7a-linux-androideabi ;;
    x86) triple=i686-linux-android ;;
    *)
      echo "unknown ABI: $abi" >&2
      exit 1
      ;;
    esac
    echo "=== cross-configure for $abi (${triple}${api}) ==="
    cp -a "$work/libiconv-${VERSION}" "$work/$abi"
    (
      cd "$work/$abi"
      CC="$bin/clang --target=${triple}${api}" AR="$bin/llvm-ar" RANLIB="$bin/llvm-ranlib" \
        ./configure --host="$triple" --enable-static --disable-shared >configure.out 2>&1 ||
        {
          tail -20 configure.out >&2
          exit 1
        }
    )
    if [ -z "$first" ]; then
      first="$abi"
    else
      # One set serves every ABI, so a difference here needs rethinking.
      for f in "${GENERATED[@]}"; do
        diff -u "$work/$first/$f" "$work/$abi/$f" || {
          echo "$f differs between $first and $abi" >&2
          exit 1
        }
      done
    fi
  done
  for f in "${GENERATED[@]}"; do
    mkdir -p "$HEADERS/$(dirname "$f")"
    cp "$work/$first/$f" "$HEADERS/$f"
  done
  echo "=== wrote $HEADERS ==="
  (cd "$HEADERS" && ls -l "${GENERATED[@]}")
}

if [ "${1:-}" = "--regen-headers" ]; then
  regen_headers
  exit 0
fi

# Checked before the download, so a bump with no header set fails at once.
[ -d "$HEADERS" ] || {
  echo "no checked-in headers for libiconv ${VERSION} at $HEADERS" >&2
  echo "run $0 --regen-headers with ANDROID_NDK_ROOT set" >&2
  exit 1
}

if [ -f "$DEST/include/iconv.h" ] && [ -f "$DEST/lib/config.h" ]; then
  echo "libiconv ${VERSION} already vendored + configured at $DEST"
  exit 0
fi

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
echo "=== fetching libiconv ${VERSION} ==="
fetch_and_unpack "$work"

echo "=== installing the generated headers from $HEADERS ==="
cp -R "$HEADERS/." "$work/libiconv-${VERSION}/"

rm -rf "$DEST"
mkdir -p "$(dirname "$DEST")"
mv "$work/libiconv-${VERSION}" "$DEST"

echo "=== vendored at $DEST ==="
ls "$DEST/include/iconv.h" "$DEST/lib/config.h" "$DEST/lib/aliases.h" 2>&1
