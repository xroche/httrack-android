package com.httrack.android;

/**
 * Who drives the crawl. A user-initiated data transfer job exists from API 34 on, and below that
 * the activity is the only owner there can be; no Android type appears here, so each decision can
 * be checked against its truth table.
 */
final class CrawlOwnerPolicy {
  /** The first release with user-initiated data transfer jobs. */
  static final int FIRST_JOB_SDK = 34;

  private CrawlOwnerPolicy() {
  }

  /**
   * Does a job own the crawl on this device?
   *
   * @param sdkInt
   *          the running Android release
   * @return true when the crawl must be handed to the job
   */
  static boolean ownsInJob(final int sdkInt) {
    return sdkInt >= FIRST_JOB_SDK;
  }

  /**
   * Must the activity start the crawl itself? A refused job falls back here, which is the same
   * path every device below 34 takes anyway.
   *
   * @param jobOwns
   *          whether a job owns the crawl on this device
   * @param scheduleAccepted
   *          whether the system accepted the job
   * @return true when startRunner() must run
   */
  static boolean startsInActivity(final boolean jobOwns, final boolean scheduleAccepted) {
    return !jobOwns || !scheduleAccepted;
  }

  /**
   * May a start begin a new crawl, rather than attach to the one already running? Scheduling over
   * a live job id cancels its execution, so this has to be answered before the call.
   *
   * @param sessionLive
   *          whether the session slot holds a crawl that has not ended
   * @param jobPending
   *          whether the scheduler already holds our job
   * @return true when a new crawl may be started
   */
  static boolean startsNewCrawl(final boolean sessionLive, final boolean jobPending) {
    return !sessionLive && !jobPending;
  }
}
