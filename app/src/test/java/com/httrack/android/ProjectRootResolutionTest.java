package com.httrack.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;

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
        StoragePaths.defaultRoot(null, external, internal));
  }

  /** With the volume unmounted there is no external dir, and internal always answers. */
  @Test
  public void defaultsToInternalWithoutAnExternalDirectory() {
    assertEquals(new File(internal, "Websites"), StoragePaths.defaultRoot(null, null, internal));
  }

  /** The grant is the only input that changed, and the root the app writes moves with it. */
  @Test
  public void grantingAllFilesAccessMovesTheRoot() {
    final File before = StoragePaths.defaultRoot(null, external, internal);
    final File after = StoragePaths.defaultRoot(shared, external, internal);
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

  /** The block HEAD opens, so a statement moved just past its closing brace stops matching. */
  private static String guardedBlock(final String body, final String head) {
    final int at = body.indexOf(head);
    assertTrue(head + " not found", at != -1);
    final int open = body.indexOf('{', at);
    assertTrue("no block after " + head, open != -1);
    int depth = 0;
    for (int i = open; i < body.length(); i++) {
      final char c = body.charAt(i);
      if (c == '{') {
        depth++;
      } else if (c == '}' && --depth == 0) {
        return body.substring(open, i);
      }
    }
    throw new AssertionError("unterminated block after " + head);
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
  public void computeStorageTargetNeverWritesTheSettings() throws Exception {
    assertFalse("resolving the root must not edit the stored BasePath, however spelled",
        computeStorageTargetBody().contains(".edit()"));
  }

  /** initRootPath leaks a buffer per call, so a re-resolve that moved nothing must not repeat it. */
  @Test
  public void computeStorageTargetGatesTheNativeReinitOnAMove() throws Exception {
    final String guarded =
        guardedBlock(computeStorageTargetBody(), "if (StoragePaths.rootMoved(");
    assertTrue("initRootPath must sit inside the moved-root block",
        guarded.contains("HTTrackLib.initRootPath("));
  }
}
