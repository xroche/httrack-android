package com.httrack.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import org.junit.Test;

/** A caught native fault arms coffeecatch's 30s alarm(), and SIGALRM kills the
 *  process; we recover the fault as a Java Error and keep crawling, so every
 *  protected entry point has to disarm it. */
public class CoffeeCatchAlarmTest {
  private String jni() throws IOException {
    return TestSources.jniSource("htslibjni.c");
  }

  @Test
  public void noEntryPointUsesTheBareMacro() throws IOException {
    assertEquals("upstream COFFEE_TRY_JNI leaves the alarm armed", 0,
        TestSources.occurrences(jni(), "COFFEE_TRY_JNI("));
  }

  @Test
  public void everyProtectedBlockGoesThroughTheWrapper() throws IOException {
    final String source = jni();
    // The lone COFFEE_TRY() is the wrapper's own; a hand-rolled one would skip
    // the cancel.
    assertEquals(1, TestSources.occurrences(source, "COFFEE_TRY()"));
    assertTrue(TestSources.occurrences(source, "COFFEE_TRY_JNI_RECOVER(") > 1);
  }

  @Test
  public void cancelsAfterTheThrowAndBeforeCleanup() throws IOException {
    final String all = jni();
    final int at = all.indexOf("#define COFFEE_TRY_JNI_RECOVER");
    assertTrue(at != -1);
    final String source = all.substring(at);
    final int thrown = source.indexOf("coffeecatch_throw_exception(ENV)");
    final int cancel = source.indexOf("coffeecatch_cancel_pending_alarm()");
    final int end = source.indexOf("COFFEE_END()");
    assertTrue("cancel must not disarm the watchdog before the throw allocates",
        thrown != -1 && cancel > thrown);
    assertTrue("COFFEE_END() frees the state the cancel reads",
        end != -1 && cancel < end);
  }
}
