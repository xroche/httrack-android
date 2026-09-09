package com.httrack.android;

/**
 * Holds at most one pending update, always the newest. One posted task per engine refresh grows
 * the main thread's queue until the app stops answering input. The frames dropped in between cost
 * nothing, because each one replaces the whole progress pane.
 */
final class ProgressCoalescer<T> {
  /** Holds the frame to draw next, and is null exactly when no task has been posted. */
  private T pending;

  /**
   * Keep this payload as the one to draw next, and say whether a task must now be posted.
   *
   * @param payload the newest progress, never null, since a null would read as nothing pending
   * @return true when the caller must schedule the drawing task, false when a pending one will
   *         carry this payload instead
   */
  synchronized boolean offerNeedsPost(final T payload) {
    if (payload == null) {
      throw new NullPointerException("payload");
    }
    final boolean needsPost = pending == null;
    pending = payload;
    return needsPost;
  }

  /**
   * Take the payload to draw, and let the next offer schedule again. Clear before the drawing, not
   * after, so a refresh landing mid-draw gets a task of its own.
   *
   * @return the newest payload, or null when nothing is pending
   */
  synchronized T take() {
    final T payload = pending;
    disarm();
    return payload;
  }

  /** Drop whatever is pending and let the next offer schedule again. */
  synchronized void disarm() {
    pending = null;
  }
}
