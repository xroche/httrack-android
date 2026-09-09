package com.httrack.android;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;

/**
 * One window of the end of a log file. A crawl log reaches tens of MB, so reading it whole exhausts
 * memory and a view given it all blocks laying it out. WINDOW bounds both. No Android type appears
 * here, so the windowing can be tested.
 */
final class LogTail {
  /** Bytes read per window, and so the most text a view is ever given. */
  static final int WINDOW = 128 * 1024;

  /**
   * Continuation bytes a UTF-8 sequence split by the window start can leave behind. Bytes that are
   * not UTF-8 at all must not cost the window more than that.
   */
  static final int MAX_SPLIT_TAIL = 3;

  private LogTail() {
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
    // FileChannel.open needs File.toPath, which is API 26; getChannel is the one minSdk 24 has.
    final RandomAccessFile rd = new RandomAccessFile(file, "r");
    try {
      return read(rd.getChannel(), end);
    } finally {
      rd.close();
    }
  }

  /** Read one window from {@code source}, which the caller opens and closes. */
  static Window read(final SeekableByteChannel source, final long end) throws IOException {
    final long stop = Math.min(end, source.size());
    if (stop <= 0) {
      return new Window("", 0);
    }
    final long begin = Math.max(0, stop - WINDOW);
    final ByteBuffer buf = ByteBuffer.allocate((int) (stop - begin));
    source.position(begin);
    while (buf.hasRemaining() && source.read(buf) > 0) {
      // A read can come back short, so ask again until the window is full or the file ends.
    }
    final int len = buf.position();
    final int skip = begin != 0 ? boundary(buf.array(), len) : 0;
    return new Window(
        new String(buf.array(), skip, len - skip, StandardCharsets.UTF_8), begin + skip);
  }

  /** Read the end of the log, which is the window a reader opening it wants. */
  static Window readNewest(final File file) throws IOException {
    return read(file, Long.MAX_VALUE);
  }

  /**
   * Where a mid-file window may start, chosen so it never comes back empty. Skip to the first
   * newline, but not one on the buffer's last byte. A UTF-8 sequence never contains a newline, so
   * stopping there also lands on a character boundary. Failing that, skip up to MAX_SPLIT_TAIL
   * continuation bytes, never the whole buffer.
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
