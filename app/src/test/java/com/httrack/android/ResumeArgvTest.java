package com.httrack.android;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import org.junit.Test;

/** The argv a rescheduled crawl runs. A wrong answer here downloads the whole mirror again. */
public class ResumeArgvTest {
  private static final String URL = "http://example.com/";
  private static final String OTHER = "http://other.example.com/";

  private static String[] retry(final String... argv) {
    return ResumeArgv.forStart(argv, true);
  }

  private static String[] firstAttempt(final String... argv) {
    return ResumeArgv.forStart(argv, false);
  }

  private static int indexOf(final String[] argv, final String token) {
    for (int i = 0; i < argv.length; i++) {
      if (token.equals(argv[i])) {
        return i;
      }
    }
    return -1;
  }

  /**
   * The engine's own rule, from cmdl_opt() in htscoremain.c: a token is read as a URL unless it
   * opens with '-' and holds none of '.', '/' or '*', a '%' excusing the dot. -O takes its path as
   * a separate argument, which the pre-pass marks as a parameter rather than counting.
   */
  private static int firstUrlIndex(final String[] argv) {
    for (int i = 1; i < argv.length; i++) {
      if ("-O".equals(argv[i])) {
        i++;
        continue;
      }
      final String token = argv[i];
      if (token.isEmpty() || token.charAt(0) != '-'
          || (token.indexOf('.') != -1 && token.indexOf('%') == -1) || token.indexOf('/') != -1
          || token.indexOf('*') != -1) {
        return i;
      }
    }
    return -1;
  }

  /** Both operands first: an absent token and an absent URL would compare as a pass. */
  private static void assertAheadOfEveryUrl(final String[] argv) {
    final String shown = Arrays.toString(argv);
    final int forced = indexOf(argv, ResumeArgv.CONTINUE);
    final int url = firstUrlIndex(argv);
    assertTrue("no " + ResumeArgv.CONTINUE + " in " + shown, forced >= 0);
    assertTrue("no URL in " + shown, url >= 0);
    assertTrue(ResumeArgv.CONTINUE + " at " + forced + " but a URL at " + url + " in " + shown,
        forced < url);
    assertEquals("one continue token in " + shown, 1, count(argv));
  }

  private static int count(final String[] argv) {
    int found = 0;
    for (final String token : argv) {
      if (ResumeArgv.CONTINUE.equals(token)) {
        found++;
      }
    }
    return found;
  }

  @Test
  public void noActionTokenGainsOne() {
    final String[] out = retry("httrack", "-O", "/t", URL, "-r3");
    assertArrayEquals(new String[] { "httrack", "-iC1", "-O", "/t", URL, "-r3" }, out);
    assertAheadOfEveryUrl(out);
  }

  @Test
  public void continueIsLeftAsTheModeItAlreadyAsksFor() {
    final String[] out = retry("httrack", "-O", "/t", "-iC1", URL);
    assertArrayEquals(new String[] { "httrack", "-iC1", "-O", "/t", URL }, out);
    assertAheadOfEveryUrl(out);
  }

  @Test
  public void updateBecomesContinue() {
    final String[] out = retry("httrack", "-O", "/t", "-iC2", URL);
    assertArrayEquals(new String[] { "httrack", "-iC1", "-O", "/t", URL }, out);
    assertAheadOfEveryUrl(out);
  }

  @Test
  public void anUntickedCacheIsDropped() {
    final String[] out = retry("httrack", "-O", "/t", URL, "-C0", "-%P");
    assertArrayEquals(new String[] { "httrack", "-iC1", "-O", "/t", URL, "-%P" }, out);
    assertAheadOfEveryUrl(out);
  }

  @Test
  public void updateAndAnUntickedCacheBothGo() {
    final String[] out = retry("httrack", "-O", "/t", "-iC2", URL, "-C0");
    assertArrayEquals(new String[] { "httrack", "-iC1", "-O", "/t", URL }, out);
    assertAheadOfEveryUrl(out);
  }

  /** An -iC* past the first URL makes the engine load hts-cache/doit.log over the command line,
   *  so moving it is what rewriting in place cannot do. */
  @Test
  public void tokensPastTheUrlComeBackAheadOfIt() {
    final String[] out = retry("httrack", "-O", "/t", URL, "-iC2", "-C0");
    assertArrayEquals(new String[] { "httrack", "-iC1", "-O", "/t", URL }, out);
    assertAheadOfEveryUrl(out);
  }

  @Test
  public void severalUrlsKeepTheirOrderBehindTheToken() {
    final String[] out = retry("httrack", "-@i4", "-O", "/t", "-iC2", URL, OTHER, "+*.png");
    assertArrayEquals(
        new String[] { "httrack", "-iC1", "-@i4", "-O", "/t", URL, OTHER, "+*.png" }, out);
    assertAheadOfEveryUrl(out);
  }

  /** Two action tokens is what a hand-edited profile plus a rewrite would leave. */
  @Test
  public void twoActionTokensCollapseToOne() {
    final String[] out = retry("httrack", "-iC2", "-O", "/t", "-iC1", URL);
    assertArrayEquals(new String[] { "httrack", "-iC1", "-O", "/t", URL }, out);
    assertAheadOfEveryUrl(out);
  }

  /** Index 1 is after the program name, and an argv without one has no crawl to resume. */
  @Test
  public void anEmptyArgvIsLeftEmpty() {
    assertArrayEquals(new String[] {}, retry());
    assertNull(ResumeArgv.forStart(null, true));
  }

  @Test
  public void anArgvWithNoUrlStillGainsTheToken() {
    assertArrayEquals(new String[] { "httrack", "-iC1", "-O", "/t" }, retry("httrack", "-O", "/t"));
  }

  /** A first attempt is the start the user asked for, so their Update and their unticked cache
   *  both reach the engine. */
  @Test
  public void aFirstAttemptPassesThrough() {
    final String[] argv = { "httrack", "-O", "/t", "-iC2", URL, "-C0" };
    assertArrayEquals(argv, firstAttempt(argv));
    assertArrayEquals(new String[] { "httrack", "-O", "/t", URL },
        firstAttempt("httrack", "-O", "/t", URL));
    assertArrayEquals(new String[] {}, firstAttempt());
  }

  /** -C alone is already HTS_CACHE_PRIORITY, and -Cx is not the cache option at all. */
  @Test
  public void onlyACacheModeIsDropped() {
    assertArrayEquals(new String[] { "httrack", "-iC1", "-C", "-Cx", "-iCx", URL },
        retry("httrack", "-C", "-Cx", "-iCx", URL));
  }

  /** What the activity actually hands the engine, rather than a shape written for this test. */
  @Test
  public void theBuiltCommandlineResumesToo() {
    final String[] built = CrawlArgv.build(false, "/mirrors/p",
        Arrays.asList("-iC2", URL, "-C0", "-r9"));
    assertArrayEquals(new String[] { "httrack", "-@i4", "-O", "/mirrors/p", "-iC2", URL, "-C0",
        "-r9" }, built);
    final String[] out = ResumeArgv.forStart(built, true);
    assertArrayEquals(
        new String[] { "httrack", "-iC1", "-@i4", "-O", "/mirrors/p", URL, "-r9" }, out);
    assertAheadOfEveryUrl(out);
  }

  /** No caller emits one today, because CrawlArgv.build always writes the program name first.
   *  The insert still has to be total: index 1 of an argv the filter emptied does not exist. */
  @Test
  public void anArgvOfNothingButDroppableTokensStillResumes() {
    assertArrayEquals(new String[] { ResumeArgv.CONTINUE }, retry("-C0"));
    assertArrayEquals(new String[] { ResumeArgv.CONTINUE }, retry("-iC2", "-C0"));
    assertArrayEquals(new String[] { "httrack", ResumeArgv.CONTINUE }, retry("httrack", "-C0"));
  }

  /** The oracle has to fire on the shape it exists to reject, or every row above passes vacuously
   *  whatever forStart returns. */
  @Test
  public void theOracleRejectsATokenPastTheUrl() {
    final String[] wrong = { "httrack", "-O", "/t", URL, "-iC1" };
    assertEquals("the oracle must skip the -O path it would otherwise read as a URL", 3,
        firstUrlIndex(wrong));
    assertEquals(3, indexOf(wrong, URL));
    assertEquals(4, indexOf(wrong, ResumeArgv.CONTINUE));
    boolean fired = false;
    try {
      assertAheadOfEveryUrl(wrong);
    } catch (final AssertionError caught) {
      fired = true;
    }
    assertTrue("the oracle accepted an -iC1 sitting past the URL", fired);
  }
}
