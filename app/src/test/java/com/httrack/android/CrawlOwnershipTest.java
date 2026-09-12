package com.httrack.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import org.junit.Test;

/** The crawl has to survive the window that started it, so nothing of the activity may creep back
 *  into it. A job owner reaches the same run through the same seams. */
public class CrawlOwnershipTest {
  private static String source(final String name) throws IOException {
    return TestSources.withoutCommentsAndStrings(TestSources.javaSource(name));
  }

  /** Body of the method whose declaration starts with SIGNATURE, braces balanced. */
  private static String body(final String source, final String signature) {
    final int at = source.indexOf(signature);
    assertTrue("no " + signature.trim(), at != -1);
    return TestSources.balancedBlock(source, at + signature.length());
  }

  /** An activity held here is one the crawl outlives, and a fragment one it cannot reach. */
  @Test
  public void theCrawlKnowsNoWindow() throws IOException {
    final String crawl = source("CrawlRun");
    for (final String android : new String[] { "HTTrackActivity parent", "Fragment", "AsyncTask",
        "Activity ", "findViewById", "runOnUiThread" }) {
      assertFalse("the crawl core still names " + android, crawl.contains(android));
    }
    // Statics only: an instance call would need the activity the core refuses to hold.
    assertFalse("the crawl core holds an activity instance",
        crawl.matches("(?s).*\\bHTTrackActivity\\s+\\w+\\s*[;=)].*"));
  }

  /** The claim set was a static on the activity, which a headless owner can neither see nor
   *  serve; one holder is what makes the two owners refuse each other. */
  @Test
  public void oneSetHoldsEveryClaim() throws IOException {
    assertTrue("the claims must live with the session",
        source("MirrorSession").contains("HashSet<String> claims"));
    for (final File file : TestSources.javaSources()) {
      if (file.getName().equals("MirrorSession.java")) {
        continue;
      }
      final String other = TestSources.withoutCommentsAndStrings(TestSources.read(file));
      assertFalse(file.getName() + " keeps a claim set of its own",
          other.contains("runningInstances") || other.contains("markRunningInstance"));
    }
  }

  /** A crawl that never reaches the slot is one a second owner starts over. */
  @Test
  public void theRunTakesTheSlotAndGivesItBack() throws IOException {
    final String crawl = source("CrawlRun");
    assertTrue("the run must put itself in the slot",
        body(crawl, "void runMirror()").contains("session.begin(this)"));
    assertEquals("the slot must be freed by the run that took it",
        "advance(MirrorSession.Event.END); MirrorSession.get().end(this);",
        body(crawl, "void end()").replaceAll("\\s+", " ").trim());
    assertTrue("a slot freed by name would free another owner's crawl",
        body(source("MirrorSession"), "synchronized void end(final CrawlRun crawl)")
            .contains("if (run == crawl)"));
  }

  /** Every decision the run makes has to be in the core, or one owner behaves unlike the other. */
  @Test
  public void theFragmentAdapterDecidesNothing() throws IOException {
    final String activity = source("HTTrackActivity");
    final int at = activity.indexOf("protected static class Runner extends AsyncTask");
    assertTrue("no Runner class", at != -1);
    final String runner = TestSources.balancedBlock(activity, at);
    for (final String engineWork : new String[] { "engine.", "tryLock", "ProfileLockPolicy",
        "MirrorOutcome", "CrawlArgv", "setInterruptedProfile" }) {
      assertFalse("the adapter still does the crawl's own work: " + engineWork,
          runner.contains(engineWork));
    }
    assertTrue("the adapter must drive the core rather than a copy of it",
        runner.contains("crawl.runMirror()"));
  }
}
