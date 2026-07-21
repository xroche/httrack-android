package com.httrack.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import java.io.File;
import java.nio.file.Files;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** Attacks MirrorServer.resolveWithinRoot: what it serves, what it must refuse to leave the root. */
public class MirrorServerTest {
  @Rule
  public final TemporaryFolder tmp = new TemporaryFolder();

  private File root;

  @Before
  public void setUp() throws Exception {
    root = tmp.newFolder("Websites");
  }

  @Test
  public void resolvesANormalFile() throws Exception {
    final File page = new File(root, "index.html");
    Files.createFile(page.toPath());
    assertEquals(page.getCanonicalFile(), MirrorServer.resolveWithinRoot(root, "/index.html"));
  }

  @Test
  public void resolvesTheRootItself() throws Exception {
    assertEquals(root.getCanonicalFile(), MirrorServer.resolveWithinRoot(root, "/"));
  }

  @Test
  public void refusesDotDotTraversal() {
    assertNull(MirrorServer.resolveWithinRoot(root, "/../../etc/passwd"));
  }

  @Test
  public void containsAbsolutePathInjectionUnderRoot() throws Exception {
    // File(root, "/etc/hosts") resolves under root on Unix; it must never reach the real /etc/hosts.
    assertEquals(new File(root, "etc/hosts").getCanonicalFile(),
        MirrorServer.resolveWithinRoot(root, "/etc/hosts"));
  }

  /** A sibling whose name extends root's ("Websites-evil" vs "Websites") is outside root. */
  @Test
  public void refusesASiblingSharingRootsNamePrefix() throws Exception {
    final File sibling = tmp.newFolder("Websites-evil");
    Files.createFile(new File(sibling, "secret.txt").toPath());
    assertNull(MirrorServer.resolveWithinRoot(root, "/../Websites-evil/secret.txt"));
  }

  @Test
  public void refusesASymlinkLeavingTheRoot() throws Exception {
    final File outside = tmp.newFolder("outside");
    Files.createSymbolicLink(new File(root, "escape").toPath(), outside.toPath());
    assertNull(MirrorServer.resolveWithinRoot(root, "/escape/secret"));
  }

  @Test
  public void relativeUrlPathForAFileUnderRoot() throws Exception {
    final File page = new File(new File(root, "html"), "index.html");
    assertEquals("html/index.html", MirrorServer.relativeUrlPath(root, page));
  }

  @Test
  public void relativeUrlPathForRootItselfIsEmpty() throws Exception {
    assertEquals("", MirrorServer.relativeUrlPath(root, root));
  }

  @Test
  public void relativeUrlPathEncodesSpaces() throws Exception {
    assertEquals("a%20b/c.html",
        MirrorServer.relativeUrlPath(root, new File(new File(root, "a b"), "c.html")));
  }

  /** The doc/license 404: a resources-cache file addressed against the Websites root is refused. */
  @Test
  public void relativeUrlPathRefusesAFileOutsideRoot() throws Exception {
    final File resources = tmp.newFolder("resources");
    final File doc = new File(new File(resources, "license"), "gpl-3.0-standalone.html");
    assertNull(MirrorServer.relativeUrlPath(root, doc));
  }

  @Test
  public void relativeUrlPathRefusesASiblingSharingRootsNamePrefix() throws Exception {
    final File sibling = tmp.newFolder("Websites-evil");
    assertNull(MirrorServer.relativeUrlPath(root, new File(sibling, "secret.txt")));
  }
}
