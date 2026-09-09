package com.httrack.android;

/**
 * Where a back press goes while a crawl runs. Finishing the activity would take the retained
 * fragment, and the crawl it holds, down with it, so back only moves the task out of the way.
 * No Android type appears here, so each decision can be checked against its truth table.
 */
final class BackgroundPolicy {
  private BackgroundPolicy() {
  }

  /**
   * Ask the launcher for the home screen, the only way out left once the task refuses to move.
   *
   * @param movedToBack whether the task went to the background
   * @return true when the home intent must be fired
   */
  static boolean askTheLauncher(final boolean movedToBack) {
    return !movedToBack;
  }

  /**
   * Report a back press that changed nothing, which neither way out says on its own.
   *
   * @param movedToBack whether the task went to the background
   * @param wentHome    whether the launcher took the home intent
   * @return true when the app is still in front of the user
   */
  static boolean stillOnScreen(final boolean movedToBack, final boolean wentHome) {
    return !movedToBack && !wentHome;
  }
}
