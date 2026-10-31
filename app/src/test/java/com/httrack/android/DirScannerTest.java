package com.httrack.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** Exercises what the file chooser offers, and the rule that only the newest scan is drawn. */
public class DirScannerTest {
  @Rule
  public final TemporaryFolder tmp = new TemporaryFolder();

  /** Queues what it is handed, standing in for the worker thread and for the looper. */
  private static final class Deferred implements Executor {
    private final List<Runnable> queued = new ArrayList<Runnable>();

    @Override
    public void execute(final Runnable command) {
      queued.add(command);
    }

    void runAll() {
      final List<Runnable> batch = new ArrayList<Runnable>(queued);
      queued.clear();
      for (final Runnable command : batch) {
        command.run();
      }
    }
  }

  /** Counts the scans that really list, which a superseded one must not do. */
  private static final class CountingScanner extends DirScanner {
    final AtomicInteger scans = new AtomicInteger();

    CountingScanner(final Executor worker, final Executor ui) {
      super(worker, ui);
    }

    @Override
    Listing list(final File dir, final File fallback) {
      scans.incrementAndGet();
      return super.list(dir, fallback);
    }
  }

  private static final class Recorder implements DirScanner.Sink {
    final List<DirScanner.Listing> listings = new ArrayList<DirScanner.Listing>();

    @Override
    public void onListing(final DirScanner.Listing listing) {
      listings.add(listing);
    }
  }

  private static List<String> names(final List<File> files) {
    final List<String> names = new ArrayList<String>();
    for (final File file : files) {
      names.add(file.getName());
    }
    Collections.sort(names);
    return names;
  }

  private static final Executor DIRECT = new Executor() {
    @Override
    public void execute(final Runnable command) {
      command.run();
    }
  };

  private File folder(final String name, final String... children) throws Exception {
    final File dir = tmp.newFolder(name);
    for (final String child : children) {
      assertTrue(new File(dir, child).mkdir());
    }
    return dir;
  }

  @Test
  public void offersTheVisibleSubdirectoriesOnly() throws Exception {
    final File dir = folder("base", "sub", ".hidden");
    assertTrue(new File(dir, "page.html").createNewFile());

    final DirScanner.Listing listing = new DirScanner(DIRECT, DIRECT).list(dir, tmp.getRoot());
    assertEquals(Arrays.asList("sub"), names(listing.subdirectories));
    assertEquals(dir.getParentFile().getAbsolutePath(), listing.parent.getAbsolutePath());
  }

  @Test
  public void theRootLeadsBackToItself() throws Exception {
    final DirScanner.Listing listing = new DirScanner(DIRECT, DIRECT)
        .list(new File("/"), tmp.getRoot());
    assertEquals("/", listing.parent.getAbsolutePath());
  }

  @Test
  public void aDirectoryThatIsGoneFallsBackToTheDefault() throws Exception {
    final File fallback = folder("default", "websites");
    final File gone = tmp.newFolder("gone");
    assertTrue(gone.delete());

    final DirScanner.Listing listing = new DirScanner(DIRECT, DIRECT).list(gone, fallback);
    assertEquals(Arrays.asList("websites"), names(listing.subdirectories));
    // Not the fallback's parent: a path that no longer exists says nothing about where it sat.
    assertEquals("/", listing.parent.getAbsolutePath());
  }

  /** listFiles() answers null on anything but a directory, and that is not an empty listing. */
  @Test
  public void aPlainFileOffersNothing() throws Exception {
    final File file = tmp.newFile("note.txt");
    final DirScanner.Listing listing = new DirScanner(DIRECT, DIRECT).list(file, tmp.getRoot());
    assertTrue(listing.subdirectories.isEmpty());
  }

  /** The listing reads a directory created after scan() returned, so it cannot have run inline. */
  @Test
  public void scanReadsTheDirectoryAfterItReturns() throws Exception {
    final Deferred worker = new Deferred();
    final Deferred ui = new Deferred();
    final Recorder recorder = new Recorder();
    final File dir = folder("base");

    new DirScanner(worker, ui).scan(dir, dir, recorder);
    assertTrue(recorder.listings.isEmpty());
    assertTrue(new File(dir, "late").mkdir());
    worker.runAll();
    ui.runAll();

    assertEquals(Arrays.asList("late"), names(recorder.listings.get(0).subdirectories));
  }

  /** A listing finished before the next tap must not draw over the directory that tap opened. */
  @Test
  public void aFinishedScanIsDroppedOnceAnotherIsAsked() throws Exception {
    final Deferred worker = new Deferred();
    final Deferred ui = new Deferred();
    final Recorder recorder = new Recorder();
    final DirScanner scanner = new DirScanner(worker, ui);
    final File first = folder("first", "in-first");
    final File second = folder("second", "in-second");

    scanner.scan(first, first, recorder);
    worker.runAll();
    scanner.scan(second, second, recorder);
    worker.runAll();
    ui.runAll();

    assertEquals(1, recorder.listings.size());
    assertEquals(Arrays.asList("in-second"), names(recorder.listings.get(0).subdirectories));
  }

  /** A scan still queued when the next tap arrives must not list at all. */
  @Test
  public void aQueuedScanIsNeverRunOnceAnotherIsAsked() throws Exception {
    final Deferred worker = new Deferred();
    final Deferred ui = new Deferred();
    final Recorder recorder = new Recorder();
    final CountingScanner scanner = new CountingScanner(worker, ui);
    final File first = folder("first", "in-first");
    final File second = folder("second", "in-second");

    scanner.scan(first, first, recorder);
    scanner.scan(second, second, recorder);
    worker.runAll();
    ui.runAll();

    assertEquals(1, scanner.scans.get());
    assertEquals(Arrays.asList("in-second"), names(recorder.listings.get(0).subdirectories));
  }

  @Test
  public void theChooserNeverListsOnItsOwn() throws Exception {
    assertFalse("listing belongs to DirScanner, which runs off the UI thread",
        TestSources.javaSource("FileChooserActivity").contains("listFiles("));
  }
}
