package com.httrack.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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
    // "COFFEE_TRY_JNI (" is legal C, so match the call rather than a literal.
    final Matcher m = Pattern.compile("COFFEE_TRY_JNI\\s*\\(").matcher(jni());
    int count = 0;
    while (m.find()) {
      count++;
    }
    assertEquals("upstream COFFEE_TRY_JNI leaves the alarm armed", 0, count);
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
    assertTrue("a cancel past COFFEE_END() would run on success too, and the"
        + " pending flag is process-wide, so it would clear another thread's"
        + " watchdog mid-throw",
        end != -1 && cancel < end);
  }
}
