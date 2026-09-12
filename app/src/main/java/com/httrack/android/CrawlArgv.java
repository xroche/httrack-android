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
   *          the mirror directory, the engine's -O argument
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
    args.add("-O");
    args.add(targetPath);
    args.addAll(options);
    return args.toArray(new String[] {});
  }
}
