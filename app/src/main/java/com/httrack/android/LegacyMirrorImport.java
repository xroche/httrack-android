package com.httrack.android;

import android.content.ContentResolver;
import android.net.Uri;

import androidx.documentfile.provider.DocumentFile;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * One-time copy of mirrors from a user-picked legacy location into the app-specific directory.
 *
 * Scoped storage put the old public HTTrack/Websites tree out of reach, so the user grants it
 * once through the storage-access framework and its contents are copied here. The copy never
 * touches the source, so a failed or interrupted run loses nothing and can simply be re-run:
 * existing destination files are skipped, which also makes it idempotent.
 */
final class LegacyMirrorImport {
  private static final int BUFFER = 64 * 1024;

  // A tree nested deeper than this is refused rather than recursing until the stack overflows.
  static final int MAX_DEPTH = 100;

  private LegacyMirrorImport() {
  }

  /** A node of the tree being copied, so the walk can be tested without a real DocumentFile. */
  interface Source {
    String getName();

    boolean isDirectory();

    long length();

    List<Source> children();

    InputStream openStream() throws IOException;
  }

  // Cap the retained messages: a broken tree could otherwise produce one per file.
  private static final int MAX_ERRORS = 20;

  /** Tally of a copy run; a per-file failure is recorded, not thrown, so the rest still copies. */
  static final class Result {
    int copied;
    int skipped;
    int failed;
    // Set by the caller when the tree does not fit; nothing is copied in that case.
    boolean outOfSpace;
    final List<String> errors = new ArrayList<String>();

    boolean isComplete() {
      return failed == 0 && !outOfSpace;
    }

    String firstError() {
      return errors.isEmpty() ? null : errors.get(0);
    }
  }

  /**
   * Copy {@code src}'s contents into {@code destDir}, recursively. Files already present at the
   * destination are left as they are. Never modifies the source.
   *
   * @return the tally; {@link Result#isComplete()} is false if any file could not be copied
   */
  static Result copyTree(final Source src, final File destDir) {
    final Result result = new Result();
    copyInto(src, destDir, result);
    return result;
  }

  private static void copyInto(final Source dir, final File destDir, final Result result) {
    copyInto(dir, destDir, result, MAX_DEPTH);
  }

  private static void copyInto(final Source dir, final File destDir, final Result result,
      final int depthLeft) {
    if (depthLeft <= 0) {
      record(result, "tree too deep at " + destDir);
      return;
    }
    if (!destDir.exists() && !destDir.mkdirs()) {
      record(result, "could not create " + destDir);
      return;
    }
    for (final Source child : dir.children()) {
      final String name = child.getName();
      if (name == null || name.isEmpty() || name.equals(".") || name.equals("..")
          || name.indexOf('/') >= 0) {
        record(result, "unsafe entry name '" + name + "'");
        continue;
      }
      final File dest = new File(destDir, name);
      if (child.isDirectory()) {
        copyInto(child, dest, result, depthLeft - 1);
      } else if (dest.isDirectory()) {
        // A directory already sits where this file would go; skipping would drop it silently.
        record(result, "name clash at " + dest);
      } else if (dest.exists()) {
        result.skipped++;
      } else {
        copyFile(child, dest, result);
      }
    }
  }

  private static void copyFile(final Source src, final File dest, final Result result) {
    // A partial file from an interrupted run must not look complete on the next pass; write to a
    // temp sibling and rename only once whole, so only finished files ever satisfy dest.exists().
    final File tmp = new File(dest.getParentFile(), dest.getName() + ".part");
    try (final InputStream in = src.openStream();
        final OutputStream out = new FileOutputStream(tmp)) {
      final byte[] buffer = new byte[BUFFER];
      int n;
      while ((n = in.read(buffer)) != -1) {
        out.write(buffer, 0, n);
      }
    } catch (final IOException e) {
      tmp.delete();
      record(result, "could not copy " + src.getName() + ": " + e.getMessage());
      return;
    }
    if (tmp.renameTo(dest)) {
      result.copied++;
    } else {
      tmp.delete();
      record(result, "could not finalize " + dest);
    }
  }

  private static void record(final Result result, final String message) {
    result.failed++;
    if (result.errors.size() < MAX_ERRORS) {
      result.errors.add(message);
    }
  }

  /** Bytes the tree holds, for a free-space check before starting. Skipped files still count. */
  static long totalSize(final Source src) {
    return totalSize(src, MAX_DEPTH);
  }

  // Long.MAX_VALUE for a tree deeper than the cap so the caller's space check refuses it,
  // rather than recursing until the stack overflows.
  private static long totalSize(final Source src, final int depthLeft) {
    if (!src.isDirectory()) {
      return src.length();
    }
    if (depthLeft <= 0) {
      return Long.MAX_VALUE;
    }
    long total = 0;
    for (final Source child : src.children()) {
      final long childSize = totalSize(child, depthLeft - 1);
      if (childSize == Long.MAX_VALUE) {
        return Long.MAX_VALUE;
      }
      total += childSize;
    }
    return total;
  }

  /** A DocumentFile-backed source; the adapter the app uses over a picked tree. */
  static final class DocumentFileSource implements Source {
    private final ContentResolver resolver;
    private final DocumentFile file;

    DocumentFileSource(final ContentResolver resolver, final DocumentFile file) {
      this.resolver = resolver;
      this.file = file;
    }

    @Override
    public String getName() {
      return file.getName();
    }

    @Override
    public boolean isDirectory() {
      return file.isDirectory();
    }

    @Override
    public long length() {
      return file.length();
    }

    @Override
    public List<Source> children() {
      final List<Source> out = new ArrayList<Source>();
      for (final DocumentFile child : file.listFiles()) {
        out.add(new DocumentFileSource(resolver, child));
      }
      return out;
    }

    @Override
    public InputStream openStream() throws IOException {
      final Uri uri = file.getUri();
      final InputStream in = resolver.openInputStream(uri);
      if (in == null) {
        throw new IOException("no stream for " + uri);
      }
      return in;
    }
  }
}
