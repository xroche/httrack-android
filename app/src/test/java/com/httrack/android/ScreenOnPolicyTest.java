package com.httrack.android;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/** Truth table for the screen-on decision. The source-text tests next door prove the activity
 *  delegates here; this one proves the answers. */
public class ScreenOnPolicyTest {
  /** Each answer is written out rather than computed, so the table cannot agree with a change
   *  to the expression it checks. Arguments are preferred, onProgressPane, crawlRunning. */
  @Test
  public void onlyAllThreeTogetherKeepTheDisplayAwake() {
    assertEquals("no/no/no", false, ScreenOnPolicy.keepScreenOn(false, false, false));
    assertEquals("no/no/yes", false, ScreenOnPolicy.keepScreenOn(false, false, true));
    assertEquals("no/yes/no", false, ScreenOnPolicy.keepScreenOn(false, true, false));
    assertEquals("no/yes/yes", false, ScreenOnPolicy.keepScreenOn(false, true, true));
    assertEquals("yes/no/no", false, ScreenOnPolicy.keepScreenOn(true, false, false));
    assertEquals("yes/no/yes", false, ScreenOnPolicy.keepScreenOn(true, false, true));
    assertEquals("yes/yes/no", false, ScreenOnPolicy.keepScreenOn(true, true, false));
    assertEquals("yes/yes/yes", true, ScreenOnPolicy.keepScreenOn(true, true, true));
  }
}
