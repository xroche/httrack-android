package com.httrack.android;

import static com.httrack.android.HandoverPolicy.Attachment.FINISHED;
import static com.httrack.android.HandoverPolicy.Attachment.NOTHING;
import static com.httrack.android.HandoverPolicy.Attachment.PROGRESS;
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

  /** Every cell of attaches(), crawl by verdict by refresh. */
  @Test
  public void theWholeAttachmentTableIsWrittenOut() {
    assertEquals("dead/none/none", NOTHING, HandoverPolicy.attaches(false, false, false));
    assertEquals("dead/none/stats", NOTHING, HandoverPolicy.attaches(false, false, true));
    assertEquals("dead/verdict/none", FINISHED, HandoverPolicy.attaches(false, true, false));
    assertEquals("dead/verdict/stats", FINISHED, HandoverPolicy.attaches(false, true, true));
    assertEquals("live/none/none", NOTHING, HandoverPolicy.attaches(true, false, false));
    assertEquals("live/none/stats", PROGRESS, HandoverPolicy.attaches(true, false, true));
    assertEquals("live/verdict/none", FINISHED, HandoverPolicy.attaches(true, true, false));
    assertEquals("live/verdict/stats", FINISHED, HandoverPolicy.attaches(true, true, true));
  }
}
