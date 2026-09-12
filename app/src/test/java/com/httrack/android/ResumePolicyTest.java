package com.httrack.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** Truth tables for what a crawl whose activity is gone does with its verdict, and for what a
 *  cold launch says about the project it left behind. The source-text checks below prove the
 *  activity and the runner delegate here. */
public class ResumePolicyTest {
  @Rule
  public final TemporaryFolder tmp = new TemporaryFolder();

  private File root;

  @Before
  public void setUp() throws Exception {
    root = tmp.newFolder("projects");
  }

  /** A project directory, left interrupted by "ours", by the engine's own lock, or by nothing. */
  private File project(final String name, final String marker) throws IOException {
    final File target = new File(root, name);
    assertTrue(new File(target, "hts-cache").mkdirs());
    if ("ours".equals(marker)) {
      HTTrackActivity.setInterruptedProfile(target, true);
    } else if ("engine".equals(marker)) {
      assertTrue(new File(target, "hts-in_progress.lock").createNewFile());
    }
    return target;
  }

  @Test
  public void theRunsOwnDirectoryTakesTheMarker() {
    final File run = new File("/projects/run");
    final File attached = new File("/projects/other");
    assertEquals("the run wrote there, whatever the activity names now", run,
        ResumePolicy.markerDirectory(run, attached));
    assertEquals(run, ResumePolicy.markerDirectory(run, null));
  }

  @Test
  public void aStopBeforeTheRunClaimedADirectoryAsksTheActivity() {
    final File attached = new File("/projects/other");
    assertEquals(attached, ResumePolicy.markerDirectory(null, attached));
  }

  @Test
  public void withNoDirectoryAtAllNothingIsStamped() {
    assertNull(ResumePolicy.markerDirectory(null, null));
  }

  @Test
  public void theTopIndexNeedsBothPaths() {
    final File path = new File("/projects");
    assertTrue(ResumePolicy.topIndexRunsHeadless(path, path));
    assertFalse(ResumePolicy.topIndexRunsHeadless(null, path));
    assertFalse(ResumePolicy.topIndexRunsHeadless(path, null));
    assertFalse(ResumePolicy.topIndexRunsHeadless(null, null));
  }

  @Test
  public void bothMarkersMakeAProjectResumable() throws Exception {
    project("ours", "ours");
    project("engine", "engine");
    assertEquals(Arrays.asList("ours", "engine"),
        ResumePolicy.resumableProjects(root, new String[] { "ours", "engine" }));
  }

  @Test
  public void aProjectThatFinishedIsNotOffered() throws Exception {
    project("done", null);
    project("stopped", "ours");
    assertEquals(Collections.singletonList("stopped"),
        ResumePolicy.resumableProjects(root, new String[] { "done", "stopped" }));
  }

  /** getProjectNames() returns null when the root is missing, and a name can go away between
   *  the listing and the check. */
  @Test
  public void nothingToListMeansNothingToOffer() throws Exception {
    assertTrue(ResumePolicy.resumableProjects(root, null).isEmpty());
    assertTrue(ResumePolicy.resumableProjects(null, new String[] { "gone" }).isEmpty());
    assertTrue(ResumePolicy.resumableProjects(root, new String[] {}).isEmpty());
    assertTrue(ResumePolicy.resumableProjects(root, new String[] { "gone", null })
        .isEmpty());
  }

  @Test
  public void theNoticeNamesEveryUnfinishedProject() {
    assertEquals("left: one", ResumePolicy.resumeNotice("left: %s",
        Collections.singletonList("one")));
    assertEquals("left: one, two",
        ResumePolicy.resumeNotice("left: %s", Arrays.asList("one", "two")));
  }

  @Test
  public void nothingUnfinishedSaysNothing() {
    assertNull(ResumePolicy.resumeNotice("left: %s", Collections.<String> emptyList()));
    assertNull(ResumePolicy.resumeNotice("left: %s", null));
    assertNull(ResumePolicy.resumeNotice(null, Collections.singletonList("one")));
  }

  @Test
  public void anIntentsStateLoadsOnlyWhenThereIsSomeAndNoCrawlToLose() {
    assertTrue(ResumePolicy.restoresIntentState(true, false));
    assertFalse("a launcher tap carries no project",
        ResumePolicy.restoresIntentState(false, false));
    assertFalse("restoring would overwrite the running crawl's settings",
        ResumePolicy.restoresIntentState(true, true));
    assertFalse(ResumePolicy.restoresIntentState(false, true));
  }

  private static String source() throws IOException {
    return TestSources.withoutCommentsAndStrings(TestSources.javaSource("HTTrackActivity"));
  }

  /** Body of the runner's own METHOD declaration. */
  private static String runnerBody(final String declaration) throws IOException {
    final String source = source();
    final int at = source.indexOf(declaration);
    assertTrue(declaration + " is gone", at != -1);
    return TestSources.balancedBlock(source, at);
  }

  @Test
  public void aDetachedRunStillStampsItsVerdict() throws Exception {
    final String body = runnerBody(
        "private synchronized void setInterruptedProfile(final boolean interrupted)");
    assertTrue("the marker must go to the directory the run captured",
        body.contains("ResumePolicy.markerDirectory(runTarget"));
    assertFalse("a marker write through the activity cannot happen once detached",
        body.contains("parent.setInterruptedProfile"));
  }

  @Test
  public void theTopIndexIsBuiltRatherThanQueued() throws Exception {
    final String body = runnerBody("private void buildTopIndex()");
    assertFalse("a queued build waits for an activity that adds nothing to it",
        body.contains("pendingParentActions"));
    assertTrue(body.contains("HTTrackActivity.buildTopIndex(appContext"));
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
    assertTrue("the tapped intent must replace the one onCreate read",
        body.contains("setIntent(intent)"));
    assertTrue(body.contains("ResumePolicy.restoresIntentState("));
    assertTrue("without singleTop the tap builds a second activity",
        TestSources.read(TestSources.mainFile("AndroidManifest.xml"))
            .contains("android:launchMode=\"singleTop\""));
  }

  @Test
  public void theWelcomePaneNamesAnUnfinishedProject() throws Exception {
    final String startup = TestSources.between(source(), "case R.layout.activity_startup:",
        "case R.layout.activity_proj_name:");
    assertTrue(startup.contains("ResumePolicy.resumeNotice("));
    assertTrue("a project name is user-supplied and the pane renders HTML",
        startup.contains("TextUtils.htmlEncode("));
  }

  /** resumeNotice fills a single %s, so a template with none says nothing and one with two
   *  repeats the names. */
  @Test
  public void theNoticeStringCarriesOnePlaceholder() throws Exception {
    final String notice = TestSources.between(
        TestSources.read(TestSources.resFile("values/strings.xml")),
        "name=\"unfinished_downloads_xx\"", "</string>");
    assertEquals(notice, 1, TestSources.occurrences(notice, "%s"));
  }
}
