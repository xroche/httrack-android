package com.httrack.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
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

  /** A run whose every link either arrived or was refused by the server. */
  private static final long NONE_FAILED = 0;

  @Before
  public void setUp() throws Exception {
    target = tmp.newFolder("project");
    new File(target, "hts-cache").mkdirs();
  }

  /** The engine returns 0 once its queue is drained, whatever failed on the way. */
  @Test
  public void aRunThatReachesTheEndIsNotResumable() {
    assertFalse("a crawl that ran to the end has nothing to continue",
        HTTrackActivity.leavesPendingWork(false, 0, NONE_FAILED));
  }

  @Test
  public void aCrawlCutShortIsResumable() {
    // A soft stop lets pending transfers finish, so the engine still returns 0; so does a size or
    // time cap, which the engine applies by stopping itself. HTTrackLib.wasStopped() sees both.
    assertTrue(HTTrackActivity.leavesPendingWork(true, 0, NONE_FAILED));
    assertTrue(HTTrackActivity.leavesPendingWork(true, -1, NONE_FAILED));
  }

  @Test
  public void anEngineThatGaveUpIsResumable() {
    assertTrue(HTTrackActivity.leavesPendingWork(false, -1, NONE_FAILED));
    assertTrue(HTTrackActivity.leavesPendingWork(false, 1, NONE_FAILED));
  }

  /**
   * The engine drains its queue and returns 0 after a timeout storm exactly as it does after a
   * clean run, so stat_transport_failures is the only thing telling the two apart.
   */
  @Test
  public void aRunWithFailedTransfersIsResumable() {
    assertTrue("a link whose transfer failed left a hole the next run can fill",
        HTTrackActivity.leavesPendingWork(false, 0, 1));
    assertTrue(HTTrackActivity.leavesPendingWork(false, 0, 234));
  }

  @Test
  public void aFailedTransferStampsTheProjectEndToEnd() throws Exception {
    assertTrue("a timeout storm", reopensOnContinue(false, 0, 9));
    assertFalse("the same run with every link answered", reopensOnContinue(false, 0, NONE_FAILED));
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
    return reopensOnContinue(stoppedByUser, engineCode, NONE_FAILED);
  }

  private boolean reopensOnContinue(final boolean stoppedByUser, final int engineCode,
      final long transportFailures) throws IOException {
    HTTrackActivity.setInterruptedProfile(target,
        HTTrackActivity.leavesPendingWork(stoppedByUser, engineCode, transportFailures));
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

  /** CrawlRun.stopMirror needs a live engine, so the guard is pinned in the source instead. */
  private static String stopMirrorBody() throws IOException {
    return crawlRunBody("boolean stopMirror(final boolean force) {");
  }

  private static String crawlRunBody(final String declaration) throws IOException {
    final String source = TestSources
        .withoutCommentsAndStrings(TestSources.javaSource("CrawlRun"));
    final int from = source.indexOf(declaration);
    assertTrue("CrawlRun no longer declares " + declaration, from != -1);
    return TestSources.balancedBlock(source, from);
  }

  /** A stop runs on whichever thread asked for it, and onStopJob's is the main one on an 8
   *  second budget, so the stop may only record that a marker is owed. */
  @Test
  public void aStopRequestOnlyEverAsksForTheMarker() throws Exception {
    final String stop = stopMirrorBody();
    assertEquals("a write here is disk work on the stopper's own thread", 0,
        TestSources.occurrences(stop, "setInterruptedProfile"));
    assertEquals("one request, and it belongs to the guard that made the stop the recorder", 1,
        TestSources.occurrences(stop, "requestMarker();"));
    final String run = crawlRunBody("void runMirror() {");
    assertEquals("the crawl thread must write what a stop asked for, as well as its own", 1,
        TestSources.occurrences(run, "if (closeMarker(engineRan)) {"));
    assertEquals("the run's own reading of what it left behind", "pendingWork",
        TestSources.arguments(run, "setInterruptedProfile").trim());
  }

  @Test
  public void aStopAfterTheCrawlEndedIsIgnored() throws Exception {
    assertTrue("the finished pane's own stopMirror() would mark every project resumable",
        stopMirrorBody().contains("ResumePolicy.stopWritesMarker(isEnded(),"));
  }

  /** The verdict belongs to the run, so it is written where the run ends. */
  @Test
  public void theCrawlStampsItsOwnOutcome() throws Exception {
    final String source = TestSources.javaSource("CrawlRun");
    final int from = source.indexOf("void runMirror()");
    final int to = source.indexOf("owner.onFinished(displayMessage, errorsCount,");
    assertTrue("runMirror no longer bounded by its onFinished call", from != -1 && to > from);
    final String body = source.substring(from, to);
    final String resumeOffer = TestSources.arguments(body, "leavesPendingWork");
    assertTrue("the resume offer must read the run's own stop verdict",
        resumeOffer.contains("stop"));
    assertFalse("the resume offer must not read the user's flag alone",
        resumeOffer.contains("interrupted"));
    assertFalse("the resume offer must not narrow to one kind of stop",
        resumeOffer.contains("=="));
    assertTrue("runMirror must write the verdict before the finished pane opens",
        body.contains("setInterruptedProfile(pendingWork)"));
    assertTrue("the crawl must read as ended before the finished pane asks for a stop",
        body.contains("\n      end();"));
    assertTrue("end() is what makes isEnded() true", TestSources
        .balancedBlock(source, source.indexOf("void end() {"))
        .contains("advance(MirrorSession.Event.END)"));
  }

  /* The predicate below only ever sees what wasStopped() reports, so the engine's
     two abort flags are pinned here: stop alone misses a fatal disk error. */
  @Test
  public void wasStoppedWeighsBothOfTheEnginesAbortFlags() throws Exception {
    final String body = TestSources.between(
        TestSources.jniSource("htslibjni.c"), "HTTrackLib_wasStopped", "\n}");
    assertTrue("wasStopped must read state.stop", body.contains("state.stop"));
    assertTrue("wasStopped must also read exit_xh, which stop never sets",
        body.contains("hts_is_exiting"));
  }
}
