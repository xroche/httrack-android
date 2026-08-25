package com.httrack.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.httrack.android.jni.HTTrackLib;
import com.httrack.android.jni.HTTrackStats;
import org.junit.Test;

/** MirrorOutcome.of() has no android.* dependency, so unlike the pane it builds it can be run. */
public class MirrorOutcomeTest {
  private static final int ABORT_CALLBACK = 1;
  private static final int ABORT_UNKNOWN = 3;
  private static final boolean FINISHED = false;
  private static final boolean GAVE_UP = true;

  private static HTTrackStats stats(final long errorsCount, final long filesWritten) {
    final HTTrackStats stats = new HTTrackStats();
    stats.errorsCount = errorsCount;
    stats.filesWritten = filesWritten;
    return stats;
  }

  private static void check(final MirrorOutcome expected, final MirrorOutcome.Stop stop,
      final boolean engineAborted, final int abortCode, final long errorsCount,
      final long filesWritten) {
    assertEquals("stop=" + stop + " engineAborted=" + engineAborted + " abortCode=" + abortCode
        + " errors=" + errorsCount + " written=" + filesWritten, expected,
        MirrorOutcome.of(stop, engineAborted, abortCode, stats(errorsCount, filesWritten)));
  }

  /**
   * The engine sets its abort flag on its own for the two commonest ways a user stops a crawl: an
   * early stop is rolled back for want of data (htscore.c:2088), and a forced stop refuses the loop
   * callback (htscore.c:951). Weighing the abort first would report both as unwanted aborts.
   */
  @Test
  public void aStopTheUserAskedForIsNeverAnAbort() {
    check(MirrorOutcome.INTERRUPTED, MirrorOutcome.Stop.USER, FINISHED,
        MirrorOutcome.ABORT_ROLLBACK, 0, 0);
    check(MirrorOutcome.INTERRUPTED, MirrorOutcome.Stop.USER, FINISHED, ABORT_CALLBACK, 2, 40);
    check(MirrorOutcome.INTERRUPTED, MirrorOutcome.Stop.USER, FINISHED, MirrorOutcome.ABORT_FATAL,
        0, 7);
    check(MirrorOutcome.INTERRUPTED, MirrorOutcome.Stop.USER, FINISHED, MirrorOutcome.ABORT_NONE, 0,
        7);
  }

  /**
   * main() returns 0 for these, so without the abort flag they would all read as a success. An
   * abort sets wasStopped() as well, but must be named by its cause whether or not it did.
   */
  @Test
  public void anAbortTheUserDidNotAskForIsNamedByItsCause() {
    check(MirrorOutcome.ABORTED_FATAL, MirrorOutcome.Stop.ENGINE, GAVE_UP,
        MirrorOutcome.ABORT_FATAL, 0, 3);
    check(MirrorOutcome.ABORTED_FATAL, MirrorOutcome.Stop.NONE, GAVE_UP,
        MirrorOutcome.ABORT_FATAL, 0, 3);
    check(MirrorOutcome.ABORTED_ROLLBACK, MirrorOutcome.Stop.ENGINE, FINISHED,
        MirrorOutcome.ABORT_ROLLBACK, 0, 0);
    check(MirrorOutcome.ABORTED_ROLLBACK, MirrorOutcome.Stop.NONE, FINISHED,
        MirrorOutcome.ABORT_ROLLBACK, 0, 0);
  }

  /** An abort code nobody has mapped must still abort, not fall through to success. */
  @Test
  public void anUnrecognisedAbortCodeIsStillAnAbort() {
    check(MirrorOutcome.ABORTED_OTHER, MirrorOutcome.Stop.ENGINE, FINISHED, ABORT_CALLBACK, 0, 0);
    check(MirrorOutcome.ABORTED_OTHER, MirrorOutcome.Stop.NONE, FINISHED, ABORT_CALLBACK, 0, 0);
    check(MirrorOutcome.ABORTED_OTHER, MirrorOutcome.Stop.ENGINE, FINISHED, ABORT_UNKNOWN, 0, 40);
    check(MirrorOutcome.ABORTED_OTHER, MirrorOutcome.Stop.NONE, FINISHED, ABORT_UNKNOWN, 0, 40);
    check(MirrorOutcome.ABORTED_OTHER, MirrorOutcome.Stop.NONE, GAVE_UP, ABORT_CALLBACK, 0, 0);
    check(MirrorOutcome.ABORTED_OTHER, MirrorOutcome.Stop.NONE, GAVE_UP, ABORT_UNKNOWN, 0, 40);
  }

  /**
   * back_checkmirror() asks for a smooth stop on the max-size and max-time caps, which sets the
   * engine's stop flag but no abort flag. The mirror is short, so a clean error count is not a
   * success, and pendingWork already offers the resume that says so.
   */
  @Test
  public void aCapTheEngineHitIsNotASuccess() {
    check(MirrorOutcome.STOPPED_AT_LIMIT, MirrorOutcome.Stop.ENGINE, FINISHED,
        MirrorOutcome.ABORT_NONE, 0, 400);
    check(MirrorOutcome.STOPPED_AT_LIMIT, MirrorOutcome.Stop.ENGINE, FINISHED,
        MirrorOutcome.ABORT_NONE, 5, 400);
    check(MirrorOutcome.STOPPED_AT_LIMIT, MirrorOutcome.Stop.ENGINE, FINISHED,
        MirrorOutcome.ABORT_NONE, 5, 0);
  }

  @Test
  public void aCompletedRunIsStillJudgedOnItsErrorCount() {
    check(MirrorOutcome.SUCCESS, MirrorOutcome.Stop.NONE, FINISHED, MirrorOutcome.ABORT_NONE, 0,
        40);
    check(MirrorOutcome.SUCCESS, MirrorOutcome.Stop.NONE, FINISHED, MirrorOutcome.ABORT_NONE, 0, 0);
    check(MirrorOutcome.SUCCESS_WITH_ERRORS, MirrorOutcome.Stop.NONE, FINISHED,
        MirrorOutcome.ABORT_NONE, 5, 40);
    check(MirrorOutcome.FAILED, MirrorOutcome.Stop.NONE, FINISHED, MirrorOutcome.ABORT_NONE, 5, 0);
  }

  /** Reading the aborted code before the cause would flatten every named abort to ABORTED_OTHER. */
  @Test
  public void aNamedCauseOutranksTheBareAbortedCode() {
    check(MirrorOutcome.ABORTED_FATAL, MirrorOutcome.Stop.ENGINE, GAVE_UP,
        MirrorOutcome.ABORT_FATAL, 0, 3);
    check(MirrorOutcome.INTERRUPTED, MirrorOutcome.Stop.USER, GAVE_UP,
        MirrorOutcome.ABORT_FATAL, 0, 3);
  }

  /**
   * httpmirror() gives up on a few allocation and filter-cap guards without ever setting exit_xh,
   * so abortCode() and the stats both read like a clean run and only the exit code disagrees.
   */
  @Test
  public void anAbortWithNoCauseIsAnAbortAndNotASuccess() {
    check(MirrorOutcome.ABORTED_OTHER, MirrorOutcome.Stop.NONE, GAVE_UP,
        MirrorOutcome.ABORT_NONE, 0, 0);
    check(MirrorOutcome.ABORTED_OTHER, MirrorOutcome.Stop.NONE, GAVE_UP,
        MirrorOutcome.ABORT_NONE, 0, 40);
    check(MirrorOutcome.ABORTED_OTHER, MirrorOutcome.Stop.ENGINE, GAVE_UP,
        MirrorOutcome.ABORT_NONE, 0, 400);
    check(MirrorOutcome.ABORTED_OTHER, MirrorOutcome.Stop.NONE, GAVE_UP,
        MirrorOutcome.ABORT_NONE, 5, 40);
  }

  /** Only these two codes mean a mirror ran; anything else is a command line the engine refused. */
  @Test
  public void theCodesThatMeanAMirrorRanAreTheOnlyOnes() {
    assertTrue(MirrorOutcome.mirrorRan(0));
    assertTrue(MirrorOutcome.mirrorRan(HTTrackLib.EXIT_MIRROR_ABORTED));
    assertFalse(MirrorOutcome.mirrorRan(-1));
    assertFalse(MirrorOutcome.mirrorRan(1));
    assertFalse(MirrorOutcome.mirrorRan(2));
    assertFalse(MirrorOutcome.mirrorRan(255));
    assertFalse(MirrorOutcome.mirrorAborted(0));
    assertTrue(MirrorOutcome.mirrorAborted(HTTrackLib.EXIT_MIRROR_ABORTED));
    assertFalse(MirrorOutcome.mirrorAborted(-1));
    assertFalse(MirrorOutcome.mirrorAborted(1));
    assertFalse(MirrorOutcome.mirrorAborted(2));
    assertFalse(MirrorOutcome.mirrorAborted(255));
  }

  private static void verdict(final String expectedText, final boolean expectedLink,
      final int engineCode, final MirrorOutcome.Stop stop, final int abortCode,
      final long errorsCount, final long filesWritten) {
    final MirrorOutcome.Verdict v = MirrorOutcome.decide(engineCode, stop, abortCode,
        stats(errorsCount, filesWritten));
    final String where = "code=" + engineCode + " stop=" + stop + " abortCode=" + abortCode;
    assertEquals(where, expectedText, v.text());
    assertEquals(where + " folder link", expectedLink, v.showsFolderLink());
  }

  /** A mirror that ran wrote a folder, so its verdict names the cause and keeps the link. */
  @Test
  public void everyRunThatMirroredKeepsItsFolderLink() {
    verdict("<b>Success</b>!", true, 0, MirrorOutcome.Stop.NONE, MirrorOutcome.ABORT_NONE, 0, 40);
    verdict("<b>Success</b>! (5 errors)", true, 0, MirrorOutcome.Stop.NONE,
        MirrorOutcome.ABORT_NONE, 5, 40);
    verdict("<b>Failed</b>! (5 errors, no files written)", true, 0, MirrorOutcome.Stop.NONE,
        MirrorOutcome.ABORT_NONE, 5, 0);
    verdict("<b>Interrupted</b>! (2 errors)", true, 0, MirrorOutcome.Stop.USER,
        MirrorOutcome.ABORT_NONE, 2, 40);
    verdict("<b>Stopped</b>! (size or time limit reached, 0 errors)", true, 0,
        MirrorOutcome.Stop.ENGINE, MirrorOutcome.ABORT_NONE, 0, 400);
    verdict("<b>Aborted</b>! (nothing was transferred, so the mirror was left as it was)", true,
        0, MirrorOutcome.Stop.ENGINE, MirrorOutcome.ABORT_ROLLBACK, 0, 0);
  }

  /** The engine gave up, and the half-written mirror is still on disk. */
  @Test
  public void anAbortedMirrorNamesItsCauseAndKeepsItsFolderLink() {
    verdict("<b>Aborted</b>! (out of disk space, too many links, or another fatal error)", true,
        HTTrackLib.EXIT_MIRROR_ABORTED, MirrorOutcome.Stop.ENGINE, MirrorOutcome.ABORT_FATAL, 0, 3);
    verdict("<b>Aborted</b>! (the engine could not continue)", true,
        HTTrackLib.EXIT_MIRROR_ABORTED, MirrorOutcome.Stop.NONE, MirrorOutcome.ABORT_NONE, 0, 0);
  }

  /** Whatever cause the engine names, a mirror it gave up on had already written a folder. */
  @Test
  public void everyAbortedRunKeepsItsFolderLink() {
    final int[] causes = { MirrorOutcome.ABORT_NONE, MirrorOutcome.ABORT_FATAL,
        MirrorOutcome.ABORT_ROLLBACK, ABORT_CALLBACK, ABORT_UNKNOWN };
    for (final MirrorOutcome.Stop stop : MirrorOutcome.Stop.values()) {
      for (final int abortCode : causes) {
        final MirrorOutcome.Verdict v = MirrorOutcome.decide(HTTrackLib.EXIT_MIRROR_ABORTED, stop,
            abortCode, stats(0, 3));
        final String where = "stop=" + stop + " abortCode=" + abortCode;
        assertTrue(where + " lost its folder link", v.showsFolderLink());
        assertTrue(where + " lost its cause: " + v.text(), v.text().startsWith("<b>"));
      }
    }
  }

  /** A refused command line mirrored nothing, so there is no folder to offer. */
  @Test
  public void aRefusedCommandLineShowsItsCodeAndNoLink() {
    verdict("<b>Error</b> (<i>code -1</i>)", false, -1, MirrorOutcome.Stop.NONE,
        MirrorOutcome.ABORT_NONE, 0, 0);
    verdict("<b>Error</b> (<i>code 1</i>)", false, 1, MirrorOutcome.Stop.NONE,
        MirrorOutcome.ABORT_NONE, 0, 0);
    verdict("<b>Error</b> (<i>code 255</i>)", false, 255, MirrorOutcome.Stop.NONE,
        MirrorOutcome.ABORT_NONE, 0, 0);
  }
}
