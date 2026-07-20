package com.httrack.android;

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
}
