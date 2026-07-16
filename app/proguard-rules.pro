# The JNI layer calls back into these from native code, so they have no reachable
# Java caller for R8 to trace. Only consulted if minifyEnabled is turned on.
-keep class com.httrack.android.jni.** { *; }
