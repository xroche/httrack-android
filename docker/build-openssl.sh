#!/usr/bin/env bash
#
# Cross-compile OpenSSL 3.x static libs for Android, all target ABIs, using the
# NDK's own Clang toolchain. Replaces the dead 2017 setenv-android.sh + OpenSSL
# 1.0.2k hack (see ../openssl-ndk-analysis.md).
#
# Output layout (consumed by app/src/main/jni/Android.mk):
#   $OUT/<abi>/lib/libssl.a
#   $OUT/<abi>/lib/libcrypto.a
#   $OUT/<abi>/include/openssl/*.h
#
# Requires: ANDROID_NDK_ROOT set, wget, perl, make. Run inside the Docker image.
# (wget, not curl: curl's threaded resolver fails on this host's Docker/kernel.)
set -euo pipefail

DEFAULT_VERSION=3.5.8
# Pin the hash — verify against https://www.openssl.org/source/ before bumping.
DEFAULT_SHA256=a8f84a39918ec6415ce765d9b429d313ba97b8143169c172e734b9514464f5b2
OPENSSL_VERSION="${OPENSSL_VERSION:-$DEFAULT_VERSION}"
OPENSSL_SHA256="${OPENSSL_SHA256:-$DEFAULT_SHA256}"
# The default hash belongs to the default version, so a version override that
# brings no hash would check one release against another's digest.
if [ "$OPENSSL_VERSION" != "$DEFAULT_VERSION" ] && [ "$OPENSSL_SHA256" = "$DEFAULT_SHA256" ]; then
  echo "set OPENSSL_SHA256 when overriding OPENSSL_VERSION" >&2
  exit 1
fi
MIN_API="${MIN_API:-21}"
# Constant on purpose: OpenSSL compiles the prefix in as string literals, so
# deriving it from $OUT would make the libraries depend on who built them.
RB_PREFIX=/openssl-android
# OpenSSL stamps a build date into cversion.o. Its mkbuildinf.pl honours
# SOURCE_DATE_EPOCH, so pin it to the pinned release date and the libraries stop
# depending on when they were built.
export SOURCE_DATE_EPOCH="${SOURCE_DATE_EPOCH:-1787616000}"
OUT="${OUT:-/opt/openssl-android}"
ABIS="${ABIS:-arm64-v8a x86_64}"

: "${ANDROID_NDK_ROOT:?set ANDROID_NDK_ROOT}"
HOST_TAG="linux-x86_64"
export PATH="$ANDROID_NDK_ROOT/toolchains/llvm/prebuilt/$HOST_TAG/bin:$PATH"

abi_to_target() {
  case "$1" in
    arm64-v8a)   echo android-arm64 ;;
    armeabi-v7a) echo android-arm   ;;
    x86_64)      echo android-x86_64 ;;
    x86)         echo android-x86   ;;
    *) echo "unknown ABI: $1" >&2; exit 1 ;;
  esac
}

work="$(mktemp -d)"
cd "$work"
wget -q --tries=3 --timeout=30 --retry-connrefused "https://github.com/openssl/openssl/releases/download/openssl-${OPENSSL_VERSION}/openssl-${OPENSSL_VERSION}.tar.gz"
echo "${OPENSSL_SHA256}  openssl-${OPENSSL_VERSION}.tar.gz" | sha256sum -c -
tar xf "openssl-${OPENSSL_VERSION}.tar.gz"
src="$work/openssl-${OPENSSL_VERSION}"

for abi in $ABIS; do
  target="$(abi_to_target "$abi")"
  echo "=== Building OpenSSL ${OPENSSL_VERSION} for ${abi} (${target}) ==="
  builddir="$work/build-$abi"
  cp -a "$src" "$builddir"
  # Match the branch protection Android.mk gives our own code. The linker ANDs
  # .note.gnu.property over every input, so one unmarked member clears it.
  # OPENSSL_TLS_SECURITY_LEVEL pins what 3.0.15 shipped. 3.5 defaults to 2, which
  # refuses the sub-2048-bit DH groups that old sites still offer.
  harden=()
  if [ "$abi" = arm64-v8a ]; then
    harden=(-mbranch-protection=standard)
  fi
  ( cd "$builddir"
    ./Configure "$target" "-D__ANDROID_API__=${MIN_API}" "${harden[@]}" \
        -DOPENSSL_TLS_SECURITY_LEVEL=1 \
        no-shared no-tests no-ui-console no-engine no-comp no-dso no-legacy \
        --prefix="$RB_PREFIX" --libdir=lib
    make -j"$(nproc)" build_libs
    # A reproducible build needs the baked OPENSSLDIR/ENGINESDIR/MODULESDIR strings
    # to be the same wherever we stage, so Configure gets a constant prefix and the
    # install is redirected here instead.
    make install_dev DESTDIR="$builddir/stage"
    mkdir -p "$OUT/$abi"
    cp -a "$builddir/stage$RB_PREFIX/." "$OUT/$abi/"
  )
done

echo "=== OpenSSL static libs installed under $OUT/<abi>/ ==="
find "$OUT" -name 'lib*.a' -printf '%p\n'
