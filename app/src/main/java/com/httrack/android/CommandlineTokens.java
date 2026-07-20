package com.httrack.android;

import java.util.ArrayList;
import java.util.List;

/** Classifies engine commandline tokens; pure, Android-free, so it is unit-testable. */
public final class CommandlineTokens {
  private CommandlineTokens() {
  }

  /**
   * True if the token would be read by the engine as an option or scan-filter
   * rule ("-...", "+...") rather than a URL. Only a tampered profile puts one in
   * the URL field, since no legitimate URL begins with those characters.
   */
  public static boolean isOptionLike(final String token) {
    return token.length() != 0
        && (token.charAt(0) == '-' || token.charAt(0) == '+');
  }

  /**
   * Split a URL-field value into argv tokens, dropping empty ones and any that an
   * engine would read as an option. This is the enforcement point for the
   * winprofile.ini URL-field hardening, kept here so it is unit-testable without
   * loading OptionsMapper (whose static init needs Android).
   */
  public static List<String> urlTokens(final String value) {
    final List<String> out = new ArrayList<String>();
    if (value == null) {
      return out;
    }
    // Collapse whitespace (as OptionsMapper.cleanupString does) then split on it.
    for (String token : value.replaceAll("\\s+", " ").trim().split("\\s+")) {
      token = token.trim();
      if (token.length() != 0 && !isOptionLike(token)) {
        out.add(token);
      }
    }
    return out;
  }
}
