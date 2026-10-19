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

  /**
   * Should the picker open? Deleting a mirror a crawl still holds loses the delete and keeps
   * the session, because the engine recreates what it has open when it winds down. Choosing a
   * project removes nothing, so that flow stays available throughout.
   *
   * @param selecting
   *          true when the picker was opened to choose a project, false to delete one
   * @param crawlLive
   *          true while a crawl owns a mirror, in this window or in the job
   * @param hasProjects
   *          true when there is at least one project to show
   * @return true when the picker may open
   */
  static boolean opens(final boolean selecting, final boolean crawlLive,
      final boolean hasProjects) {
    return hasProjects && (selecting || !crawlLive);
  }
}
