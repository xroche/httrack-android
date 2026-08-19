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

  /**
   * Split a line-separated field value into one value per line, dropping the
   * blank ones. Blanks inside a line are collapsed rather than split on, since
   * an extra header or a rule holds them.
   */
  public static List<String> lineTokens(final String value) {
    final List<String> out = new ArrayList<String>();
    if (value == null) {
      return out;
    }
    for (final String line : value.split("\n")) {
      final String trimmed = line.replaceAll("\\s+", " ").trim();
      if (trimmed.length() != 0) {
        out.add(trimmed);
      }
    }
    return out;
  }

  /* -%Z is the checkbox; --single-file-max-size switches single-file on by
     itself, so both spellings count. */
  private static final String SINGLE_FILE_TOKENS[] = { "-%Z",
      "--single-file-max-size" };

  /**
   * True if these arguments ask for both ways of making one self-contained
   * page. The engine refuses to start on MHTML (-%M) and single-file HTML
   * together, naming the flags rather than the two boxes.
   */
  public static boolean hasSelfContainedConflict(final List<String> commandline) {
    if (!commandline.contains("-%M")) {
      return false;
    }
    for (final String token : SINGLE_FILE_TOKENS) {
      if (commandline.contains(token)) {
        return true;
      }
    }
    return false;
  }

  /**
   * Split a rule-list field value into one rule per engine flag. Whitespace
   * separates two rules. Beside a ',' or an '=' it holds one rule together
   * instead, so it is dropped there rather than splitting the rule in three.
   */
  public static List<String> ruleTokens(final String value) {
    final List<String> out = new ArrayList<String>();
    if (value == null) {
      return out;
    }
    for (final String rule : value.replaceAll("\\s*([,=])\\s*", "$1")
        .split("\\s+")) {
      if (rule.length() != 0) {
        out.add(rule);
      }
    }
    return out;
  }
}
