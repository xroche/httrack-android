package com.httrack.android;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import fi.iki.elonen.NanoHTTPD;
import fi.iki.elonen.ResponseProbe;

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

  /** File.length() lying the way a stat landing mid-rewrite does must not shorten what we serve. */
  @Test
  public void servesTheOpenFileWhateverThePathStatSays() throws Exception {
    final File page = new File(root, "index.html");
    final byte[] content = "<html>hello</html>".getBytes("UTF-8");
    Files.write(page.toPath(), content);
    final File racing = new File(page.getPath()) {
      @Override
      public long length() {
        return 0;
      }
    };
    assertArrayEquals(content, body(MirrorServer.fileResponse(racing)));
  }

  @Test
  public void fileResponseOnAnUnopenableFileIs404() throws Exception {
    final NanoHTTPD.Response response = MirrorServer.fileResponse(new File(root, "gone.html"));
    assertEquals(NanoHTTPD.Response.Status.NOT_FOUND, response.getStatus());
  }

  /** Body as written on the wire, checked against the Content-Length advertised beside it. */
  private static byte[] body(final NanoHTTPD.Response response) throws Exception {
    final ByteArrayOutputStream wire = new ByteArrayOutputStream();
    ResponseProbe.send(response, wire);
    final byte[] bytes = wire.toByteArray();
    // ISO-8859-1 maps bytes one to one, so the index it yields is also the byte offset.
    final int split = new String(bytes, "ISO-8859-1").indexOf("\r\n\r\n");
    assertNotEquals("no header terminator on the wire", -1, split);
    final byte[] payload = Arrays.copyOfRange(bytes, split + 4, bytes.length);
    final Matcher length = Pattern.compile("(?im)^Content-Length: *(\\d+)")
        .matcher(new String(bytes, 0, split, "US-ASCII"));
    assertTrue("no Content-Length header", length.find());
    assertEquals(Integer.parseInt(length.group(1)), payload.length);
    return payload;
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
