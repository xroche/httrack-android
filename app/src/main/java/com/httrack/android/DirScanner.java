package com.httrack.android;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Lists directories for the file chooser away from the UI thread. Listing one and stating each of
 * its entries blows past the ANR budget on a large or slow directory, and the newest scan is the
 * only one worth drawing, because a slower one started earlier would otherwise draw over it.
 */
class DirScanner {
  /** What one scan found. */
  static final class Listing {
    /** Where the "up one level" entry leads, the filesystem root when there is nothing above. */
    final File parent;

    /** The visible sub-directories, in the order the filesystem listed them. */
    final List<File> subdirectories;

    Listing(final File parent, final List<File> subdirectories) {
      this.parent = parent;
      this.subdirectories = subdirectories;
    }
  }

  /** Takes the listing of the newest scan, on the UI executor. */
  interface Sink {
    void onListing(Listing listing);
  }

  private final Executor worker;
  private final Executor ui;

  /** Counts the scans asked for, so a scan can tell whether it is still the newest. */
  private final AtomicInteger asked = new AtomicInteger();

  DirScanner(final Executor worker, final Executor ui) {
    this.worker = worker;
    this.ui = ui;
  }

  /**
   * Scan a directory and hand the result to a sink.
   *
   * @param dir the directory to list, as the chooser currently has it
   * @param fallback listed instead when dir is gone
   * @param sink called on the UI executor, and never for a scan a later one has superseded
   */
  void scan(final File dir, final File fallback, final Sink sink) {
    final int ticket = asked.incrementAndGet();
    worker.execute(new Runnable() {
      @Override
      public void run() {
        if (ticket != asked.get()) {
          return;
        }
        final Listing listing = list(dir, fallback);
        ui.execute(new Runnable() {
          @Override
          public void run() {
            if (ticket == asked.get()) {
              sink.onListing(listing);
            }
          }
        });
      }
    });
  }

  /**
   * Read the parent and the visible sub-directories of a directory.
   *
   * @param dir the directory to list, which may be null or gone
   * @param fallback listed instead when dir is gone, which leaves the parent at the root
   * @return what the chooser must show
   */
  Listing list(final File dir, final File fallback) {
    final boolean usable = dir != null && dir.exists();
    return new Listing(parentOf(usable ? dir : null), subdirectoriesOf(usable ? dir : fallback));
  }

  /** Returns the directory above dir, or the filesystem root when there is nothing above it. */
  private static File parentOf(final File dir) {
    final File parent = dir != null ? dir.getParentFile() : null;
    return parent != null ? parent : new File("/");
  }

  /** Returns the sub-directories of dir the chooser offers, which excludes the hidden ones. */
  private static List<File> subdirectoriesOf(final File dir) {
    final List<File> dirs = new ArrayList<File>();
    final File[] list = dir != null ? dir.listFiles() : null;
    if (list != null) {
      for (final File file : list) {
        if (file.isDirectory() && !file.isHidden()) {
          dirs.add(file);
        }
      }
    }
    return dirs;
  }
}
