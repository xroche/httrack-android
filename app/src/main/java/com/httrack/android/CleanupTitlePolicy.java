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
   * Must the delete flow be refused? Deleting a mirror a crawl still holds loses the delete and
   * keeps the session, because the engine recreates what it has open when it winds down. The
   * "..." picker is not gated, because choosing a project removes nothing. Phrased as the
   * refusal so its one guard needs no negation, which is the clause a reader inverts by mistake.
   *
   * @param crawlLive true while a crawl owns a mirror, in this window or in the job
   * @param hasProjects true when there is at least one project to show
   * @return true when the delete flow must not open
   */
  static boolean refusesDelete(final boolean crawlLive, final boolean hasProjects) {
    return !hasProjects || crawlLive;
  }
}
