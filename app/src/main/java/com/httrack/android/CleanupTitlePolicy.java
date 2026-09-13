package com.httrack.android;

/**
 * Which action-bar title the project picker shows. The delete label only fits the delete flow;
 * the "..." picker reuses the same activity to choose a project, not to remove one.
 */
final class CleanupTitlePolicy {
  private CleanupTitlePolicy() {
  }

  /**
   * Which title string resource fits the picker's mode?
   *
   * @param selecting true when the picker was opened to choose a project, false to delete one
   * @return the string resource id for the action bar title
   */
  static int titleFor(final boolean selecting) {
    return selecting ? R.string.title_activity_cleanup_select
        : R.string.title_activity_cleanup;
  }
}
