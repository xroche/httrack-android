package com.httrack.android;

import java.util.concurrent.atomic.AtomicInteger;

import android.app.Activity;
import android.app.Application;
import android.os.Bundle;

/** Registers edge-to-edge inset handling for every activity, so a newly added one can't silently miss it. */
public final class HTTrackApplication extends Application {
  private static final AtomicInteger liveActivities = new AtomicInteger();

  /**
   * Does this process hold an activity? A process the system started for the job alone never
   * does, which is what lets a faulted crawl end it without taking a window with it.
   *
   * @return true while at least one activity has been created and not destroyed
   */
  static boolean hasLiveActivity() {
    return liveActivities.get() != 0;
  }

  /** One more activity in this process. */
  static void activityCreated() {
    liveActivities.incrementAndGet();
  }

  /** One fewer. */
  static void activityDestroyed() {
    liveActivities.decrementAndGet();
  }

  @Override
  public void onCreate() {
    super.onCreate();
    registerActivityLifecycleCallbacks(new ActivityLifecycleCallbacks() {
      @Override
      public void onActivityCreated(final Activity activity, final Bundle savedInstanceState) {
        activityCreated();
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
        activityDestroyed();
      }
    });
  }
}
