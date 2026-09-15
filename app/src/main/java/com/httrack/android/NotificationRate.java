package com.httrack.android;

/**
 * How often a running mirror may repaint its notification. The engine refreshes far faster than
 * anyone can read, and one post per refresh is the same flood the progress pane had.
 */
final class NotificationRate {
  /** No frame posted yet, which the first one must not be measured against. */
  static final long NEVER = Long.MIN_VALUE;

  private NotificationRate() {
  }

  /**
   * Is a progress notification found in the shade the remains of a process that died? Only a
   * crawl in this process can be behind one, so with neither a crawl nor an execution left it
   * shows progress nothing is making any more.
   *
   * @param crawlLive
   *          whether the session slot holds a crawl that has not ended
   * @param jobExecuting
   *          whether an execution of the mirror job holds this process
   * @return true when the notification must be cancelled
   */
  static boolean cancelsStale(final boolean crawlLive, final boolean jobExecuting) {
    return !crawlLive && !jobExecuting;
  }

  /**
   * Is this the frame to post?
   *
   * @param lastPostedMs
   *          when the last frame was posted, or {@link #NEVER}
   * @param nowMs
   *          the reading of the same clock now
   * @param minIntervalMs
   *          the shortest gap between two posted frames
   * @param finalFrame
   *          whether this frame reports the end of the mirror
   * @return true when this frame must be posted
   */
  static boolean shouldPost(final long lastPostedMs, final long nowMs,
      final long minIntervalMs, final boolean finalFrame) {
    if (finalFrame || lastPostedMs == NEVER) {
      return true;
    }
    final long since = nowMs - lastPostedMs;
    // A clock that went backwards would otherwise hold the notification for the whole interval.
    return since < 0 || since >= minIntervalMs;
  }
}
