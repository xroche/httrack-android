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
  public void refusesAbsolutePathInjection() {
    // new File(root, "/etc/hosts") stays under root; canonicalisation must not let it escape.
    final File resolved = MirrorServer.resolveWithinRoot(root, "/etc/hosts");
    if (resolved != null) {
      assertEquals(true, resolved.getPath().startsWith(root.getPath()));
    }
  }

  @Test
  public void refusesASymlinkLeavingTheRoot() throws Exception {
    final File outside = tmp.newFolder("outside");
    Files.createSymbolicLink(new File(root, "escape").toPath(), outside.toPath());
    assertNull(MirrorServer.resolveWithinRoot(root, "/escape/secret"));
  }
}
