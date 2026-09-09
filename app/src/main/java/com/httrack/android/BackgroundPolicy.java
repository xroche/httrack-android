package com.httrack.android;

/**
 * These decide where a back press goes while a crawl runs. Finishing the activity would take the
 * retained fragment, and the crawl it holds, down with it, so back only moves the task out of
 * the way. No Android type appears here, so each decision can be checked against its truth table.
 */
final class BackgroundPolicy {
  private BackgroundPolicy() {
  }

  /**
   * Should the launcher be asked, the only way out once the task refuses to move?
   *
   * @param movedToBack whether the task went to the background
   * @return true when the home intent must be fired
   */
  static boolean askTheLauncher(final boolean movedToBack) {
    return !movedToBack;
  }

  /**
   * Did the app stay on screen because both ways out failed, not just one?
   *
   * @param movedToBack whether the task went to the background
   * @param wentHome    whether the launcher took the home intent
   * @return true when the app is still in front of the user
   */
  static boolean stillOnScreen(final boolean movedToBack, final boolean wentHome) {
    return !movedToBack && !wentHome;
  }
}
