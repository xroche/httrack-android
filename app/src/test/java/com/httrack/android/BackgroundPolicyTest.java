package com.httrack.android;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import org.junit.Test;

/** Truth tables for where back goes while a crawl runs, plus the source-text checks that prove
 *  the activity delegates here. */
public class BackgroundPolicyTest {
  @Test
  public void aTaskThatMovedNeedsNothingElse() {
    assertFalse(BackgroundPolicy.askTheLauncher(true));
    assertFalse(BackgroundPolicy.stillOnScreen(true, false));
    assertFalse(BackgroundPolicy.stillOnScreen(true, true));
  }

  @Test
  public void aTaskThatWouldNotMoveFallsBackToTheLauncher() {
    assertTrue(BackgroundPolicy.askTheLauncher(false));
    assertFalse(BackgroundPolicy.stillOnScreen(false, true));
  }

  @Test
  public void bothWaysOutFailingLeavesTheAppInFront() {
    assertTrue(BackgroundPolicy.stillOnScreen(false, false));
  }

  @Test
  public void backSendsTheTaskAwayRatherThanFiringAnIntent() throws IOException {
    final String home = TestSources.between(TestSources.javaSource("HTTrackActivity"),
        "private void goToHome()", "\n  }");
    assertTrue("the task move is what keeps the crawl alive",
        home.contains("moveTaskToBack(true)"));
    assertTrue("a refused move must not be ignored",
        home.contains("BackgroundPolicy.askTheLauncher(")
            && home.contains("BackgroundPolicy.stillOnScreen("));
  }

  @Test
  public void theHomeIntentIsGuarded() throws IOException {
    final String source = TestSources.withoutCommentsAndStrings(
        TestSources.javaSource("HTTrackActivity"));
    final String intent = TestSources.between(source, "private boolean startHomeIntent()",
        "\n  }");
    assertTrue("startActivity throws the launcher's refusal back at us",
        intent.indexOf("startActivity(") < intent.indexOf("catch ("));
  }

  @Test
  public void leavingWhileMirroringNeverFinishesTheActivity() throws IOException {
    final String source = TestSources.withoutCommentsAndStrings(
        TestSources.javaSource("HTTrackActivity"));
    for (final String method : new String[] { "private void goToHome()",
        "private boolean startHomeIntent()", "public void handleOnBackPressed()" }) {
      assertFalse(method + " would take the retained fragment down",
          TestSources.between(source, method, "\n  }").contains("finish()"));
    }
  }
}
