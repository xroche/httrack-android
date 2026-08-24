package com.httrack.android;

import com.httrack.android.jni.HTTrackStats;

/**
 * What a finished crawl actually was. Kept free of android.* so the choice can be tested; the
 * wording that goes with each case belongs to the caller.
 */
enum MirrorOutcome {
  /** The user asked for the stop. */
  INTERRUPTED,
  /** Out of room: a write that failed, or a link the table could not record. */
  ABORTED_FATAL,
  /** Nothing arrived, so the engine rolled the session back. */
  ABORTED_ROLLBACK,
  /** The engine gave up for a reason it does not name. No path is known to reach this. */
  ABORTED_OTHER,
  SUCCESS,
  SUCCESS_WITH_ERRORS,
  /** Errors, and no file written. */
  FAILED;

  /** The values HTTrackLib.abortCode() reports; anything else is ABORTED_OTHER. */
  static final int ABORT_NONE = 0;
  static final int ABORT_FATAL = -1;
  static final int ABORT_ROLLBACK = 2;

  /**
   * Weigh ABORTCODE, from HTTrackLib.abortCode(), against what the run recorded. INTERRUPTED must
   * come first: a user stop sets the engine's abort flag two ways of its own, so the verdict alone
   * reads it as an abort nobody asked for.
   */
  static MirrorOutcome of(final boolean interrupted, final int abortCode,
      final HTTrackStats stats) {
    if (interrupted) {
      return INTERRUPTED;
    }
    switch (abortCode) {
      case ABORT_NONE:
        break;
      case ABORT_FATAL:
        return ABORTED_FATAL;
      case ABORT_ROLLBACK:
        return ABORTED_ROLLBACK;
      default:
        return ABORTED_OTHER;
    }
    if (stats.errorsCount == 0) {
      return SUCCESS;
    }
    return stats.filesWritten != 0 ? SUCCESS_WITH_ERRORS : FAILED;
  }
}
