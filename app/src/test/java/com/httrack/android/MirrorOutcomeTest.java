package com.httrack.android;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/** MirrorOutcome.of() has no android.* dependency, so unlike the pane it builds it can be run. */
public class MirrorOutcomeTest {
  private static final int NOT_ABORTED = 0;
  private static final int ABORT_IO = -1;
  private static final int ABORT_CALLBACK = 1;
  private static final int ABORT_ROLLBACK = 2;

  private static void check(final MirrorOutcome expected, final int code, final boolean interrupted,
      final int abortCode, final long errorsCount, final long filesWritten) {
    assertEquals("code=" + code + " interrupted=" + interrupted + " abortCode=" + abortCode
        + " errors=" + errorsCount + " written=" + filesWritten, expected,
        MirrorOutcome.of(code, interrupted, abortCode, errorsCount, filesWritten));
  }

  @Test
  public void aFailedMainOutranksEverythingElse() {
    check(MirrorOutcome.ERROR, -1, false, NOT_ABORTED, 0, 12);
    check(MirrorOutcome.ERROR, -1, true, ABORT_IO, 3, 0);
  }

  /**
   * The engine sets exit_xh on its own for two of the commonest ways a user stops a crawl: an early
   * stop is rolled back for want of data, and a forced stop refuses the loop callback. Weighing the
   * abort first would report every one of those as an abort the user never asked for.
   */
  @Test
  public void aStopTheUserAskedForIsNeverAnAbort() {
    check(MirrorOutcome.INTERRUPTED, 0, true, ABORT_ROLLBACK, 0, 0);
    check(MirrorOutcome.INTERRUPTED, 0, true, ABORT_CALLBACK, 2, 40);
    check(MirrorOutcome.INTERRUPTED, 0, true, ABORT_IO, 0, 7);
    check(MirrorOutcome.INTERRUPTED, 0, true, NOT_ABORTED, 0, 7);
  }

  /** main() returns 0 for these, so without exit_xh they would all read as a success. */
  @Test
  public void anAbortTheUserDidNotAskForIsNamedByItsCause() {
    check(MirrorOutcome.ABORTED_IO, 0, false, ABORT_IO, 0, 3);
    check(MirrorOutcome.ABORTED_ROLLBACK, 0, false, ABORT_ROLLBACK, 0, 0);
    check(MirrorOutcome.ABORTED, 0, false, ABORT_CALLBACK, 0, 0);
  }

  @Test
  public void aCompletedRunIsStillJudgedOnItsErrorCount() {
    check(MirrorOutcome.SUCCESS, 0, false, NOT_ABORTED, 0, 40);
    check(MirrorOutcome.SUCCESS, 0, false, NOT_ABORTED, 0, 0);
    check(MirrorOutcome.SUCCESS_WITH_ERRORS, 0, false, NOT_ABORTED, 5, 40);
    check(MirrorOutcome.FAILED, 0, false, NOT_ABORTED, 5, 0);
  }
}
