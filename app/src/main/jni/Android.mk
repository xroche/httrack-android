# Copyright (C) 2009 The Android Open Source Project
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#      http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#

LOCAL_PATH := $(call my-dir)
TARGET_ARCH := arm

# <https://github.com/langresser/libiconv-1.15-android/blob/master/Android.mk>
include $(CLEAR_VARS)
LOCAL_MODULE    := libiconv
LOCAL_CFLAGS := \
  -Wno-multichar \
  -DANDROID \
  -DLIBDIR="\"c\"" \
  -DBUILDING_LIBICONV \
  -DIN_LIBRARY \
  -DLIBICONV_PLUG
LOCAL_SRC_FILES := \
  libiconv-1.15/libcharset/lib/localcharset.c \
  libiconv-1.15/lib/iconv.c \
  libiconv-1.15/lib/relocatable.c
LOCAL_C_INCLUDES += \
  $(LOCAL_PATH)/include/iconv \
  $(LOCAL_PATH)/libiconv-1.15/include \
  $(LOCAL_PATH)/libiconv-1.15/libcharset \
  $(LOCAL_PATH)/libiconv-1.15/lib \
  $(LOCAL_PATH)/libiconv-1.15/libcharset/include \
  $(LOCAL_PATH)/libiconv-1.15/srclib
LOCAL_EXPORT_C_INCLUDES       := $(LOCAL_PATH)/libiconv-1.15/include
include $(BUILD_SHARED_LIBRARY)

# TODO FIXME: INVESTIGATE THIS
# Android seems to have both /system/lib/libcrypto.so and
# /system/lib/libssl.so, and Java loads them in priority when using
# System.loadLibrary(...)
# We should either ensure that the system libs are fine (they seems fine BTW)
# or explicitly load the application-cached-library using some dirty magic
# (ie. giving the explicit full path and .so)
# For now, we do not ship the libraries, which will spare some space, and
# hope the ABI won't change too much.

include $(CLEAR_VARS)
LOCAL_MODULE    := libcrypto
LOCAL_SRC_FILES := ../prebuild/libcrypto.a
include $(PREBUILT_STATIC_LIBRARY)

include $(CLEAR_VARS)
LOCAL_MODULE    := libssl
LOCAL_SRC_FILES := ../prebuild/libssl.a
include $(PREBUILT_STATIC_LIBRARY)

include $(CLEAR_VARS)
LOCAL_MODULE    := libhttrack
LOCAL_SRC_FILES := httrack/src/htscore.c httrack/src/htsparse.c 			\
	httrack/src/htsback.c httrack/src/htscache.c httrack/src/htscatchurl.c 	\
	httrack/src/htsfilters.c httrack/src/htsftp.c httrack/src/htshash.c 	\
	httrack/src/coucal/coucal.c httrack/src/htshelp.c httrack/src/htslib.c 	\
	httrack/src/htscoremain.c httrack/src/htsname.c httrack/src/htsrobots.c	\
	httrack/src/htstools.c httrack/src/htswizard.c httrack/src/htsalias.c 	\
	httrack/src/htsthread.c httrack/src/htsindex.c httrack/src/htsbauth.c 	\
	httrack/src/htsmd5.c httrack/src/htszlib.c httrack/src/htswrap.c 		\
	httrack/src/htsconcat.c httrack/src/htsmodules.c 						\
	httrack/src/htscharset.c httrack/src/punycode.c 						\
	httrack/src/htsencoding.c httrack/src/md5.c httrack/src/minizip/ioapi.c	\
	httrack/src/minizip/mztools.c httrack/src/minizip/unzip.c				\
	httrack/src/minizip/zip.c
LOCAL_C_INCLUDES := $(LOCAL_PATH)/httrack/src	\
	$(LOCAL_PATH)/httrack/src/coucal			\
	$(LOCAL_PATH)/include
LOCAL_LDLIBS := -ldl -lz
LOCAL_LDLIBS += -L$(LOCAL_PATH)/../prebuild
LOCAL_SHARED_LIBRARIES := libiconv
LOCAL_STATIC_LIBRARIES := libssl libcrypto
LOCAL_CFLAGS += -O3 -g3 -funwind-tables -fPIC -rdynamic 					\
	-fstrict-aliasing -fvisibility=hidden									\
	-Wall -Wformat -Wformat-security -Wmultichar -Wwrite-strings -Wcast-qual\
	-Wcast-align -Wstrict-prototypes -Wmissing-prototypes					\
	-Wmissing-declarations -Wdeclaration-after-statement -Wpointer-arith	\
	-Wsequence-point -Wnested-externs -Wparentheses -Winit-self				\
	-Wunused-but-set-parameter -Waddress -Wuninitialized -Wformat=2			\
	-Wformat-nonliteral -Wmissing-parameter-type -Wold-style-definition		\
	-Wignored-qualifiers -Wstrict-aliasing -Wno-sign-compare				\
	-Wno-type-limits -Wno-missing-field-initializers -Wno-cast-align		\
	-Wno-nested-externs														\
	-D_REENTRANT -DPIC -DANDROID -D_ANDROID -DHAVE_CONFIG_H -DINET6			\
	-DLIBHTTRACK_EXPORTS -DZLIB_CONST -DHTS_INTHASH_USES_MD5 -DLIBICONV_PLUG\
	-DHTS_CRASH_TEST														\
	-Wl,--no-merge-exidx-entries -Wl,-O1
LOCAL_CPPFLAGS += -pthread
include $(BUILD_SHARED_LIBRARY)

include $(CLEAR_VARS)
LOCAL_MODULE    := libhtsjava
LOCAL_SRC_FILES := httrack/src/htsjava.c
LOCAL_C_INCLUDES := $(LOCAL_PATH)/httrack/src	\
	$(LOCAL_PATH)/httrack/src/coucal			\
	$(LOCAL_PATH)/include
LOCAL_LDLIBS += -L$(LOCAL_PATH)/../prebuild
LOCAL_SHARED_LIBRARIES := libhttrack
LOCAL_CFLAGS += -O3 -g3 -funwind-tables -fPIC -rdynamic 					\
	-fstrict-aliasing -fvisibility=hidden									\
	-Wall -Wformat -Wformat-security -Wmultichar -Wwrite-strings -Wcast-qual\
	-Wcast-align -Wstrict-prototypes -Wmissing-prototypes					\
	-Wmissing-declarations -Wdeclaration-after-statement -Wpointer-arith	\
	-Wsequence-point -Wnested-externs -Wparentheses -Winit-self				\
	-Wunused-but-set-parameter -Waddress -Wuninitialized -Wformat=2			\
	-Wformat-nonliteral -Wmissing-parameter-type -Wold-style-definition		\
	-Wignored-qualifiers -Wstrict-aliasing -Wno-sign-compare				\
	-Wno-type-limits -Wno-missing-field-initializers -Wno-cast-align		\
	-Wno-nested-externs														\
	-D_REENTRANT -DPIC -DANDROID -D_ANDROID -DHAVE_CONFIG_H -DINET6			\
	-DLIBHTTRACK_EXPORTS -DZLIB_CONST -DHTS_INTHASH_USES_MD5 -DLIBICONV_PLUG\
	-DHTS_CRASH_TEST														\
	-Wl,--no-merge-exidx-entries -Wl,-O1
LOCAL_CPPFLAGS += -pthread
include $(BUILD_SHARED_LIBRARY)

include $(CLEAR_VARS)
LOCAL_MODULE    := htslibjni
LOCAL_SRC_FILES := htslibjni.c coffeecatch/coffeecatch.c coffeecatch/coffeejni.c
LOCAL_C_INCLUDES := $(LOCAL_PATH)/httrack/src	\
	$(LOCAL_PATH)/httrack/src/coucal			\
	$(LOCAL_PATH)/coffeecatch					\
	$(LOCAL_PATH)/include
LOCAL_SHARED_LIBRARIES := libhttrack
LOCAL_LDLIBS := -llog
LOCAL_CFLAGS := -O3 -g -funwind-tables \
	-Wl,--no-merge-exidx-entries -Wl,-O1 \
	-W -Wall -Wextra -Werror -Wno-unused-parameter
include $(BUILD_SHARED_LIBRARY)
