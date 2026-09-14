package com.httrack.android;

import java.util.ArrayList;
import java.util.List;

/**
 * The command line a rescheduled crawl must run. The engine raises HTS_CACHE_PRIORITY by itself
 * when it finds hts-cache/hts-in_progress.lock, then lets the command line override it again, so a
 * retry replaying the argv it inherited downloads the whole mirror a second time.
 */
final class ResumeArgv {
  /** Continue an interrupted mirror: -i is quiet, C1 is HTS_CACHE_PRIORITY. */
  static final String CONTINUE = "-iC1";

  /**
   * Where the forced token goes. An -iC* landing past the first URL makes the engine load
   * hts-cache/doit.log over the command line, and index 1 is ahead of every URL any caller builds.
   */
  static final int CONTINUE_INDEX = 1;

  private ResumeArgv() {
  }

  /**
   * The argv this start must use.
   *
   * @param argv
   *          the argv the first attempt was given
   * @param projectInterrupted
   *          true when an earlier execution of this same crawl left a mirror to resume
   * @return argv itself on a first attempt, otherwise one forcing the resume mode
   */
  static String[] forStart(final String[] argv, final boolean projectInterrupted) {
    // An argv with no program name has no crawl to resume.
    if (argv == null || argv.length == 0 || !projectInterrupted) {
      return argv;
    }
    // Position-blind: a dashvalue_opt field whose value the user typed as -C0 is dropped as well.
    final List<String> out = new ArrayList<String>(argv.length + 1);
    for (final String token : argv) {
      if (!isActionToken(token) && !isCacheToken(token)) {
        out.add(token);
      }
    }
    // An argv whose every token was dropped has no index 1 left to insert at.
    out.add(Math.min(CONTINUE_INDEX, out.size()), CONTINUE);
    return out.toArray(new String[] {});
  }

  /** -iC0, -iC2 and -iC1 itself: the action radio, whose Update mode re-checks the whole site. */
  private static boolean isActionToken(final String token) {
    return digitsAfter(token, "-iC");
  }

  /** -C0 and its kin: the cache checkbox, which is HTS_CACHE_NONE when the user unticks it. */
  private static boolean isCacheToken(final String token) {
    return digitsAfter(token, "-C");
  }

  /* A digitless -C or -iC is already HTS_CACHE_PRIORITY, so only a mode-carrying token has to go. */
  private static boolean digitsAfter(final String token, final String prefix) {
    return token != null && token.startsWith(prefix)
        && OptionValues.isDigits(token.substring(prefix.length()));
  }
}
