package com.httrack.android;

import static org.junit.Assert.assertTrue;

import java.io.IOException;
import org.junit.Test;

/**
 * hts_main2() returns 0 for nearly every abort, so the run's own code cannot say
 * whether the mirror died. Neither half of that contract is reachable from JUnit,
 * so both are pinned against their sources.
 */
public class EngineAbortReportedTest {
  private static String between(final String body, final String from, final String to) {
    final int at = body.indexOf(from);
    assertTrue(from + " is gone", at > 0);
    final int end = body.indexOf(to, at);
    assertTrue(to + " is gone", end > at);
    return body.substring(at, end);
  }

  /** The engine's own verdict, not the return code, decides that a run aborted. */
  @Test
  public void abortReasonAsksTheEngineWhetherItGaveUp() throws Exception {
    final String jni = TestSources.jniSource("htslibjni.c");
    final String body = between(jni, "HTTrackLib_abortReason", "\n}");
    assertTrue("abortReason must ask the engine, not read a return code",
        body.contains("hts_is_exiting"));
    assertTrue("abortReason must carry the engine's own message",
        body.contains("hts_errmsg"));
  }

  /** A full disk arrives here with code 0, so success must be the last branch. */
  @Test
  public void anAbortIsReportedBeforeAnySuccess() throws IOException {
    final String source = TestSources.javaSource("HTTrackActivity");
    final String branch = between(source, "final String aborted = engine.abortReason();",
        "mirrorFolder = target;");
    assertTrue("the abort must be weighed", branch.contains("if (aborted != null)"));
    assertTrue("an aborted run must not be announced as a success",
        branch.indexOf("aborted != null") < branch.indexOf("<b>Success</b>"));
    assertTrue("the engine's text lands in an HTML pane, so it must be escaped",
        branch.contains("TextUtils.htmlEncode(aborted)"));
  }
}
