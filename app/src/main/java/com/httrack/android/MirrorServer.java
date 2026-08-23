/*
HTTrack Android Java Interface.

HTTrack Website Copier, Offline Browser for Windows and Unix
Copyright (C) Xavier Roche and other contributors

This program is free software; you can redistribute it and/or
modify it under the terms of the GNU General Public License
as published by the Free Software Foundation; either version 3
of the License, or any later version.

This program is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU General Public License for more details.

You should have received a copy of the GNU General Public License
along with this program; if not, write to the Free Software
Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA  02111-1307, USA.
 */

package com.httrack.android;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import fi.iki.elonen.NanoHTTPD;

/**
 * Loopback-only HTTP server exposing a HTTrack mirror to an external browser. Android 11+ denies a
 * browser file:// access to our scoped storage, so the mirror is served over http://127.0.0.1 and
 * read as an ordinary web site. Filenames come from crawled URLs, so path resolution is hostile
 * input and lives in the unit-tested {@link #resolveWithinRoot(File, String)}.
 *
 * Loopback is not by itself a boundary: any page the user visits can point a name it controls at
 * 127.0.0.1 and read the mirror through the browser, so requests must also name us
 * ({@link #isOwnAuthority}) and the port must not outlive the reading ({@link #watchForIdle}).
 *
 * Plain thread (NanoHTTPD owns it): reliability while browsing backgrounded relies on us staying
 * the most-recently-used cached process. A foreground service (needs an Android-14 type) is a
 * future step.
 */
final class MirrorServer extends NanoHTTPD {
  // Ports tried in order; mirrors the engine's htscatchurl.c try_to_listen_to[]. 0 = OS-assigned
  // ephemeral fallback. Privileged (<1024) ports are omitted: Android forbids binding them.
  private static final int[] PORTS = { 8080, 3128, 8081, 3129, 0 };

  // Long enough to read one page and follow a link, short enough that a forgotten browse does not
  // leave the port open for the rest of the process's life.
  private static final long IDLE_TIMEOUT_MS = 30 * 60 * 1000L;

  // One server per served tree, keyed by canonical path: docs and mirrors live in different trees,
  // and restarting a shared server on every switch would cut off whatever is reading the other one.
  private static final Map<String, MirrorServer> servers = new HashMap<String, MirrorServer>();

  private final File root;

  private final long idleTimeoutMs;

  /** When the last response ended: the only evidence available that something is still reading. */
  private volatile long lastActivityMs = System.currentTimeMillis();

  private final AtomicInteger streaming = new AtomicInteger();

  private MirrorServer(final File root, final int port, final long idleTimeoutMs) {
    super("127.0.0.1", port);
    this.root = root;
    this.idleTimeoutMs = idleTimeoutMs;
  }

  /**
   * Start a loopback server rooted at {@code root} (the Websites directory), binding the first port
   * from {@link #PORTS} that accepts a listen.
   *
   * @param root
   *          the served tree; every request is confined to it
   * @return a started server
   * @throws IOException
   *           if no candidate port could be bound
   */
  static MirrorServer start(final File root) throws IOException {
    return start(root, IDLE_TIMEOUT_MS);
  }

  /** Same, with the idle lifetime spelled out, so a test need not wait out the real one. */
  static MirrorServer start(final File root, final long idleTimeoutMs) throws IOException {
    IOException last = null;
    for (final int port : PORTS) {
      final MirrorServer server = new MirrorServer(root, port, idleTimeoutMs);
      try {
        server.start(NanoHTTPD.SOCKET_READ_TIMEOUT, true);
        server.watchForIdle();
        return server;
      } catch (final IOException e) {
        last = e;
      }
    }
    throw last != null ? last : new IOException("no loopback port available");
  }

  /**
   * Started server for {@code root}, reusing the one already serving that tree unless it has since
   * gone idle. Servers outlive the activity that opened them: the browser reading a mirror is a
   * separate app, and stopping the server on our onDestroy would break its page.
   *
   * @param root
   *          the served tree; every request is confined to it
   * @return a started server, valid until it goes idle
   * @throws IOException
   *           if the tree cannot be canonicalised, or no port could be bound
   */
  static synchronized MirrorServer forRoot(final File root) throws IOException {
    final String key = root.getCanonicalPath();
    MirrorServer server = servers.get(key);
    if (server == null || !server.isAlive()) {
      server = start(root);
      servers.put(key, server);
    }
    return server;
  }

  /** Stops serving and drops the registry entry, so the next browse starts a fresh server. */
  @Override
  public void stop() {
    synchronized (MirrorServer.class) {
      servers.values().remove(this);
    }
    super.stop();
  }

  /** Stops every server; the ports are what an attacker needs open, so none may be left running. */
  static synchronized void stopAll() {
    for (final MirrorServer server : new ArrayList<MirrorServer>(servers.values())) {
      server.stop();
    }
    servers.clear();
  }

  /**
   * Daemon watchdog stopping us once nothing has read for {@link #idleTimeoutMs}. The reader is
   * another app, so no callback of ours says it is done; only the absence of requests does.
   */
  private void watchForIdle() {
    final Thread watchdog = new Thread(new Runnable() {
      @Override
      public void run() {
        try {
          for (;;) {
            final long left = idleTimeoutMs - (System.currentTimeMillis() - lastActivityMs);
            if (left <= 0 && streaming.get() == 0) {
              break;
            }
            Thread.sleep(left > 0 ? left : idleTimeoutMs);
          }
        } catch (final InterruptedException interrupted) {
          return;
        }
        if (isAlive()) {
          stop();
        }
      }
    }, "mirror-idle-" + getListeningPort());
    watchdog.setDaemon(true);
    watchdog.start();
  }

  /** Actual bound port, valid once started. */
  int getPort() {
    return getListeningPort();
  }

  /** Base URL other code prefixes a relative mirror path onto. */
  String getBaseUrl() {
    return "http://127.0.0.1:" + getListeningPort();
  }

  @Override
  public Response serve(final IHTTPSession session) {
    lastActivityMs = System.currentTimeMillis();
    final Method method = session.getMethod();
    final boolean headOnly = method == Method.HEAD;
    if (!isOwnAuthority(session.getHeaders().get("host"), getListeningPort())) {
      // Bodiless: a page probing us through a rebound name is told nothing, not even an error text.
      return errorResponse(Response.Status.FORBIDDEN, "", headOnly);
    }
    if (method != Method.GET && !headOnly) {
      return newFixedLengthResponse(Response.Status.METHOD_NOT_ALLOWED, MIME_PLAINTEXT,
          "405 Method Not Allowed");
    }
    final File target = resolveWithinRoot(root, session.getUri());
    if (target == null) {
      return errorResponse(Response.Status.FORBIDDEN, "403 Forbidden", headOnly);
    }
    File file = target;
    if (file.isDirectory()) {
      file = new File(file, "index.html");
    }
    if (!file.exists() || file.isDirectory()) {
      return errorResponse(Response.Status.NOT_FOUND, "404 Not Found", headOnly);
    }
    final Response response = fileResponse(file, headOnly);
    response.setData(new ActiveStream(response.getData()));
    return response;
  }

  /**
   * True if {@code host} is the authority this server itself listens on. A browser fills Host in
   * from the URL and forbids a page to forge it, so refusing every other name is what stops a name
   * the attacker rebound to 127.0.0.1 from reading the mirror. A missing Host is refused too: the
   * only clients are browsers, which always send one.
   *
   * @param host
   *          the request's Host header, or null when it had none
   * @param port
   *          the port this server is listening on
   * @return true if the request addressed us by a loopback name and our own port
   */
  static boolean isOwnAuthority(final String host, final int port) {
    if (host == null) {
      return false;
    }
    final int colon = host.lastIndexOf(':');
    // No port means 80, which is privileged and so never ours.
    if (colon < 0) {
      return false;
    }
    final String name = host.substring(0, colon);
    if (!"127.0.0.1".equals(name) && !"localhost".equalsIgnoreCase(name)) {
      return false;
    }
    return String.valueOf(port).equals(host.substring(colon + 1));
  }

  /**
   * Holds the idle watchdog off while a response is streaming, however slowly the client reads it:
   * a server blocked writing to a browser makes no progress of its own to measure.
   */
  private final class ActiveStream extends FilterInputStream {
    // NanoHTTPD closes the body both after sending it and again with the response.
    private final AtomicBoolean done = new AtomicBoolean();

    ActiveStream(final InputStream data) {
      super(data);
      streaming.incrementAndGet();
    }

    @Override
    public void close() throws IOException {
      try {
        super.close();
      } finally {
        if (done.compareAndSet(false, true)) {
          lastActivityMs = System.currentTimeMillis();
          streaming.decrementAndGet();
        }
      }
    }
  }

  /**
   * Short text error page, bodiless for a HEAD: a body the client is entitled not to read gets
   * parsed as the head of the next response on the connection.
   */
  private static Response errorResponse(final Response.Status status, final String text,
      final boolean headOnly) {
    if (headOnly) {
      return newFixedLengthResponse(status, MIME_PLAINTEXT,
          new ByteArrayInputStream(new byte[0]), text.length());
    }
    return newFixedLengthResponse(status, MIME_PLAINTEXT, text);
  }

  @Override
  protected boolean useGzipWhenAccepted(final Response response) {
    // Gzipping a HEAD emits a compressed empty body carrying neither a length nor chunk framing.
    return response.getRequestMethod() != Method.HEAD && super.useGzipWhenAccepted(response);
  }

  /**
   * Response for {@code file}. A GET is chunked because the engine rewrites index.html in place, so
   * a length measured before the reads can contradict the bytes that go out. A HEAD instead answers
   * the stat'd length and no body, opening nothing: chunking it would advertise the chunked
   * response's own -1 as its Content-Length.
   *
   * @param file
   *          an existing regular file, already confined to the root
   * @param headOnly
   *          true for a HEAD request, answered with headers alone
   * @return a 200 streaming the file, or a 404 if it cannot be opened
   */
  static Response fileResponse(final File file, final boolean headOnly) {
    String mime = getMimeTypeForFile(file.getName());
    if (mime == null) {
      mime = "application/octet-stream";
    }
    if (headOnly) {
      // NanoHTTPD writes a body for HEAD too, so it gets an empty stream to write it from.
      return newFixedLengthResponse(Response.Status.OK, mime,
          new ByteArrayInputStream(new byte[0]), file.length());
    }
    try {
      // NanoHTTPD owns the stream from here, and closes it on every path out of the response.
      return newChunkedResponse(Response.Status.OK, mime, new FileInputStream(file));
    } catch (final IOException e) {
      return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "404 Not Found");
    }
  }

  /**
   * Resolve {@code uriPath} to a file provably inside {@code root}. URL-decodes the path, resolves
   * it against root, then compares canonical paths so "..", absolute injection and escaping
   * symlinks all fold away before the check.
   *
   * @param root
   *          the served tree
   * @param uriPath
   *          the request path, already percent-decoded by NanoHTTPD and possibly hostile
   * @return the confined canonical file, or null if it resolves outside root or cannot be resolved
   */
  static File resolveWithinRoot(final File root, final String uriPath) {
    try {
      // NanoHTTPD already decoded the path; decoding again would corrupt a literal '%' in a name.
      final File target = new File(root, uriPath);
      final String canonicalRoot = root.getCanonicalPath();
      final String canonicalTarget = target.getCanonicalPath();
      // The separator suffix is what stops a sibling like "Websites2" from matching "Websites".
      if (canonicalTarget.equals(canonicalRoot)
          || canonicalTarget.startsWith(canonicalRoot + File.separator)) {
        return new File(canonicalTarget);
      }
      return null;
    } catch (final Exception e) {
      // Any decode/canonicalisation failure on hostile input denies rather than leaks.
      return null;
    }
  }

  /**
   * Root-relative, percent-encoded URL path for {@code file}, or null if {@code file} is not
   * inside {@code root}. Inverse of {@link #resolveWithinRoot}: refusing an out-of-root file is
   * what stops a bogus sliced path (a resources-cache file addressed against the Websites root)
   * from ever reaching the server.
   *
   * @param root
   *          the served tree
   * @param file
   *          the file to link to, expected under root
   * @return the root-relative %-encoded path, or null if file is outside root
   */
  static String relativeUrlPath(final File root, final File file) throws IOException {
    final String rootPath = root.getCanonicalPath();
    final String filePath = file.getCanonicalPath();
    if (!filePath.equals(rootPath)
        && !filePath.startsWith(rootPath + File.separator)) {
      return null;
    }
    String relative = filePath.substring(rootPath.length());
    if (relative.startsWith(File.separator)) {
      relative = relative.substring(1);
    }
    final StringBuilder encoded = new StringBuilder();
    for (final String segment : relative.split("/")) {
      if (segment.length() == 0) {
        continue;
      }
      if (encoded.length() != 0) {
        encoded.append('/');
      }
      encoded.append(URLEncoder.encode(segment, "UTF-8").replace("+", "%20"));
    }
    return encoded.toString();
  }
}
