package com.httrack.android;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import org.junit.Test;

/** The engine reads the argv positionally: it counts URLs from the first argument that is not an
 *  option, and -iC* only selects a resume while it precedes them. CrawlArgvGoldenTest holds the
 *  whole line for a real option map; these pin the prelude on its own. */
public class CrawlArgvTest {
  private static final String TARGET = "/storage/emulated/0/HTTrack/demo";

  @Test
  public void theProgramNameComesFirstAndTheOptionsLast() {
    assertArrayEquals(new String[] { "httrack", "-O1", TARGET, "-iC1", "http://example.com/" },
        CrawlArgv.build(true, TARGET, Arrays.asList("-iC1", "http://example.com/")));
  }

  /** -@i4 pins the engine to IPv4, so it must appear exactly when the device has no IPv6. */
  @Test
  public void theFamilyIsForcedOnlyWithoutIPv6() {
    assertArrayEquals(new String[] { "httrack", "-@i4", "-O1", TARGET },
        CrawlArgv.build(false, TARGET, Collections.<String> emptyList()));
    assertArrayEquals(new String[] { "httrack", "-O1", TARGET },
        CrawlArgv.build(true, TARGET, Collections.<String> emptyList()));
  }

  /** An option landing between -O1 and its path would be taken for the mirror directory. */
  @Test
  public void theTargetFollowsItsOwnOption() {
    final String[] argv = CrawlArgv.build(false, TARGET, Arrays.asList("http://example.com/"));
    assertArrayEquals(new String[] { "httrack", "-@i4", "-O1", TARGET, "http://example.com/" },
        argv);
  }

  /** A comma is a legal project name, so the target must ride -O1, which never splits it. */
  @Test
  public void theTargetUsesTheFlagThatDoesNotSplitOnCommas() {
    final String comma = "/storage/emulated/0/HTTrack/Smith, Jones & Co";
    assertArrayEquals(new String[] { "httrack", "-O1", comma },
        CrawlArgv.build(true, comma, Collections.<String> emptyList()));
    assertTrue("a comma in the project name must stay a legal name",
        StoragePaths.isValidProjectName("Smith, Jones & Co"));
  }

  /**
   * The engine takes a password in a URL and in the proxy address, both free-text fields, and -%X
   * carries a whole header line. Each row is one thing the log must not leak.
   */
  @Test
  public void theRedactedArgvHidesCredentials() {
    assertRedacted("https://***@example.com/", "https://bob:s3cret@example.com/");
    assertRedacted("http://***@example.com/", "http://:pw@example.com/");
    // The engine ends the userinfo at the LAST '@', so a password may contain one.
    assertRedacted("http://***@example.com/", "http://bob:p@ss@example.com/");
    // Two userinfo sites in one argument: redacting only the first would leak the second.
    assertRedacted("http://***@x/?u=http://***@y", "http://a:b@x/?u=http://c:d@y");
    assertRedacted("-P socks5://***@proxy.lan:1080", "-P", "socks5://user:pw@proxy.lan:1080");
    assertRedacted("-P connect://***@proxy.lan:8080", "-P", "connect://user:pw@proxy.lan:8080");
    assertRedacted("-%X ***", "-%X", "Authorization: Bearer eyJsecret");
  }

  /** Blanking must not touch an argv that carries none, or the log stops being readable. */
  @Test
  public void theRedactedArgvLeavesACredentialFreeLineAlone() {
    assertRedacted("httrack -@i4 -O1 " + TARGET + " -P proxy.lan:8080 http://example.com/ -F me",
        CrawlArgv.build(false, TARGET, Arrays.asList("-P", "proxy.lan:8080",
            "http://example.com/", "-F", "me")));
    // An '@' in the mirror path is not userinfo, and -O1 says the next argument is that path.
    assertRedacted("-O1 /sdcard/HTTrack/a@b", "-O1", "/sdcard/HTTrack/a@b");
    assertRedacted("http://[2001:db8::1]:8080/", "http://[2001:db8::1]:8080/");
  }

  /** Over-redaction is the safe direction, so record where it costs log detail. */
  @Test
  public void theRedactedArgvBlanksUserinfoShapesThatCarryNoPassword() {
    assertRedacted("http://***@example.com/", "http://bob@example.com/");
    assertRedacted("http://example.com/***@v", "http://example.com/a?x=u:p@v");
  }

  /** A revert to the raw join would put the credentials back in logcat. */
  @Test
  public void theRunLogsTheRedactedArgv() throws IOException {
    final String source = TestSources.withoutCommentsAndStrings(
        TestSources.javaSource("CrawlRun")).replaceAll("\\s+", " ");
    assertTrue("the engine argv must be logged through CrawlArgv.redacted",
        source.contains("CrawlArgv.redacted(cargs)"));
    assertFalse("printArray would log the credentials verbatim",
        source.contains("printArray(cargs)"));
  }

  private static void assertRedacted(final String expected, final String... argv) {
    assertEquals(expected, CrawlArgv.redacted(argv));
  }

  /** The device answer has to reach the build, or every phone gets one family's argv. */
  @Test
  public void theRunAsksTheDeviceForItsAnswer() throws IOException {
    final String source = TestSources.withoutCommentsAndStrings(
        TestSources.javaSource("CrawlRun"));
    assertTrue("the argv must be built from the device's own IPv6 answer and the run's target",
        source.replaceAll("\\s+", " ").contains("CrawlArgv.build(HTTrackActivity.isIPv6Enabled(), "
            + "target.getAbsolutePath(), options)"));
  }
}
