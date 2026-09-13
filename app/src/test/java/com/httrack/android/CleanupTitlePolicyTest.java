package com.httrack.android;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/** Truth table for the picker's title decision. */
public class CleanupTitlePolicyTest {
  /** Each answer is written out rather than computed, so the table cannot agree with a change
   *  to the expression it checks. */
  @Test
  public void onlySelectingGetsItsOwnTitle() {
    assertEquals("deleting keeps the default label", R.string.title_activity_cleanup,
        CleanupTitlePolicy.titleFor(false));
    assertEquals("selecting gets its own label", R.string.title_activity_cleanup_select,
        CleanupTitlePolicy.titleFor(true));
  }
}
