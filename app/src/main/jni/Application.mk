# 64-bit only: arm64-v8a is the primary target; x86_64 covers emulators/CI and
# ChromeOS. 32-bit armeabi-v7a is dropped — Play allows 64-bit-only builds, and
# minSdk 24 already excludes almost all 32-bit-only hardware.
#
# APP_PLATFORM is android-24: httrack's bundled minizip (ioapi.c) calls
# fseeko/ftello for large-file (>2GB) zip-cache seeks, which bionic only provides
# at API 24+.
APP_ABI := arm64-v8a x86_64
APP_PLATFORM := android-24

# Play requires 16 KB-aligned LOAD segments at targetSdk 35+; r26d's lld still defaults to 4 KB.
APP_LDFLAGS := -Wl,-z,max-page-size=16384
