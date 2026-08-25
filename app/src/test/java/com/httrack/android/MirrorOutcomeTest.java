package com.httrack.android;

import static org.junit.Assert.assertEquals;

import com.httrack.android.jni.HTTrackStats;
import org.junit.Test;

/** MirrorOutcome.of() has no android.* dependency, so unlike the pane it builds it can be run. */
public class MirrorOutcomeTest {
  private static final int ABORT_CALLBACK = 1;
  private static final int ABORT_UNKNOWN = 3;
  private static final int EXIT_OK = 0;

  private static HTTrackStats stats(final long errorsCount, final long filesWritten) {
    final HTTrackStats stats = new HTTrackStats();
    stats.errorsCount = errorsCount;
    stats.filesWritten = filesWritten;
    return stats;
  }

  private static void check(final MirrorOutcome expected, final MirrorOutcome.Stop stop,
      final int abortCode, final long errorsCount, final long filesWritten) {
    check(expected, stop, EXIT_OK, abortCode, errorsCount, filesWritten);
  }

  private static void check(final MirrorOutcome expected, final MirrorOutcome.Stop stop,
      final int engineCode, final int abortCode, final long errorsCount,
      final long filesWritten) {
    assertEquals("stop=" + stop + " engineCode=" + engineCode + " abortCode=" + abortCode
        + " errors=" + errorsCount + " written=" + filesWritten, expected,
        MirrorOutcome.of(stop, engineCode, abortCode, stats(errorsCount, filesWritten)));
  }

  /**
   * The engine sets its abort flag on its own for the two commonest ways a user stops a crawl: an
   * early stop is rolled back for want of data (htscore.c:2088), and a forced stop refuses the loop
   * callback (htscore.c:951). Weighing the abort first would report both as unwanted aborts.
   */
  @Test
  public void aStopTheUserAskedForIsNeverAnAbort() {
    check(MirrorOutcome.INTERRUPTED, MirrorOutcome.Stop.USER, MirrorOutcome.ABORT_ROLLBACK, 0, 0);
    check(MirrorOutcome.INTERRUPTED, MirrorOutcome.Stop.USER, ABORT_CALLBACK, 2, 40);
    check(MirrorOutcome.INTERRUPTED, MirrorOutcome.Stop.USER, MirrorOutcome.ABORT_FATAL, 0, 7);
    check(MirrorOutcome.INTERRUPTED, MirrorOutcome.Stop.USER, MirrorOutcome.ABORT_NONE, 0, 7);
  }

  /**
   * main() returns 0 for these, so without the abort flag they would all read as a success. An
   * abort sets wasStopped() as well, but must be named by its cause whether or not it did.
   */
  @Test
  public void anAbortTheUserDidNotAskForIsNamedByItsCause() {
    check(MirrorOutcome.ABORTED_FATAL, MirrorOutcome.Stop.ENGINE, MirrorOutcome.ABORT_FATAL, 0, 3);
    check(MirrorOutcome.ABORTED_FATAL, MirrorOutcome.Stop.NONE, MirrorOutcome.ABORT_FATAL, 0, 3);
    check(MirrorOutcome.ABORTED_ROLLBACK, MirrorOutcome.Stop.ENGINE, MirrorOutcome.ABORT_ROLLBACK, 0, 0);
    check(MirrorOutcome.ABORTED_ROLLBACK, MirrorOutcome.Stop.NONE, MirrorOutcome.ABORT_ROLLBACK, 0, 0);
  }

  /** An abort code nobody has mapped must still abort, not fall through to success. */
  @Test
  public void anUnrecognisedAbortCodeIsStillAnAbort() {
    check(MirrorOutcome.ABORTED_OTHER, MirrorOutcome.Stop.ENGINE, ABORT_CALLBACK, 0, 0);
    check(MirrorOutcome.ABORTED_OTHER, MirrorOutcome.Stop.NONE, ABORT_CALLBACK, 0, 0);
    check(MirrorOutcome.ABORTED_OTHER, MirrorOutcome.Stop.ENGINE, ABORT_UNKNOWN, 0, 40);
    check(MirrorOutcome.ABORTED_OTHER, MirrorOutcome.Stop.NONE, ABORT_UNKNOWN, 0, 40);
  }

  /**
   * back_checkmirror() asks for a smooth stop on the max-size and max-time caps, which sets the
   * engine's stop flag but no abort flag. The mirror is short, so a clean error count is not a
   * success, and pendingWork already offers the resume that says so.
   */
  @Test
  public void aCapTheEngineHitIsNotASuccess() {
    check(MirrorOutcome.STOPPED_AT_LIMIT, MirrorOutcome.Stop.ENGINE, MirrorOutcome.ABORT_NONE,
        0, 400);
    check(MirrorOutcome.STOPPED_AT_LIMIT, MirrorOutcome.Stop.ENGINE, MirrorOutcome.ABORT_NONE,
        5, 400);
    check(MirrorOutcome.STOPPED_AT_LIMIT, MirrorOutcome.Stop.ENGINE, MirrorOutcome.ABORT_NONE,
        5, 0);
  }

  @Test
  public void aCompletedRunIsStillJudgedOnItsErrorCount() {
    check(MirrorOutcome.SUCCESS, MirrorOutcome.Stop.NONE, MirrorOutcome.ABORT_NONE, 0, 40);
    check(MirrorOutcome.SUCCESS, MirrorOutcome.Stop.NONE, MirrorOutcome.ABORT_NONE, 0, 0);
    check(MirrorOutcome.SUCCESS_WITH_ERRORS, MirrorOutcome.Stop.NONE, MirrorOutcome.ABORT_NONE, 5, 40);
    check(MirrorOutcome.FAILED, MirrorOutcome.Stop.NONE, MirrorOutcome.ABORT_NONE, 5, 0);
  }

  /**
   * Since 3.50 hts_main2() returns 3 for a mirror it gave up on, where it used to return the same 0
   * as a finished one. The cause it names still decides the wording.
   */
  @Test
  public void theAbortedExitCodeKeepsTheCauseTheEngineNamed() {
    check(MirrorOutcome.ABORTED_FATAL, MirrorOutcome.Stop.ENGINE, MirrorOutcome.EXIT_MIRROR_ABORTED,
        MirrorOutcome.ABORT_FATAL, 0, 3);
    check(MirrorOutcome.ABORTED_ROLLBACK, MirrorOutcome.Stop.ENGINE,
        MirrorOutcome.EXIT_MIRROR_ABORTED, MirrorOutcome.ABORT_ROLLBACK, 0, 0);
    check(MirrorOutcome.INTERRUPTED, MirrorOutcome.Stop.USER, MirrorOutcome.EXIT_MIRROR_ABORTED,
        MirrorOutcome.ABORT_FATAL, 0, 3);
  }

  /**
   * httpmirror() gives up on some startup failures without ever setting exit_xh, so abortCode()
   * and the stats both read like a clean run and only the exit code disagrees.
   */
  @Test
  public void anAbortWithNoCauseIsAnAbortAndNotASuccess() {
    check(MirrorOutcome.ABORTED_OTHER, MirrorOutcome.Stop.NONE, MirrorOutcome.EXIT_MIRROR_ABORTED,
        MirrorOutcome.ABORT_NONE, 0, 0);
    check(MirrorOutcome.ABORTED_OTHER, MirrorOutcome.Stop.NONE, MirrorOutcome.EXIT_MIRROR_ABORTED,
        MirrorOutcome.ABORT_NONE, 0, 40);
    check(MirrorOutcome.ABORTED_OTHER, MirrorOutcome.Stop.ENGINE, MirrorOutcome.EXIT_MIRROR_ABORTED,
        MirrorOutcome.ABORT_NONE, 0, 400);
  }

  /** The abort channel is the exit code's alone: 3 as an abortCode() still means an unnamed cause. */
  @Test
  public void theTwoChannelsAreReadSeparately() {
    check(MirrorOutcome.ABORTED_OTHER, MirrorOutcome.Stop.NONE, EXIT_OK, ABORT_UNKNOWN, 0, 40);
    check(MirrorOutcome.SUCCESS, MirrorOutcome.Stop.NONE, EXIT_OK, MirrorOutcome.ABORT_NONE, 0, 40);
  }
}
