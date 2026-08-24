package com.httrack.android;

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
    final String call = TestSources.between(TestSources.javaSource("HTTrackActivity"),
        "MirrorOutcome.of(", ");");
    assertTrue("the user's own stop must reach the choice", call.contains("interrupted"));
    assertTrue("the engine's verdict must reach the choice", call.contains("engine.abortCode()"));
  }

  /** Swapping two abort messages is invisible to the enum, so each is pinned to its cause. */
  @Test
  public void eachAbortCauseKeepsItsOwnWording() throws Exception {
    final String source = TestSources.javaSource("HTTrackActivity");
    assertTrue("a fatal abort must name what ran out",
        TestSources.between(source, "case ABORTED_FATAL:", "break;").contains("disk space"));
    assertTrue("a rolled-back session must say the mirror was left alone",
        TestSources.between(source, "case ABORTED_ROLLBACK:", "break;").contains("left as it was"));
  }
}
