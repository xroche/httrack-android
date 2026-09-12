package com.httrack.android;

/**
 * Whether the window must stop the display from timing out. Android implements the flag as a
 * system-owned SCREEN_BRIGHT_WAKE_LOCK, so the app holds no wake lock of its own, and the
 * protection ends as soon as the user leaves the app. No Android type appears here, so the
 * decision can be checked against its truth table.
 */
final class ScreenOnPolicy {
  private ScreenOnPolicy() {
  }

  /**
   * Should the display be kept awake?
   *
   * @param preferred      whether the user ticked the option
   * @param onProgressPane whether the pane on screen is the one a running crawl reports on
   * @param crawlRunning   whether a crawl is attached and has not ended
   * @return true when the window must carry FLAG_KEEP_SCREEN_ON
   */
  static boolean keepScreenOn(final boolean preferred, final boolean onProgressPane,
      final boolean crawlRunning) {
    return preferred && onProgressPane && crawlRunning;
  }
}
