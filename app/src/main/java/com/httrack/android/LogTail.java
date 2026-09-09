package com.httrack.android;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;

/**
 * One window of the end of a log file. A crawl log reaches tens of MB, so reading it whole exhausts
 * memory and a view given it all blocks laying it out; WINDOW bounds both. No Android type appears
 * here, so the windowing can be tested.
 */
final class LogTail {
  /** Bytes read per window, and so the most text a view is ever given. */
  static final int WINDOW = 128 * 1024;

  /** Continuation bytes a UTF-8 sequence split by the window start can leave behind. */
  private static final int MAX_SPLIT_TAIL = 3;

  private LogTail() {
  }

  /** The file operations a window needs, so a test can make a read come back short. */
  interface Source {
    long length() throws IOException;

    void seek(long position) throws IOException;

    int read(byte[] buf, int offset, int count) throws IOException;
  }

  private static final class FileSource implements Source {
    private final RandomAccessFile file;

    FileSource(final RandomAccessFile file) {
      this.file = file;
    }

    @Override
    public long length() throws IOException {
      return file.length();
    }

    @Override
    public void seek(final long position) throws IOException {
      file.seek(position);
    }

    @Override
    public int read(final byte[] buf, final int offset, final int count) throws IOException {
      return file.read(buf, offset, count);
    }
  }

  /** A window of log text, and where it begins so the caller can ask for the one before it. */
  static final class Window {
    private final String text;
    private final long start;

    Window(final String text, final long start) {
      this.text = text;
      this.start = start;
    }

    String text() {
      return text;
    }

    /** Byte offset of the first character returned; pass it back to read() to walk back. */
    long start() {
      return start;
    }

    /** Is there anything before this window? */
    boolean hasEarlier() {
      return start > 0;
    }
  }

  /**
   * Read at most WINDOW bytes ending at {@code end}. A character that {@code end} splits decodes to
   * U+FFFD, which is what a log the engine is still writing to can give.
   *
   * @param file the log file
   * @param end  byte offset to read back from, exclusive
   * @return the window, beginning on a whole line where one starts inside it
   */
  static Window read(final File file, final long end) throws IOException {
    final RandomAccessFile rd = new RandomAccessFile(file, "r");
    try {
      return read(new FileSource(rd), end);
    } finally {
      rd.close();
    }
  }

  /** Read one window from {@code source}, which the caller opens and closes. */
  static Window read(final Source source, final long end) throws IOException {
    final long stop = Math.min(end, source.length());
    if (stop <= 0) {
      return new Window("", 0);
    }
    final long begin = Math.max(0, stop - WINDOW);
    final byte[] buf = new byte[(int) (stop - begin)];
    source.seek(begin);
    int len = 0;
    while (len < buf.length) {
      final int count = source.read(buf, len, buf.length - len);
      if (count <= 0) {
        break;
      }
      len += count;
    }
    final int skip = begin != 0 ? boundary(buf, len) : 0;
    return new Window(new String(buf, skip, len - skip, StandardCharsets.UTF_8), begin + skip);
  }

  /** Read the end of the log, which is the window a reader opening it wants. */
  static Window readNewest(final File file) throws IOException {
    return read(file, Long.MAX_VALUE);
  }

  /**
   * Where a window that starts mid-file becomes readable. Past its first newline, which drops the
   * half line and lands on a character boundary too, because a newline byte never occurs inside a
   * UTF-8 sequence; with no newline to reach, past the continuation bytes instead. Never past the
   * whole buffer, or a line longer than the window would give an empty window forever.
   */
  private static int boundary(final byte[] buf, final int len) {
    for (int i = 0; i + 1 < len; i++) {
      if (buf[i] == '\n') {
        return i + 1;
      }
    }
    int i = 0;
    while (i + 1 < len && i < MAX_SPLIT_TAIL && (buf[i] & 0xc0) == 0x80) {
      i++;
    }
    return i;
  }
}
