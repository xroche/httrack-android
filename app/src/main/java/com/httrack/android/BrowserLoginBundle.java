/*
HTTrack Android Java Interface.

HTTrack Website Copier, Offline Browser for Windows and Unix
Copyright (C) Xavier Roche and other contributors

This program is free software; you can redistribute it and/or
modify it under the terms of the GNU General Public License
as published by the Free Software Foundation; either version 3
of the License, or any later version.

This program is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU General Public License for more details.

You should have received a copy of the GNU General Public License
along with this program; if not, write to the Free Software
Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA  02111-1307, USA.
 */


package com.httrack.android;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * The login bundle the engine reads, which carries the browser's identity along with its
 * cookies. A start URL ending "#httrack-bundle=<name>" names the file, and the engine sends
 * the identity to that bundle's own sites alone, where our old -F sent it to every host.
 */
public final class BrowserLoginBundle {
  private BrowserLoginBundle() {
  }

  /** The only first line the engine's bundle_version() accepts for version 1. */
  static final String HEADER = "# HTTrack bundle 1";

  /** What a start URL ends with to name a bundle, HTS_BUNDLE_SUFFIX. */
  static final String SUFFIX = "#httrack-bundle=";

  /** The extension the engine adds to the name to find the file. */
  static final String EXTENSION = ".hts";

  /** Longest line the engine reads, BUNDLE_LINE_MAX, the line end aside. */
  static final int MAX_LINE = 8192;

  /** Longest bundle name, HTS_BUNDLE_NAME_MAX, the NUL included. */
  static final int MAX_NAME = 128;

  /** Bytes, not characters: the engine measures every limit with strlen(). */
  private static int utf8Length(final String s) {
    return s.getBytes(StandardCharsets.UTF_8).length;
  }

  /** Whether KEY VALUE is a line the engine reads, so writing it cannot stop the run. */
  static boolean fits(final String key, final String value) {
    return value != null && value.length() != 0 && !OptionValues.hasControlCharacter(value)
        && utf8Length(key) + 1 + utf8Length(value) < MAX_LINE;
  }

  /**
   * The bundle's text.
   *
   * @param agent
   *          the browser's User-Agent, dropped when the engine would refuse its line. The
   *          cookies are worth more than the identity, so a refused agent costs only itself.
   * @param scope
   *          every registrable domain the bundle's cookies may reach, or empty to let the
   *          engine take the registrable domain of the start URL that names the bundle. A
   *          scope line REPLACES that default, so a caller passing one must pass them all.
   * @param records
   *          the Netscape records, as {@link BrowserLoginCookies#file} builds them
   * @return the file text
   */
  static String text(final String agent, final List<String> scope, final String records) {
    final StringBuilder text = new StringBuilder(HEADER).append('\n');
    if (fits("ua", agent)) {
      text.append("ua ").append(agent).append('\n');
    }
    for (final String domain : scope) {
      if (fits("scope", domain)) {
        text.append("scope ").append(domain).append('\n');
      }
    }
    // Every line after this one is a cookie record, which is how the engine knows where to stop.
    text.append("cookies\n").append(records);
    return text.toString();
  }

  /**
   * TOKEN with its bundle suffix cut when DIR holds no such bundle. The engine stops the whole
   * run on a bundle it cannot read, so a login the user forgot, or app storage the user
   * cleared, would otherwise leave the project unable to start at all.
   *
   * @param token
   *          one token of the address field
   * @param dir
   *          where this project's bundles are kept, or null when it has none
   * @return TOKEN, or TOKEN without the suffix it names no file with
   */
  static String withoutMissingBundle(final String token, final File dir) {
    final int at = token == null ? -1 : token.indexOf(SUFFIX);
    if (at == -1) {
      return token;
    }
    final File bundle = StoragePaths.bundleInDir(dir, token.substring(at + SUFFIX.length()));
    return bundle != null && bundle.isFile() ? token : token.substring(0, at);
  }

  /**
   * The start URL that names the bundle NAME.
   *
   * @param address
   *          the address the login ended on
   * @param name
   *          the bundle's name, which the engine has accepted
   * @return the address with the suffix appended, or ADDRESS when it already names a bundle.
   *         The engine cuts at the FIRST suffix, so a second one would leave a '#' in the name
   *         and stop the run.
   */
  static String startUrl(final String address, final String name) {
    if (address == null || name == null || address.indexOf(SUFFIX) != -1) {
      return address;
    }
    return address + SUFFIX + name;
  }
}
