#!/usr/bin/env bash
#
# Stage per-ABI OpenSSL static libs + headers into app/src/main/prebuild/<abi>/,
# the layout Android.mk expects (../prebuild/$(TARGET_ARCH_ABI)/lib{ssl,crypto}.a
# and .../include/openssl).
#
# Source of the statics, in priority order:
#   1. $OPENSSL_ANDROID_ROOT (set in the toolchain Docker image → /opt/openssl-android)
#   2. build them here from a standalone NDK (needs $ANDROID_NDK_ROOT), via
#      docker/build-openssl.sh — used by the container-less emulator job.
set -euo pipefail

JNI_DIR="$(cd "$(dirname "$0")/../../app/src/main/jni" && pwd)"
PREBUILD="$JNI_DIR/../prebuild"
ABIS="${ABIS:-arm64-v8a x86_64}"
SRC="${OPENSSL_ANDROID_ROOT:-}"

if [ -z "$SRC" ] || [ ! -d "$SRC" ]; then
  echo "OPENSSL_ANDROID_ROOT not set/found — building OpenSSL from NDK ..."
  : "${ANDROID_NDK_ROOT:?set ANDROID_NDK_ROOT or OPENSSL_ANDROID_ROOT}"
  SRC="${OUT:-/tmp/openssl-android}"
  OUT="$SRC" ABIS="$ABIS" bash "$(dirname "$0")/../../docker/build-openssl.sh"
fi

for abi in $ABIS; do
  mkdir -p "$PREBUILD/$abi/include"
  cp "$SRC/$abi/lib/libssl.a" "$SRC/$abi/lib/libcrypto.a" "$PREBUILD/$abi/"
  cp -r "$SRC/$abi/include/openssl" "$PREBUILD/$abi/include/"
done
echo "staged OpenSSL statics for: $ABIS"
