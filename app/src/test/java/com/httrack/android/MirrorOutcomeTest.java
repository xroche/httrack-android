package com.httrack.android;

import static org.junit.Assert.assertEquals;

import com.httrack.android.jni.HTTrackStats;
import org.junit.Test;

/** MirrorOutcome.of() has no android.* dependency, so unlike the pane it builds it can be run. */
public class MirrorOutcomeTest {
  private static final int ABORT_CALLBACK = 1;
  private static final int ABORT_UNKNOWN = 3;

  private static HTTrackStats stats(final long errorsCount, final long filesWritten) {
    final HTTrackStats stats = new HTTrackStats();
    stats.errorsCount = errorsCount;
    stats.filesWritten = filesWritten;
    return stats;
  }

  private static void check(final MirrorOutcome expected, final boolean interrupted,
      final int abortCode, final long errorsCount, final long filesWritten) {
    assertEquals("interrupted=" + interrupted + " abortCode=" + abortCode + " errors="
        + errorsCount + " written=" + filesWritten, expected,
        MirrorOutcome.of(interrupted, abortCode, stats(errorsCount, filesWritten)));
  }

  /**
   * The engine sets its abort flag on its own for the two commonest ways a user stops a crawl: an
   * early stop is rolled back for want of data (htscore.c:2088), and a forced stop refuses the loop
   * callback (htscore.c:951). Weighing the abort first would report both as unwanted aborts.
   */
  @Test
  public void aStopTheUserAskedForIsNeverAnAbort() {
    check(MirrorOutcome.INTERRUPTED, true, MirrorOutcome.ABORT_ROLLBACK, 0, 0);
    check(MirrorOutcome.INTERRUPTED, true, ABORT_CALLBACK, 2, 40);
    check(MirrorOutcome.INTERRUPTED, true, MirrorOutcome.ABORT_FATAL, 0, 7);
    check(MirrorOutcome.INTERRUPTED, true, MirrorOutcome.ABORT_NONE, 0, 7);
  }

  /** main() returns 0 for these, so without the abort flag they would all read as a success. */
  @Test
  public void anAbortTheUserDidNotAskForIsNamedByItsCause() {
    check(MirrorOutcome.ABORTED_FATAL, false, MirrorOutcome.ABORT_FATAL, 0, 3);
    check(MirrorOutcome.ABORTED_ROLLBACK, false, MirrorOutcome.ABORT_ROLLBACK, 0, 0);
  }

  /** An abort code nobody has mapped must still abort, not fall through to success. */
  @Test
  public void anUnrecognisedAbortCodeIsStillAnAbort() {
    check(MirrorOutcome.ABORTED_OTHER, false, ABORT_CALLBACK, 0, 0);
    check(MirrorOutcome.ABORTED_OTHER, false, ABORT_UNKNOWN, 0, 40);
  }

  @Test
  public void aCompletedRunIsStillJudgedOnItsErrorCount() {
    check(MirrorOutcome.SUCCESS, false, MirrorOutcome.ABORT_NONE, 0, 40);
    check(MirrorOutcome.SUCCESS, false, MirrorOutcome.ABORT_NONE, 0, 0);
    check(MirrorOutcome.SUCCESS_WITH_ERRORS, false, MirrorOutcome.ABORT_NONE, 5, 40);
    check(MirrorOutcome.FAILED, false, MirrorOutcome.ABORT_NONE, 5, 0);
  }
}
