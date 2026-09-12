package com.httrack.android;

/**
 * Whether the cache checkbox reaches the engine as -C0. The engine raises HTS_CACHE_PRIORITY on
 * finding hts-cache/hts-in_progress.lock and only then parses -C, so a -C0 undoes the resume and
 * re-downloads the whole site. No Android type appears here, so the decision can be checked
 * against its truth table.
 */
final class CachePolicy {
  /** Action radio index of "Continue interrupted download". */
  static final int ACTION_CONTINUE = 0;

  /** Cache checkbox value asking for no cache, which is the only one that emits anything. */
  static final String CACHE_UNTICKED = "0";

  private CachePolicy() {
  }

  /**
   * Is the cache option emitted?
   *
   * @param action
   *          the action radio value, or null when no mirror exists to continue or update
   * @param cachePreference
   *          the cache checkbox value
   * @return true when -C0 must reach the engine
   */
  static boolean emitsCacheOption(final String action,
      final String cachePreference) {
    // The checkbox is compared as text because SimpleOptionFlag reads it that way.
    return !isContinue(action) && CACHE_UNTICKED.equals(cachePreference);
  }

  /** Does the action select "Continue interrupted download"? */
  private static boolean isContinue(final String action) {
    // MultipleChoicesOption picks -iC1 off the parsed index, so "00" is Continue as much as "0".
    return OptionValues.isDigits(action)
        && OptionValues.parseInt(action, -1) == ACTION_CONTINUE;
  }
}
