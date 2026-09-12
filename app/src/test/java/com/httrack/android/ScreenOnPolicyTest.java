package com.httrack.android;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/** Truth table for the screen-on decision. The source-text tests next door prove the activity
 *  delegates here; this one proves the answers. */
public class ScreenOnPolicyTest {
  @Test
  public void onlyAllThreeTogetherKeepTheDisplayAwake() {
    for (int row = 0; row < 8; row++) {
      final boolean preferred = (row & 4) != 0;
      final boolean onProgressPane = (row & 2) != 0;
      final boolean crawlRunning = (row & 1) != 0;
      assertEquals(preferred + "/" + onProgressPane + "/" + crawlRunning,
          preferred && onProgressPane && crawlRunning,
          ScreenOnPolicy.keepScreenOn(preferred, onProgressPane, crawlRunning));
    }
  }

  @Test
  public void anUntickedOptionNeverHoldsTheDisplay() {
    assertEquals(false, ScreenOnPolicy.keepScreenOn(false, true, true));
  }

  @Test
  public void aCrawlThatEndedReleasesTheDisplay() {
    assertEquals(false, ScreenOnPolicy.keepScreenOn(true, true, false));
  }

  @Test
  public void anotherPaneReleasesTheDisplay() {
    assertEquals(false, ScreenOnPolicy.keepScreenOn(true, false, true));
  }

  @Test
  public void theOneCaseTheOptionExistsFor() {
    assertEquals(true, ScreenOnPolicy.keepScreenOn(true, true, true));
  }
}
