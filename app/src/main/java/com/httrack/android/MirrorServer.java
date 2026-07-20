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

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;

import fi.iki.elonen.NanoHTTPD;

/**
 * Loopback-only HTTP server exposing a HTTrack mirror to an external browser. Android 11+ denies a
 * browser file:// access to our scoped storage, so the mirror is served over http://127.0.0.1 and
 * read as an ordinary web site. Filenames come from crawled URLs, so path resolution is hostile
 * input and lives in the unit-tested {@link #resolveWithinRoot(File, String)}.
 */
final class MirrorServer extends NanoHTTPD {
  // Ports tried in order; mirrors the engine's htscatchurl.c try_to_listen_to[]. 0 = OS-assigned
  // ephemeral fallback. Privileged (<1024) ports are omitted: Android forbids binding them.
  private static final int[] PORTS = { 8080, 3128, 8081, 3129, 0 };

  private final File root;

  private MirrorServer(final File root, final int port) {
    super("127.0.0.1", port);
    this.root = root;
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
    IOException last = null;
    for (final int port : PORTS) {
      final MirrorServer server = new MirrorServer(root, port);
      try {
        server.start(NanoHTTPD.SOCKET_READ_TIMEOUT, true);
        return server;
      } catch (final IOException e) {
        last = e;
      }
    }
    throw last != null ? last : new IOException("no loopback port available");
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
    final Method method = session.getMethod();
    if (method != Method.GET && method != Method.HEAD) {
      return newFixedLengthResponse(Response.Status.METHOD_NOT_ALLOWED, MIME_PLAINTEXT,
          "405 Method Not Allowed");
    }
    final File target = resolveWithinRoot(root, session.getUri());
    if (target == null) {
      return newFixedLengthResponse(Response.Status.FORBIDDEN, MIME_PLAINTEXT, "403 Forbidden");
    }
    File file = target;
    if (file.isDirectory()) {
      file = new File(file, "index.html");
    }
    if (!file.exists() || file.isDirectory()) {
      return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "404 Not Found");
    }
    try {
      String mime = getMimeTypeForFile(file.getName());
      if (mime == null) {
        mime = "application/octet-stream";
      }
      return newFixedLengthResponse(Response.Status.OK, mime, new FileInputStream(file),
          file.length());
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
}
