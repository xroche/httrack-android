package com.httrack.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** hts-cache/interrupted.lock is what makes a project reopen on "Continue an interrupted
 *  download", so it has to mean the crawl was cut short and nothing else. */
public class InterruptedLockTest {
  @Rule
  public final TemporaryFolder tmp = new TemporaryFolder();

  private File target;

  @Before
  public void setUp() throws Exception {
    target = tmp.newFolder("project");
    new File(target, "hts-cache").mkdirs();
  }

  /** The engine returns 0 once its queue is drained, whatever failed on the way. */
  @Test
  public void aRunThatReachesTheEndIsNotResumable() {
    assertFalse("a crawl that ran to the end has nothing to continue",
        HTTrackActivity.leavesPendingWork(false, 0));
  }

  @Test
  public void aCrawlCutShortIsResumable() {
    // A soft stop lets pending transfers finish, so the engine still returns 0; so does a size or
    // time cap, which the engine applies by stopping itself. HTTrackLib.wasStopped() sees both.
    assertTrue(HTTrackActivity.leavesPendingWork(true, 0));
    assertTrue(HTTrackActivity.leavesPendingWork(true, -1));
  }

  @Test
  public void anEngineThatGaveUpIsResumable() {
    assertTrue(HTTrackActivity.leavesPendingWork(false, -1));
    assertTrue(HTTrackActivity.leavesPendingWork(false, 1));
  }

  @Test
  public void theMarkerRoundTrips() throws Exception {
    assertFalse(HTTrackActivity.isInterruptedProfile(target));
    HTTrackActivity.setInterruptedProfile(target, true);
    assertTrue(HTTrackActivity.isInterruptedProfile(target));
    HTTrackActivity.setInterruptedProfile(target, false);
    assertFalse(HTTrackActivity.isInterruptedProfile(target));
  }

  /** The engine leaves this one behind only when it never got to end. */
  @Test
  public void theEnginesOwnLockAlsoMeansInterrupted() throws Exception {
    assertTrue(new File(target, "hts-in_progress.lock").createNewFile());
    assertTrue(HTTrackActivity.isInterruptedProfile(target));
  }

  /** What the finished pane does, end to end, for each way a crawl can end. */
  private boolean reopensOnContinue(final boolean stoppedByUser, final int engineCode)
      throws IOException {
    HTTrackActivity.setInterruptedProfile(target,
        HTTrackActivity.leavesPendingWork(stoppedByUser, engineCode));
    return HTTrackActivity.isInterruptedProfile(target);
  }

  @Test
  public void everyOutcomeStampsTheProjectItsOwnWay() throws Exception {
    assertFalse("a completed crawl", reopensOnContinue(false, 0));
    assertTrue("a stop, asked for or capped", reopensOnContinue(true, 0));
    assertTrue("an engine that gave up", reopensOnContinue(false, -1));
    assertFalse("a completed crawl again", reopensOnContinue(false, 0));
  }

  /** A project stopped last time and finished this time no longer offers to continue: the marker
   *  clears itself, which is all that ever clears one left by an older build. */
  @Test
  public void aCleanRunClearsAStaleMarker() throws Exception {
    HTTrackActivity.setInterruptedProfile(target, true);
    assertFalse("a stale marker survived a clean run", reopensOnContinue(false, 0));
  }

  /** Runner.stopMirror needs a live AsyncTask, so the guard is pinned in the source instead. */
  private static String stopMirrorBody() throws IOException {
    final String source = TestSources.javaSource("HTTrackActivity");
    // The last declaration is the runner's; the first is RunnerFragment's trunk to it.
    final int from = source.lastIndexOf("public boolean stopMirror(final boolean force) {");
    assertTrue("Runner.stopMirror is gone", from != -1);
    final int to = source.indexOf("\n    }\n", from);
    assertTrue("Runner.stopMirror is not closed where expected", to > from);
    return source.substring(from, to);
  }

  @Test
  public void aStopRequestOnlyEverWritesTheMarker() throws Exception {
    final Matcher m = Pattern.compile("setInterruptedProfile\\(([^)]*)\\)")
        .matcher(stopMirrorBody());
    int calls = 0;
    while (m.find()) {
      calls++;
      assertEquals("stopMirror must not clear the marker, nor pass a verdict it cannot make",
          "true", m.group(1));
    }
    assertEquals("stopMirror no longer touches the marker at all", 1, calls);
  }

  @Test
  public void aStopAfterTheCrawlEndedIsIgnored() throws Exception {
    assertTrue("the finished pane's own stopMirror() would mark every project resumable",
        stopMirrorBody().contains("if (!ended)"));
  }

  /** The verdict belongs to the run, so it is written where the run ends. */
  @Test
  public void theCrawlStampsItsOwnOutcome() throws Exception {
    final String source = TestSources.javaSource("HTTrackActivity");
    final int from = source.indexOf("protected void runInternal()");
    final int to = source.indexOf("displayFinishedPanel(displayMessage, errorsCount,");
    assertTrue("runInternal no longer bounded by its displayFinishedPanel call",
        from != -1 && to > from);
    final String body = source.substring(from, to);
    assertTrue("runInternal must weigh the engine's own stop, not just the user's",
        body.contains("leavesPendingWork(interrupted || engine.wasStopped(), code)"));
    assertTrue("runInternal must write the verdict before the finished pane opens",
        body.contains("setInterruptedProfile(pendingWork)"));
    assertTrue("ended must be set before the finished pane asks for a stop",
        body.contains("ended = true"));
  }

  /* The predicate below only ever sees what wasStopped() reports, so the engine's
     two abort flags are pinned here: stop alone misses a fatal disk error. */
  @Test
  public void wasStoppedWeighsBothOfTheEnginesAbortFlags() throws Exception {
    final String jni = TestSources.jniSource("htslibjni.c");
    final int at = jni.indexOf("HTTrackLib_wasStopped");
    assertTrue("wasStopped is gone", at > 0);
    final String body = jni.substring(at, jni.indexOf("\n}", at));
    assertTrue("wasStopped must read state.stop", body.contains("state.stop"));
    assertTrue("wasStopped must also read exit_xh, which stop never sets",
        body.contains("hts_is_exiting"));
  }
}
