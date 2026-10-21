package com.httrack.android;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * What the browser login opens, and what it changes about the crawl. A logged-in crawl has to
 * stay quiet and keep away from the links that end the session, so the hand-off settles those.
 */
final class BrowserLoginPolicy {
  private BrowserLoginPolicy() {
  }

  /** Connections a logged-in crawl may open: a live session plus many sockets reads as abuse. */
  static final int MAX_SOCKETS = 2;

  /** Keeps the crawl off the links that end the session. The engine's last match wins. */
  static final String LOGOUT_FILTER = "-*logout*";

  /** The login window only opens this scheme, because a password does not belong on cleartext. */
  static final String SCHEME = "https";

  /** A leading "scheme:", which tells an address from a bare host name. */
  private static final String HAS_SCHEME = "^[a-zA-Z][a-zA-Z0-9+.-]*:.*";

  /**
   * The address to open for the login, taken from the project's address field. An http address
   * is raised to https, which leaves the addresses the crawl itself uses untouched.
   *
   * @param urlField
   *          the field's text, one or more whitespace-separated addresses
   * @return the first address we may open, or null when the field holds none
   */
  static String startUrl(final String urlField) {
    // urlTokens also drops what a tampered profile puts in that field, such as an engine option.
    for (final String token : CommandlineTokens.urlTokens(urlField)) {
      final String url = secureUrl(token);
      if (hostOf(url) != null) {
        return url;
      }
    }
    return null;
  }

  /** TOKEN as an https address: a bare host gains the scheme, and http is raised to it. */
  private static String secureUrl(final String token) {
    if (!token.matches(HAS_SCHEME)) {
      return SCHEME + "://" + token;
    }
    if (token.regionMatches(true, 0, "http://", 0, "http://".length())) {
      return SCHEME + "://" + token.substring("http://".length());
    }
    return token;
  }

  /**
   * The host URL names, lowercased the way the engine compares a cookie domain.
   *
   * @return the host, or null unless URL is an https address naming one
   */
  static String hostOf(final String url) {
    if (url == null) {
      return null;
    }
    final URI uri;
    try {
      uri = new URI(url);
    } catch (final URISyntaxException e) {
      return null;
    }
    final String host = uri.getHost();
    // Only https: a WebView given a file: or a content: address would read our own storage.
    if (host == null || host.length() == 0 || uri.getScheme() == null
        || !uri.getScheme().equalsIgnoreCase(SCHEME)) {
      return null;
    }
    return host.toLowerCase(Locale.ROOT);
  }

  /**
   * Every host the crawl starts from, which the capture needs besides the hosts the user
   * visited. A host-only cookie reaches that host alone, and CookieManager answers for a host
   * the browser never opened, so asking for these costs nothing and no visit.
   *
   * @param urlField
   *          the field's text, one or more whitespace-separated addresses
   * @return the hosts, empty when the field names none
   */
  static Set<String> crawlHosts(final String urlField) {
    final Set<String> out = new LinkedHashSet<String>();
    for (final String token : CommandlineTokens.urlTokens(urlField)) {
      final String host = hostOf(secureUrl(token));
      if (host != null) {
        out.add(host);
      }
    }
    return out;
  }

  /**
   * The Set-Cookie value that drops NAME from DOMAIN, or from the host itself when DOMAIN is
   * null. CookieManager has no per-site removal, so a cookie is dropped by overwriting it with
   * one already past its expiry.
   *
   * @param name
   *          the cookie's name
   * @param domain
   *          the domain to drop it from, or null for a host-only cookie
   * @return the value to hand setCookie
   */
  static String expiredCookie(final String name, final String domain) {
    final String header = name + "=; Max-Age=0; Path=/";
    return domain == null ? header : header + "; Domain=" + domain;
  }

  /**
   * The parent domains of HOST, longest first, stopping before the last two labels. A session
   * cookie is often set on a parent, and dropping it from the host alone would leave it live.
   *
   * @param host
   *          a lowercased host name
   * @return the parents, empty for a host with no more than two labels
   */
  static List<String> parentDomains(final String host) {
    final List<String> out = new ArrayList<String>();
    if (host == null) {
      return out;
    }
    /* A public suffix needs no special case: the browser refuses a cookie on one, so an
       attempt at "co.uk" is turned down rather than widening anything. */
    for (int cut = host.indexOf('.'); cut != -1; cut = host.indexOf('.', cut + 1)) {
      final String parent = host.substring(cut + 1);
      if (parent.indexOf('.') == -1) {
        break;
      }
      out.add(parent);
    }
    return out;
  }

  /**
   * The connection count to use, which only ever goes down.
   *
   * @param current
   *          the Sockets value the project holds, possibly empty or absent
   */
  static String sockets(final String current) {
    final String value = current == null ? "" : current.trim();
    /* The same ASCII-digit test the option mapper applies, because Integer.parseInt also takes
       an Arabic-Indic digit that the mapper then refuses, dropping -c and leaving the crawl on
       the engine's own higher default. */
    if (OptionValues.isAtMost(value, MAX_SOCKETS)) {
      final int count = Integer.parseInt(value);
      if (count > 0) {
        return String.valueOf(count);
      }
    }
    return String.valueOf(MAX_SOCKETS);
  }

  /**
   * The scan rules to use, with the logout filter appended when nothing there mentions logout.
   *
   * @param current
   *          the WildCardFilters value the project holds, possibly empty or absent
   */
  static String rules(final String current) {
    final String rules = current == null ? "" : current.trim();
    if (rules.toLowerCase(Locale.ROOT).indexOf("logout") != -1) {
      return rules;
    }
    return rules.length() == 0 ? LOGOUT_FILTER : rules + " " + LOGOUT_FILTER;
  }
}
