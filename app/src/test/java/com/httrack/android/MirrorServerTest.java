package com.httrack.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.fail;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Files;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import fi.iki.elonen.NanoHTTPD;

/**
 * Attacks MirrorServer: what it serves, what it must refuse to leave the root, and what length it
 * puts on a file the engine may be rewriting underneath it.
 */
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

  /** A File.length() lying the way a stat landing mid-rewrite does must not reach the response. */
  @Test
  public void measuresTheOpenFileNotThePath() throws Exception {
    final File page = new File(root, "index.html");
    final byte[] content = "<html>hello</html>".getBytes("UTF-8");
    Files.write(page.toPath(), content);
    final File racing = new File(page.getPath()) {
      @Override
      public long length() {
        return 0;
      }
    };
    final MirrorServer.OpenFile open = MirrorServer.openAndMeasure(racing);
    try {
      assertEquals(content.length, open.length);
    } finally {
      open.stream.close();
    }
  }

  /** A failed measure leaves nobody holding the stream, so it has to close it itself. */
  @Test
  public void measureClosesTheStreamItCannotMeasure() throws Exception {
    final File page = new File(root, "index.html");
    Files.write(page.toPath(), "x".getBytes("UTF-8"));
    final int[] closes = { 0 };
    final FileInputStream stream = new FileInputStream(page) {
      @Override
      public void close() throws IOException {
        closes[0]++;
        super.close();
      }
    };
    // A closed channel is what makes size() throw; closing it here closes the stream with it.
    stream.getChannel().close();
    final int before = closes[0];
    try {
      MirrorServer.measure(stream);
      fail("measuring a closed channel should throw");
    } catch (final IOException expected) {
      assertEquals(before + 1, closes[0]);
    }
  }

  @Test
  public void fileResponseOnAnUnopenableFileIs404() throws Exception {
    final NanoHTTPD.Response response = MirrorServer.fileResponse(new File(root, "gone.html"));
    assertEquals(NanoHTTPD.Response.Status.NOT_FOUND, response.getStatus());
  }

  /** Docs and mirrors are served at once, so each root must keep its own server. */
  @Test
  public void forRootKeepsOneServerPerTree() throws Exception {
    final File resources = tmp.newFolder("resources");
    final MirrorServer mirrors = MirrorServer.forRoot(root);
    assertSame(mirrors, MirrorServer.forRoot(root));
    assertNotSame(mirrors, MirrorServer.forRoot(resources));
    assertNotEquals(mirrors.getPort(), MirrorServer.forRoot(resources).getPort());
  }

  /** Keyed by canonical path: "/sdcard" is a symlink on Android, so aliases must not fork servers. */
  @Test
  public void forRootFoldsAliasesOfTheSameTree() throws Exception {
    assertSame(MirrorServer.forRoot(root), MirrorServer.forRoot(new File(root, "html/..")));
  }
}
