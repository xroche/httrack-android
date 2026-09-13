package com.httrack.android;

/**
 * Where a headless crawl's reports go, and what a window draws the moment it attaches to a crawl
 * that is already running. No Android type appears here, so each decision can be checked against
 * its truth table.
 */
final class HandoverPolicy {
  /** Where one report from the crawl goes. */
  enum Delivery {
    /** To the window that registered itself as the listener. */
    ACTIVITY,
    /** Into the session, because only a window can show it and none is attached. */
    HELD,
    /** Nowhere: a progress frame no window can draw is worth nothing later either. */
    DROPPED
  }

  /** What a window draws the moment it attaches. */
  enum Attachment {
    NOTHING, PROGRESS, FINISHED
  }

  private HandoverPolicy() {
  }

  /**
   * Where does this report from the crawl go?
   *
   * @param windowAttached
   *          whether a window has registered itself as the session's listener
   * @param endsTheCrawl
   *          whether this report is the crawl's verdict rather than a progress frame
   * @return where the report goes
   */
  static Delivery delivers(final boolean windowAttached, final boolean endsTheCrawl) {
    if (windowAttached) {
      return Delivery.ACTIVITY;
    }
    return endsTheCrawl ? Delivery.HELD : Delivery.DROPPED;
  }

  /**
   * What must a window draw the moment it attaches? A held verdict wins over everything else,
   * since only a crawl that has ended can have left one.
   *
   * @param crawlLive
   *          whether the session slot holds a crawl that has not ended
   * @param verdictHeld
   *          whether the session holds a verdict no window has shown yet
   * @param statsKnown
   *          whether the session holds a refresh the crawl published
   * @return what to draw
   */
  static Attachment attaches(final boolean crawlLive, final boolean verdictHeld,
      final boolean statsKnown) {
    if (verdictHeld) {
      return Attachment.FINISHED;
    }
    // A symmetric AND, so no truth table can catch a caller that passes these two the wrong way.
    return crawlLive && statsKnown ? Attachment.PROGRESS : Attachment.NOTHING;
  }
}
