package com.httrack.android;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** Exercises CleanupActivity.deleteRecursively: full delete, null-listFiles safety, depth cap. */
public class CleanupActivityTest {
  @Rule
  public final TemporaryFolder tmp = new TemporaryFolder();

  @Test
  public void deletesANestedTreeWhole() throws Exception {
    final File root = tmp.newFolder("proj");
    final File deep = new File(new File(root, "a"), "b");
    assertTrue(deep.mkdirs());
    assertTrue(new File(deep, "page.html").createNewFile());
    assertTrue(new File(root, "top.html").createNewFile());

    assertTrue(CleanupActivity.deleteRecursively(root));
    assertFalse(root.exists());
  }

  /** A directory populated and then wiped: delete() fails first, then succeeds once emptied. */
  @Test
  public void removesADirectoryThatWasNotInitiallyEmpty() throws Exception {
    final File dir = tmp.newFolder("dir");
    assertTrue(new File(dir, "child.txt").createNewFile());

    assertTrue(CleanupActivity.deleteRecursively(dir));
    assertFalse(dir.exists());
  }

  @Test
  public void deletesASingleFile() throws Exception {
    final File file = tmp.newFile("lone.txt");
    assertTrue(CleanupActivity.deleteRecursively(file));
    assertFalse(file.exists());
  }

  /** A tree deeper than the cap is refused rather than blowing the stack; the low levels survive. */
  @Test
  public void refusesATreeDeeperThanTheDepthCap() throws Exception {
    File cursor = tmp.newFolder("deep");
    final File root = cursor;
    for (int i = 0; i <= CleanupActivity.MAX_DEPTH; i++) {
      cursor = new File(cursor, "d");
      assertTrue(cursor.mkdir());
    }

    assertFalse(CleanupActivity.deleteRecursively(root));
    assertTrue(root.exists());
  }

  /** The bounded worker stops at its limit: with limit 1 it cannot reach a file two levels down. */
  @Test
  public void boundedWorkerRefusesBeyondItsLimit() throws Exception {
    final File root = tmp.newFolder("bounded");
    final File sub = new File(root, "sub");
    assertTrue(sub.mkdir());
    assertTrue(new File(sub, "leaf.txt").createNewFile());

    assertFalse(CleanupActivity.deleteRecursively(root, 1));
    assertTrue(root.exists());
  }
}
