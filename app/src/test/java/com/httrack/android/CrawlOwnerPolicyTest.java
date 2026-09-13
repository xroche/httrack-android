package com.httrack.android;

import static org.junit.Assert.assertEquals;

import java.io.IOException;
import org.junit.Test;

/** Who drives the crawl, and the wiring that asks. */
public class CrawlOwnerPolicyTest {
  private static String norm(final String text) {
    return text.replaceAll("\\s+", " ").trim();
  }

  @Test
  public void onlyThirtyFourAndAboveOwnsInAJob() {
    final StringBuilder got = new StringBuilder();
    for (final int sdk : new int[] { 21, 24, 30, 33, 34, 35, 36, 99 }) {
      got.append(sdk).append('=').append(CrawlOwnerPolicy.ownsInJob(sdk)).append(' ');
    }
    assertEquals("21=false 24=false 30=false 33=false 34=true 35=true 36=true 99=true ",
        got.toString());
  }

  @Test
  public void theActivityStartsTheCrawlWheneverTheJobDoesNot() {
    assertEquals("the job took it", false, CrawlOwnerPolicy.startsInActivity(true, true));
    assertEquals("the system refused the job", true,
        CrawlOwnerPolicy.startsInActivity(true, false));
    assertEquals("no job below 34", true, CrawlOwnerPolicy.startsInActivity(false, true));
    assertEquals("no job, and nothing scheduled", true,
        CrawlOwnerPolicy.startsInActivity(false, false));
  }

  @Test
  public void onlyAnIdleSessionMayStartANewCrawl() {
    assertEquals("nothing is running", true, CrawlOwnerPolicy.startsNewCrawl(false, false));
    assertEquals("a crawl holds the slot", false, CrawlOwnerPolicy.startsNewCrawl(true, false));
    assertEquals("the scheduler holds our job", false,
        CrawlOwnerPolicy.startsNewCrawl(false, true));
    assertEquals("both", false, CrawlOwnerPolicy.startsNewCrawl(true, true));
  }

  /** Both decisions above read the same with their arguments swapped, so no truth table can
   *  catch a swapped call. The names in the declaration are the whole guard. */
  @Test
  public void theParameterNamesSayWhichIsWhich() throws IOException {
    final String source = TestSources.withoutCommentsAndStrings(
        TestSources.javaSource("CrawlOwnerPolicy"));
    assertEquals("final boolean jobOwns, final boolean scheduleAccepted",
        norm(TestSources.arguments(source, "static boolean startsInActivity")));
    assertEquals("final boolean sessionLive, final boolean jobPending",
        norm(TestSources.arguments(source, "static boolean startsNewCrawl")));
  }
}
