package com.httrack.android;

import com.httrack.android.jni.HTTrackLib;
import com.httrack.android.jni.HTTrackStats;

/**
 * What a finished crawl actually was. Kept apart from the pane so the choice can be tested; the
 * wording that goes with each case belongs to the caller.
 */
enum MirrorOutcome {
  /** The user asked for the stop. */
  INTERRUPTED,
  /** Out of room: a write that failed, or a link the table could not record. */
  ABORTED_FATAL,
  /** Nothing arrived, so the engine rolled the session back. */
  ABORTED_ROLLBACK,
  /** The engine gave up for a reason it does not name. */
  ABORTED_OTHER,
  /** A size or time cap the user set was reached, so the mirror is short. */
  STOPPED_AT_LIMIT,
  SUCCESS,
  SUCCESS_WITH_ERRORS,
  /** Errors, and no file written. */
  FAILED;

  /** Who asked the run to end early, if anyone. */
  enum Stop {
    NONE,
    /** The user tapped Stop. */
    USER,
    /** The engine stopped itself, at a cap or on an abort; of() tells the two apart. */
    ENGINE
  }

  /** The values HTTrackLib.abortCode() reports; anything else is ABORTED_OTHER. */
  static final int ABORT_NONE = 0;
  static final int ABORT_FATAL = -1;
  static final int ABORT_ROLLBACK = 2;

  /** What the finish pane says about a run, and whether a mirror exists for it to link to. */
  static final class Verdict {
    private final String text;
    private final boolean showsFolderLink;

    Verdict(final String text, final boolean showsFolderLink) {
      this.text = text;
      this.showsFolderLink = showsFolderLink;
    }

    String text() {
      return text;
    }

    boolean showsFolderLink() {
      return showsFolderLink;
    }
  }

  /**
   * Weigh the run and say what the pane shows. A command line the engine refused mirrored
   * nothing, so it gets its bare code and no link; everything else ran, an abort included.
   */
  static Verdict decide(final int engineCode, final Stop stop, final int abortCode,
      final HTTrackStats stats) {
    if (!mirrorRan(engineCode)) {
      return new Verdict("<b>Error</b> (<i>code " + engineCode + "</i>)", false);
    }
    return new Verdict(
        describe(of(stop, mirrorAborted(engineCode), abortCode, stats), stats), true);
  }

  private static String describe(final MirrorOutcome outcome, final HTTrackStats stats) {
    switch (outcome) {
    case INTERRUPTED:
      return "<b>Interrupted</b>! (" + stats.errorsCount + " errors)";
    case STOPPED_AT_LIMIT:
      return "<b>Stopped</b>! (size or time limit reached, " + stats.errorsCount + " errors)";
    case ABORTED_FATAL:
      return "<b>Aborted</b>! (out of disk space, too many links, or another fatal error)";
    case ABORTED_ROLLBACK:
      return "<b>Aborted</b>! (nothing was transferred, so the mirror was left as it was)";
    case ABORTED_OTHER:
      return "<b>Aborted</b>! (the engine could not continue)";
    case SUCCESS:
      return "<b>Success</b>!";
    case SUCCESS_WITH_ERRORS:
      return "<b>Success</b>! (" + stats.errorsCount + " errors)";
    case FAILED:
      return "<b>Failed</b>! (" + stats.errorsCount + " errors, no files written)";
    default:
      // No build-time check catches a new constant; a null message would ship as "null".
      throw new IllegalStateException(outcome.name());
    }
  }

  /** Whether the engine ran a mirror at all, rather than refusing the command line. */
  static boolean mirrorRan(final int engineCode) {
    return engineCode == 0 || engineCode == HTTrackLib.EXIT_MIRROR_ABORTED;
  }

  /** Whether the engine gave up on a mirror it had started. */
  static boolean mirrorAborted(final int engineCode) {
    return engineCode == HTTrackLib.EXIT_MIRROR_ABORTED;
  }

  /**
   * Weigh STOP, ENGINEABORTED from mirrorAborted() and ABORTCODE from HTTrackLib.abortCode()
   * against what the run recorded. A user stop must come first: it sets the engine's abort flag
   * two ways of its own, so the verdict alone reads it as an abort nobody asked for. An abort
   * outranks a cap, because reaching a cap also makes the engine report itself stopped.
   */
  static MirrorOutcome of(final Stop stop, final boolean engineAborted, final int abortCode,
      final HTTrackStats stats) {
    if (stop == Stop.USER) {
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
    // A few engine guards give up without setting a cause; only the exit code shows it.
    if (engineAborted) {
      return ABORTED_OTHER;
    }
    if (stop == Stop.ENGINE) {
      return STOPPED_AT_LIMIT;
    }
    if (stats.errorsCount == 0) {
      return SUCCESS;
    }
    return stats.filesWritten != 0 ? SUCCESS_WITH_ERRORS : FAILED;
  }
}
