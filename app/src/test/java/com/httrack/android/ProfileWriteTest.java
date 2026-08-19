package com.httrack.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.httrack.android.OptionsMapper.ProfileFormat;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * A crawl writes the profile through the very descriptor it holds the "already
 * in progress" lock on, so the write must neither close it nor append to it.
 */
public class ProfileWriteTest {
  @Rule
  public final TemporaryFolder tmp = new TemporaryFolder();

  private static Map<String, String> lines(final String... pairs) {
    final Map<String, String> lines = new LinkedHashMap<String, String>();
    for (int i = 0; i < pairs.length; i += 2) {
      lines.put(pairs[i], pairs[i + 1]);
    }
    return lines;
  }

  private File profile(final String contents) throws IOException {
    final File file = tmp.newFile("winprofile.ini");
    Files.write(file.toPath(), contents.getBytes("UTF-8"));
    return file;
  }

  private static String read(final File file) throws IOException {
    return new String(Files.readAllBytes(file.toPath()), "UTF-8");
  }

  @Test
  public void keepsTheLockTheCrawlWritesThrough() throws IOException {
    final File file = profile("Category=old\n");
    final FileOutputStream stream = new FileOutputStream(file, true);
    try {
      final FileLock lock = stream.getChannel().tryLock();
      ProfileFormat.write(stream, lines("Category", "new"));
      assertTrue("lock released before the mirror starts", lock.isValid());
      assertTrue(stream.getChannel().isOpen());
    } finally {
      stream.close();
    }
  }

  @Test
  public void replacesWhatTheProfileHeld() throws IOException {
    final File file = profile("Category=old\nProjectName=old\n");
    final FileOutputStream stream = new FileOutputStream(file, true);
    try {
      ProfileFormat.write(stream, lines("Category", "new"));
    } finally {
      stream.close();
    }
    assertEquals("Category=new\n", read(file));
  }

  @Test
  public void writesEveryPairInOrder() throws IOException {
    final File file = profile("");
    final FileOutputStream stream = new FileOutputStream(file, true);
    try {
      ProfileFormat.write(stream, lines("Category", "b", "ProjectName", "a"));
    } finally {
      stream.close();
    }
    assertEquals("Category=b\nProjectName=a\n", read(file));
  }

  /** What the tests above cannot see: a close() around the write. */
  @Test
  public void serializeLeavesTheStreamItIsHandedOpen() throws IOException {
    final String source = TestSources.javaSource("OptionsMapper");
    final String signature =
        "public void serialize(final FileOutputStream fos,";
    final int start = source.indexOf(signature);
    assertTrue(signature, start != -1);
    final int end = source.indexOf("\n  }", start);
    assertTrue(signature, end != -1);
    assertFalse("serialize() closes the stream carrying the crawl's lock",
        source.substring(start, end).contains("close("));
  }
}
