package com.httrack.android;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.util.List;

import android.content.Context;
import android.text.TextUtils;
import android.util.Log;

import com.httrack.android.jni.HTTrackCallbacks;
import com.httrack.android.jni.HTTrackLib;
import com.httrack.android.jni.HTTrackStats;

/**
 * One mirror, from the profile lock to the resume marker. It holds no view and no activity, so
 * whichever owner started it may go away while it runs; everything it shows the user goes through
 * its {@link Owner}.
 */
final class CrawlRun implements HTTrackCallbacks {
  /**
   * What the crawl needs from whoever started it. Every method may be called from the crawl
   * thread, and none of them may assume a window is on screen.
   */
  interface Owner {
    /** Refuse the run when the owner cannot supply the project, as a detached activity cannot. */
    void checkAttached() throws IOException;

    /** The mirror directory, or null when no project is named. */
    File target();

    /** The directory holding every project. */
    File projectRoot();

    /** The unpacked help and template resources, for the top index. */
    File resources();

    /** Create the project and its hts-cache/, and return the winprofile.ini to lock. */
    File createProfileDirectory() throws IOException;

    /** The engine options the option map emits. */
    List<String> options() throws IOException;

    /** Write the profile through the locked channel, which stays open. */
    void serializeProfile(FileChannel channel, File profile) throws IOException;

    /** A one-line status, shown while the crawl has no statistics yet. */
    void onProgress(String[] lines);

    /** A refresh to render; never called once a hard stop was asked for. */
    void onStats(HTTrackStats stats);

    /** The crawl is over, and this is what to show. */
    void onFinished(String message, long errorsCount, File mirrorFolder);
  }

  /** The messages the crawl itself produces, in the user's language. */
  static final class Messages {
    final String creatingProject;
    final String startingMirror;
    final String selfContainedConflict;
    final String alreadyInProgress;
    final String engineFaulted;
    final String mirrorFinished;

    Messages(final String creatingProject, final String startingMirror,
        final String selfContainedConflict, final String alreadyInProgress,
        final String engineFaulted, final String mirrorFinished) {
      this.creatingProject = creatingProject;
      this.startingMirror = startingMirror;
      this.selfContainedConflict = selfContainedConflict;
      this.alreadyInProgress = alreadyInProgress;
      this.engineFaulted = engineFaulted;
      this.mirrorFinished = mirrorFinished;
    }
  }

  private final HTTrackLib engine = new HTTrackLib(this);
  private final Owner owner;
  // Application context, captured once and never detached, so a crash after detach() still dumps.
  private final Context appContext;
  private volatile Messages messages;
  // Captured when the run starts, so its finish path needs no activity.
  private volatile File runTarget;
  private volatile File runProjectRoot;
  private volatile File runResources;
  private boolean mirrorRefresh;
  private HTTrackStats lastStats;
  // Set as soon as the run knows what it left behind, so a late stop cannot overwrite it.
  private volatile boolean verdictRecorded;
  private volatile MirrorSession.State state = MirrorSession.State.NONE;
  private volatile boolean interrupted;
  private volatile boolean interruptedHard;

  CrawlRun(final Context appContext, final Owner owner, final Messages messages) {
    this.appContext = appContext;
    this.owner = owner;
    this.messages = messages;
  }

  /** Re-read the messages, so a run started in one language answers in the current one. */
  void setMessages(final Messages messages) {
    this.messages = messages;
  }

  /** Advance the state, and keep the session slot in step. */
  private void advance(final MirrorSession.Event event) {
    state = MirrorSession.next(state, event);
  }

  MirrorSession.State state() {
    return state;
  }

  /** Has the mirror stopped? */
  boolean isEnded() {
    return state == MirrorSession.State.ENDED;
  }

  /** Has the mirror been interrupted? */
  boolean isInterrupted() {
    return interrupted;
  }

  /**
   * Run the mirror to its end. Absorbs its own failures, reporting each one as the message the
   * finished pane shows.
   */
  void runMirror() {
    // Rock'in!
    String message = null;
    // Null unless the mirror completed, leaving the finish message unlinked.
    File mirrorFolder = null;
    RandomAccessFile outLock = null;
    FileLock lock = null;
    File profile = null;
    // Only the run that registered the profile may deregister it, or a refused second run
    // would release the live one's claim.
    boolean profileMarked = false;
    // Did the engine get as far as running, and if so did it leave anything to resume?
    boolean engineRan = false;
    boolean pendingWork = true;
    final MirrorSession session = MirrorSession.get();
    session.begin(this);
    advance(MirrorSession.Event.START);
    try {
      // Sanity checks
      owner.checkAttached();
      // A recovered fault left the engine mid-operation; only a new process clears it.
      if (HTTrackLib.hasFaulted()) {
        throw new IOException(messages.engineFaulted);
      }
      final File target = owner.target();
      if (target == null) {
        throw new IOException("no project name defined!");
      }
      runTarget = target;
      runProjectRoot = owner.projectRoot();
      runResources = owner.resources();

      // Progress info for slow phones
      owner.onProgress(new String[] { messages.creatingProject });

      // Validate path
      if (!HTTrackActivity.mkdirs(target)) {
        throw new IOException("Unable to create " + target.getAbsolutePath());
      }
      HTTrackActivity.setFileReadWrite(target);

      // Inter-thread locking
      profile = owner.createProfileDirectory();
      profileMarked = session.claim(profile);

      // "rw" creates winprofile.ini without emptying it, so a refused lock
      // still leaves the settings behind.
      outLock = new RandomAccessFile(profile, "rw");
      boolean lockOverlapped = false;
      try {
        lock = outLock.getChannel().tryLock();
      } catch (final OverlappingFileLockException overlap) {
        // A lock this JVM already holds is thrown, not returned as null.
        lockOverlapped = true;
      }
      if (ProfileLockPolicy.alreadyInProgress(!profileMarked, lock == null,
          lockOverlapped)) {
        throw new IOException(messages.alreadyInProgress);
      }

      // Get args from mapper
      final List<String> options = owner.options();
      if (CommandlineTokens.hasSelfContainedConflict(options)) {
        throw new IOException(messages.selfContainedConflict);
      }

      // Final args array
      final String[] cargs = CrawlArgv.build(HTTrackActivity.isIPv6Enabled(),
          target.getAbsolutePath(), options);
      Log.v(getClass().getSimpleName(),
          "starting engine: " + HTTrackActivity.printArray(cargs));

      // Serialize settings
      owner.serializeProfile(outLock.getChannel(), profile);

      // Progress info for slow phones
      owner.onProgress(new String[] { messages.startingMirror });

      // Run engine
      engineRan = true;
      advance(MirrorSession.Event.ENGINE_STARTED);
      final int code = engine.main(cargs);
      // One reading of the engine's verdict, so the pane and the resume offer cannot disagree.
      final MirrorOutcome.Stop stop = interrupted ? MirrorOutcome.Stop.USER
          : engine.wasStopped() ? MirrorOutcome.Stop.ENGINE : MirrorOutcome.Stop.NONE;
      pendingWork = HTTrackActivity.leavesPendingWork(stop != MirrorOutcome.Stop.NONE, code);
      verdictRecorded = true;

      final MirrorOutcome.Verdict verdict = MirrorOutcome.decide(code, stop,
          engine.abortCode(), lastStats);
      message = verdict.text();
      if (verdict.showsFolderLink()) {
        mirrorFolder = target;
        message += "<br /><br />Mirror copied in <i><a href=\""
            + HTTrackActivity.MIRROR_FOLDER_HREF + "\">"
            + TextUtils.htmlEncode(target.getAbsolutePath()) + "</a></i>";
      }

      // Build top index
      buildTopIndex();
    } catch (final IOException io) {
      // Carries user-supplied paths, and the panel renders it as HTML.
      final String detail = io.getMessage();
      message = TextUtils.htmlEncode(
          detail != null ? detail : HTTrackActivity.describeCrash(io));
    } catch (final Throwable t) {
      // A native fault recovered by coffeecatch lands here as java.lang.Error.
      Log.e(getClass().getSimpleName(), "crawl aborted", t);
      HTTrackActivity.emergencyDump(appContext, t);
      message = "<b>Error</b>: "
          + TextUtils.htmlEncode(HTTrackActivity.describeCrash(t));
      // Anything else here is an ordinary Java failure, which leaves the engine usable.
      if (HTTrackLib.hasFaulted()) {
        message += "<br /><br />" + TextUtils.htmlEncode(messages.engineFaulted);
      }
    } finally {
      // Release inter-thread lock
      if (profileMarked) {
        session.release(profile);
      }
      // Release lock
      if (lock != null) {
        try {
          lock.release();
        } catch (IOException io) {
        }
      }
      // Closed whatever the lock did, or a refused run leaks the handle it opened.
      if (outLock != null) {
        try {
          outLock.close();
        } catch (IOException io) {
        }
      }
      // Stamp what the run actually was; a project the engine never touched keeps its marker.
      if (engineRan) {
        try {
          setInterruptedProfile(pendingWork);
        } catch (final IOException io) {
          Log.w(getClass().getSimpleName(), "could not update the resume marker", io);
        }
      }
      // Before the finished pane, whose own stopMirror() must not read as an interruption.
      end();
    }

    // Ensure we switch to the final pane
    final String displayMessage = messages.mirrorFinished + ": " + message;
    final long errorsCount = lastStats != null ? lastStats.errorsCount : 0;
    owner.onFinished(displayMessage, errorsCount, mirrorFolder);
  }

  /* Built rather than queued, since a queue leaves the mirror indexless until one attaches. */
  private void buildTopIndex() {
    HTTrackActivity.buildTopIndex(appContext, runProjectRoot, runResources);
  }

  /** Mark the crawl over and free the session slot; called again from the owner's own guard. */
  void end() {
    advance(MirrorSession.Event.END);
    MirrorSession.get().end(this);
  }

  /* Stamped against the run's own directory, so a detached end still records its verdict. */
  private void setInterruptedProfile(final boolean interrupted) throws IOException {
    final File target = runTarget != null ? runTarget : owner.target();
    if (target == null) {
      throw new IOException("no project directory for the resume marker");
    }
    HTTrackActivity.setInterruptedProfile(target, interrupted);
  }

  /**
   * Stop the mirror.
   *
   * @param force
   *          true to cut the transfers short rather than let them finish
   * @return true when the stop reached the engine
   */
  boolean stopMirror(final boolean force) {
    // Set interrupted flags
    interrupted = true;
    if (force) {
      interruptedHard = true;
    }
    advance(MirrorSession.Event.STOP);
    // Stop engine
    final boolean stopSent = engine.stop(force);
    // The finished pane asks for a stop too, long after the run decided the real outcome.
    if (ResumePolicy.stopWritesMarker(isEnded(), verdictRecorded)) {
      try {
        setInterruptedProfile(true);
      } catch (final IOException io) {
        Log.w(getClass().getSimpleName(), "could not write the resume marker", io);
      }
    }
    return stopSent;
  }

  @Override
  public void onRefresh(HTTrackStats stats) {
    // fake first refresh for cosmetic reasons.
    if (stats == null) {
      if (mirrorRefresh) {
        return;
      }
      mirrorRefresh = true;
      stats = new HTTrackStats();
    } else {
      synchronized (this) {
        lastStats = stats;
      }
    }

    // Do not refresh GUI if stopped
    if (interruptedHard) {
      return;
    }

    owner.onStats(stats);
  }

  /**
   * Get last statistics.
   *
   * @return last statistics (or @c null if none)
   */
  synchronized HTTrackStats getLastStats() {
    return lastStats;
  }
}
