package com.httrack.android;

import android.app.Activity;
import android.app.Application;
import android.os.Bundle;

/** Registers edge-to-edge inset handling for every activity, so a newly added one can't silently miss it. */
public final class HTTrackApplication extends Application {
  @Override
  public void onCreate() {
    super.onCreate();
    registerActivityLifecycleCallbacks(new ActivityLifecycleCallbacks() {
      @Override
      public void onActivityCreated(final Activity activity, final Bundle savedInstanceState) {
        EdgeToEdge.fitSystemWindows(activity);
      }

      @Override
      public void onActivityStarted(final Activity activity) {
      }

      @Override
      public void onActivityResumed(final Activity activity) {
      }

      @Override
      public void onActivityPaused(final Activity activity) {
      }

      @Override
      public void onActivityStopped(final Activity activity) {
      }

      @Override
      public void onActivitySaveInstanceState(final Activity activity, final Bundle outState) {
      }

      @Override
      public void onActivityDestroyed(final Activity activity) {
      }
    });
  }
}
