package com.httrack.android;

/**
 * What becomes of the mirror job: whether a stop brings it back, and whether a user's Stop has to
 * drop it. The stop reasons mirror {@code android.app.job.JobParameters}, repeated here because
 * the unit suite cannot load {@code android.*}. A rescheduled job resumes rather than re-downloads
 * because {@link ResumeArgv} rewrites the argv it replays.
 */
final class JobStopPolicy {
  static final int STOP_REASON_UNDEFINED = 0;
  static final int STOP_REASON_CANCELLED_BY_APP = 1;
  static final int STOP_REASON_PREEMPT = 2;
  static final int STOP_REASON_TIMEOUT = 3;
  static final int STOP_REASON_DEVICE_STATE = 4;
  static final int STOP_REASON_CONSTRAINT_BATTERY_NOT_LOW = 5;
  static final int STOP_REASON_CONSTRAINT_CHARGING = 6;
  static final int STOP_REASON_CONSTRAINT_CONNECTIVITY = 7;
  static final int STOP_REASON_CONSTRAINT_DEVICE_IDLE = 8;
  static final int STOP_REASON_CONSTRAINT_STORAGE_NOT_LOW = 9;
  static final int STOP_REASON_QUOTA = 10;
  static final int STOP_REASON_BACKGROUND_RESTRICTION = 11;
  static final int STOP_REASON_APP_STANDBY = 12;
  static final int STOP_REASON_USER = 13;
  static final int STOP_REASON_SYSTEM_PROCESSING = 14;
  static final int STOP_REASON_ESTIMATED_APP_LAUNCH_TIME_CHANGED = 15;
  static final int STOP_REASON_TIMEOUT_ABANDONED = 16;

  private JobStopPolicy() {
  }

  /**
   * Must the stopped job be run again? An unknown reason is not a reason to abandon a mirror, so
   * only the three reasons whose retry cannot help are refused.
   *
   * @param stopReason
   *          what the system gave as its reason for the stop
   * @return true when onStopJob must return true
   */
  static boolean reschedules(final int stopReason) {
    switch (stopReason) {
    // Ours, from the Stop button or from jobFinished.
    case STOP_REASON_CANCELLED_BY_APP:
    // The Task Manager stop demotes the job, so a retry loses the Doze, quota and network
    // exemptions together.
    case STOP_REASON_USER:
    // A background-restricted app gets no network bypass, so a retry only damages the mirror.
    case STOP_REASON_BACKGROUND_RESTRICTION:
      return false;
    default:
      return true;
    }
  }

  /**
   * Must the app explain the stop? Only where nothing else will: the two stops that abandon the
   * mirror without the user having asked for it from our own Stop button.
   *
   * @param stopReason
   *          what the system gave as its reason for the stop
   * @return true when only a notification can explain the stop
   */
  static boolean tellsTheUser(final int stopReason) {
    return stopReason == STOP_REASON_USER
        || stopReason == STOP_REASON_BACKGROUND_RESTRICTION;
  }

  /**
   * Must this Stop drop the job the scheduler holds? The engine's own stop leaves it scheduled,
   * so without the cancel a mirror the user abandoned comes back as a retry.
   *
   * @param force
   *          whether the stop cuts the transfers short rather than letting them finish
   * @param ownerAnswered
   *          whether an owner was found to take the stop
   * @return true when MirrorJobService.cancel must be called
   */
  static boolean cancelsScheduledJob(final boolean force, final boolean ownerAnswered) {
    // A soft interrupt keeps the job, whose exemptions are what let the pending transfers finish.
    return force || !ownerAnswered;
  }

  /**
   * Must a start refused as already in progress come back? Only where the crawl this execution is
   * retrying was still winding down; any other holder is a second mirror of the same project,
   * which a retry must leave refused rather than loop on.
   *
   * @param refusedAsInProgress
   *          whether ProfileLockPolicy refused this run
   * @param earlierExecutionLive
   *          whether the previous execution of this job was still running when this one started
   * @return true when jobFinished must ask for the reschedule
   */
  static boolean reschedulesRefusedStart(final boolean refusedAsInProgress,
      final boolean earlierExecutionLive) {
    return refusedAsInProgress && earlierExecutionLive;
  }
}
