package com.httrack.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;

import org.junit.Test;

/** A crawl outlives the activity that started it, so its finish path must reach disk with no
 *  parent attached. ResumePolicyTest holds the truth tables; these read the wiring that feeds
 *  them, pinning whole argument lists so a dropped, duplicated or swapped argument reds. */
public class DetachedRunTest {
  private static String source() throws IOException {
    return TestSources
        .withoutCommentsAndStrings(TestSources.javaSource("HTTrackActivity"));
  }

  private static String crawlSource() throws IOException {
    return TestSources.withoutCommentsAndStrings(TestSources.javaSource("CrawlRun"));
  }

  /** Body of the crawl core's METHOD declaration. */
  private static String crawlBody(final String declaration) throws IOException {
    final String source = crawlSource();
    final int at = source.indexOf(declaration);
    assertTrue(declaration + " is gone", at != -1);
    return TestSources.balancedBlock(source, at);
  }

  /** Body of the Runner adapter, which is where the crawl reaches a window. */
  private static String runnerBody() throws IOException {
    final String source = source();
    return TestSources.balancedBlock(source,
        TestSources.indexOf(source, "protected static class Runner extends AsyncTask"));
  }

  /** Arguments of the call to NAME, whitespace collapsed. */
  private static String callArguments(final String source, final String name) {
    return TestSources.arguments(source, name).trim().replaceAll("\\s+", " ");
  }

  @Test
  public void aDetachedRunStillStampsItsVerdict() throws Exception {
    final String body = crawlBody(
        "private synchronized void setInterruptedProfile(final boolean interrupted)");
    assertTrue("a stop before the capture stamps the owner's directory rather than nothing",
        body.replaceAll("\\s+", " ")
            .contains("final File target = runTarget != null ? runTarget : owner.target();"));
    assertEquals("the stamp may reach the owner for the fallback target and nothing else", 1,
        TestSources.occurrences(body, "owner."));
    assertEquals("gated on a parent, a detached run stamps nothing", 0,
        TestSources.depthOf(body, "HTTrackActivity.setInterruptedProfile(target, interrupted)"));
  }

  @Test
  public void aLateStopDoesNotOverwriteTheVerdict() throws Exception {
    final String body = crawlBody("boolean stopMirror(final boolean force)");
    assertEquals("ended alone is set after the top index, leaving a window",
        "isEnded(), verdictRecorded",
        callArguments(body, "ResumePolicy.stopWritesMarker"));
    assertTrue("the verdict has to be latched where it is computed",
        TestSources.between(crawlSource(), "pendingWork = HTTrackActivity.leavesPendingWork",
            "MirrorOutcome.Verdict verdict").contains("verdictRecorded = true"));
  }

  @Test
  public void theTopIndexIsBuiltRatherThanQueued() throws Exception {
    final String body = crawlBody("private void buildTopIndex()");
    assertFalse("a queued build waits for an activity that adds nothing to it",
        body.contains("pendingParentActions"));
    assertEquals("the two paths are same-typed, so a swap compiles",
        "appContext, runProjectRoot, runResources",
        callArguments(body, "HTTrackActivity.buildTopIndex"));
    assertTrue("a browse-all tap and a finishing run reach this on separate threads",
        source().contains("protected static synchronized int buildTopIndex("));
  }

  /** Everything above reads what the run captured, so the capture has to precede the engine. */
  @Test
  public void theRunCapturesWhatItsFinishPathNeeds() throws Exception {
    final String body = TestSources.between(crawlSource(), "void runMirror()",
        "engine.main(cargs)");
    for (final String field : new String[] { "runTarget =", "runProjectRoot =",
        "runResources =" }) {
      assertTrue(field + " is not captured before the engine runs", body.contains(field));
    }
  }

  /** Formatting a refresh takes long enough for the window to go; the post has to look again. */
  @Test
  public void aDetachWhileARefreshIsLaidOutDropsIt() throws Exception {
    final String runner = runnerBody();
    final String post = TestSources.balancedBlock(runner,
        TestSources.indexOf(runner,
            "private synchronized void postProgressLines(final String[] lines)"));
    assertEquals("the parent read once at the top is the one that may have gone",
        "if (parent != null) { parent.setProgressLines(lines); }",
        post.replaceAll("\\s+", " ").trim());

    final String stats = TestSources.balancedBlock(runner,
        TestSources.indexOf(runner, "public void onStats(final HTTrackStats stats)"));
    assertEquals("a refresh may only reach the pane through the re-checking post", 0,
        TestSources.occurrences(stats, "setProgressLines("));
    assertEquals("a second post would be one the check does not cover", 1,
        TestSources.occurrences(stats, "postProgressLines("));
    assertEquals("what was laid out is what gets posted", "attached.formatProgress(stats)",
        callArguments(stats, "postProgressLines"));

    assertEquals("laying out and posting must stay apart, or there is nothing to drop", 0,
        TestSources.occurrences(TestSources.balancedBlock(source(),
            TestSources.indexOf(source(), "String[] formatProgress(final HTTrackStats stats)")),
            "setProgressLines("));
  }

  @Test
  public void aNotificationTapReachesTheLiveActivity() throws Exception {
    final String source = source();
    final int at = source.indexOf("protected void onNewIntent(final Intent intent)");
    assertTrue("with no onNewIntent a tap re-enters through onCreate", at != -1);
    final String body = TestSources.balancedBlock(source, at);
    assertEquals("runner stays non-null once a crawl ends, so it is not liveness",
        "extras != null, hasLiveRunner()",
        callArguments(body, "ResumePolicy.restoresIntentState"));
    assertTrue("a refused bundle must not become the intent restartActivity() reopens",
        TestSources.depthOf(body, "setIntent(intent)") > 0);
    assertTrue("without singleTop the tap builds a second activity",
        TestSources.read(TestSources.mainFile("AndroidManifest.xml"))
            .contains("android:launchMode=\"singleTop\""));
  }

  @Test
  public void theWelcomePaneNamesAnUnfinishedProject() throws Exception {
    final String startup = TestSources.between(source(),
        "case R.layout.activity_startup:", "case R.layout.activity_proj_name:");
    assertEquals("both templates are strings, so a swap compiles",
        "getString(R.string.unfinished_downloads_xx), "
            + "getString(R.string.unfinished_downloads_more_xx), "
            + "getProjectRootFile(), getProjectNames()",
        callArguments(startup, "ResumePolicy.resumeNotice"));
    assertTrue("a project name is user-supplied and the pane renders HTML",
        startup.contains("TextUtils.htmlEncode("));
  }

  /** resumeNotice fills a single %s, so a template with none says nothing and one with two
   *  repeats the names. */
  @Test
  public void theNoticeStringsCarryOnePlaceholderEach() throws Exception {
    final String strings = TestSources.read(TestSources.resFile("values/strings.xml"));
    for (final String name : new String[] { "unfinished_downloads_xx",
        "unfinished_downloads_more_xx" }) {
      final String text = TestSources.between(strings, "name=\"" + name + "\"",
          "</string>");
      assertEquals(text, 1, TestSources.occurrences(text, "%s"));
    }
  }
}
