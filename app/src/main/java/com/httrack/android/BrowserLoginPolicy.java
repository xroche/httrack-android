package com.httrack.android;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

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

  /** What a start URL ends with to name a login bundle, HTS_BUNDLE_SUFFIX in the engine. */
  static final String BUNDLE_SUFFIX = "#httrack-bundle=";

  /**
   * The address field with the login's own start URL added on a line of its own. The engine
   * built that URL: it cut the fragment, refused a password and named the bundle, so nothing
   * here inspects it again. The addresses already there are left alone, so the user trims what
   * they do not want.
   *
   * @param urlField
   *          the field's text, one or more whitespace-separated addresses
   * @param bundleUrl
   *          the start URL the engine wrote, or null when it wrote none
   * @return the field's new text
   */
  static String withBundleUrl(final String urlField, final String bundleUrl) {
    final String current = urlField == null ? "" : urlField.trim();
    if (bundleUrl == null || bundleUrl.length() == 0) {
      return current;
    }
    for (final String token : CommandlineTokens.urlTokens(current)) {
      if (token.equals(bundleUrl)) {
        return current;
      }
    }
    return current.length() == 0 ? bundleUrl : current + "\n" + bundleUrl;
  }

  /**
   * The address field with every bundle name dropped, which is what Forget leaves behind. A
   * start URL naming a bundle that is gone stops the crawl at startup rather than running it
   * logged out, so the name goes when the file does.
   *
   * @param urlField
   *          the field's text
   * @return the text with each "#httrack-bundle=NAME" cut out, the rest byte for byte
   */
  static String withoutBundleNames(final String urlField) {
    /* The token only, not the rest of the line: the field separates addresses on whitespace,
       so cutting to the end of the line would eat the address after this one. */
    return urlField == null ? "" : urlField.replaceAll(Pattern.quote(BUNDLE_SUFFIX) + "\\S*", "");
  }

  /**
   * The bundle STARTURL names.
   *
   * @param startUrl
   *          a start URL the engine wrote
   * @return the name, or null when STARTURL names no bundle
   */
  static String bundleNameOf(final String startUrl) {
    final int at = startUrl == null ? -1 : startUrl.indexOf(BUNDLE_SUFFIX);
    return at == -1 ? null : startUrl.substring(at + BUNDLE_SUFFIX.length());
  }

  /**
   * Did the login end on a site the address field did not name? A login can end on an identity
   * provider, and that address is then a second crawl root with a live session, so the user is
   * told. The address is still filled in, because a field the user has to retype is worse.
   *
   * @param urlFieldBeforeLogin
   *          the field's text as it was BEFORE the login, because the answer is always false
   *          once the login's own address has been added to it
   * @param bundleUrl
   *          the start URL the engine wrote
   * @return true when BUNDLEURL names a host no address in the field shares a site with
   */
  static boolean landedElsewhere(final String urlFieldBeforeLogin, final String bundleUrl) {
    final String host = hostOf(bundleUrl);

    return host != null && !namesTheSameSite(
        urlFieldBeforeLogin == null ? "" : urlFieldBeforeLogin, host);
  }

  /**
   * Whether HOST belongs to a site the address field already names. Either direction counts,
   * because a login moves between a site and its subdomains in both.
   *
   * @param urlField
   *          the field's text
   * @param host
   *          the host the login ended on
   * @return true when the field names that host, a parent of it, or a child of it
   */
  private static boolean namesTheSameSite(final String urlField, final String host) {
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
   * Every host the address field names, which are the hosts a crawl will start from.
   *
   * @param urlField
   *          the field's text
   * @return the hosts, in the field's order, without repeats
   */
  static List<String> fieldHosts(final String urlField) {
    final List<String> found = new ArrayList<String>();
    for (final String token : CommandlineTokens.urlTokens(urlField)) {
      final String host = hostOf(secureUrl(token));
      if (host != null && !found.contains(host)) {
        found.add(host);
      }
    }
    return found;
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
