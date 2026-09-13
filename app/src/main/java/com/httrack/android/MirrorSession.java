package com.httrack.android;

import java.io.File;
import java.util.HashSet;

import com.httrack.android.jni.HTTrackStats;

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

    /**
     * Stop this crawl.
     *
     * @param force
     *          true to cut the transfers short rather than let them finish
     * @return true when the stop reached the engine
     */
    boolean stopMirror(boolean force);
  }

  /**
   * What an attached window is told, from whichever thread the crawl runs on. Never called while
   * the session lock is held, so a listener may take it.
   */
  interface Listener {
    /** The slot now holds a live crawl, which some of a window's own state is decided on. */
    void onCrawlLive();

    /** A one-line status, shown while the crawl has no statistics yet. */
    void onProgressLines(String[] lines);

    /** A refresh to render. */
    void onStats(HTTrackStats stats);

    /** The crawl is over, and this is what to show. */
    void onFinished(Verdict verdict);
  }

  /** What a crawl left behind, held until a window shows it. */
  static final class Verdict {
    final String message;
    final long errorsCount;
    final File mirrorFolder;

    Verdict(final String message, final long errorsCount, final File mirrorFolder) {
      this.message = message;
      this.errorsCount = errorsCount;
      this.mirrorFolder = mirrorFolder;
    }
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

  /** At most one, because only the window on screen can draw anything. */
  private Listener listener;

  private HTTrackStats lastStats;

  /** Non-null only while a verdict is waiting for a window to show it. */
  private Verdict verdict;

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
  void begin(final Crawl crawl) {
    final Listener window;
    synchronized (this) {
      run = crawl;
      lastStats = null;
      verdict = null;
      window = listener;
    }
    if (window != null) {
      window.onCrawlLive();
    }
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
   * The crawl an owner may attach to, or stop.
   *
   * @return the crawl in the slot while it has not ended, else null
   */
  synchronized Crawl live() {
    return run != null && run.state() != State.ENDED ? run : null;
  }

  /**
   * Take the single listener slot, replacing whoever held it.
   *
   * @param window
   *          the window that will draw what the crawl reports
   */
  synchronized void listen(final Listener window) {
    listener = window;
  }

  /**
   * Give the slot back, unless a later window already took it.
   *
   * @param window
   *          the window that is going away
   */
  synchronized void unlisten(final Listener window) {
    if (listener == window) {
      listener = null;
    }
  }

  /**
   * The newest refresh this crawl published.
   *
   * @return the statistics, or null before the first refresh
   */
  synchronized HTTrackStats lastStats() {
    return lastStats;
  }

  /**
   * The verdict no pane has drawn yet, left where it is for whoever draws it.
   *
   * @return the verdict, or null when none is held
   */
  synchronized Verdict heldVerdict() {
    return verdict;
  }

  /**
   * Drop the held verdict, once a pane has drawn it, so a second attach does not show it again.
   *
   * @return the verdict, or null when none is held
   */
  synchronized Verdict takeVerdict() {
    final Verdict held = verdict;
    verdict = null;
    return held;
  }

  /**
   * Report a one-line status.
   *
   * @param lines
   *          the status lines
   */
  void publishProgress(final String[] lines) {
    final Listener window;
    synchronized (this) {
      window = listener;
    }
    if (HandoverPolicy.delivers(window != null, false) == HandoverPolicy.Delivery.ACTIVITY) {
      window.onProgressLines(lines);
    }
  }

  /**
   * Report a refresh, which is kept whether or not a window is there to draw it.
   *
   * @param stats
   *          the engine's statistics
   */
  void publishStats(final HTTrackStats stats) {
    final Listener window;
    synchronized (this) {
      lastStats = stats;
      window = listener;
    }
    if (HandoverPolicy.delivers(window != null, false) == HandoverPolicy.Delivery.ACTIVITY) {
      window.onStats(stats);
    }
  }

  /**
   * Report what the crawl left behind. The verdict is held whoever takes it, because a window
   * draws its pane from a posted message and may die before that message runs.
   *
   * @param reached
   *          the verdict
   * @return ACTIVITY when a window took it, HELD when the caller must tell the user itself
   */
  HandoverPolicy.Delivery publishVerdict(final Verdict reached) {
    final HandoverPolicy.Delivery delivery;
    final Listener window;
    synchronized (this) {
      window = listener;
      delivery = HandoverPolicy.delivers(window != null, true);
      verdict = reached;
    }
    if (delivery == HandoverPolicy.Delivery.ACTIVITY) {
      window.onFinished(reached);
    }
    return delivery;
  }
}
