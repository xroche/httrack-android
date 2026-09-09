package com.httrack.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** LogTail windows a real file on disk, so these tests write real files. */
public class LogTailTest {
  @Rule
  public final TemporaryFolder tmp = new TemporaryFolder();

  private File write(final String content) throws IOException {
    final File file = tmp.newFile();
    Files.write(file.toPath(), content.getBytes(StandardCharsets.UTF_8));
    return file;
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
    final LogTail.Window window = LogTail.read(write(content), Long.MAX_VALUE);
    assertEquals(content, window.text());
    assertEquals(0, window.start());
    assertFalse(window.hasEarlier());
  }

  @Test
  public void anEmptyFileComesBackEmpty() throws IOException {
    final LogTail.Window window = LogTail.read(write(""), Long.MAX_VALUE);
    assertEquals("", window.text());
    assertFalse(window.hasEarlier());
  }

  @Test
  public void aFileLargerThanTheWindowIsCutAtALineBoundary() throws IOException {
    final String content = lines("padding");
    final File file = write(content);
    final LogTail.Window window = LogTail.read(file, Long.MAX_VALUE);

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
    final File file = write(content);
    String seen = "";
    long end = Long.MAX_VALUE;
    LogTail.Window window;
    do {
      window = LogTail.read(file, end);
      seen = window.text() + seen;
      end = window.start();
    } while (window.hasEarlier());
    assertEquals(content, seen);
  }

  /**
   * The euro sign is three bytes, so with a window of 131072 the cut always lands one byte into a
   * character. Reaching the next newline is what gets the decoder back onto a boundary.
   */
  @Test
  public void aCutInsideACharacterStillDecodes() throws IOException {
    final LogTail.Window window = LogTail.read(write(lines("\u20ac\u20ac\u20ac")), Long.MAX_VALUE);
    assertTrue(window.hasEarlier());
    assertTrue(window.text().startsWith("line "));
    assertEquals(-1, window.text().indexOf('\ufffd'));
  }

  /** Without a newline to reach, only the continuation bytes of the split character are dropped. */
  @Test
  public void aWindowWithoutANewlineDropsTheSplitCharacterOnly() throws IOException {
    final StringBuilder text = new StringBuilder();
    while (text.length() < LogTail.WINDOW) {
      text.append('\u20ac');
    }
    final String content = text.toString();
    final LogTail.Window window = LogTail.read(write(content), Long.MAX_VALUE);

    assertEquals(-1, window.text().indexOf('\ufffd'));
    assertTrue(content.endsWith(window.text()));
    assertTrue(window.hasEarlier());
    assertEquals(0, window.start() % 3);
    assertEquals(content.length() - window.start() / 3, window.text().length());
  }

  @Test
  public void anUnreadableFileIsReported() {
    try {
      LogTail.read(new File(tmp.getRoot(), "absent"), Long.MAX_VALUE);
      fail("a missing log must not read as an empty one");
    } catch (final IOException e) {
      // expected
    }
  }
}
