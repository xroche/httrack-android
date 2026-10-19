package com.httrack.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Truth table for the picker's title decision. */
public class CleanupTitlePolicyTest {
  /**
   * The crawl recreates what it still holds, so a delete during one keeps the session and
   * loses the delete. Choosing a project removes nothing, so that flow stays open.
   */
  @Test
  public void theDeleteFlowWaitsForTheCrawlButThePickerDoesNot() {
    assertFalse("deleting during a crawl", CleanupTitlePolicy.opens(false, true, true));
    assertTrue("deleting with no crawl", CleanupTitlePolicy.opens(false, false, true));
    assertTrue("choosing during a crawl", CleanupTitlePolicy.opens(true, true, true));
  }

  @Test
  public void nothingOpensWithoutAProject() {
    assertFalse(CleanupTitlePolicy.opens(false, false, false));
    assertFalse(CleanupTitlePolicy.opens(true, false, false));
    assertFalse(CleanupTitlePolicy.opens(true, true, false));
  }

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
