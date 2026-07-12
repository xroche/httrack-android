# 64-bit multi-ABI: arm64-v8a is the primary target, armeabi-v7a kept for old
# devices, x86_64 for emulators/CI. 64-bit ABIs require API 21+.
#
# APP_PLATFORM is android-24, not 21: httrack's bundled minizip (ioapi.c) calls
# fseeko/ftello for large-file (>2GB) zip-cache seeks, and bionic only introduces
# them at API 24. Keeping the min at 21 would require an upstream minizip guard
# (fall back to fseek/ftell below 24, capping the cache at 2GB). See the
# android-modernization plan — this is a min-API decision for Xavier.
APP_ABI := arm64-v8a armeabi-v7a x86_64
APP_PLATFORM := android-24
