package com.httrack.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
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

  /** Without all-files access (no shared root), a public path is not ours to write. */
  @Test
  public void refusesThePublicRootWithoutAllFilesAccess() {
    final File old = new File(tmp.getRoot(), "ext/HTTrack/Websites");
    assertEquals(Boolean.FALSE, StoragePaths.isWritable(old, external, internal));
    assertEquals(Boolean.FALSE, StoragePaths.isWritable(new File("/"), external, internal));
    assertEquals(Boolean.FALSE, StoragePaths.isWritable(old, external, internal, null));
  }

  /** With all-files access, the public HTTrack root passed as {@code shared} is writable. */
  @Test
  public void acceptsThePublicRootWithAllFilesAccess() throws Exception {
    final File shared = tmp.newFolder("shared"); // stands in for /storage/emulated/0
    final File mirror = new File(new File(shared, "HTTrack"), "Websites");
    assertEquals(Boolean.TRUE, StoragePaths.isWritable(mirror, external, internal, shared));
    // A sibling of the shared root is still outside it.
    assertEquals(Boolean.FALSE,
        StoragePaths.isWritable(new File(tmp.getRoot(), "elsewhere"), external, internal, shared));
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

  /** A public folder maps to its "primary:<relative>" documents id. */
  @Test
  public void mapsAPublicFolderToItsDocId() throws Exception {
    final File shared = tmp.newFolder("shared");
    final File mirror = new File(new File(shared, "HTTrack"), "Websites");
    assertEquals("primary:HTTrack" + File.separator + "Websites",
        StoragePaths.externalStorageDocId(mirror, shared));
  }

  /** The Android/ subtree is hidden from file managers, so it must not map. */
  @Test
  public void refusesThePrivateAndroidSubtree() throws Exception {
    final File shared = tmp.newFolder("shared2");
    final File priv = new File(shared, "Android/data/com.httrack.android/files/Websites");
    assertNull(StoragePaths.externalStorageDocId(priv, shared));
  }

  @Test
  public void refusesAPathOutsideTheSharedRootOrWithoutIt() throws Exception {
    final File shared = tmp.newFolder("shared3");
    assertNull(StoragePaths.externalStorageDocId(new File(tmp.getRoot(), "elsewhere"), shared));
    assertNull(StoragePaths.externalStorageDocId(shared, shared)); // the root itself, no sub-path
    assertNull(StoragePaths.externalStorageDocId(new File(shared, "HTTrack"), null));
  }

  private static File legacyTree(final File shared, final boolean withProject) throws IOException {
    final File websites = new File(new File(new File(shared, "Download"), "HTTrack"), "Websites");
    assertTrue(websites.mkdirs());
    if (withProject) {
      assertTrue(new File(websites, "someproject").mkdir());
    }
    return websites;
  }

  /** A question the user cannot answer is worse than no question, so silence is the default. */
  @Test
  public void anAbsentLegacyFolderIsNotOffered() throws IOException {
    assertNull(StoragePaths.legacyMirrors(tmp.newFolder("bare")));
  }

  /** Builds before versionCode 61 wrote here; an empty folder is not a reason to ask. */
  @Test
  public void anEmptyLegacyFolderIsNotOffered() throws IOException {
    final File shared = tmp.newFolder("empty");
    legacyTree(shared, false);
    assertNull(StoragePaths.legacyMirrors(shared));
  }

  /** A stray file is not a project; only a project directory earns the question. */
  @Test
  public void aFileIsNotAProject() throws IOException {
    final File shared = tmp.newFolder("stray");
    final File websites = legacyTree(shared, false);
    assertTrue(new File(websites, "notes.txt").createNewFile());
    assertNull(StoragePaths.legacyMirrors(shared));
  }

  @Test
  public void aLegacyProjectIsOffered() throws IOException {
    final File shared = tmp.newFolder("real");
    final File websites = legacyTree(shared, true);
    assertEquals(websites, StoragePaths.legacyMirrors(shared));
  }
}
