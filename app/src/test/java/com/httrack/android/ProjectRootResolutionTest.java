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

  @Before
  public void setUp() throws Exception {
    base = tmp.newFolder("chosen");
    fallback = tmp.newFolder("Android", "data", "com.httrack.android", "files", "Websites");
  }

  @Test
  public void adoptsAVettedBaseThatExists() {
    assertEquals(base, StoragePaths.resolveRoot(base, Boolean.TRUE, fallback));
  }

  @Test
  public void fallsBackWithNoBaseSet() {
    assertEquals(fallback, StoragePaths.resolveRoot(null, Boolean.TRUE, fallback));
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
   * Granting access moves the default; the resolved root must follow it rather than wait for the
   * next cold start.
   */
  @Test
  public void theAnswerFollowsTheAccessHeld() {
    assertEquals(base, StoragePaths.resolveRoot(base, Boolean.TRUE, fallback));
    assertEquals(fallback, StoragePaths.resolveRoot(base, Boolean.FALSE, fallback));
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

  /**
   * Guards what the helper cannot: computeStorageTarget used to keep the current root if it
   * existed, and every grant then waited for the next launch.
   */
  @Test
  public void computeStorageTargetDoesNotKeepTheRootInUse() throws Exception {
    final String body = computeStorageTargetBody();
    assertFalse("the fallback must not be conditioned on the root already in use",
        body.contains("projectPath.exists()"));
    // The vetting verdict has to reach the helper, not a constant standing in for it.
    assertTrue("the root must come from resolveRoot, passed the real verdict",
        body.contains("StoragePaths.resolveRoot(baseFile, writable,"));
  }

  /** A base that is merely unreachable now must survive, or a remount cannot bring it back. */
  @Test
  public void computeStorageTargetKeepsAnUnusableBasePath() throws Exception {
    final String body = computeStorageTargetBody();
    assertFalse("an unusable base path must not be erased",
        body.contains("remove(BASE_NAME)"));
    assertFalse("nor erased under the literal key",
        body.contains("remove(\"BasePath\")"));
  }

  /** initRootPath leaks a buffer per call, so a re-resolve that moved nothing must not repeat it. */
  @Test
  public void computeStorageTargetGatesTheNativeReinitOnAMove() throws Exception {
    final String body = computeStorageTargetBody();
    final int guard = body.indexOf("!previous.equals(projectPath)");
    final int init = body.indexOf("HTTrackLib.initRootPath(");
    assertTrue("the moved-root guard is gone", guard != -1);
    assertTrue("initRootPath must sit behind the moved-root guard", init > guard);
  }
}
