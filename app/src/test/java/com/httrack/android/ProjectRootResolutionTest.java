package com.httrack.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** Which root mirrors go to, and that gaining or losing access re-points it (issue #128). */
public class ProjectRootResolutionTest {
  @Rule
  public final TemporaryFolder tmp = new TemporaryFolder();

  private File base() throws Exception {
    return tmp.newFolder("chosen");
  }

  private File fallback() {
    return new File(tmp.getRoot(), "Android/data/com.httrack.android/files/Websites");
  }

  @Test
  public void adoptsAVettedBase() throws Exception {
    final File base = base();
    assertEquals(base, StoragePaths.resolveRoot(base, Boolean.TRUE, true, fallback()));
  }

  @Test
  public void fallsBackWithNoBaseSet() {
    assertEquals(fallback(), StoragePaths.resolveRoot(null, null, false, fallback()));
  }

  /** Only a decided yes: an unvettable base must not be honoured either. */
  @Test
  public void fallsBackWhenTheBaseIsRefusedOrUndecided() throws Exception {
    final File base = base();
    assertEquals(fallback(), StoragePaths.resolveRoot(base, Boolean.FALSE, true, fallback()));
    assertEquals(fallback(), StoragePaths.resolveRoot(base, null, true, fallback()));
  }

  @Test
  public void fallsBackWhenTheBaseIsNotADirectory() throws Exception {
    assertEquals(fallback(), StoragePaths.resolveRoot(base(), Boolean.TRUE, false, fallback()));
  }

  /**
   * The defect: granting all-files access moves the default, and the answer has to move with it
   * rather than stay on whatever root is already in use until the next cold start.
   */
  @Test
  public void followsTheDefaultWhenItMoves() {
    final File privateRoot = new File(tmp.getRoot(), "Android/data/com.httrack.android/files/Websites");
    final File publicRoot = new File(tmp.getRoot(), "HTTrack/Websites");
    assertEquals(privateRoot, StoragePaths.resolveRoot(null, null, false, privateRoot));
    assertEquals(publicRoot, StoragePaths.resolveRoot(null, null, false, publicRoot));
  }

  /**
   * Guards the wiring the helper cannot: computeStorageTarget used to keep the current root
   * whenever it still existed, which is what deferred every grant to the next launch.
   */
  @Test
  public void computeStorageTargetDoesNotKeepTheRootInUse() throws Exception {
    final String source = TestSources.javaSource("HTTrackActivity");
    final int from = source.indexOf("private void computeStorageTarget()");
    final int to = source.indexOf("private void setBasePath(");
    assertTrue("computeStorageTarget not found", from != -1);
    assertTrue("setBasePath not found, the slice is wrong", to > from);
    final String body = source.substring(from, to);
    assertTrue("the root must come from StoragePaths.resolveRoot",
        body.contains("StoragePaths.resolveRoot("));
    assertFalse("the fallback must not be conditioned on the root already in use",
        body.contains("projectPath.exists()"));
  }

  /** A base that is merely unreachable now must survive, or a remount cannot bring it back. */
  @Test
  public void computeStorageTargetKeepsAnUnusableBasePath() throws Exception {
    final String source = TestSources.javaSource("HTTrackActivity");
    final int from = source.indexOf("private void computeStorageTarget()");
    final int to = source.indexOf("private void setBasePath(");
    final String body = source.substring(from, to);
    assertFalse("an unusable base path must not be erased",
        body.contains("remove(BASE_NAME)"));
  }
}
