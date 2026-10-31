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

  private static final Executor DIRECT = new Executor() {
    @Override
    public void execute(final Runnable command) {
      command.run();
    }
  };

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

  /** A directory whose listing the test controls: it counts the calls and can fix what they give. */
  private static final class CountedDir extends File {
    private static final long serialVersionUID = 1L;

    final AtomicInteger listings = new AtomicInteger();

    private final File[] contents;

    /** Lists the real directory, and counts. */
    CountedDir(final File dir) {
      this(dir, null);
    }

    /** Lists exactly these, in this order. */
    CountedDir(final File dir, final File[] contents) {
      super(dir.getPath());
      this.contents = contents;
    }

    @Override
    public File[] listFiles() {
      listings.incrementAndGet();
      return contents != null ? contents : super.listFiles();
    }
  }

  private static final class Recorder implements DirScanner.Sink {
    final List<DirScanner.Listing> listings = new ArrayList<DirScanner.Listing>();

    @Override
    public void onListing(final DirScanner.Listing listing) {
      listings.add(listing);
    }
  }

  /** Scans with no thread and no looper, and returns what the sink was handed. */
  private static DirScanner.Listing listed(final File dir, final File fallback) {
    final Recorder recorder = new Recorder();
    new DirScanner(DIRECT, DIRECT).scan(dir, fallback, recorder);
    return recorder.listings.get(0);
  }

  private static List<String> names(final List<File> files) {
    final List<String> names = new ArrayList<String>();
    for (final File file : files) {
      names.add(file.getName());
    }
    return names;
  }

  private static List<String> sortedNames(final List<File> files) {
    final List<String> names = names(files);
    Collections.sort(names);
    return names;
  }

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

    final DirScanner.Listing listing = listed(dir, tmp.getRoot());
    assertEquals(Arrays.asList("sub"), sortedNames(listing.subdirectories));
    assertEquals(dir.getParentFile().getAbsolutePath(), listing.parent.getAbsolutePath());
  }

  /** listFiles() answers in no particular order, and the chooser offers that order unchanged. */
  @Test
  public void keepsTheOrderTheFilesystemGave() throws Exception {
    final File dir = folder("base", "a", "b", "c");
    final File[] reversed = { new File(dir, "c"), new File(dir, "b"), new File(dir, "a") };

    final DirScanner.Listing listing = listed(new CountedDir(dir, reversed), tmp.getRoot());
    assertEquals(Arrays.asList("c", "b", "a"), names(listing.subdirectories));
  }

  /** Nothing above the directory means the root, both at "/" and for a directory that is gone. */
  @Test
  public void theParentEntryFallsBackToTheRoot() throws Exception {
    final File gone = tmp.newFolder("gone");
    assertTrue(gone.delete());

    assertEquals("/", listed(new File("/"), tmp.getRoot()).parent.getAbsolutePath());
    assertEquals("/", listed(gone, tmp.getRoot()).parent.getAbsolutePath());
  }

  @Test
  public void aDirectoryThatIsGoneIsListedFromTheFallback() throws Exception {
    final File fallback = folder("default", "websites");
    final File gone = tmp.newFolder("gone");
    assertTrue(gone.delete());

    assertEquals(Arrays.asList("websites"), sortedNames(listed(gone, fallback).subdirectories));
  }

  /** listFiles() answers null on anything but a directory, and that is not an empty listing. */
  @Test
  public void aPlainFileOffersNothing() throws Exception {
    final File file = tmp.newFile("note.txt");
    assertTrue(listed(file, tmp.getRoot()).subdirectories.isEmpty());
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

    assertEquals(Arrays.asList("late"), sortedNames(recorder.listings.get(0).subdirectories));
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
    assertEquals(Arrays.asList("in-second"), sortedNames(recorder.listings.get(0).subdirectories));
  }

  /** A scan still queued when the next tap arrives must not read the filesystem at all. */
  @Test
  public void aQueuedScanIsNeverRunOnceAnotherIsAsked() throws Exception {
    final Deferred worker = new Deferred();
    final Deferred ui = new Deferred();
    final Recorder recorder = new Recorder();
    final DirScanner scanner = new DirScanner(worker, ui);
    final CountedDir first = new CountedDir(folder("first", "in-first"));
    final File second = folder("second", "in-second");

    scanner.scan(first, first, recorder);
    scanner.scan(second, second, recorder);
    worker.runAll();
    ui.runAll();

    assertEquals(0, first.listings.get());
    assertEquals(Arrays.asList("in-second"), sortedNames(recorder.listings.get(0).subdirectories));
  }

  /** The activity owns no listing of its own, and never draws one into a destroyed window. */
  @Test
  public void theChooserOnlyDrawsWhatTheScannerHandsIt() throws Exception {
    final String source = TestSources.javaSource("FileChooserActivity");
    assertFalse("listing belongs to DirScanner, which runs off the UI thread",
        source.contains("listFiles(") || source.contains(".list()"));
    assertTrue("a listing can land after onDestroy, and the views are gone by then",
        TestSources.between(source, "private void showDir", "files.clear()")
            .contains("isDestroyed()"));
  }
}
