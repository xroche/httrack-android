package com.httrack.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

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
        "MirrorOutcome.of");
    assertEquals("the pane must weigh the same stop the resume offer does", "stop",
        call.split(",")[0].trim());
    assertTrue("the engine's verdict must reach the choice", call.contains("engine.abortCode()"));
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

  /** Swapping two abort messages is invisible to the enum, so each is pinned to its cause. */
  @Test
  public void eachAbortCauseKeepsItsOwnWording() throws Exception {
    final String source = TestSources.javaSource("HTTrackActivity");
    assertTrue("a fatal abort must name what ran out",
        TestSources.between(source, "case ABORTED_FATAL:", "break;").contains("disk space"));
    assertTrue("a rolled-back session must say the mirror was left alone",
        TestSources.between(source, "case ABORTED_ROLLBACK:", "break;").contains("left as it was"));
    assertTrue("a capped run must name the cap, not an abort",
        TestSources.between(source, "case STOPPED_AT_LIMIT:", "break;").contains("limit reached"));
  }
}
