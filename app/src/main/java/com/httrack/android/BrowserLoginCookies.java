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

import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The Netscape cookies.txt the in-app browser login hands to the engine through -%K.
 *
 * WebView gives us a "name=value; name2=value2" header string per host and no attributes, so a
 * line states the host it came from, every subdomain of it, path "/" and a session expiry.
 * Every length here is in bytes, because the engine's parse measures with strlen().
 */
public final class BrowserLoginCookies {
  private BrowserLoginCookies() {
  }

  /** What cookie_load() reads a record's fields into, so a longer one is skipped there. */
  static final int MAX_DOMAIN = 255;
  static final int MAX_NAME = 1023;
  static final int MAX_VALUE = 8191;

  /** cookie_load() drops a whole line at or above its line_max of 8000. */
  static final int MAX_LINE = 7999;

  /**
   * Bytes we may write. The engine's jar holds 30000 and loads the mirror's own cookies.txt
   * first, so this leaves that file room and the jar room for its longer expiry field.
   */
  static final int MAX_BYTES = 20000;

  private static final Charset UTF8 = Charset.forName("UTF-8");

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

  static int bytes(final String s) {
    return s.getBytes(UTF8).length;
  }

  /**
   * Whether FIELD can go in a record. A tab or a newline would split the line somewhere the
   * engine's parse does not expect, so such a value never reaches the file.
   */
  static boolean isClean(final String field) {
    if (field == null) {
      return false;
    }
    for (int i = 0; i < field.length(); i++) {
      final char c = field.charAt(i);
      if (c == '\t' || c == '\n' || c == '\r' || c < ' ' || c == 0x7f) {
        return false;
      }
    }
    return true;
  }

  /**
   * One record for cookie NAME on HOST. A cookie may hold an empty value, but not an empty name.
   *
   * @return the line, newline included, or null when the engine would skip it
   */
  static String line(final String host, final String name, final String value) {
    if (!isClean(host) || !isClean(name) || !isClean(value)) {
      return null;
    }
    // cookie_load() skips any line starting with '#', and the host leads every record.
    if (host.length() == 0 || name.length() == 0 || host.charAt(0) == '#') {
      return null;
    }
    if (bytes(host) > MAX_DOMAIN || bytes(name) > MAX_NAME || bytes(value) > MAX_VALUE) {
      return null;
    }
    final String line = host + "\tTRUE\t/\tFALSE\t0\t" + name + "\t" + value;
    return bytes(line) <= MAX_LINE ? line + "\n" : null;
  }

  /**
   * Split a {@code CookieManager.getCookie()} header into name and value pairs.
   *
   * @return two entries per cookie, the name then the value, in the header's order
   */
  static List<String> split(final String header) {
    final List<String> pairs = new ArrayList<String>();
    if (header == null) {
      return pairs;
    }
    for (final String item : header.split(";")) {
      final String entry = item.trim();
      final int equals = entry.indexOf('=');
      // A value may hold '=', so only the first one separates. A nameless entry is not a cookie.
      if (equals > 0) {
        pairs.add(entry.substring(0, equals).trim());
        pairs.add(entry.substring(equals + 1).trim());
      }
    }
    return pairs;
  }

  /**
   * Build the file from what each host's cookie header held.
   *
   * @param cookiesByHost
   *          host to the header {@code CookieManager.getCookie()} returned for it, in visit order
   */
  public static Result file(final Map<String, String> cookiesByHost) {
    final StringBuilder text = new StringBuilder(HEADER);
    int size = bytes(HEADER);
    int cookies = 0;
    int hosts = 0;
    int dropped = 0;
    for (final Map.Entry<String, String> entry : cookiesByHost.entrySet()) {
      final List<String> pairs = split(entry.getValue());
      boolean used = false;
      for (int i = 0; i + 1 < pairs.size(); i += 2) {
        final String line = line(entry.getKey(), pairs.get(i), pairs.get(i + 1));
        if (line == null || size + bytes(line) > MAX_BYTES) {
          dropped++;
          continue;
        }
        text.append(line);
        size += bytes(line);
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
