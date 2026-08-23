package com.httrack.android;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.zip.GZIPInputStream;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import fi.iki.elonen.NanoHTTPD;

/**
 * Attacks MirrorServer: what it serves, what it must refuse to leave the root, and how it frames a
 * file the engine may be rewriting underneath it.
 */
public class MirrorServerTest {
  @Rule
  public final TemporaryFolder tmp = new TemporaryFolder();

  private File root;

  @Before
  public void setUp() throws Exception {
    root = tmp.newFolder("Websites");
  }

  @After
  public void tearDown() {
    MirrorServer.stopAll();
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

  @Test
  public void fileResponseOnAnUnopenableFileIs404() throws Exception {
    final NanoHTTPD.Response response =
        MirrorServer.fileResponse(new File(root, "gone.html"), false);
    assertEquals(NanoHTTPD.Response.Status.NOT_FOUND, response.getStatus());
  }

  @Test
  public void servesAFileChunkedAndIntact() throws Exception {
    final byte[] content = "<html>hello</html>".getBytes("UTF-8");
    Files.write(new File(root, "index.html").toPath(), content);
    final MirrorServer server = MirrorServer.start(root);
    try (Client client = new Client(server.getPort())) {
      client.send("GET", "/index.html");
      final Reply reply = client.read(false);
      assertEquals("HTTP/1.1 200 OK", reply.status);
      assertEquals("chunked", reply.headers.get("transfer-encoding"));
      assertNull(reply.headers.get("content-length"));
      assertArrayEquals(content, reply.body);
    } finally {
      server.stop();
    }
  }

  /**
   * The engine truncating index.html mid-serve. Chunked framing cannot hand the client a short body
   * under a length it already promised, so the message ends and the connection stays usable.
   */
  @Test
  public void aTruncationMidServeStillEndsTheMessage() throws Exception {
    final File page = new File(root, "big.html");
    // Larger than any socket buffer pair, so the server is provably still writing when we truncate.
    final byte[] content = new byte[16 * 1024 * 1024];
    for (int i = 0; i < content.length; i++) {
      content[i] = (byte) i;
    }
    Files.write(page.toPath(), content);
    final byte[] next = "<html>after</html>".getBytes("UTF-8");
    Files.write(new File(root, "next.html").toPath(), next);
    final MirrorServer server = MirrorServer.start(root);
    try (Client client = new Client(server.getPort())) {
      client.send("GET", "/big.html");
      final Reply reply = client.readHead();
      assertEquals("HTTP/1.1 200 OK", reply.status);
      // Blocking for one chunk pins the truncation mid-body, where a timer only makes it likely.
      final ByteArrayOutputStream served = new ByteArrayOutputStream();
      served.write(client.readChunk());
      assertTrue("truncation landed before the serve started, so nothing was under test",
          served.size() > 0);
      final RandomAccessFile rewrite = new RandomAccessFile(page, "rw");
      rewrite.setLength(0);
      rewrite.close();
      for (byte[] chunk = client.readChunk(); chunk.length != 0; chunk = client.readChunk()) {
        served.write(chunk);
      }
      final byte[] body = served.toByteArray();
      assertTrue("truncation landed after the whole file went out", body.length < content.length);
      assertArrayEquals(Arrays.copyOf(content, body.length), body);
      client.send("GET", "/next.html");
      assertArrayEquals(next, client.read(false).body);
    } finally {
      server.stop();
    }
  }

  /** HEAD keeps a real length, which chunked framing turns into "Content-Length: -1" instead. */
  @Test
  public void headAnswersALengthAndNoBody() throws Exception {
    assertHeadIsBodiless("");
  }

  /** A browser asks for gzip, and NanoHTTPD gzips a HEAD into a body it frames with nothing. */
  @Test
  public void headAnswersALengthAndNoBodyWhenGzipIsAccepted() throws Exception {
    assertHeadIsBodiless("Accept-Encoding: gzip\r\n");
  }

  private void assertHeadIsBodiless(final String extraHeaders) throws Exception {
    final byte[] content = "<html>hello</html>".getBytes("UTF-8");
    Files.write(new File(root, "index.html").toPath(), content);
    final MirrorServer server = MirrorServer.start(root);
    try (Client client = new Client(server.getPort())) {
      client.send("HEAD", "/index.html", extraHeaders);
      final Reply reply = client.read(true);
      assertEquals("HTTP/1.1 200 OK", reply.status);
      assertEquals(String.valueOf(content.length), reply.headers.get("content-length"));
      assertNull(reply.headers.get("transfer-encoding"));
      assertNull(reply.headers.get("content-encoding"));
      // A stray HEAD body would be read as the head of this reply, so its status line is the probe.
      client.send("GET", "/index.html");
      final Reply second = client.read(false);
      assertEquals("HTTP/1.1 200 OK", second.status);
      assertArrayEquals(content, second.body);
    } finally {
      server.stop();
    }
  }

  /** The 404 never reaches fileResponse, so it has to frame its own HEAD or the stream desyncs. */
  @Test
  public void headOnAMissingFileIsBodilessToo() throws Exception {
    assertErrorHeadIsBodiless("/gone.html", "404 Not Found");
  }

  /** Same for the path-escape refusal, which returns earlier still. */
  @Test
  public void headOnAnEscapingPathIsBodilessToo() throws Exception {
    assertErrorHeadIsBodiless("/../../etc/passwd", "403 Forbidden");
  }

  private void assertErrorHeadIsBodiless(final String path, final String error) throws Exception {
    final byte[] content = "<html>hello</html>".getBytes("UTF-8");
    Files.write(new File(root, "index.html").toPath(), content);
    final MirrorServer server = MirrorServer.start(root);
    try (Client client = new Client(server.getPort())) {
      client.send("GET", path);
      final Reply get = client.read(false);
      assertEquals("HTTP/1.1 " + error, get.status);
      assertArrayEquals(error.getBytes("US-ASCII"), get.body);
      client.send("HEAD", path);
      final Reply reply = client.read(true);
      assertEquals("HTTP/1.1 " + error, reply.status);
      assertEquals(String.valueOf(error.length()), reply.headers.get("content-length"));
      // A stray HEAD body would be read as the head of this reply, so its status line is the probe.
      client.send("GET", "/index.html");
      final Reply second = client.read(false);
      assertEquals("HTTP/1.1 200 OK", second.status);
      assertArrayEquals(content, second.body);
    } finally {
      server.stop();
    }
  }

  /** Gzip is suppressed for HEAD alone, so a GET that asks for it must still be compressed. */
  @Test
  public void getIsGzippedWhenAccepted() throws Exception {
    final byte[] content = "<html>hello</html>".getBytes("UTF-8");
    Files.write(new File(root, "index.html").toPath(), content);
    final MirrorServer server = MirrorServer.start(root);
    try (Client client = new Client(server.getPort())) {
      client.send("GET", "/index.html", "Accept-Encoding: gzip\r\n");
      final Reply reply = client.read(false);
      assertEquals("HTTP/1.1 200 OK", reply.status);
      assertEquals("gzip", reply.headers.get("content-encoding"));
      assertArrayEquals(content, gunzip(reply.body));
    } finally {
      server.stop();
    }
  }

  private static byte[] gunzip(final byte[] compressed) throws IOException {
    final ByteArrayOutputStream plain = new ByteArrayOutputStream();
    try (InputStream gzip = new GZIPInputStream(new ByteArrayInputStream(compressed))) {
      final byte[] buffer = new byte[4096];
      for (int read = gzip.read(buffer); read > 0; read = gzip.read(buffer)) {
        plain.write(buffer, 0, read);
      }
    }
    return plain.toByteArray();
  }

  /** HEAD answers from a stat, so it must not open the file it describes at all. */
  @Test
  public void headOpensNoDescriptor() throws Exception {
    final File fds = new File("/proc/self/fd");
    assumeTrue("descriptor table only readable on Linux", fds.isDirectory());
    final File page = new File(root, "index.html");
    Files.write(page.toPath(), "<html>hello</html>".getBytes("UTF-8"));
    final NanoHTTPD.Response response = MirrorServer.fileResponse(page, true);
    try {
      assertEquals(0, openDescriptors(fds, page));
    } finally {
      response.close();
    }
  }

  /** We hand NanoHTTPD an open file and never see it again, so it has to be the one closing it. */
  @Test
  public void servingLeavesNoOpenDescriptor() throws Exception {
    final File fds = new File("/proc/self/fd");
    assumeTrue("descriptor table only readable on Linux", fds.isDirectory());
    final File page = new File(root, "index.html");
    Files.write(page.toPath(), "<html>hello</html>".getBytes("UTF-8"));
    final MirrorServer server = MirrorServer.start(root);
    try (Client client = new Client(server.getPort())) {
      client.send("GET", "/index.html");
      client.read(false);
      // The close trails the last byte the client sees, so wait for it rather than sampling once.
      final long deadline = System.currentTimeMillis() + 5000;
      while (openDescriptors(fds, page) != 0 && System.currentTimeMillis() < deadline) {
        Thread.sleep(20);
      }
      assertEquals(0, openDescriptors(fds, page));
    } finally {
      server.stop();
    }
  }

  private static int openDescriptors(final File fds, final File file) throws IOException {
    final Path target = file.getCanonicalFile().toPath();
    int open = 0;
    for (final File fd : fds.listFiles()) {
      try {
        if (target.equals(Files.readSymbolicLink(fd.toPath()))) {
          open++;
        }
      } catch (final IOException closedUnderUs) {
        continue;
      }
    }
    return open;
  }

  /** One response as the client framed it. */
  private static final class Reply {
    final String status;

    final Map<String, String> headers;

    final byte[] body;

    Reply(final String status, final Map<String, String> headers, final byte[] body) {
      this.status = status;
      this.headers = headers;
      this.body = body;
    }
  }

  /**
   * Minimal HTTP/1.1 client on one keep-alive connection. It sends no Accept-Encoding unless a test
   * asks for one, so the framing under test is the one the server chose.
   */
  private static final class Client implements Closeable {
    private final Socket socket;

    private final InputStream in;

    private final OutputStream out;

    private final int port;

    Client(final int port) throws IOException {
      this.port = port;
      socket = new Socket();
      // A small receive window keeps the server blocked mid-body while a test rewrites the file.
      socket.setReceiveBufferSize(4096);
      socket.connect(new InetSocketAddress("127.0.0.1", port));
      socket.setSoTimeout(30000);
      in = new BufferedInputStream(socket.getInputStream());
      out = socket.getOutputStream();
    }

    void send(final String method, final String path) throws IOException {
      send(method, path, "");
    }

    void send(final String method, final String path, final String extraHeaders)
        throws IOException {
      sendWithHost(method, path, "127.0.0.1:" + port, extraHeaders);
    }

    /** Sends whatever Host the caller names, or none at all when {@code host} is null. */
    void sendWithHost(final String method, final String path, final String host,
        final String extraHeaders) throws IOException {
      final String request = method + " " + path + " HTTP/1.1\r\n"
          + (host == null ? "" : "Host: " + host + "\r\n") + extraHeaders + "\r\n";
      out.write(request.getBytes("US-ASCII"));
      out.flush();
    }

    Reply read(final boolean head) throws IOException {
      final Reply reply = readHead();
      return head ? reply : new Reply(reply.status, reply.headers, readBody(reply.headers));
    }

    /** Status line and headers, leaving the body on the socket for piecewise reading. */
    Reply readHead() throws IOException {
      // NanoHTTPD pads its status line with a trailing space.
      final String status = line().trim();
      final Map<String, String> headers = new HashMap<String, String>();
      for (String header = line(); header.length() != 0; header = line()) {
        final int colon = header.indexOf(':');
        headers.put(header.substring(0, colon).toLowerCase(Locale.ROOT),
            header.substring(colon + 1).trim());
      }
      return new Reply(status, headers, new byte[0]);
    }

    /** One chunk of a chunked body; empty at the terminating chunk. */
    byte[] readChunk() throws IOException {
      final byte[] chunk = readFully(chunkSize());
      line();
      return chunk;
    }

    private byte[] readBody(final Map<String, String> headers) throws IOException {
      if (!"chunked".equals(headers.get("transfer-encoding"))) {
        return readFully(Integer.parseInt(headers.get("content-length")));
      }
      final ByteArrayOutputStream body = new ByteArrayOutputStream();
      for (byte[] chunk = readChunk(); chunk.length != 0; chunk = readChunk()) {
        body.write(chunk);
      }
      return body.toByteArray();
    }

    private int chunkSize() throws IOException {
      final String size = line();
      final int extension = size.indexOf(';');
      final String value = extension < 0 ? size : size.substring(0, extension);
      return Integer.parseInt(value.trim(), 16);
    }

    private byte[] readFully(final int size) throws IOException {
      final byte[] buffer = new byte[size];
      for (int done = 0; done < size;) {
        final int read = in.read(buffer, done, size - done);
        if (read < 0) {
          throw new IOException("connection closed " + (size - done) + " bytes short");
        }
        done += read;
      }
      return buffer;
    }

    private String line() throws IOException {
      final StringBuilder text = new StringBuilder();
      for (int c = in.read(); c != -1 && c != '\n'; c = in.read()) {
        if (c != '\r') {
          text.append((char) c);
        }
      }
      return text.toString();
    }

    @Override
    public void close() throws IOException {
      socket.close();
    }
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

  @Test
  public void ownAuthorityAcceptsBothLoopbackNamesOnOurPort() {
    assertTrue(MirrorServer.isOwnAuthority("127.0.0.1:8080", 8080));
    assertTrue(MirrorServer.isOwnAuthority("localhost:8080", 8080));
    assertTrue(MirrorServer.isOwnAuthority("LocalHost:8080", 8080));
  }

  @Test
  public void ownAuthorityRefusesEverythingElse() {
    assertFalse(MirrorServer.isOwnAuthority("evil.example.com:8080", 8080));
    assertFalse(MirrorServer.isOwnAuthority("127.0.0.1:8081", 8080));
    assertFalse(MirrorServer.isOwnAuthority("127.0.0.1", 8080));
    assertFalse(MirrorServer.isOwnAuthority("localhost.evil.example.com:8080", 8080));
    assertFalse(MirrorServer.isOwnAuthority("[::1]:8080", 8080));
    assertFalse(MirrorServer.isOwnAuthority(null, 8080));
  }

  @Test
  public void serverAnswersOurOwnAuthority() throws Exception {
    assertHostIsServed("127.0.0.1");
    assertHostIsServed("localhost");
  }

  private void assertHostIsServed(final String name) throws Exception {
    final byte[] content = "<html>hello</html>".getBytes("UTF-8");
    Files.write(new File(root, "index.html").toPath(), content);
    final MirrorServer server = MirrorServer.start(root);
    try (Client client = new Client(server.getPort())) {
      client.sendWithHost("GET", "/index.html", name + ":" + server.getPort(), "");
      final Reply reply = client.read(false);
      assertEquals("HTTP/1.1 200 OK", reply.status);
      assertArrayEquals(content, reply.body);
    } finally {
      server.stop();
    }
  }

  /** A page on a name rebound to 127.0.0.1 reads the mirror unless a foreign Host is refused. */
  @Test
  public void aForeignHostIsRefusedWithoutABody() throws Exception {
    assertHostIsRefused("evil.example.com:PORT");
  }

  /** The port is half of the identity: our name on someone else's port is not us. */
  @Test
  public void theRightNameOnTheWrongPortIsRefused() throws Exception {
    assertHostIsRefused("127.0.0.1:1");
  }

  @Test
  public void aMissingHostIsRefused() throws Exception {
    assertHostIsRefused(null);
  }

  private void assertHostIsRefused(final String host) throws Exception {
    Files.write(new File(root, "index.html").toPath(), "<html>hello</html>".getBytes("UTF-8"));
    final MirrorServer server = MirrorServer.start(root);
    try (Client client = new Client(server.getPort())) {
      final String sent = host == null ? null
          : host.replace("PORT", String.valueOf(server.getPort()));
      client.sendWithHost("GET", "/index.html", sent, "");
      final Reply reply = client.read(false);
      assertEquals("HTTP/1.1 403 Forbidden", reply.status);
      assertEquals(0, reply.body.length);
    } finally {
      server.stop();
    }
  }

  /** The Host check runs first, so the escape refusal has to stay reachable behind it. */
  @Test
  public void traversalIsStillRefusedWithAGoodHost() throws Exception {
    final File secret = tmp.newFile("secret.txt");
    Files.write(secret.toPath(), "top secret".getBytes("UTF-8"));
    final MirrorServer server = MirrorServer.start(root);
    try (Client client = new Client(server.getPort())) {
      client.send("GET", "/../secret.txt");
      final Reply reply = client.read(false);
      assertEquals("HTTP/1.1 403 Forbidden", reply.status);
      assertArrayEquals("403 Forbidden".getBytes("US-ASCII"), reply.body);
    } finally {
      server.stop();
    }
  }

  /** The reader is another app, so nothing but the absence of requests can close the port. */
  @Test
  public void anIdleServerStopsItself() throws Exception {
    final MirrorServer server = MirrorServer.start(root, 100);
    final long deadline = System.currentTimeMillis() + 10000;
    while (server.isAlive() && System.currentTimeMillis() < deadline) {
      Thread.sleep(20);
    }
    assertFalse("the idle watchdog never stopped the server", server.isAlive());
  }

  /** Half the defence is that the port dies; a caller we refuse must not be able to postpone it. */
  @Test
  public void aRefusedCallerCannotHoldThePortOpen() throws Exception {
    Files.write(new File(root, "index.html").toPath(), "<html>hi</html>".getBytes("UTF-8"));
    final MirrorServer server = MirrorServer.start(root, 400);
    final long deadline = System.currentTimeMillis() + 10000;
    while (server.isAlive() && System.currentTimeMillis() < deadline) {
      // Exactly what a rebound page sends, faster than the watchdog's own timeout.
      try (Client client = new Client(server.getPort())) {
        client.sendWithHost("GET", "/index.html", "evil.example.com", "");
        client.read(false);
      } catch (final IOException stopped) {
        break;
      }
      Thread.sleep(100);
    }
    assertFalse("a refused caller kept the server alive", server.isAlive());
  }

  /** A browser reading slowly must not be cut off, so a live transfer holds the server open. */
  @Test
  public void aSlowReadKeepsTheServerAlive() throws Exception {
    final byte[] content = new byte[2 * 1024 * 1024];
    Files.write(new File(root, "big.html").toPath(), content);
    final MirrorServer server = MirrorServer.start(root, 500);
    try (Client client = new Client(server.getPort())) {
      client.send("GET", "/big.html");
      assertEquals("HTTP/1.1 200 OK", client.readHead().status);
      for (int i = 0; i < 15; i++) {
        Thread.sleep(100);
        assertTrue("the server stopped under a reader still asking for bytes",
            client.readChunk().length > 0);
      }
      assertTrue(server.isAlive());
    } finally {
      server.stop();
    }
  }

  @Test
  public void stopDropsTheRegistryEntry() throws Exception {
    final MirrorServer server = MirrorServer.forRoot(root);
    server.stop();
    assertNotSame(server, MirrorServer.forRoot(root));
  }
}
