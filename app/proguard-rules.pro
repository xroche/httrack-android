# Native code resolves these reflectively, leaving R8 no Java caller to trace.
-keep class com.httrack.android.jni.** { *; }
