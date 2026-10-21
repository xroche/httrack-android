package com.httrack.android;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;

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
   * The address field with LANDED added on a line of its own. Where the user ended up is what
   * they opened the browser to reach, and a login usually ends deeper than it began. The
   * addresses already there are left alone, so the user trims what they do not want.
   *
   * @param urlField
   *          the field's text, one or more whitespace-separated addresses
   * @param landed
   *          the address the login ended on, which is ignored unless it is an https address
   *          naming a host
   * @return the field's new text
   */
  static String withLandedUrl(final String urlField, final String landed) {
    final String current = urlField == null ? "" : urlField.trim();
    final String address = crawlRoot(landed);
    if (address == null || !namesTheSameSite(current, hostOf(address))) {
      return current;
    }
    for (final String token : CommandlineTokens.urlTokens(current)) {
      if (secureUrl(token).equals(address)) {
        return current;
      }
    }
    return current.length() == 0 ? address : current + "\n" + address;
  }

  /**
   * LANDED as an address the crawl may start from, or null when it is none. The address field
   * is saved into the project's profile and handed to the engine, so a secret must not reach
   * it: userinfo carries a password, and a query or fragment carries an OAuth code or token.
   *
   * @param landed
   *          the address the login ended on
   * @return the address without its query or fragment, or null
   */
  private static String crawlRoot(final String landed) {
    if (hostOf(landed) == null) {
      return null;
    }
    final URI uri;
    try {
      uri = new URI(landed);
    } catch (final URISyntaxException e) {
      return null;
    }
    // Dropped rather than stripped: the engine would authenticate with it, which we never meant.
    if (uri.getUserInfo() != null) {
      return null;
    }
    final int query = landed.indexOf('?');
    final int fragment = landed.indexOf('#');
    int cut = landed.length();
    if (query != -1) {
      cut = query;
    }
    if (fragment != -1 && fragment < cut) {
      cut = fragment;
    }
    return landed.substring(0, cut);
  }

  /**
   * Whether HOST belongs to a site the address field already names. A login can end on an
   * identity provider, and adding that would set the crawl copying someone else's site with a
   * live session. Either direction counts, because a login moves between a site and its
   * subdomains in both.
   *
   * @param urlField
   *          the field's text
   * @param host
   *          the landed address's host
   * @return true when the field names that host, a parent of it, or a child of it
   */
  private static boolean namesTheSameSite(final String urlField, final String host) {
    if (host == null) {
      return false;
    }
    for (final String token : CommandlineTokens.urlTokens(urlField)) {
      final String known = hostOf(secureUrl(token));
      if (known != null && (known.equals(host) || known.endsWith("." + host)
          || host.endsWith("." + known))) {
        return true;
      }
    }
    return false;
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
