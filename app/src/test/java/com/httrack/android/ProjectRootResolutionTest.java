package com.httrack.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** Which root mirrors go to, and that gaining or losing access re-points it (issue #128). */
public class ProjectRootResolutionTest {
  @Rule
  public final TemporaryFolder tmp = new TemporaryFolder();

  private File base;
  private File fallback;
  private File external;
  private File internal;
  private File shared;

  @Before
  public void setUp() throws Exception {
    base = tmp.newFolder("chosen");
    external = tmp.newFolder("ext", "Android", "data", "com.httrack.android", "files");
    internal = tmp.newFolder("int", "data", "com.httrack.android", "files");
    shared = tmp.newFolder("emulated"); // stands in for /storage/emulated/0
    fallback = new File(external, "Websites");
  }

  @Test
  public void adoptsAVettedBaseThatExists() {
    assertEquals(base, StoragePaths.resolveRoot(base, Boolean.TRUE, fallback));
  }

  /** No base set means no verdict to pass either. */
  @Test
  public void fallsBackWithNoBaseSet() {
    assertEquals(fallback, StoragePaths.resolveRoot(null, null, fallback));
  }

  /** Only a decided yes: an unvettable base must not be honoured either. */
  @Test
  public void fallsBackWhenTheBaseIsRefusedOrUndecided() {
    assertEquals(fallback, StoragePaths.resolveRoot(base, Boolean.FALSE, fallback));
    assertEquals(fallback, StoragePaths.resolveRoot(base, null, fallback));
  }

  @Test
  public void fallsBackWhenTheBaseIsAbsentOrAFile() throws Exception {
    final File absent = new File(tmp.getRoot(), "never-created");
    assertEquals(fallback, StoragePaths.resolveRoot(absent, Boolean.TRUE, fallback));
    assertEquals(fallback, StoragePaths.resolveRoot(tmp.newFile("plain"), Boolean.TRUE, fallback));
  }

  /**
   * Losing access re-points the running session rather than waiting for the next cold start.
   */
  @Test
  public void theAnswerFollowsTheAccessHeld() {
    assertEquals(base, StoragePaths.resolveRoot(base, Boolean.TRUE, fallback));
    assertEquals(fallback, StoragePaths.resolveRoot(base, Boolean.FALSE, fallback));
  }

  @Test
  public void defaultsToOurOwnExternalDirectory() {
    assertEquals(new File(external, "Websites"),
        StoragePaths.defaultRoot(external, internal, null));
  }

  /** With the volume unmounted there is no external dir, and internal always answers. */
  @Test
  public void defaultsToInternalWithoutAnExternalDirectory() {
    assertEquals(new File(internal, "Websites"), StoragePaths.defaultRoot(null, internal, null));
  }

  /** The grant is the only input that changed, and the root the app writes moves with it. */
  @Test
  public void grantingAllFilesAccessMovesTheRoot() {
    final File before = StoragePaths.defaultRoot(external, internal, null);
    final File after = StoragePaths.defaultRoot(external, internal, shared);
    assertEquals(new File(new File(shared, "HTTrack"), "Websites"), after);
    assertTrue(StoragePaths.rootMoved(before, after));
    assertTrue(StoragePaths.rootMoved(after, before));
  }

  /** Nothing was resolved before, so the once-per-move work must run. */
  @Test
  public void theFirstResolutionMoves() {
    assertTrue(StoragePaths.rootMoved(null, fallback));
  }

  /** Resume after resume the answer is the same, and the once-per-move work must not repeat. */
  @Test
  public void anUnchangedRootDoesNotMoveAcrossResumes() {
    File previous = null;
    for (int resume = 0; resume < 3; resume++) {
      final File resolved = StoragePaths.resolveRoot(base, Boolean.TRUE, fallback);
      assertEquals("resume " + resume, resume == 0, StoragePaths.rootMoved(previous, resolved));
      previous = resolved;
    }
    // Same path, freshly built: it is the path that must match, not the instance.
    assertFalse(StoragePaths.rootMoved(new File(base.getPath()), base));
  }

  @Test
  public void aDifferentRootMoves() {
    assertTrue(StoragePaths.rootMoved(fallback, base));
  }

  /** computeStorageTarget, up to the next method. */
  private static String computeStorageTargetBody() throws IOException {
    final String source = TestSources.javaSource("HTTrackActivity");
    final int from = source.indexOf("private void computeStorageTarget()");
    final int to = source.indexOf("private void setBasePath(");
    assertTrue("computeStorageTarget not found", from != -1);
    assertTrue("setBasePath not found, the slice is wrong", to > from);
    return source.substring(from, to);
  }

  /** Stands in for the activity, so the gate is judged on what it called and with what. */
  private static final class Recorder implements StoragePaths.RootMoveActions {
    final List<String> calls = new ArrayList<String>();

    @Override
    public void warnMissingDirectory() {
      calls.add("warn");
    }

    @Override
    public void initNativeRoot(final File root) {
      calls.add("init " + root);
    }

    @Override
    public void refreshProjectSuggestions() {
      calls.add("refresh");
    }

    @Override
    public void noteMovedFrom(final File previous) {
      calls.add("from " + previous);
    }
  }

  /**
   * Guards what the helper cannot: computeStorageTarget used to keep the current root if it
   * existed, and every grant then waited for the next launch.
   */
  @Test
  public void computeStorageTargetDoesNotKeepTheRootInUse() throws Exception {
    assertFalse("the fallback must not be conditioned on the root already in use",
        computeStorageTargetBody().contains("projectPath.exists()"));
  }

  /** A base that is merely unreachable now must survive, or a remount cannot bring it back. */
  @Test
  public void computeStorageTargetNeverWritesTheStoredBasePath() throws Exception {
    final String body = computeStorageTargetBody();
    assertFalse("resolving the root must not rewrite the stored BasePath, however spelled",
        body.contains("putString(BASE_NAME") || body.contains("remove(BASE_NAME"));
    // Counted, so a second edit has to come back through this test.
    assertEquals("only the previous-root hint may be persisted while resolving", 1,
        body.split("\\.edit\\(\\)", -1).length - 1);
    assertTrue(body.contains("PREVIOUS_BASE_NAME"));
  }

  /** Wiring, not behaviour: the recorder tests below cannot see the call site at all. */
  @Test
  public void computeStorageTargetIsStillWiredToApplyRootMove() throws Exception {
    assertTrue("computeStorageTarget must hand the move to StoragePaths",
        computeStorageTargetBody().contains("StoragePaths.applyRootMove("));
  }

  /** A move re-points the native side at the root just resolved, and relists what is under it. */
  @Test
  public void aMoveReinitsAtTheNewRoot() {
    final Recorder rec = new Recorder();
    assertTrue(StoragePaths.applyRootMove(fallback, base, false, rec));
    assertEquals(Arrays.asList("init " + base, "refresh", "from " + fallback), rec.calls);
  }

  /** initRootPath leaks a buffer per call, so a re-resolve that moved nothing must do nothing. */
  @Test
  public void anUnchangedRootDoesNothing() {
    final Recorder rec = new Recorder();
    assertFalse(StoragePaths.applyRootMove(base, new File(base.getPath()), false, rec));
    assertEquals(Collections.emptyList(), rec.calls);
  }

  /** Nothing was listed before the first resolution, so there are no stale suggestions to drop. */
  @Test
  public void theFirstResolutionSkipsTheSuggestionRefresh() {
    final Recorder rec = new Recorder();
    assertTrue(StoragePaths.applyRootMove(null, fallback, false, rec));
    assertEquals(Arrays.asList("init " + fallback), rec.calls);
  }

  /** The warning rides the move, or it would toast again on every resume. */
  @Test
  public void theMissingDirectoryWarningRidesTheMove() {
    final Recorder moved = new Recorder();
    assertTrue(StoragePaths.applyRootMove(fallback, base, true, moved));
    assertEquals(Arrays.asList("warn", "init " + base, "refresh", "from " + fallback), moved.calls);

    final Recorder stayed = new Recorder();
    assertFalse(StoragePaths.applyRootMove(base, base, true, stayed));
    assertEquals(Collections.emptyList(), stayed.calls);
  }

  /**
   * Granting all-files access re-points the root and migrates nothing, so the move must name the
   * root it left; a resume that moved nothing must stay silent, or the offer would toast forever.
   */
  @Test
  public void aMoveNamesTheRootItLeft() {
    final Recorder moved = new Recorder();
    assertTrue(StoragePaths.applyRootMove(fallback, base, false, moved));
    assertTrue(moved.calls.contains("from " + fallback));

    final Recorder first = new Recorder();
    assertTrue(StoragePaths.applyRootMove(null, fallback, false, first));
    assertFalse("there is no earlier root on the first resolution",
        first.calls.contains("from null"));
  }
}
