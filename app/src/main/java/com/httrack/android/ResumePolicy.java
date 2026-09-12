package com.httrack.android;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * What a crawl left behind, and what the app does about it once the activity that started it is
 * gone. Only files and flags appear here, so each decision can be checked against its truth
 * table.
 */
final class ResumePolicy {
  private ResumePolicy() {
  }

  /**
   * Where the resume marker goes, the run's own directory first so a detached end can still
   * stamp it.
   *
   * @param runTarget      the project directory the run captured when it started
   * @param attachedTarget the directory the activity names now, if one is attached
   * @return the directory to stamp, or null when neither is known
   */
  static File markerDirectory(final File runTarget, final File attachedTarget) {
    return runTarget != null ? runTarget : attachedTarget;
  }

  /**
   * Can the top index be built with no activity? It takes two paths and the engine, and a
   * missing one leaves nothing an activity could add later.
   *
   * @param projectRoot the directory holding every project
   * @param resources   the extracted HTML resource directory
   * @return true when the build can run as it stands
   */
  static boolean topIndexRunsHeadless(final File projectRoot, final File resources) {
    return projectRoot != null && resources != null;
  }

  /**
   * The projects a crawl left unfinished, in listing order.
   *
   * @param root  the directory holding every project
   * @param names the project names to examine, as listed on disk
   * @return the names that reopen on "Continue interrupted download"
   */
  static List<String> resumableProjects(final File root, final String[] names) {
    final List<String> resumable = new ArrayList<String>();
    if (root != null && names != null) {
      for (final String name : names) {
        if (name != null
            && HTTrackActivity.isInterruptedProfile(new File(root, name))) {
          resumable.add(name);
        }
      }
    }
    return resumable;
  }

  /**
   * What the welcome pane says about them, since a cold launch carries no state pointing at one.
   *
   * @param template a message with a single %s for the names
   * @param names    the unfinished projects
   * @return the message, or null when there is nothing to say
   */
  static String resumeNotice(final String template, final List<String> names) {
    if (template == null || names == null || names.isEmpty()) {
      return null;
    }
    final StringBuilder joined = new StringBuilder();
    for (final String name : names) {
      joined.append(joined.length() == 0 ? "" : ", ").append(name);
    }
    return template.replace("%s", joined.toString());
  }

  /**
   * Should an intent's saved state be loaded? A notification carries the project it was posted
   * for, which would overwrite the settings of a crawl still running.
   *
   * @param hasExtras     whether the intent carries a saved state
   * @param crawlAttached whether a runner is attached to the activity
   * @return true when the state may be restored
   */
  static boolean restoresIntentState(final boolean hasExtras, final boolean crawlAttached) {
    return hasExtras && !crawlAttached;
  }
}
