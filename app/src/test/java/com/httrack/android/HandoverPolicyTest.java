package com.httrack.android;

import static com.httrack.android.HandoverPolicy.Attachment.FINISHED;
import static com.httrack.android.HandoverPolicy.Attachment.NOTHING;
import static com.httrack.android.HandoverPolicy.Attachment.PROGRESS;
import static com.httrack.android.HandoverPolicy.Attachment.WAITING;
import static com.httrack.android.HandoverPolicy.Delivery.ACTIVITY;
import static com.httrack.android.HandoverPolicy.Delivery.DROPPED;
import static com.httrack.android.HandoverPolicy.Delivery.HELD;
import static org.junit.Assert.assertEquals;

import org.junit.Test;

/** Where the crawl's reports go, and what an attaching window draws. Every answer below is
 *  written out rather than computed, so the table cannot agree with a change to the expression
 *  it checks. */
public class HandoverPolicyTest {
  /** Every cell of delivers(), window by report. */
  @Test
  public void theWholeDeliveryTableIsWrittenOut() {
    assertEquals("attached/progress", ACTIVITY, HandoverPolicy.delivers(true, false));
    assertEquals("attached/verdict", ACTIVITY, HandoverPolicy.delivers(true, true));
    assertEquals("detached/progress", DROPPED, HandoverPolicy.delivers(false, false));
    assertEquals("detached/verdict", HELD, HandoverPolicy.delivers(false, true));
  }

  /** Every cell of attaches(), crawl by verdict by refresh by scheduled job. */
  @Test
  public void theWholeAttachmentTableIsWrittenOut() {
    assertEquals("dead/none/none/unscheduled", NOTHING,
        HandoverPolicy.attaches(false, false, false, false));
    assertEquals("dead/none/none/scheduled", WAITING,
        HandoverPolicy.attaches(false, false, false, true));
    assertEquals("dead/none/stats/unscheduled", NOTHING,
        HandoverPolicy.attaches(false, false, true, false));
    assertEquals("dead/none/stats/scheduled", WAITING,
        HandoverPolicy.attaches(false, false, true, true));
    assertEquals("dead/verdict/none/unscheduled", FINISHED,
        HandoverPolicy.attaches(false, true, false, false));
    assertEquals("dead/verdict/none/scheduled", FINISHED,
        HandoverPolicy.attaches(false, true, false, true));
    assertEquals("dead/verdict/stats/unscheduled", FINISHED,
        HandoverPolicy.attaches(false, true, true, false));
    assertEquals("dead/verdict/stats/scheduled", FINISHED,
        HandoverPolicy.attaches(false, true, true, true));
    assertEquals("live/none/none/unscheduled", NOTHING,
        HandoverPolicy.attaches(true, false, false, false));
    assertEquals("live/none/none/scheduled", NOTHING,
        HandoverPolicy.attaches(true, false, false, true));
    assertEquals("live/none/stats/unscheduled", PROGRESS,
        HandoverPolicy.attaches(true, false, true, false));
    assertEquals("live/none/stats/scheduled", PROGRESS,
        HandoverPolicy.attaches(true, false, true, true));
    assertEquals("live/verdict/none/unscheduled", FINISHED,
        HandoverPolicy.attaches(true, true, false, false));
    assertEquals("live/verdict/none/scheduled", FINISHED,
        HandoverPolicy.attaches(true, true, false, true));
    assertEquals("live/verdict/stats/unscheduled", FINISHED,
        HandoverPolicy.attaches(true, true, true, false));
    assertEquals("live/verdict/stats/scheduled", FINISHED,
        HandoverPolicy.attaches(true, true, true, true));
  }

  /** Every cell of waitsForNetwork(), crawl by scheduled job. A crawl the scheduler holds for
   *  want of a network has started no engine and no worker thread. */
  @Test
  public void theWholeWaitTableIsWrittenOut() {
    assertEquals("dead/unscheduled", false, HandoverPolicy.waitsForNetwork(false, false));
    assertEquals("dead/scheduled", true, HandoverPolicy.waitsForNetwork(false, true));
    assertEquals("live/unscheduled", false, HandoverPolicy.waitsForNetwork(true, false));
    assertEquals("live/scheduled", false, HandoverPolicy.waitsForNetwork(true, true));
  }
}
