package com.httrack.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** LogTail windows a real file on disk, so these tests write real files. */
public class LogTailTest {
  /** Bytes in the UTF-8 euro sign, the character these fixtures let the window cut through. */
  private static final int EURO_BYTES = 3;

  @Rule
  public final TemporaryFolder tmp = new TemporaryFolder();

  private File write(final String content) throws IOException {
    return writeBytes(content.getBytes(StandardCharsets.UTF_8));
  }

  private File writeBytes(final byte[] content) throws IOException {
    final File file = tmp.newFile();
    Files.write(file.toPath(), content);
    return file;
  }

  private static boolean isContinuation(final byte b) {
    return (b & 0xc0) == 0x80;
  }

  /** Refuse a fixture whose window does not start inside a character, which would prove nothing. */
  private static void assertCutsACharacter(final byte[] content) {
    assertTrue("the window starts on a character boundary",
        isContinuation(content[content.length - LogTail.WINDOW]));
  }

  /** Walk back to the file start and return everything seen, refusing a step that does not move. */
  private static String walkBack(final File file) throws IOException {
    LogTail.Window window = LogTail.readNewest(file);
    String seen = window.text();
    while (window.hasEarlier()) {
      final long end = window.start();
      window = LogTail.read(file, end);
      assertTrue("stuck at offset " + window.start(), window.start() < end);
      seen = window.text() + seen;
    }
    return seen;
  }

  /** Enough lines to overflow the window several times over. */
  private static String lines(final String filler) {
    final StringBuilder text = new StringBuilder();
    for (int i = 0; text.length() < 3 * LogTail.WINDOW; i++) {
      text.append("line ").append(i).append(' ').append(filler).append('\n');
    }
    return text.toString();
  }

  @Test
  public void aFileSmallerThanTheWindowComesBackWhole() throws IOException {
    final String content = "first line\nsecond line\n";
    final LogTail.Window window = LogTail.readNewest(write(content));
    assertEquals(content, window.text());
    assertEquals(0, window.start());
    assertFalse(window.hasEarlier());
  }

  @Test
  public void anEmptyFileComesBackEmpty() throws IOException {
    final LogTail.Window window = LogTail.readNewest(write(""));
    assertEquals("", window.text());
    assertFalse(window.hasEarlier());
  }

  @Test
  public void aFileLargerThanTheWindowIsCutAtALineBoundary() throws IOException {
    final String content = lines("padding");
    final File file = write(content);
    final LogTail.Window window = LogTail.readNewest(file);

    assertTrue(window.hasEarlier());
    assertTrue(window.text().length() < content.length());
    assertTrue(window.text().getBytes(StandardCharsets.UTF_8).length <= LogTail.WINDOW);
    assertTrue(content.endsWith(window.text()));
    assertTrue(window.text().startsWith("line "));
    assertEquals('\n', Files.readAllBytes(file.toPath())[(int) window.start() - 1]);
  }

  /** Walking back must neither repeat a line nor drop one, so the windows have to join up. */
  @Test
  public void earlierWindowsJoinBackIntoTheWholeFile() throws IOException {
    final String content = lines("padding");
    assertEquals(content, walkBack(write(content)));
  }

  /**
   * The window then ends on the file's only newline, so dropping the partial line would drop
   * everything and leave the reader an empty dialog it could never step out of.
   */
  @Test
  public void aFileOneByteOverTheWindowIsNotEmpty() throws IOException {
    final byte[] bytes = new byte[LogTail.WINDOW + 1];
    Arrays.fill(bytes, (byte) 'a');
    bytes[LogTail.WINDOW] = '\n';
    final File file = writeBytes(bytes);

    assertFalse(LogTail.readNewest(file).text().isEmpty());
    assertEquals(new String(bytes, StandardCharsets.UTF_8), walkBack(file));
  }

  /** A line longer than the window offers no newline to stop at, so walking back must not need one. */
  @Test
  public void aLineLongerThanTheWindowIsStillWalkedPast() throws IOException {
    final StringBuilder text = new StringBuilder("start\n");
    while (text.length() < 2 * LogTail.WINDOW) {
      text.append('a');
    }
    final String content = text.append("\nend\n").toString();
    final File file = write(content);

    assertEquals("end\n", LogTail.readNewest(file).text());
    assertEquals(content, walkBack(file));
  }

  /** Bytes that are not UTF-8 at all must not all read as the tail of one split character. */
  @Test
  public void aWindowOfContinuationBytesIsNotSkippedWhole() throws IOException {
    final byte[] bytes = new byte[LogTail.WINDOW + 1];
    Arrays.fill(bytes, (byte) 0x80);
    final File file = writeBytes(bytes);

    assertFalse(LogTail.readNewest(file).text().isEmpty());
    assertFalse(walkBack(file).isEmpty());
  }

  /**
   * A run of euro signs long enough to hold the whole window, framed by one-byte text, so the cut
   * cannot land anywhere but inside a character. Reaching the next newline is what puts the decoder
   * back onto a boundary.
   */
  @Test
  public void aCutInsideACharacterStillDecodes() throws IOException {
    final StringBuilder text = new StringBuilder("head line\n");
    while (text.length() * EURO_BYTES < 2 * LogTail.WINDOW) {
      text.append('\u20ac');
    }
    final String content = text.append("\nend line\n").toString();
    final File file = write(content);
    assertCutsACharacter(Files.readAllBytes(file.toPath()));

    final LogTail.Window window = LogTail.readNewest(file);
    assertTrue(window.hasEarlier());
    assertEquals("end line\n", window.text());
    assertEquals(-1, window.text().indexOf('\ufffd'));
    assertEquals(content, walkBack(file));
  }

  /** Without a newline to reach, only the continuation bytes of the split character are dropped. */
  @Test
  public void aWindowWithoutANewlineDropsTheSplitCharacterOnly() throws IOException {
    final StringBuilder text = new StringBuilder();
    while (text.length() * EURO_BYTES < 2 * LogTail.WINDOW) {
      text.append('\u20ac');
    }
    final String content = text.toString();
    final File file = write(content);
    assertCutsACharacter(Files.readAllBytes(file.toPath()));

    final LogTail.Window window = LogTail.readNewest(file);
    assertEquals(-1, window.text().indexOf('\ufffd'));
    assertTrue(content.endsWith(window.text()));
    assertTrue(window.hasEarlier());
    assertEquals(0, window.start() % EURO_BYTES);
    assertEquals(content.length() - window.start() / EURO_BYTES, window.text().length());
  }

  /** One byte per read, which is the case the fill loop exists for. */
  private static final class DribblingSource implements LogTail.Source {
    private final byte[] bytes;
    private int position;

    DribblingSource(final byte[] bytes) {
      this.bytes = bytes;
    }

    @Override
    public long length() {
      return bytes.length;
    }

    @Override
    public void seek(final long start) {
      position = (int) start;
    }

    @Override
    public int read(final byte[] buf, final int offset, final int count) {
      if (count == 0 || position >= bytes.length) {
        return -1;
      }
      buf[offset] = bytes[position++];
      return 1;
    }
  }

  @Test
  public void aShortReadStillFillsTheWindow() throws IOException {
    final byte[] bytes = "first line\nsecond line\n".getBytes(StandardCharsets.UTF_8);
    final LogTail.Window window = LogTail.read(new DribblingSource(bytes), bytes.length);
    assertEquals(new String(bytes, StandardCharsets.UTF_8), window.text());
  }

  @Test
  public void anUnreadableFileIsReported() {
    try {
      LogTail.readNewest(new File(tmp.getRoot(), "absent"));
      fail("a missing log must not read as an empty one");
    } catch (final IOException e) {
      // expected
    }
  }
}
