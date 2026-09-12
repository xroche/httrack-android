package com.httrack.android;

/**
 * Whether a second crawl may take the project profile. Three mechanisms refuse it, and one of
 * them throws where the others return, so a refusal read as a run failure reports a crash to the
 * user. No Android type appears here, so the decision can be checked against its truth table.
 */
final class ProfileLockPolicy {
  private ProfileLockPolicy() {
  }

  /**
   * Is a mirror of this project already running?
   *
   * @param instanceMarked whether this process already registered a run on the profile
   * @param lockRefused    whether tryLock() returned null, so another process holds the lock
   * @param lockOverlapped whether tryLock() threw OverlappingFileLockException, which is how it
   *                       reports a lock held by this same JVM
   * @return true when the run must report that a mirror is already in progress
   */
  static boolean alreadyInProgress(final boolean instanceMarked, final boolean lockRefused,
      final boolean lockOverlapped) {
    return instanceMarked || lockRefused || lockOverlapped;
  }
}
