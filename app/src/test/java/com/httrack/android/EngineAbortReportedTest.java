package com.httrack.android;

import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** MirrorOutcomeTest covers the choice; what it cannot reach is the wiring that feeds it. */
public class EngineAbortReportedTest {
  /** exit_xh's value, not its truth: the three causes carry three different messages. */
  @Test
  public void abortCodeHandsBackTheEnginesOwnVerdict() throws Exception {
    final String body = TestSources.between(TestSources.jniSource("htslibjni.c"),
        "HTTrackLib_abortCode", "\n}");
    assertTrue("abortCode must return hts_is_exiting()'s value, not a flag derived from it",
        body.contains("aborted = hts_is_exiting(context->opt);"));
  }

  /** Both inputs must reach the choice; the return code alone cannot see either. */
  @Test
  public void theFinishedPaneWeighsBothTheStopAndTheEnginesVerdict() throws Exception {
    final String call = TestSources.between(TestSources.javaSource("HTTrackActivity"),
        "MirrorOutcome.of(", ");");
    assertTrue("the user's own stop must reach the choice", call.contains("interrupted"));
    assertTrue("the engine's verdict must reach the choice", call.contains("engine.abortCode()"));
  }
}
