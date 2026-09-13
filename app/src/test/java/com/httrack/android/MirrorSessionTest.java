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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.httrack.android.jni.HTTrackStats;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Test;

/** The crawl state the owners read, and the profile claims that used to be a static set on the
 *  activity. Every answer below is written out rather than computed, so the table cannot agree
 *  with a change to the expression it checks. */
public class MirrorSessionTest {
  /** A crawl the session can hold, so the slot is exercised rather than read as source. */
  private static final class FakeCrawl implements MirrorSession.Crawl {
    private MirrorSession.State state = STARTING;
    private Boolean stoppedForce;

    @Override
    public MirrorSession.State state() {
      return state;
    }

    @Override
    public boolean stopMirror(final boolean force) {
      stoppedForce = Boolean.valueOf(force);
      return true;
    }
  }

  /** A window the session can talk to, recording what reached it and in what order. */
  private static final class FakeWindow implements MirrorSession.Listener {
    final List<String> reached = new ArrayList<String>();

    @Override
    public void onProgressLines(final String[] lines) {
      reached.add("progress:" + Arrays.toString(lines));
    }

    @Override
    public void onStats(final HTTrackStats stats) {
      reached.add("stats:" + stats.linksScanned);
    }

    @Override
    public void onFinished(final MirrorSession.Verdict verdict) {
      reached.add("finished:" + verdict.message + "/" + verdict.errorsCount + "/"
          + verdict.mirrorFolder);
    }
  }

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

  /** The table above is a pure function, so only these calls tie it to a crawl that runs. */
  @Test
  public void everyStateChangeSitsInTheRun() throws IOException {
    final String crawl = TestSources
        .withoutCommentsAndStrings(TestSources.javaSource("CrawlRun"));

    final String run = body(crawl, "void runMirror()");
    assertEquals("the run moves the state twice and nowhere else", 2,
        TestSources.occurrences(run, "advance("));
    assertTrue("a crawl reaching the slot as NONE is one nothing reads as started",
        TestSources.indexOf(run, "advance(MirrorSession.Event.START);") < TestSources
            .indexOf(run, "session.begin(this);"));
    assertEquals("a START inside a branch leaves the other branch at NONE", 0,
        TestSources.depthOf(run, "advance(MirrorSession.Event.START);"));
    assertTrue("the state has to say RUNNING while the engine is in fact running",
        TestSources.indexOf(run, "advance(MirrorSession.Event.ENGINE_STARTED);") < TestSources
            .indexOf(run, "engine.main(cargs)"));
    assertEquals("the engine call lives in the try, so its event belongs at that depth", 1,
        TestSources.depthOf(run, "advance(MirrorSession.Event.ENGINE_STARTED);"));

    final String stop = body(crawl, "boolean stopMirror(final boolean force)");
    assertEquals("one STOP, and the state is what a second owner would read", 1,
        TestSources.occurrences(stop, "advance("));
    assertTrue("a STOP recorded after the engine returns races the run's own end",
        TestSources.indexOf(stop, "advance(MirrorSession.Event.STOP);") < TestSources
            .indexOf(stop, "engine.stop(force)"));
    assertEquals("gated on force, a soft stop would leave the state saying RUNNING", 0,
        TestSources.depthOf(stop, "advance(MirrorSession.Event.STOP);"));

    final String end = body(crawl, "void end()");
    assertEquals("one END, or the slot is freed by a crawl that never reached ENDED", 1,
        TestSources.occurrences(end, "advance("));
    assertTrue("a crawl freed before it says ENDED is one live() still offers",
        TestSources.indexOf(end, "advance(MirrorSession.Event.END);") < TestSources
            .indexOf(end, "MirrorSession.get().end(this);"));
    assertEquals("an END inside a branch leaves the crawl reading as live", 0,
        TestSources.depthOf(end, "advance(MirrorSession.Event.END);"));
  }

  /** The whole point of the claim: a second run on one project is refused, a first is not. */
  @Test
  public void oneProfileIsClaimedOnce() {
    final MirrorSession session = MirrorSession.get();
    final File profile = new File("/tmp/httrack-test/one/hts-cache/winprofile.ini");
    final File other = new File("/tmp/httrack-test/two/hts-cache/winprofile.ini");
    try {
      assertTrue("a free profile must be claimable", session.claim(profile));
      assertFalse("a claimed profile must refuse the second run", session.claim(profile));
      assertTrue("another project is not the same claim", session.claim(other));
      session.release(profile);
      assertTrue("a released profile is claimable again", session.claim(profile));
    } finally {
      // The session is process-wide, so a failing assertion must not leave a claim behind.
      session.release(profile);
      session.release(other);
    }
  }

  /** Releasing must name one profile: a release that emptied the set would free the live run. */
  @Test
  public void aReleaseLeavesEveryOtherClaimAlone() {
    final MirrorSession session = MirrorSession.get();
    final File mine = new File("/tmp/httrack-test/mine/hts-cache/winprofile.ini");
    final File theirs = new File("/tmp/httrack-test/theirs/hts-cache/winprofile.ini");
    try {
      assertTrue(session.claim(mine));
      assertTrue(session.claim(theirs));
      session.release(mine);
      assertFalse("the other run's claim went with it", session.claim(theirs));
    } finally {
      session.release(mine);
      session.release(theirs);
    }
  }

  /** An ended crawl left in the slot is one a second owner would attach to. */
  @Test
  public void theSlotOnlyOffersALiveCrawl() {
    final MirrorSession session = MirrorSession.get();
    final FakeCrawl crawl = new FakeCrawl();
    try {
      assertNull("the slot starts empty", session.live());
      session.begin(crawl);
      assertSame("a started crawl is what an owner attaches to", crawl, session.live());
      crawl.state = ENDED;
      assertNull("an ended crawl is no longer one to attach to", session.live());
    } finally {
      session.end(crawl);
    }
    assertNull("the slot must come back empty", session.live());
  }

  /** A slot freed by whoever ends first would drop the crawl that holds it. */
  @Test
  public void onlyTheCrawlInTheSlotMayEmptyIt() {
    final MirrorSession session = MirrorSession.get();
    final FakeCrawl first = new FakeCrawl();
    final FakeCrawl second = new FakeCrawl();
    try {
      session.begin(first);
      session.begin(second);
      session.end(first);
      assertSame("the earlier crawl's end took the later one's slot", second, session.live());
    } finally {
      session.end(first);
      session.end(second);
    }
    assertNull("both ends leave the slot empty", session.live());
  }

  /** The Stop button has no fragment to go through on API 34 and later. */
  @Test
  public void theSlotIsWhatAStopReaches() {
    final MirrorSession session = MirrorSession.get();
    final FakeCrawl crawl = new FakeCrawl();
    try {
      session.begin(crawl);
      assertTrue("the slot must hand back a crawl that can be stopped",
          session.live().stopMirror(false));
      assertEquals("a soft stop must not arrive as a hard one", Boolean.FALSE, crawl.stoppedForce);
      session.live().stopMirror(true);
      assertEquals("a hard stop must not arrive as a soft one", Boolean.TRUE, crawl.stoppedForce);
    } finally {
      session.end(crawl);
    }
  }

  /** A progress frame no window can draw is dropped; the verdict is kept for the next attach. */
  @Test
  public void whatSurvivesADetachedCrawlIsTheVerdict() {
    final MirrorSession session = MirrorSession.get();
    final FakeCrawl crawl = new FakeCrawl();
    final File folder = new File("/tmp/httrack-test/mirror");
    try {
      session.begin(crawl);
      session.publishProgress(new String[] { "Creating project" });
      final HTTrackStats stats = new HTTrackStats();
      stats.linksScanned = 7;
      session.publishStats(stats);
      assertSame("the newest refresh is what an attaching window draws", stats,
          session.lastStats());
      assertNull("a running crawl has left no verdict", session.takeVerdict());

      assertEquals("nobody took it, so the caller must tell the user itself",
          HandoverPolicy.Delivery.HELD,
          session.publishVerdict(new MirrorSession.Verdict("done", 3, folder)));
      final MirrorSession.Verdict held = session.takeVerdict();
      assertNotNull("the verdict waits for a window", held);
      assertEquals("done", held.message);
      assertEquals(3, held.errorsCount);
      assertSame(folder, held.mirrorFolder);
      assertNull("a taken verdict must not show the finished pane twice",
          session.takeVerdict());
    } finally {
      session.end(crawl);
      session.takeVerdict();
    }
  }

  /** An attached window gets every report, and the verdict is not left behind for a second one. */
  @Test
  public void anAttachedWindowIsToldEverything() {
    final MirrorSession session = MirrorSession.get();
    final FakeCrawl crawl = new FakeCrawl();
    final FakeWindow window = new FakeWindow();
    try {
      session.begin(crawl);
      session.listen(window);
      session.publishProgress(new String[] { "Creating project" });
      final HTTrackStats stats = new HTTrackStats();
      stats.linksScanned = 12;
      session.publishStats(stats);
      assertEquals("a window took it, so no notification is owed",
          HandoverPolicy.Delivery.ACTIVITY,
          session.publishVerdict(new MirrorSession.Verdict("done", 0, null)));
      assertEquals(Arrays.asList("progress:[Creating project]", "stats:12", "finished:done/0/null"),
          window.reached);
      assertNull("a delivered verdict must not also wait for the next attach",
          session.takeVerdict());
    } finally {
      session.unlisten(window);
      session.end(crawl);
      session.takeVerdict();
    }
  }

  /** onStop clears the slot, and a window that lost it to a later one must not clear that one. */
  @Test
  public void onlyTheWindowInTheSlotMayLeaveIt() {
    final MirrorSession session = MirrorSession.get();
    final FakeWindow first = new FakeWindow();
    final FakeWindow second = new FakeWindow();
    try {
      session.listen(first);
      session.listen(second);
      session.unlisten(first);
      session.publishProgress(new String[] { "still here" });
      assertEquals("the earlier window's onStop took the later one's slot",
          Collections.singletonList("progress:[still here]"), second.reached);
      session.unlisten(second);
      session.publishProgress(new String[] { "gone" });
      assertEquals("nothing may reach a window that has stopped",
          Collections.singletonList("progress:[still here]"), second.reached);
    } finally {
      session.unlisten(first);
      session.unlisten(second);
    }
  }

  /** A second crawl starting on a stale verdict would show the previous mirror's finished pane. */
  @Test
  public void aNewCrawlClearsWhatTheLastOneLeft() {
    final MirrorSession session = MirrorSession.get();
    final FakeCrawl first = new FakeCrawl();
    final FakeCrawl second = new FakeCrawl();
    try {
      session.begin(first);
      final HTTrackStats stats = new HTTrackStats();
      stats.linksScanned = 4;
      session.publishStats(stats);
      session.publishVerdict(new MirrorSession.Verdict("done", 0, null));
      session.begin(second);
      assertNull("the previous verdict must not reach the new crawl's window",
          session.takeVerdict());
      assertNull("nor must its last refresh", session.lastStats());
    } finally {
      session.end(first);
      session.end(second);
      session.takeVerdict();
    }
  }

  /** Body of the method whose declaration starts with SIGNATURE. */
  private static String body(final String source, final String signature) {
    return TestSources.balancedBlock(source,
        TestSources.indexOf(source, signature) + signature.length());
  }
}
