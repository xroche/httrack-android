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
}
