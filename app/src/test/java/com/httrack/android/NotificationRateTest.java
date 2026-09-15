package com.httrack.android;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/** How often a running mirror repaints its notification. */
public class NotificationRateTest {
  private static final long SECOND = 1000L;

  @Test
  public void theFirstFrameIsAlwaysPosted() {
    assertEquals(true, NotificationRate.shouldPost(NotificationRate.NEVER, 0L, SECOND, false));
    assertEquals(true, NotificationRate.shouldPost(NotificationRate.NEVER, 1L, SECOND, false));
    assertEquals("a huge interval must not hold the first frame back", true,
        NotificationRate.shouldPost(NotificationRate.NEVER, 0L, Long.MAX_VALUE, false));
  }

  @Test
  public void theFrameReportingTheEndIsAlwaysPosted() {
    assertEquals(true, NotificationRate.shouldPost(1000L, 1000L, SECOND, true));
    assertEquals(true, NotificationRate.shouldPost(1000L, 1001L, SECOND, true));
    assertEquals("a frame arriving in the same millisecond still reports the end", true,
        NotificationRate.shouldPost(5000L, 5000L, Long.MAX_VALUE, true));
  }

  @Test
  public void atMostOneFramePerInterval() {
    assertEquals("nothing has elapsed", false,
        NotificationRate.shouldPost(1000L, 1000L, SECOND, false));
    assertEquals("one millisecond short", false,
        NotificationRate.shouldPost(1000L, 1999L, SECOND, false));
    assertEquals("exactly the interval", true,
        NotificationRate.shouldPost(1000L, 2000L, SECOND, false));
    assertEquals("well past it", true,
        NotificationRate.shouldPost(1000L, 9999L, SECOND, false));
  }

  /** A clock that went backwards would otherwise freeze the notification for a whole interval. */
  @Test
  public void aBackwardClockPostsRatherThanStalls() {
    assertEquals(true, NotificationRate.shouldPost(9999L, 1000L, SECOND, false));
  }

  /** NEVER is the only reading that is not a time, so a real timestamp of zero must not be
   *  mistaken for it. */
  @Test
  public void zeroIsATimeLikeAnyOther() {
    assertEquals(false, NotificationRate.shouldPost(0L, 999L, SECOND, false));
    assertEquals(true, NotificationRate.shouldPost(0L, 1000L, SECOND, false));
  }

  /** An interval of zero posts every frame, which is what a caller asking for no gate means. */
  @Test
  public void aZeroIntervalGatesNothing() {
    assertEquals(true, NotificationRate.shouldPost(1000L, 1000L, 0L, false));
  }

  /** Cancelling one a live crawl is still repainting takes the only sign of it off the screen,
   *  so every reason to believe something is running refuses the cancel. */
  @Test
  public void onlyANotificationWithNothingBehindItIsCancelled() {
    assertEquals("nothing runs, so the shade is showing a dead process's last frame", true,
        NotificationRate.cancelsStale(false, false));
    assertEquals("a crawl is repainting it", false, NotificationRate.cancelsStale(true, false));
    assertEquals("an execution that has not reached its crawl yet still owns it", false,
        NotificationRate.cancelsStale(false, true));
    assertEquals("both", false, NotificationRate.cancelsStale(true, true));
  }
}
