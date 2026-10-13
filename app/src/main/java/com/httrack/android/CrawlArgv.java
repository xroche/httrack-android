package com.httrack.android;

import java.util.ArrayList;
import java.util.List;

/**
 * The command line the engine is started with. The prelude is positional: the engine counts URLs
 * from the first argument that is not an option, so nothing may be appended after the options.
 */
final class CrawlArgv {
  /** -%X carries a whole header line, so its value can hold an Authorization or Cookie secret. */
  private static final String HEADER_OPTION = "-%X";

  /** The mirror directory rides -O1 and is a local path, so its text is never a credential. */
  private static final String TARGET_OPTION = "-O1";

  private CrawlArgv() {
  }

  /**
   * Build the whole engine argv.
   *
   * @param ipv6Enabled
   *          whether this device has an IPv6 address; without one the engine is pinned to IPv4
   * @param targetPath
   *          the mirror directory, the engine's -O1 argument
   * @param options
   *          the options the mapper emitted, appended in order
   * @return the argv, program name first
   */
  static String[] build(final boolean ipv6Enabled, final String targetPath,
      final List<String> options) {
    final List<String> args = new ArrayList<String>();
    args.add("httrack");
    if (!ipv6Enabled) {
      args.add("-@i4");
    }
    /* -O1, not -O: -O splits "html,log" on an unquoted comma, which truncates a
     * comma-bearing project name. */
    args.add("-O1");
    args.add(targetPath);
    args.addAll(options);
    return args.toArray(new String[] {});
  }

  /**
   * The argv as one log line, with credentials blanked out. A URL and the proxy address are free
   * text and the engine takes a password in either, so the raw argv is not loggable. Redacting too
   * much only costs log detail, so anything shaped like userinfo goes.
   *
   * @param argv
   *          the argv as handed to the engine
   * @return the log line, arguments separated by a space
   */
  static String redacted(final String[] argv) {
    final StringBuilder out = new StringBuilder();
    String option = "";
    for (final String arg : argv) {
      if (out.length() != 0) {
        out.append(' ');
      }
      if (HEADER_OPTION.equals(option)) {
        out.append("***");
      } else if (TARGET_OPTION.equals(option) || arg.startsWith("-")) {
        out.append(arg);
      } else {
        // Up to the LAST '@' of each run, because the engine reads that one as the end of the
        // userinfo and a password may itself contain '@'.
        out.append(arg.replaceAll("[^/\\s]*@", "***@"));
      }
      option = arg;
    }
    return out.toString();
  }
}
