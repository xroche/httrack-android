package com.httrack.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
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

  /** The engine keeps a .bak and stages a .tmp, so a delete that takes only the name leaks. */
  @Test
  public void aDeletedLoginTakesWhatTheEngineLeftBesideIt() {
    assertTrue(StoragePaths.isLoginSibling("My Site.txt", "My Site.txt"));
    assertTrue(StoragePaths.isLoginSibling("My Site.txt", "My Site.txt.bak"));
    assertTrue(StoragePaths.isLoginSibling("My Site.txt", "My Site.txt.4321.tmp"));
    /* The .bak is staged through a temporary of its own, and that file holds a whole jar, so
       the suffixes stack. The engine's own self-test asserts this name. */
    assertTrue(StoragePaths.isLoginSibling("My Site.txt", "My Site.txt.bak.4321.tmp"));
  }

  /** The matcher must take whatever our write stages, or a crash mid-save leaks the session. */
  @Test
  public void aDeletedLoginTakesTheStagedWriteACrashLeftBehind() {
    final File login = StoragePaths.loginCookiesFile(internal, "My Site");
    assertTrue(StoragePaths.isLoginSibling(login.getName(),
        StoragePaths.stagedLoginFile(login).getName()));
  }

  @Test
  public void aDeletedLoginLeavesAnotherProjectAlone() {
    assertFalse(StoragePaths.isLoginSibling("A.txt", "B.txt"));
    assertFalse(StoragePaths.isLoginSibling("A.txt", "A.txt.bak.txt"));
    // A project may legitimately be called "A.txt", whose own login file is "A.txt.txt".
    assertFalse(StoragePaths.isLoginSibling("A.txt", "A.txt.txt"));
    assertFalse(StoragePaths.isLoginSibling("A.txt", "A.txt.tmp"));
    assertFalse(StoragePaths.isLoginSibling("A.txt", "A.txt.bak2"));
    assertFalse(StoragePaths.isLoginSibling("A.txt", "A.txt.bak.tmp"));
    assertFalse(StoragePaths.isLoginSibling("A.txt", "A.txt.4321.bak"));
    // The staged suffix stands alone, because nothing writes it onto a .bak or a temporary.
    assertFalse(StoragePaths.isLoginSibling("A.txt", "A.txt.bak.new"));
    assertFalse(StoragePaths.isLoginSibling("A.txt", "A.txt.new.new"));
    assertFalse(StoragePaths.isLoginSibling("A.txt", null));
    /* isValidProjectName allows a regex metacharacter, so the name is quoted before it becomes
       a pattern. Unquoted, "A+" would match another project's "AA.txt" and delete its session,
       and "a(" would throw mid-delete. */
    assertFalse(StoragePaths.isLoginSibling("A+.txt", "AA.txt"));
    assertTrue(StoragePaths.isLoginSibling("a(.txt", "a(.txt"));
    assertTrue(StoragePaths.isLoginSibling("a(.txt", "a(.txt.bak"));
  }

  /** Over real files, because the matching decides what gets deleted off a user's device. */
  @Test
  public void savedLoginFilesTakesOurOwnAndLeavesTheNeighbour() throws IOException {
    final File internal = tmp.newFolder("files");
    final File logins = new File(internal, "logins");
    assertTrue(logins.mkdirs());
    for (final String name : new String[] { "P.txt", "P.txt.bak", "P.txt.4321.tmp",
        "P.txt.bak.4321.tmp", "P.txt.new", "Q.txt", "Q.txt.bak", "Q.txt.new", "notes.txt" }) {
      assertTrue(new File(logins, name).createNewFile());
    }
    final java.util.List<File> ours = StoragePaths.savedLoginFiles(internal, "P");
    final java.util.Set<String> names = new java.util.TreeSet<String>();
    for (final File file : ours) {
      names.add(file.getName());
    }
    assertEquals(new java.util.TreeSet<String>(java.util.Arrays.asList("P.txt", "P.txt.bak",
        "P.txt.4321.tmp", "P.txt.bak.4321.tmp", "P.txt.new")), names);
  }

  @Test
  public void savedLoginFilesIsEmptyBeforeAnyLoginIsSaved() throws IOException {
    final File internal = tmp.newFolder("files-empty");
    assertTrue(StoragePaths.savedLoginFiles(internal, "P").isEmpty());
    assertTrue(StoragePaths.savedLoginFiles(internal, "..").isEmpty());
  }

  /** A deleted project must not leave a live session behind, so both callers share this path. */
  @Test
  public void theLoginCookiesFileSitsInPrivateStorage() {
    final File internal = new File("/data/user/0/com.httrack.android/files");
    assertEquals(new File(internal, "logins/My Site.txt"),
        StoragePaths.loginCookiesFile(internal, "My Site"));
  }

  @Test
  public void noLoginPathIsBuiltFromAnUnusableName() {
    final File internal = new File("/data/user/0/com.httrack.android/files");
    assertNull(StoragePaths.loginCookiesFile(internal, null));
    assertNull(StoragePaths.loginCookiesFile(internal, ""));
    // A name that is not one segment must never become a path under our own storage.
    assertNull(StoragePaths.loginCookiesFile(internal, ".."));
    assertNull(StoragePaths.loginCookiesFile(internal, "a/b"));
    assertNull(StoragePaths.loginCookiesFile(internal, "/etc/passwd"));
    assertNull(StoragePaths.loginCookiesFile(null, "My Site"));
  }

  @Test
  public void treatsAnEscapingPathAsOutsideTheRoot() throws IOException {
    final String root = external.getCanonicalPath();
    assertTrue(StoragePaths.isInsideRoot(new File(external, "html/index.html").getCanonicalPath(),
        root));
    assertTrue("the root itself is inside itself", StoragePaths.isInsideRoot(root, root));
    assertFalse(StoragePaths.isInsideRoot(new File(external, "../evil").getCanonicalPath(), root));
    assertFalse(StoragePaths.isInsideRoot(
        new File(external, "html/../../../evil").getCanonicalPath(), root));
    assertFalse(StoragePaths.isInsideRoot(root + "2", root));
  }

  /**
   * The guard is only worth anything if the extraction loop calls it, refuses on it, and does so
   * before it opens the file. A call whose answer is dropped, or one made after the write, would
   * leave the predicate right and the extraction wide open.
   */
  /**
   * The resource bundle is extracted entry by entry, and a ".." in a name would land outside the
   * cache. Canonical paths are what the guard compares, so the escape must be built that way.
   */
  @Test
  public void theResourceExtractionRefusesAnEscapingEntryBeforeWriting() throws IOException {
    final String source = TestSources.withoutCommentsAndStrings(
        TestSources.javaSource("HTTrackActivity")).replaceAll("\\s+", " ");
    final String guard = "if (!StoragePaths.isInsideRoot(dest.getCanonicalPath(), "
        + "rscPath.getCanonicalPath())) { throw new IOException(";
    assertTrue("the zip entry destination must be refused unless it is inside the cache root",
        source.contains(guard));
    assertTrue("the guard must precede the write it protects",
        source.indexOf(guard) < source.indexOf("new FileOutputStream(dest)"));
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

  /** The path builds before versionCode 61 wrote to, spelled out so a change to it fails here. */
  private static File legacyTree(final File shared) throws IOException {
    final File websites = new File(new File(new File(shared, "Download"), "HTTrack"), "Websites");
    assertTrue(websites.mkdirs());
    return websites;
  }

  private static void project(final File websites, final String name) throws IOException {
    final File dir = new File(websites, name);
    assertTrue(dir.mkdir());
    assertTrue(new File(dir, "index.html").createNewFile());
  }

  /** A question the user cannot answer is worse than no question, so silence is the default. */
  @Test
  public void anAbsentLegacyFolderIsNotOffered() throws IOException {
    assertNull(StoragePaths.legacyMirrorRoot(tmp.newFolder("bare")));
  }

  /** Builds before versionCode 61 wrote here; an empty folder is not a reason to ask. */
  @Test
  public void anEmptyLegacyFolderIsNotOffered() throws IOException {
    final File shared = tmp.newFolder("empty");
    legacyTree(shared);
    assertNull(StoragePaths.legacyMirrorRoot(shared));
  }

  /** An empty project would import nothing, so it is not worth a question either. */
  @Test
  public void anEmptyProjectIsNotOffered() throws IOException {
    final File shared = tmp.newFolder("hollow");
    assertTrue(new File(legacyTree(shared), "someproject").mkdir());
    assertNull(StoragePaths.legacyMirrorRoot(shared));
  }

  /** A stray file is not a project; only a project directory earns the question. */
  @Test
  public void aFileIsNotAProject() throws IOException {
    final File shared = tmp.newFolder("stray");
    assertTrue(new File(legacyTree(shared), "notes.txt").createNewFile());
    assertNull(StoragePaths.legacyMirrorRoot(shared));
  }

  /** The one test that pins the path itself: every negative case passes on a wrong path too. */
  @Test
  public void aLegacyProjectIsOffered() throws IOException {
    final File shared = tmp.newFolder("real");
    final File websites = legacyTree(shared);
    project(websites, "someproject");
    assertEquals(websites, StoragePaths.legacyMirrorRoot(shared));
  }

  /** Only the resume that brings access can reveal anything; every other must stay silent. */
  @Test
  public void onlyTheGrantItselfRaisesTheOffer() {
    assertTrue(StoragePaths.accessJustAppeared(false, true));
    assertFalse("a plain resume with access must not re-ask",
        StoragePaths.accessJustAppeared(true, true));
    assertFalse(StoragePaths.accessJustAppeared(false, false));
    assertFalse("access going away is not an invitation",
        StoragePaths.accessJustAppeared(true, false));
  }
}
