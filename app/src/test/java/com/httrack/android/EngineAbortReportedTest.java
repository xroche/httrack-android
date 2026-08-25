package com.httrack.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.httrack.android.jni.HTTrackLib;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.Test;

/** MirrorOutcomeTest covers the choice; what it cannot reach is the wiring that feeds it. */
public class EngineAbortReportedTest {
  private static String abortCodeBody() throws Exception {
    return TestSources.between(TestSources.jniSource("htslibjni.c"), "HTTrackLib_abortCode", "\n}");
  }

  /** The value, not its truth: the causes carry different messages. */
  @Test
  public void abortCodeHandsBackTheEnginesOwnVerdict() throws Exception {
    final String body = abortCodeBody();
    assertTrue("abortCode must ask the engine", body.contains("hts_is_exiting"));
    assertFalse("abortCode must not collapse the value to a flag",
        body.matches("(?s).*hts_is_exiting\\([^)]*\\)\\s*(!=|==)\\s*0.*"));
    assertFalse("abortCode must not collapse the value to a flag",
        body.matches("(?s).*hts_is_exiting.*\\?.*:.*"));
  }

  /** Both inputs must reach the choice; the return code alone cannot see either. */
  @Test
  public void theFinishedPaneWeighsBothTheStopAndTheEnginesVerdict() throws Exception {
    final String call = TestSources.arguments(TestSources.javaSource("HTTrackActivity"),
        "MirrorOutcome.decide");
    assertEquals("the exit code must reach the choice", "code", call.split(",")[0].trim());
    assertEquals("the pane must weigh the same stop the resume offer does", "stop",
        call.split(",")[1].trim());
    assertEquals("the engine's verdict must reach the choice", "engine.abortCode()",
        call.split(",")[2].trim());
    assertEquals("the run's own tally must reach the choice", "lastStats",
        call.split(",")[3].trim());
  }

  /** Java hardcodes the value, and a pin bump that renumbered it would fail nothing else. */
  @Test
  public void theExitCodeMatchesThePinnedEngine() throws Exception {
    final String header = TestSources.withoutCommentsAndStrings(
        TestSources.read(TestSources.engineFile("src/httrack-library.h")));
    final Matcher define = Pattern.compile(
        "#define\\s+HTS_EXIT_MIRROR_ABORTED\\s+(\\d+)").matcher(header);
    assertTrue("the engine no longer defines HTS_EXIT_MIRROR_ABORTED", define.find());
    assertEquals("the engine renumbered its aborted exit code", define.group(1),
        String.valueOf(HTTrackLib.EXIT_MIRROR_ABORTED));
  }

  /** MirrorOutcomeTest owns the text and the link; only the folder the link points at is here. */
  @Test
  public void theFolderIsOfferedExactlyWhenTheVerdictSaysSo() throws Exception {
    final String body = TestSources.between(TestSources.javaSource("HTTrackActivity"),
        "protected void runInternal()", "// Build top index");
    assertEquals("the mirror folder must be offered once, and only under the verdict", 1,
        body.split("mirrorFolder = target", -1).length - 1);
    assertTrue("the folder must hang off the verdict's own answer",
        body.contains("if (verdict.showsFolderLink()) {\n          mirrorFolder = target;"));
    // A second verdict, or a second assignment, would answer over the one decide() handed back.
    assertTrue("the verdict must not be replaceable",
        body.contains("final MirrorOutcome.Verdict verdict = MirrorOutcome.decide("));
    assertEquals("the pane must say what the verdict says, once", 1,
        body.split(Pattern.quote("message = verdict.text();"), -1).length - 1);
    assertEquals("nothing else may set the message", 1,
        Pattern.compile("(?<!String )\\bmessage\\s*=[^=]").matcher(body).results().count());
  }

  /** Transposing the two branches is invisible to the enum, so each is pinned to its source. */
  @Test
  public void theStopSourceNamesWhoAskedForIt() throws Exception {
    final String assigned = TestSources.between(TestSources.javaSource("HTTrackActivity"),
        "final MirrorOutcome.Stop stop", ";");
    assertTrue("the user's own tap must be the USER stop",
        assigned.matches("(?s).*interrupted\\s*\\?\\s*MirrorOutcome\\.Stop\\.USER.*"));
    assertTrue("the engine stopping itself must be the ENGINE stop",
        assigned.matches("(?s).*wasStopped\\(\\)\\s*\\?\\s*MirrorOutcome\\.Stop\\.ENGINE.*"));
  }
}
