package com.httrack.android;

import java.util.ArrayList;
import java.util.List;

/**
 * The command line the engine is started with. The prelude is positional: the engine counts URLs
 * from the first argument that is not an option, so nothing may be appended after the options.
 */
final class CrawlArgv {
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
   * The argv as one log line, with any "user:password@" blanked out. A URL and the proxy address
   * are both free text, and the engine takes credentials in them, so the raw argv is not loggable.
   *
   * @param argv
   *          the argv as handed to the engine
   * @return the printable form, arguments separated by a space
   */
  static String printable(final String[] argv) {
    final StringBuilder out = new StringBuilder();
    for (final String arg : argv) {
      if (out.length() != 0) {
        out.append(' ');
      }
      out.append(arg.replaceAll("[^/@:\\s]+:[^/@\\s]*@", "***@"));
    }
    return out.toString();
  }
}
