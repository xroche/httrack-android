package com.httrack.android;

import java.io.File;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
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
    final String token = openedToken(urlField);

    return token == null ? null : secureUrl(token);
  }

  /**
   * The field's own text for the address {@link #startUrl} opens, which is the token the login
   * replaces afterwards.
   *
   * @param urlField
   *          the field's text
   * @return the token, exactly as the field holds it, or null when the field names no address
   */
  static String openedToken(final String urlField) {
    // urlTokens also drops what a tampered profile puts in that field, such as an engine option.
    for (final String token : CommandlineTokens.urlTokens(urlField)) {
      if (hostOf(secureUrl(token)) != null) {
        return token;
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
   * The hosts whose cookies the login keeps, as the engine's comma-separated list. A host
   * gets a scope line for its registrable domain and never becomes a crawl root, so the
   * crawl can authenticate on a site the login passed through without copying it.
   *
   * @param hosts
   *          the hosts that answered with a cookie, in the order they were asked
   * @return the list, or null when no host answered, which keeps the engine's older
   *         behaviour of taking the address's own cookies alone
   */
  static String keptHosts(final Collection<String> hosts) {
    final StringBuilder list = new StringBuilder();

    for (final String host : hosts) {
      if (host != null && host.length() != 0) {
        list.append(list.length() == 0 ? "" : ",").append(host);
      }
    }
    return list.length() == 0 ? null : list.toString();
  }

  /**
   * The addresses to offer the engine for this login, best first. The login stops on the page
   * the user wants copied, so that address leads. The engine refuses an address it cannot
   * name, such as a sign-on callback carrying a long token, and the login is then saved under
   * the opened address instead. Nothing is screened here that only the engine can judge.
   *
   * @param landedUrl
   *          the login window's own address, or null while no page has committed
   * @param openedUrl
   *          the address the window was opened for, which carries no credentials
   * @return the addresses to try in order, without repeats
   */
  static List<String> addressesToTry(final String landedUrl, final String openedUrl) {
    final List<String> urls = new ArrayList<String>();
    final String landed = withoutUserInfo(landedUrl);

    if (hostOf(landed) != null) {
      urls.add(landed);
    }
    if (hostOf(openedUrl) != null && !urls.contains(openedUrl)) {
      urls.add(openedUrl);
    }
    return urls;
  }

  /** What a start URL ends with to name a login bundle, HTS_BUNDLE_SUFFIX in the engine. */
  static final String BUNDLE_SUFFIX = "#httrack-bundle=";

  /** One address, or one run of the whitespace between two. */
  private static final Pattern FIELD_PARTS = Pattern.compile("\\S+|\\s+");

  /**
   * URL without the user name and password it may carry. The engine refuses a paste whose
   * address holds either, and the crawl still authenticates from the field's own copy.
   *
   * @param url
   *          an https address
   * @return the address with any userinfo cut out
   */
  static String withoutUserInfo(final String url) {
    final int host = url == null ? -1 : url.indexOf("://");
    final int at = host == -1 ? -1 : url.lastIndexOf('@', endOfAuthority(url, host + 3));

    return at == -1 || at < host ? url : url.substring(0, host + 3) + url.substring(at + 1);
  }

  /** Where URL's authority ends, counting from FROM. */
  private static int endOfAuthority(final String url, final int from) {
    for (int i = from; i < url.length(); i++) {
      final char c = url.charAt(i);

      if (c == '/' || c == '?' || c == '#' || c == '\\') {
        return i;
      }
    }
    return url.length();
  }

  /**
   * The address field with the login's own address in place of the one the window was opened
   * for. The login stops on the page the user wants copied, so that page becomes the crawl's
   * root. One token is replaced, so the field gains no second crawl root.
   *
   * @param urlField
   *          the field's text, one or more whitespace-separated addresses
   * @param bundleUrl
   *          the start URL the engine wrote, or null when it wrote none
   * @param openedToken
   *          the token the login was opened for, which {@link #openedToken} picked
   * @return the field's new text, with the user's own spacing and other addresses kept
   */
  static String withBundleUrl(final String urlField, final String bundleUrl,
      final String openedToken) {
    final String current = urlField == null ? "" : urlField.trim();

    if (bundleUrl == null || bundleUrl.indexOf(BUNDLE_SUFFIX) == -1) {
      return current;
    }
    final Matcher parts = FIELD_PARTS.matcher(current);
    final StringBuilder kept = new StringBuilder();
    boolean named = false;

    // Each match is one address or one run of the whitespace between two, so spacing survives.
    while (parts.find()) {
      final String part = parts.group();

      if (!named && part.equals(openedToken)) {
        kept.append(withUserInfoOf(bundleUrl, part));
        named = true;
      } else {
        kept.append(part);
      }
    }
    if (named) {
      return kept.toString();
    }
    // The field no longer holds the address the login opened, so the new one needs its own line.
    return kept.length() == 0 ? bundleUrl : kept + "\n" + bundleUrl;
  }

  /**
   * URL carrying the user name and password of TYPED, which the engine was not given. The
   * merge happens only when both name the same host, because a password typed for one site
   * must not follow the login to another host.
   *
   * @param url
   *          the address the engine wrote, which holds no userinfo
   * @param typed
   *          the field's own token, which may hold some
   * @return URL with that userinfo put back after its scheme
   */
  private static String withUserInfoOf(final String url, final String typed) {
    final int host = typed.indexOf("://");
    final int at = host == -1 ? -1 : typed.lastIndexOf('@', endOfAuthority(typed, host + 3));
    final int scheme = url.indexOf("://");
    final String reached = hostOf(url);
    final boolean sameHost =
        reached != null && reached.equals(withoutTrailingDots(hostOf(secureUrl(typed))));

    if (at == -1 || at < host || scheme == -1 || !sameHost) {
      return url;
    }
    return url.substring(0, scheme + 3) + typed.substring(host + 3, at + 1)
        + url.substring(scheme + 3);
  }

  /** HOST loses the trailing dots the engine also cuts, so a match compares like with like. */
  private static String withoutTrailingDots(final String host) {
    return host == null ? null : host.replaceAll("\\.+$", "");
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
   * OPTIONS with what a saved login needs, which is the whole argv decision in one place.
   *
   * A bundle's cookies reach the jar only when the engine accepts cookies, and -b0 leaves no
   * jar to put them in. The crawl would then run logged out, and the only sign is a warning
   * Android hides, so a saved login wins over a box the user unticked after saving it.
   *
   * With no --bundle-dir the engine looks for the bundle beside the mirror, which every app on
   * the device can read.
   *
   * @param options
   *          what the option mapper emitted, start URLs included
   * @param bundleDir
   *          where this project's bundles are kept, or null when it has none
   * @return the options to hand the engine, the bundle folder last
   */
  static List<String> withLogin(final List<String> options, final File bundleDir) {
    final List<String> engine = new ArrayList<String>(options);
    boolean named = false;

    for (final String option : engine) {
      named = named || option.indexOf(BUNDLE_SUFFIX) != -1;
    }
    if (named) {
      engine.remove("-b0");
    }
    if (bundleDir != null && bundleDir.isDirectory()) {
      engine.add("--bundle-dir");
      engine.add(bundleDir.getAbsolutePath());
    }
    return engine;
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
