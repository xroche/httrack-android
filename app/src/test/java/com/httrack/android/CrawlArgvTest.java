package com.httrack.android;

import static org.junit.Assert.assertArrayEquals;
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
    assertArrayEquals(new String[] { "httrack", "-O", TARGET, "-iC1", "http://example.com/" },
        CrawlArgv.build(true, TARGET, Arrays.asList("-iC1", "http://example.com/")));
  }

  /** -@i4 pins the engine to IPv4, so it must appear exactly when the device has no IPv6. */
  @Test
  public void theFamilyIsForcedOnlyWithoutIPv6() {
    assertArrayEquals(new String[] { "httrack", "-@i4", "-O", TARGET },
        CrawlArgv.build(false, TARGET, Collections.<String> emptyList()));
    assertArrayEquals(new String[] { "httrack", "-O", TARGET },
        CrawlArgv.build(true, TARGET, Collections.<String> emptyList()));
  }

  /** An option landing between -O and its path would be taken for the mirror directory. */
  @Test
  public void theTargetFollowsItsOwnOption() {
    final String[] argv = CrawlArgv.build(false, TARGET, Arrays.asList("http://example.com/"));
    assertArrayEquals(new String[] { "httrack", "-@i4", "-O", TARGET, "http://example.com/" },
        argv);
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
