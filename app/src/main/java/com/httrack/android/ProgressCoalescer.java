package com.httrack.android;

/**
 * At most one pending UI update, always carrying the newest payload. One posted task per engine
 * refresh grows the main thread's queue until the app stops answering input, and the frames dropped
 * in between cost nothing, because each one replaces the whole progress pane anyway.
 */
final class ProgressCoalescer<T> {
  private T pending;
  private boolean armed;

  /**
   * Keep this payload as the one to draw next.
   *
   * @param payload the newest progress, never null
   * @return true when the caller must schedule the drawing task
   */
  synchronized boolean offer(final T payload) {
    if (payload == null) {
      throw new NullPointerException("payload");
    }
    pending = payload;
    if (armed) {
      return false;
    }
    armed = true;
    return true;
  }

  /**
   * Take the payload to draw, and let the next offer schedule again. Disarming here rather than
   * after the drawing gives a refresh that lands mid-draw a task of its own.
   *
   * @return the newest payload, or null when nothing is pending
   */
  synchronized T take() {
    final T payload = pending;
    pending = null;
    armed = false;
    return payload;
  }
}
