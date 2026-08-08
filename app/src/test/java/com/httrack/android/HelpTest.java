package com.httrack.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;

import java.io.File;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** Which page each screen asks for, and where a page name is allowed to land. */
public class HelpTest {
  private static final String BASE = "http://127.0.0.1:8080";

  /** Stands in for an option tab; the real ones cannot load without the Android framework. */
  @OptionsActivity.HelpPage("guide.html#droid/opt-limits")
  private static class AnnotatedTab {
  }

  private static class BareTab {
  }

  @Rule
  public final TemporaryFolder tmp = new TemporaryFolder();

  private File root;

  @Before
  public void setUp() throws Exception {
    root = tmp.newFolder("resources");
    new File(root, "html").mkdirs();
  }

  private String url(final String page) throws Exception {
    final int hash = page.indexOf('#');
    final String name = hash == -1 ? page : page.substring(0, hash);
    return Loopback.url(BASE, root, new File(new File(root, "html"), name),
        hash == -1 ? null : page.substring(hash));
  }

  @Test
  public void resolvesAnOptionPage() throws Exception {
    assertEquals(BASE + "/html/guide.html#droid/opt-scan-rules",
        url("guide.html#droid/opt-scan-rules"));
  }

  @Test
  public void leavesTheFragmentByteForByte() throws Exception {
    // A space in the fragment is what tells "appended raw" apart from "percent-encoded".
    assertEquals(BASE + "/html/a.html#c d", url("a.html#c d"));
  }

  @Test
  public void encodesThePath() throws Exception {
    assertEquals(BASE + "/html/a%20b.html", url("a b.html"));
  }

  @Test
  public void refusesAPageOutsideTheBundle() throws Exception {
    assertNull(url("../../etc/passwd"));
  }

  @Test
  public void refusesTraversalThatDoesNotStartWithDotDot() throws Exception {
    // A name-prefix blocklist would pass this one; only canonicalisation catches it.
    assertNull(url("foo/../../../etc/passwd"));
  }

  @Test
  public void everyPaneHasItsOwnSection() {
    final String[] pages = { Help.pageForPane(HTTrackActivity.LAYOUT_START),
        Help.pageForPane(HTTrackActivity.LAYOUT_PROJECT_NAME),
        Help.pageForPane(HTTrackActivity.LAYOUT_PROJECT_SETUP),
        Help.pageForPane(HTTrackActivity.LAYOUT_MIRROR_PROGRESS),
        Help.pageForPane(HTTrackActivity.LAYOUT_FINISHED) };
    for (int i = 0; i < pages.length; i++) {
      for (int j = i + 1; j < pages.length; j++) {
        assertNotEquals(pages[i], pages[j]);
      }
    }
  }

  @Test
  public void panePagesCarryThePlatform() {
    // Without "#droid/" the guide falls back to sniffing the user agent.
    assertEquals("guide.html#droid/step-project",
        Help.pageForPane(HTTrackActivity.LAYOUT_PROJECT_NAME));
  }

  @Test
  public void anUnknownPaneFallsBackToTheFirstStep() {
    assertEquals(Help.PAGE_START, Help.pageForPane(-1));
  }

  @Test
  public void readsTheTabAnnotation() {
    assertEquals("guide.html#droid/opt-limits", Help.pageForTab(AnnotatedTab.class));
  }

  @Test
  public void fallsBackToTheOverviewWithoutATab() {
    assertEquals(Help.PAGE_OPTIONS, Help.pageForTab(null));
    assertEquals(Help.PAGE_OPTIONS, Help.pageForTab(BareTab.class));
  }
}
