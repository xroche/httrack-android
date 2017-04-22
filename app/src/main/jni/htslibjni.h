/*
HTTrack Android JAVA Native Interface Stubs.

HTTrack Website Copier, Offline Browser for Windows and Unix
Copyright (C) Xavier Roche and other contributors

This program is free software; you can redistribute it and/or
modify it under the terms of the GNU General Public License
as published by the Free Software Foundation; either version 3
of the License, or any later version.

This program is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU General Public License for more details.

You should have received a copy of the GNU General Public License
along with this program; if not, write to the Free Software
Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA  02111-1307, USA.
*/

#ifndef HTTRACK_HTSLIBJNI_H
#define HTTRACK_HTSLIBJNI_H

#include <jni.h>

JNIEXPORT void JNICALL
Java_com_httrack_android_jni_HTTrackLib_initStatic(JNIEnv* env, jclass clazz);

JNIEXPORT void JNICALL
Java_com_httrack_android_jni_HTTrackLib_initRootPath(JNIEnv* env,
                                                     jclass clazz,
                                                     jstring opath);

JNIEXPORT jstring JNICALL
Java_com_httrack_android_jni_HTTrackLib_getVersion(JNIEnv* env, jclass clazz);

JNIEXPORT jstring JNICALL
Java_com_httrack_android_jni_HTTrackLib_getFeatures(JNIEnv* env, jclass clazz);

JNIEXPORT void JNICALL
Java_com_httrack_android_jni_HTTrackLib_init(JNIEnv* env, jobject object);

JNIEXPORT void JNICALL
Java_com_httrack_android_jni_HTTrackLib_free(JNIEnv* env, jobject object);

JNIEXPORT jboolean JNICALL
Java_com_httrack_android_jni_HTTrackLib_stop(JNIEnv* env, jobject object,
                                             jboolean force);

JNIEXPORT jint JNICALL
Java_com_httrack_android_jni_HTTrackLib_buildTopIndex(JNIEnv* env, jclass clazz,
                                                      jstring opath, jstring otemplates);

JNIEXPORT jint JNICALL
Java_com_httrack_android_jni_HTTrackLib_main(JNIEnv* env, jobject object,
                                             jobjectArray stringArray);

#endif //HTTRACK_HTSLIBJNI_H
