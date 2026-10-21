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
import java.util.LinkedHashMap;
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

  /** Longest domain the loader keeps, because cookie_load_field refuses len >= its buffer. */
  static final int MAX_DOMAIN = 255;

  /**
   * Longest name plus value the engine's jar keeps. cookie_add refuses a pair over
   * COOKIE_MAX_PAIR, which is RFC 6265's limit, so neither field has a cap of its own.
   * A record can parse and still never reach the jar, so this is the gate that decides.
   * BrowserLoginEngineLimitsTest checks it against the pinned engine.
   */
  static final int MAX_PAIR = 4096;

  /**
   * Bytes we may write. The engine's jar holds 30000 and loads the mirror's own cookies.txt
   * first, so this leaves room for both.
   */
  static final int MAX_BYTES = 20000;

  /* Both lines are spellings cookie_known_comment accepts, so the second names the engine
     rather than describing us. An unrecognised comment counts as a line a rewrite would lose,
     and the engine then refuses to save its jar back, which costs us every cookie the site
     rotated during the crawl. */
  static final String HEADER = "# Netscape HTTP Cookie File\n"
      + "# Written by HTTrack Website Copier\n";

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
    if (!OptionValues.fitsBytes(host, MAX_DOMAIN)) {
      return null;
    }
    // Name and value share one budget. An over-long name leaves a negative one, so this
    // single test refuses the pair whichever side is at fault.
    if (utf8Length(value) > MAX_PAIR - utf8Length(name)) {
      return null;
    }
    /* No length check on the whole line: the three caps above bound it well under the
       line_max at which cookie_load drops a line, which BrowserLoginEngineLimitsTest proves. */
    /* Field 1 FALSE is host-only, which cookie_is_host_only reads. The browser told us what
       it sends to this host, so widening it to every subdomain would send a session we never
       saw there. */
    return host + "\tFALSE\t/\tFALSE\t0\t" + name + "\t" + value + "\n";
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
      /* getCookie lists a name once per scope, and every record flattens onto the host we
         asked about, so two scopes of one name would repeat in the file. The engine refuses to
         rewrite a file holding a repeat, so only one may go in. Keep the last, which is both
         what the engine itself keeps and the newer cookie: RFC 6265 orders equal paths by
         creation time, earliest first, so a session set during the login sorts last. */
      final Map<String, String> byName = new LinkedHashMap<String, String>();
      for (final String[] cookie : split(entry.getValue())) {
        byName.put(cookie[0], cookie[1]);
      }
      boolean used = false;
      for (final Map.Entry<String, String> cookie : byName.entrySet()) {
        final String line = line(entry.getKey(), cookie.getKey(), cookie.getValue());
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
