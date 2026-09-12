package com.httrack.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;

import org.junit.Test;

/** A crawl outlives the activity that started it, so its finish path must reach disk with no
 *  parent attached. ResumePolicyTest holds the truth tables; these read the wiring that feeds
 *  them, pinning whole argument lists because every path here is same-typed. */
public class DetachedRunTest {
  private static String source() throws IOException {
    return TestSources
        .withoutCommentsAndStrings(TestSources.javaSource("HTTrackActivity"));
  }

  /** Body of the runner's own METHOD declaration; RunnerFragment declares some of the same
   *  names, and it is the trunk rather than the thing that acts. */
  private static String runnerBody(final String declaration) throws IOException {
    final String source = source();
    final int runner = source
        .indexOf("protected static class Runner extends AsyncTask");
    assertTrue("no Runner class", runner != -1);
    final String body = TestSources.balancedBlock(source, runner);
    final int at = body.indexOf(declaration);
    assertTrue(declaration + " is gone", at != -1);
    return TestSources.balancedBlock(body, at);
  }

  /** Arguments of the call to NAME, whitespace collapsed. */
  private static String callArguments(final String source, final String name) {
    return TestSources.arguments(source, name).trim().replaceAll("\\s+", " ");
  }

  /** Brace depth of TEXT within BODY; zero means a statement of the method itself. */
  private static int depthOf(final String body, final String text) {
    final int at = body.indexOf(text);
    assertTrue(text + " is gone", at != -1);
    int depth = 0;
    for (int i = 0; i < at; i++) {
      if (body.charAt(i) == '{') {
        depth++;
      } else if (body.charAt(i) == '}') {
        depth--;
      }
    }
    return depth;
  }

  @Test
  public void aDetachedRunStillStampsItsVerdict() throws Exception {
    final String body = runnerBody(
        "private synchronized void setInterruptedProfile(final boolean interrupted)");
    assertTrue("the marker must go to the directory the run captured",
        body.contains("runTarget != null ? runTarget"));
    assertFalse("a marker write through the activity cannot happen once detached",
        body.contains("parent.setInterruptedProfile"));
    assertEquals("gated on a parent, a detached run stamps nothing", 0,
        depthOf(body, "HTTrackActivity.setInterruptedProfile(target, interrupted)"));
  }

  @Test
  public void aLateStopDoesNotOverwriteTheVerdict() throws Exception {
    final String body = runnerBody("public boolean stopMirror(final boolean force)");
    assertEquals("ended alone is set after the top index, leaving a window",
        "ended, verdictRecorded",
        callArguments(body, "ResumePolicy.stopWritesMarker"));
    assertTrue("the verdict has to be latched where it is computed",
        TestSources.between(source(), "pendingWork = leavesPendingWork",
            "MirrorOutcome.Verdict verdict").contains("verdictRecorded = true"));
  }

  @Test
  public void theTopIndexIsBuiltRatherThanQueued() throws Exception {
    final String body = runnerBody("private void buildTopIndex()");
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
    final String body = TestSources.between(source(), "protected void runInternal()",
        "engine.main(cargs)");
    for (final String field : new String[] { "runTarget =", "runProjectRoot =",
        "runResources =" }) {
      assertTrue(field + " is not captured before the engine runs", body.contains(field));
    }
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
        depthOf(body, "setIntent(intent)") > 0);
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
