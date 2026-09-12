package com.httrack.android;

import java.io.File;

/**
 * What a crawl left behind, and what the app does about it once the activity that started it is
 * gone. Each decision takes its inputs explicitly, so it can be checked against a truth table;
 * the resume offer also reads the project directories to find them.
 */
final class ResumePolicy {
  // The notice is one line of the welcome pane, not a project listing.
  private static final int MAX_NAMED = 3;

  private ResumePolicy() {
  }

  /**
   * What the welcome pane says about the projects a crawl left unfinished, since nothing else on
   * a cold launch points at one.
   *
   * @param template a message with a single %s for the names
   * @param andMore  a suffix with a single %s for the count the names leave out
   * @param root     the directory holding every project
   * @param names    the project names to examine, as listed on disk
   * @return the message, or null when nothing reopens on "Continue interrupted download"
   */
  static String resumeNotice(final String template, final String andMore,
      final File root, final String[] names) {
    if (template == null || root == null || names == null) {
      return null;
    }
    final StringBuilder joined = new StringBuilder();
    int resumable = 0;
    for (final String name : names) {
      if (name == null
          || !HTTrackActivity.isInterruptedProfile(new File(root, name))) {
        continue;
      }
      if (resumable++ < MAX_NAMED) {
        joined.append(joined.length() == 0 ? "" : ", ").append(name);
      }
    }
    if (resumable == 0) {
      return null;
    }
    if (resumable > MAX_NAMED && andMore != null) {
      joined.append(", ")
          .append(andMore.replace("%s", String.valueOf(resumable - MAX_NAMED)));
    }
    return template.replace("%s", joined.toString());
  }

  /**
   * Should an intent's saved state be loaded? A notification carries the project it was posted
   * for, which would overwrite the settings of a crawl still running.
   *
   * @param hasExtras whether the intent carries a saved state
   * @param crawlLive whether a crawl is still running in this activity
   * @return true when the state may be restored
   */
  static boolean restoresIntentState(final boolean hasExtras,
      final boolean crawlLive) {
    return hasExtras && !crawlLive;
  }

  /**
   * Should a stop write the "work left" marker? Only one that lands before the run reached its
   * own verdict, which is the reading the finished mirror keeps.
   *
   * @param ended           whether the run has left runInternal()
   * @param verdictRecorded whether the run already decided what it left behind
   * @return true when the stop is the only thing that can record an interruption
   */
  static boolean stopWritesMarker(final boolean ended,
      final boolean verdictRecorded) {
    return !ended && !verdictRecorded;
  }
}
