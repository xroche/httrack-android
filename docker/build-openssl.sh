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

OPENSSL_VERSION="${OPENSSL_VERSION:-3.0.15}"
# Pin the hash — verify against https://www.openssl.org/source/ before bumping.
OPENSSL_SHA256="${OPENSSL_SHA256:-23c666d0edf20f14249b3d8f0368acaee9ab585b09e1de82107c66e1f3ec9533}"
MIN_API="${MIN_API:-21}"
OUT="${OUT:-/opt/openssl-android}"
ABIS="${ABIS:-arm64-v8a armeabi-v7a x86_64}"

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
wget -q "https://github.com/openssl/openssl/releases/download/openssl-${OPENSSL_VERSION}/openssl-${OPENSSL_VERSION}.tar.gz"
echo "${OPENSSL_SHA256}  openssl-${OPENSSL_VERSION}.tar.gz" | sha256sum -c -
tar xf "openssl-${OPENSSL_VERSION}.tar.gz"
src="$work/openssl-${OPENSSL_VERSION}"

for abi in $ABIS; do
  target="$(abi_to_target "$abi")"
  echo "=== Building OpenSSL ${OPENSSL_VERSION} for ${abi} (${target}) ==="
  builddir="$work/build-$abi"
  cp -a "$src" "$builddir"
  ( cd "$builddir"
    ./Configure "$target" "-D__ANDROID_API__=${MIN_API}" \
        no-shared no-tests no-ui-console no-engine no-comp no-dso no-legacy \
        --prefix="$OUT/$abi" --libdir=lib
    make -j"$(nproc)" build_libs
    make install_dev
  )
done

echo "=== OpenSSL static libs installed under $OUT/<abi>/ ==="
find "$OUT" -name 'lib*.a' -printf '%p\n'
