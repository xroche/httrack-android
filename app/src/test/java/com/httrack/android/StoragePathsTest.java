package com.httrack.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.file.Files;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** Attacks StoragePaths.isWritable: what it must accept, what it must refuse, what it cannot tell. */
public class StoragePathsTest {
  @Rule
  public final TemporaryFolder tmp = new TemporaryFolder();

  private File external;
  private File internal;

  @Before
  public void setUp() throws Exception {
    external = tmp.newFolder("ext", "Android", "data", "com.httrack.android", "files");
    internal = tmp.newFolder("int", "data", "com.httrack.android", "files");
  }

  @Test
  public void acceptsOurOwnRoots() {
    assertEquals(Boolean.TRUE, StoragePaths.isWritable(new File(external, "Websites"), external, internal));
    assertEquals(Boolean.TRUE, StoragePaths.isWritable(new File(internal, "Websites"), external, internal));
  }

  @Test
  public void acceptsTheRootItself() {
    assertEquals(Boolean.TRUE, StoragePaths.isWritable(external, external, internal));
  }

  @Test
  public void refusesTheOldPublicRoot() {
    final File old = new File(tmp.getRoot(), "ext/HTTrack/Websites");
    assertEquals(Boolean.FALSE, StoragePaths.isWritable(old, external, internal));
    assertEquals(Boolean.FALSE, StoragePaths.isWritable(new File("/"), external, internal));
  }

  /** "files2" shares the string prefix of "files" without being inside it. */
  @Test
  public void refusesASiblingSharingThePrefix() throws Exception {
    final File sibling = tmp.newFolder("ext", "Android", "data", "com.httrack.android", "files2");
    assertEquals(Boolean.FALSE, StoragePaths.isWritable(new File(sibling, "Websites"), external, internal));
  }

  @Test
  public void refusesANeighbourPackage() throws Exception {
    final File other = tmp.newFolder("ext", "Android", "data", "com.httrack.android.evil", "files");
    assertEquals(Boolean.FALSE, StoragePaths.isWritable(other, external, internal));
  }

  @Test
  public void refusesTraversalOutOfTheRoot() {
    assertEquals(Boolean.FALSE,
        StoragePaths.isWritable(new File(external, "../../../../HTTrack"), external, internal));
  }

  @Test
  public void refusesASymlinkLeavingTheRoot() throws Exception {
    final File outside = tmp.newFolder("outside");
    final File link = new File(external, "escape");
    Files.createSymbolicLink(link.toPath(), outside.toPath());
    assertEquals(Boolean.FALSE, StoragePaths.isWritable(link, external, internal));
  }

  /**
   * The volume is gone, so an external path cannot be vetted. Answering "false" here would make
   * the caller erase a good setting that a remount would have restored.
   */
  @Test
  public void cannotDecideWithoutTheExternalRoot() {
    assertNull(StoragePaths.isWritable(new File(external, "Websites"), null, internal));
  }

  @Test
  public void stillDecidesInternalPathsWithoutTheExternalRoot() {
    assertEquals(Boolean.TRUE, StoragePaths.isWritable(new File(internal, "Websites"), null, internal));
  }

  /** Whatever the mount state, the default root the app picks must satisfy its own check. */
  @Test
  public void theDefaultRootAlwaysPassesItsOwnCheck() {
    assertTrue(StoragePaths.isWritable(new File(external, "Websites"), external, internal));
    assertTrue(StoragePaths.isWritable(new File(internal, "Websites"), null, internal));
  }
}
