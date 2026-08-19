package com.httrack.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.httrack.android.OptionsMapper.ProfileFormat;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * A crawl writes the profile through the very descriptor it holds the "already
 * in progress" lock on, so the write must neither close it nor grow the file.
 */
public class ProfileWriteTest {
  @Rule
  public final TemporaryFolder tmp = new TemporaryFolder();

  private static Map<String, String> lines(final String... keysAndValues) {
    final Map<String, String> lines = new LinkedHashMap<String, String>();
    for (int i = 0; i < keysAndValues.length; i += 2) {
      lines.put(keysAndValues[i], keysAndValues[i + 1]);
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

  /** As the crawl opens it: creates the file, and never empties it. */
  private static RandomAccessFile open(final File file) throws IOException {
    return new RandomAccessFile(file, "rw");
  }

  @Test
  public void keepsTheLockTheCrawlWritesThrough() throws IOException {
    final File file = profile("Category=old\n");
    final RandomAccessFile handle = open(file);
    try {
      final FileLock lock = handle.getChannel().tryLock();
      assertNotNull("nobody else holds this profile", lock);
      ProfileFormat.write(handle.getChannel(), lines("Category", "new"));
      assertTrue("lock released before the mirror starts", lock.isValid());
    } finally {
      handle.close();
    }
    assertEquals("Category=new\n", read(file));
  }

  @Test
  public void replacesWhatTheProfileHeld() throws IOException {
    final File file = profile("Category=old\nProjectName=old\n");
    final RandomAccessFile handle = open(file);
    try {
      ProfileFormat.write(handle.getChannel(), lines("Category", "new"));
    } finally {
      handle.close();
    }
    assertEquals("Category=new\n", read(file));
  }

  /** ProjectName before Category: sorting the keys would swap the two lines. */
  @Test
  public void writesEveryPairInTheOrderGiven() throws IOException {
    final File file = profile("");
    final RandomAccessFile handle = open(file);
    try {
      ProfileFormat.write(handle.getChannel(),
          lines("ProjectName", "a", "Category", "b"));
    } finally {
      handle.close();
    }
    assertEquals("ProjectName=a\nCategory=b\n", read(file));
  }

  /* Source of the OptionsMapper method declared as SIGNATURE, up to the first
     line closing at INDENT. */
  private static String methodBody(final String signature, final String indent)
      throws IOException {
    final String source = TestSources.javaSource("OptionsMapper");
    final int start = source.indexOf(signature);
    assertTrue(signature, start != -1);
    final int end = source.indexOf("\n" + indent + "}", start);
    assertTrue(signature, end != -1);
    return source.substring(start, end);
  }

  /** What the tests above cannot see: a close() around the write. */
  @Test
  public void serializeLeavesTheChannelItIsHandedOpen() throws IOException {
    final String body = methodBody(
        "public void serialize(final FileChannel channel,", "  ");
    // A region that stopped covering the write would pass the check below.
    assertTrue("body does not reach the write",
        body.contains("ProfileFormat.write(channel"));
    assertFalse("serialize() closes the channel carrying the crawl's lock",
        body.contains("close(") || body.contains("try ("));
  }

  /**
   * Trimming after the write has no runtime tell, and getting it backwards
   * empties the only copy of the settings until the write lands.
   */
  @Test
  public void trimsTheProfileOnlyAfterWritingIt() throws IOException {
    final String body = methodBody(
        "static void write(final FileChannel channel,", "    ");
    assertTrue("body does not reach the write",
        body.contains("channel.write("));
    assertTrue("the profile is emptied before it is written",
        body.indexOf("channel.truncate(") > body.indexOf("channel.write("));
  }
}
