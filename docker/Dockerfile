# Reproducible build environment for the modernized HTTrack Android app.
#
#   - JDK 17            (required by AGP 8.x)
#   - Android cmdline-tools + platform 35 + build-tools 35
#   - NDK r26d          (Clang, unified toolchain — replaces the r14/GCC hack)
#   - OpenSSL 3.x static libs cross-compiled for arm64-v8a/armeabi-v7a/x86_64
#
# Build:  docker build -t httrack-android-build docker/
# Use:    docker run --rm -v "$PWD":/src -w /src httrack-android-build \
#                 ./gradlew assembleRelease
#
# The OpenSSL libs are baked into the image under /opt/openssl-android/<abi>/.
# Point Android.mk's prebuilt paths at them (or copy into app/src/main/prebuild).
FROM eclipse-temurin:17-jdk-jammy

ARG ANDROID_CMDLINE_VERSION=11076708
ARG ANDROID_PLATFORM=android-35
ARG ANDROID_BUILD_TOOLS=35.0.0
ARG NDK_VERSION=26.3.11579264
ARG OPENSSL_VERSION=3.0.15
ARG OPENSSL_SHA256=23c666d0edf20f14249b3d8f0368acaee9ab585b09e1de82107c66e1f3ec9533

ENV ANDROID_SDK_ROOT=/opt/android-sdk \
    ANDROID_HOME=/opt/android-sdk \
    DEBIAN_FRONTEND=noninteractive

# NOTE: use wget, not curl, for downloads. On this host's Docker/kernel combo
# curl's threaded DNS resolver fails ("getaddrinfo() thread failed to start")
# even under seccomp=unconfined, while wget's synchronous resolver works.
RUN apt-get update && apt-get install -y --no-install-recommends \
      wget unzip git make perl file ca-certificates build-essential && \
    rm -rf /var/lib/apt/lists/*
# build-essential: needed by tools/ci/vendor-libiconv.sh (host ./configure) and
# by OpenSSL's build. wget (not curl): curl's threaded resolver fails on some
# Docker/kernel combos ("getaddrinfo() thread failed to start").

# --- Android SDK command-line tools ---
RUN mkdir -p "$ANDROID_SDK_ROOT/cmdline-tools" && cd /tmp && \
    wget -q "https://dl.google.com/android/repository/commandlinetools-linux-${ANDROID_CMDLINE_VERSION}_latest.zip" && \
    unzip -q "commandlinetools-linux-${ANDROID_CMDLINE_VERSION}_latest.zip" && \
    mv cmdline-tools "$ANDROID_SDK_ROOT/cmdline-tools/latest" && \
    rm "commandlinetools-linux-${ANDROID_CMDLINE_VERSION}_latest.zip"
ENV PATH="$ANDROID_SDK_ROOT/cmdline-tools/latest/bin:$ANDROID_SDK_ROOT/platform-tools:$PATH"

# --- SDK packages + NDK ---
RUN yes | sdkmanager --licenses >/dev/null && \
    sdkmanager --install \
      "platform-tools" \
      "platforms;${ANDROID_PLATFORM}" \
      "build-tools;${ANDROID_BUILD_TOOLS}" \
      "ndk;${NDK_VERSION}" >/dev/null
ENV ANDROID_NDK_ROOT="$ANDROID_SDK_ROOT/ndk/${NDK_VERSION}"

# --- OpenSSL 3.x static libs for all ABIs ---
COPY build-openssl.sh /usr/local/bin/build-openssl.sh
RUN chmod +x /usr/local/bin/build-openssl.sh && \
    OPENSSL_VERSION="${OPENSSL_VERSION}" OPENSSL_SHA256="${OPENSSL_SHA256}" \
    OUT=/opt/openssl-android MIN_API=21 \
    /usr/local/bin/build-openssl.sh && \
    rm -rf /tmp/*
ENV OPENSSL_ANDROID_ROOT=/opt/openssl-android

WORKDIR /src
