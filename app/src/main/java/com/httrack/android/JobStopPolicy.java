package com.httrack.android;

/**
 * What a stopped job does next. The values mirror {@code android.app.job.JobParameters}, repeated
 * here because the unit suite cannot load {@code android.*}.
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
}
