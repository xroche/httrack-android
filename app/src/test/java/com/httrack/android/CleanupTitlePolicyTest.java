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
  public void theDeleteFlowWaitsForTheCrawl() {
    assertTrue("deleting during a crawl", CleanupTitlePolicy.refusesDelete(true, true));
    assertFalse("deleting with no crawl", CleanupTitlePolicy.refusesDelete(false, true));
  }

  @Test
  public void theDeleteFlowNeedsAProject() {
    assertTrue(CleanupTitlePolicy.refusesDelete(false, false));
    assertTrue(CleanupTitlePolicy.refusesDelete(true, false));
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
