package com.httrack.android;

import static com.httrack.android.MirrorSession.Event.END;
import static com.httrack.android.MirrorSession.Event.ENGINE_STARTED;
import static com.httrack.android.MirrorSession.Event.START;
import static com.httrack.android.MirrorSession.Event.STOP;
import static com.httrack.android.MirrorSession.State.ENDED;
import static com.httrack.android.MirrorSession.State.NONE;
import static com.httrack.android.MirrorSession.State.RUNNING;
import static com.httrack.android.MirrorSession.State.STARTING;
import static com.httrack.android.MirrorSession.State.STOPPING;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import org.junit.Test;

/** The crawl state the owners read, and the profile claims that used to be a static set on the
 *  activity. Every answer below is written out rather than computed, so the table cannot agree
 *  with a change to the expression it checks. */
public class MirrorSessionTest {
  /** Every cell of next(), state by event. */
  @Test
  public void theWholeTableIsWrittenOut() {
    assertEquals("NONE/START", STARTING, MirrorSession.next(NONE, START));
    assertEquals("NONE/ENGINE_STARTED", NONE, MirrorSession.next(NONE, ENGINE_STARTED));
    assertEquals("NONE/STOP", STOPPING, MirrorSession.next(NONE, STOP));
    assertEquals("NONE/END", ENDED, MirrorSession.next(NONE, END));

    assertEquals("STARTING/START", STARTING, MirrorSession.next(STARTING, START));
    assertEquals("STARTING/ENGINE_STARTED", RUNNING, MirrorSession.next(STARTING, ENGINE_STARTED));
    assertEquals("STARTING/STOP", STOPPING, MirrorSession.next(STARTING, STOP));
    assertEquals("STARTING/END", ENDED, MirrorSession.next(STARTING, END));

    assertEquals("RUNNING/START", RUNNING, MirrorSession.next(RUNNING, START));
    assertEquals("RUNNING/ENGINE_STARTED", RUNNING, MirrorSession.next(RUNNING, ENGINE_STARTED));
    assertEquals("RUNNING/STOP", STOPPING, MirrorSession.next(RUNNING, STOP));
    assertEquals("RUNNING/END", ENDED, MirrorSession.next(RUNNING, END));

    assertEquals("STOPPING/START", STOPPING, MirrorSession.next(STOPPING, START));
    assertEquals("STOPPING/ENGINE_STARTED", STOPPING,
        MirrorSession.next(STOPPING, ENGINE_STARTED));
    assertEquals("STOPPING/STOP", STOPPING, MirrorSession.next(STOPPING, STOP));
    assertEquals("STOPPING/END", ENDED, MirrorSession.next(STOPPING, END));

    assertEquals("ENDED/START", ENDED, MirrorSession.next(ENDED, START));
    assertEquals("ENDED/ENGINE_STARTED", ENDED, MirrorSession.next(ENDED, ENGINE_STARTED));
    assertEquals("ENDED/STOP", ENDED, MirrorSession.next(ENDED, STOP));
    assertEquals("ENDED/END", ENDED, MirrorSession.next(ENDED, END));
  }

  /** A run that ends and then restarts would report a live crawl no owner is driving. */
  @Test
  public void nothingLeavesTheEndedState() {
    for (final MirrorSession.Event event : MirrorSession.Event.values()) {
      assertEquals(event.name(), ENDED, MirrorSession.next(ENDED, event));
    }
  }

  /** The whole point of the claim: a second run on one project is refused, a first is not. */
  @Test
  public void oneProfileIsClaimedOnce() {
    final MirrorSession session = MirrorSession.get();
    final File profile = new File("/tmp/httrack-test/one/hts-cache/winprofile.ini");
    final File other = new File("/tmp/httrack-test/two/hts-cache/winprofile.ini");
    assertTrue("a free profile must be claimable", session.claim(profile));
    assertFalse("a claimed profile must refuse the second run", session.claim(profile));
    assertTrue("another project is not the same claim", session.claim(other));
    session.release(profile);
    assertTrue("a released profile is claimable again", session.claim(profile));
    session.release(profile);
    session.release(other);
  }

  /** Releasing must name one profile: a release that emptied the set would free the live run. */
  @Test
  public void aReleaseLeavesEveryOtherClaimAlone() {
    final MirrorSession session = MirrorSession.get();
    final File mine = new File("/tmp/httrack-test/mine/hts-cache/winprofile.ini");
    final File theirs = new File("/tmp/httrack-test/theirs/hts-cache/winprofile.ini");
    assertTrue(session.claim(mine));
    assertTrue(session.claim(theirs));
    session.release(mine);
    assertFalse("the other run's claim went with it", session.claim(theirs));
    session.release(theirs);
  }

  /** CrawlRun builds an HTTrackLib, whose constructor is native, so the slot is read in the
   *  source instead. An ended crawl left in the slot is one a second owner would attach to. */
  @Test
  public void theSlotOnlyOffersALiveCrawl() throws IOException {
    final String source = TestSources.withoutCommentsAndStrings(
        TestSources.javaSource("MirrorSession"));
    assertEquals("an ended crawl must not read as one to attach to",
        "return run != null && run.state() != State.ENDED ? run : null;",
        block(source, "synchronized CrawlRun live()"));
    assertEquals("an empty slot must have no state of its own",
        "return run != null ? run.state() : State.NONE;",
        block(source, "synchronized State state()"));
  }

  /** Body of the method whose declaration starts with SIGNATURE, whitespace collapsed. */
  private static String block(final String source, final String signature) {
    final int at = source.indexOf(signature);
    assertTrue("no " + signature, at != -1);
    return TestSources.balancedBlock(source, at + signature.length())
        .replaceAll("\\s+", " ").trim();
  }
}
