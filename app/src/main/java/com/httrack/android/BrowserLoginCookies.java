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

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The Netscape cookies.txt the in-app browser login hands to the engine through -%K.
 *
 * WebView gives us a "name=value; name2=value2" header string per host and no attributes, so a
 * line states the host it came from, every subdomain of it, path "/" and a session expiry.
 */
public final class BrowserLoginCookies {
  private BrowserLoginCookies() {
  }

  /**
   * Longest field the engine's jar keeps. cookie_add() refuses a longer one, which costs the
   * cookie: cookie_load() reads the line into a much larger buffer first, so a record can parse
   * and still never reach the jar. BrowserLoginCookiesTest checks these against the pinned engine.
   */
  static final int MAX_DOMAIN = 255;
  static final int MAX_NAME = 256;
  static final int MAX_VALUE = 1024;

  /**
   * Bytes we may write. The engine's jar holds 30000 and loads the mirror's own cookies.txt
   * first, so this leaves room for both.
   */
  static final int MAX_BYTES = 20000;

  private static final String HEADER = "# Netscape HTTP Cookie File\n"
      + "# Saved by HTTrack Android from the in-app browser login.\n";

  /** What {@link #file} produced, for the message that tells the user what was saved. */
  public static final class Result {
    /** The file text, header included. */
    public final String text;

    public final int cookies;

    /** Hosts that gave at least one cookie. */
    public final int hosts;

    /** Cookies left out, because a field was too long or the budget ran out. */
    public final int dropped;

    Result(final String text, final int cookies, final int hosts, final int dropped) {
      this.text = text;
      this.cookies = cookies;
      this.hosts = hosts;
      this.dropped = dropped;
    }
  }

  /** Bytes, not characters: the engine measures every limit with strlen(). */
  private static int utf8Length(final String s) {
    return s.getBytes(StandardCharsets.UTF_8).length;
  }

  /** Whether FIELD can go in a record. A tab or a newline would split the line. */
  static boolean isClean(final String field) {
    return field != null && !OptionValues.hasControlCharacter(field);
  }

  /**
   * One record for cookie NAME on HOST. A cookie may hold an empty value, but not an empty name.
   *
   * @return the line, newline included, or null when the engine would not keep it
   */
  static String line(final String host, final String name, final String value) {
    if (!isClean(host) || !isClean(name) || !isClean(value)) {
      return null;
    }
    // cookie_load() skips any line starting with '#', and the host leads every record.
    if (host.length() == 0 || name.length() == 0 || host.charAt(0) == '#') {
      return null;
    }
    if (!OptionValues.fitsBytes(host, MAX_DOMAIN) || !OptionValues.fitsBytes(name, MAX_NAME)
        || !OptionValues.fitsBytes(value, MAX_VALUE)) {
      return null;
    }
    /* No length check on the whole line: the three caps above bound it well under the
       line_max at which cookie_load drops a line, which BrowserLoginEngineLimitsTest proves. */
    return host + "\tTRUE\t/\tFALSE\t0\t" + name + "\t" + value + "\n";
  }

  /**
   * Split a {@code CookieManager.getCookie()} header into cookies.
   *
   * @return one two-element array per cookie, the name then the value, in the header's order
   */
  static List<String[]> split(final String header) {
    final List<String[]> cookies = new ArrayList<String[]>();
    if (header == null) {
      return cookies;
    }
    for (final String item : header.split(";")) {
      final String entry = item.trim();
      final int equals = entry.indexOf('=');
      // A value may hold '=', so only the first one separates. A nameless entry is not a cookie.
      if (equals > 0) {
        cookies.add(new String[] { entry.substring(0, equals).trim(),
            entry.substring(equals + 1).trim() });
      }
    }
    return cookies;
  }

  /**
   * Build the file from what each host's cookie header held.
   *
   * @param cookiesByHost
   *          host to the header {@code CookieManager.getCookie()} returned for it, in visit order
   */
  public static Result file(final Map<String, String> cookiesByHost) {
    final StringBuilder text = new StringBuilder(HEADER);
    int size = utf8Length(HEADER);
    int cookies = 0;
    int hosts = 0;
    int dropped = 0;
    for (final Map.Entry<String, String> entry : cookiesByHost.entrySet()) {
      boolean used = false;
      for (final String[] cookie : split(entry.getValue())) {
        final String line = line(entry.getKey(), cookie[0], cookie[1]);
        if (line == null || size + utf8Length(line) > MAX_BYTES) {
          dropped++;
          continue;
        }
        text.append(line);
        size += utf8Length(line);
        cookies++;
        used = true;
      }
      if (used) {
        hosts++;
      }
    }
    return new Result(text.toString(), cookies, hosts, dropped);
  }
}
