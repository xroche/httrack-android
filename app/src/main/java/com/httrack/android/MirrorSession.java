package com.httrack.android;

import java.io.File;
import java.util.HashSet;

/**
 * The one crawl this process runs, and the profiles a run holds. Whoever starts a crawl puts it
 * in the slot, so a second starter finds it rather than taking the same project twice.
 */
final class MirrorSession {
  /** How far the crawl has got. */
  enum State {
    NONE, STARTING, RUNNING, STOPPING, ENDED
  }

  /** What just happened to the crawl. */
  enum Event {
    START, ENGINE_STARTED, STOP, END
  }

  /** What the session needs of the crawl in its slot. */
  interface Crawl {
    /** How far this crawl has got. */
    State state();
  }

  /**
   * Where a crawl in STATE stands once EVENT has happened.
   *
   * @param state
   *          the state before the event
   * @param event
   *          what happened
   * @return the state after it
   */
  static State next(final State state, final Event event) {
    if (state == State.ENDED) {
      return State.ENDED;
    }
    switch (event) {
    case START:
      return state == State.NONE ? State.STARTING : state;
    case ENGINE_STARTED:
      return state == State.STARTING ? State.RUNNING : state;
    case STOP:
      return State.STOPPING;
    default:
      return State.ENDED;
    }
  }

  private static final MirrorSession INSTANCE = new MirrorSession();

  static MirrorSession get() {
    return INSTANCE;
  }

  /** Profiles a run holds, keyed on the winprofile.ini path. */
  private final HashSet<String> claims = new HashSet<String>();

  /** The last crawl started, live or not; only the crawl itself clears it. */
  private Crawl run;

  private MirrorSession() {
  }

  /**
   * Take the profile for this run.
   *
   * @param profile
   *          the winprofile.ini of the project
   * @return false when another run holds it already
   */
  synchronized boolean claim(final File profile) {
    return claims.add(profile.getAbsolutePath());
  }

  /**
   * Give the profile back. Only the run that claimed it may, or a refused second run releases
   * the live one's claim.
   *
   * @param profile
   *          the winprofile.ini of the project
   */
  synchronized void release(final File profile) {
    claims.remove(profile.getAbsolutePath());
  }

  /**
   * Put this crawl in the slot.
   *
   * @param crawl
   *          the crawl that is starting
   */
  synchronized void begin(final Crawl crawl) {
    run = crawl;
  }

  /**
   * Empty the slot, unless a later crawl already took it.
   *
   * @param crawl
   *          the crawl that has ended
   */
  synchronized void end(final Crawl crawl) {
    if (run == crawl) {
      run = null;
    }
  }

  /**
   * The crawl an owner may attach to.
   *
   * @return the crawl in the slot while it has not ended, else null
   */
  synchronized Crawl live() {
    return run != null && run.state() != State.ENDED ? run : null;
  }
}
