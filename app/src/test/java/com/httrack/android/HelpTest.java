package com.httrack.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import java.io.File;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** Help.url: where a doc page name lands, and that a page name cannot leave the docs bundle. */
public class HelpTest {
  private static final String BASE = "http://127.0.0.1:8080";

  @Rule
  public final TemporaryFolder tmp = new TemporaryFolder();

  private File root;

  @Before
  public void setUp() throws Exception {
    root = tmp.newFolder("resources");
    new File(root, "html").mkdirs();
  }

  @Test
  public void resolvesAnOptionPage() throws Exception {
    assertEquals(BASE + "/html/step9_opt4.html", Help.url(BASE, root, "step9_opt4.html"));
  }

  @Test
  public void keepsTheAnchorUnencoded() throws Exception {
    assertEquals(BASE + "/html/android.html#name-the-project",
        Help.url(BASE, root, Help.PAGE_PROJECT_NAME));
  }

  @Test
  public void encodesThePathButLeavesTheAnchorAlone() throws Exception {
    assertEquals(BASE + "/html/a%20b.html#c-d", Help.url(BASE, root, "a b.html#c-d"));
  }

  @Test
  public void refusesAPageOutsideTheBundle() throws Exception {
    assertNull(Help.url(BASE, root, "../../etc/passwd"));
  }
}
