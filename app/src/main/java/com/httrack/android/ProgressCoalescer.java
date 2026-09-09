package com.httrack.android;

/**
 * At most one pending UI update, always carrying the newest payload. One posted task per engine
 * refresh grows the main thread's queue until the app stops answering input, and the frames dropped
 * in between cost nothing, because each one replaces the whole progress pane anyway.
 */
final class ProgressCoalescer<T> {
  /** The frame to draw next, and null exactly when no task has been posted to draw one. */
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
   * Take the payload to draw, and let the next offer schedule again. Clearing here rather than
   * after the drawing gives a refresh that lands mid-draw a task of its own.
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
