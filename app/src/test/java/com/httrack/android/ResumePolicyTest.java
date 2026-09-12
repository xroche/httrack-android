package com.httrack.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** Truth tables for what a crawl whose activity is gone does with its verdict, and for what a
 *  cold launch says about the project it left behind. DetachedRunTest next door proves the
 *  activity and the runner delegate here. */
public class ResumePolicyTest {
  private static final String TEMPLATE = "left: %s.";
  private static final String AND_MORE = "and %s more";

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

  private String notice(final String... names) {
    return ResumePolicy.resumeNotice(TEMPLATE, AND_MORE, root, names);
  }

  @Test
  public void bothMarkersMakeAProjectResumable() throws Exception {
    project("ours", "ours");
    project("engine", "engine");
    assertEquals("left: ours, engine.", notice("ours", "engine"));
  }

  @Test
  public void aProjectThatFinishedIsNotOffered() throws Exception {
    project("done", null);
    project("stopped", "ours");
    assertEquals("left: stopped.", notice("done", "stopped"));
  }

  /** getProjectNames() returns null when the root is missing, and a name can go away between
   *  the listing and the check. */
  @Test
  public void nothingToListMeansNothingToSay() throws Exception {
    assertNull(notice((String[]) null));
    assertNull(notice());
    assertNull(notice("gone", null));
    assertNull(ResumePolicy.resumeNotice(TEMPLATE, AND_MORE, null,
        new String[] { "gone" }));
  }

  @Test
  public void nothingResumableSaysNothing() throws Exception {
    project("done", null);
    assertNull(notice("done"));
    project("stopped", "ours");
    assertNull("a resumable project with no template to fill says nothing rather than throwing",
        ResumePolicy.resumeNotice(null, AND_MORE, root, new String[] { "stopped" }));
  }

  /** Four unfinished projects is a plausible backlog, and their names are user-supplied. */
  @Test
  public void theNoticeNamesThreeAndCountsTheRest() throws Exception {
    final String[] names = new String[] { "a", "b", "c", "d", "e" };
    for (final String name : names) {
      project(name, "ours");
    }
    assertEquals("left: a, b, c.", notice("a", "b", "c"));
    assertEquals("left: a, b, c, and 1 more.", notice("a", "b", "c", "d"));
    assertEquals("left: a, b, c, and 2 more.", notice(names));
  }

  /** A missing suffix string drops the count rather than the whole notice. */
  @Test
  public void theCountIsOptional() throws Exception {
    for (final String name : new String[] { "a", "b", "c", "d" }) {
      project(name, "ours");
    }
    assertEquals("left: a, b, c.",
        ResumePolicy.resumeNotice(TEMPLATE, null, root, new String[] { "a", "b",
            "c", "d" }));
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

  @Test
  public void onlyAStopAheadOfTheVerdictWritesTheMarker() {
    assertTrue("nothing else has decided yet",
        ResumePolicy.stopWritesMarker(false, false));
    assertFalse("the run already recorded what it left behind",
        ResumePolicy.stopWritesMarker(false, true));
    assertFalse("the finished pane's own stop is not an interruption",
        ResumePolicy.stopWritesMarker(true, true));
    assertFalse(ResumePolicy.stopWritesMarker(true, false));
  }
}
