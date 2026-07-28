package com.httrack.android;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/** Guards HTTrackActivity.describeCrash, the text a crashed crawl shows on the finished panel. */
public class CrashDescriptionTest {
  @Test
  public void keepsTheNativeFaultDetail() {
    // What coffeecatch rethrows for a recovered SIGSEGV.
    assertEquals("java.lang.Error: signal 11 (SIGSEGV) at address 0x0",
        HTTrackActivity.describeCrash(
            new Error("signal 11 (SIGSEGV) at address 0x0")));
  }

  @Test
  public void namesTheClassWhenThereIsNoDetail() {
    assertEquals("java.lang.NullPointerException",
        HTTrackActivity.describeCrash(new NullPointerException()));
    assertEquals("java.lang.IllegalStateException",
        HTTrackActivity.describeCrash(new IllegalStateException("")));
  }
}
