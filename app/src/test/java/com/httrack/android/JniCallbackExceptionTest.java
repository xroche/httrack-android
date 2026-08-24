package com.httrack.android;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import org.junit.Test;

/** A callback exception left pending makes every following JNI call illegal, and the engine
 *  abort it triggers returns the same 0 as a finished mirror. Nothing here is reachable from
 *  a unit-test JVM (no native library, no JVM to throw into), so these read the glue: they
 *  pin the shape a regression would have to undo, not the runtime behaviour. */
public class JniCallbackExceptionTest {
  private static String glue() throws IOException {
    return TestSources.jniSource("htslibjni.c");
  }

  /** Body of the top-level function whose signature contains NAME. */
  private static String body(final String source, final String name) {
    final int at = source.indexOf(name);
    assertTrue(name + " is gone from htslibjni.c", at != -1);
    return TestSources.balancedBlock(source, at);
  }

  /** The refresh path must hand a pending exception over before it returns to the engine. */
  @Test
  public void theRefreshPathClearsBeforeItReturns() throws Exception {
    final String loop = body(glue(), "static int htsshow_loop_internal(");
    final int call = loop.indexOf("meth_HTTrackCallbacks_onRefresh");
    final int capture = loop.indexOf("capturePendingException(t)", call);
    final int pop = loop.indexOf("->PopLocalFrame(", call);
    assertTrue("no onRefresh call left in htsshow_loop_internal", call != -1);
    assertTrue("nothing captures the exception after onRefresh", capture != -1);
    assertTrue("PopLocalFrame runs before the exception is captured", pop > capture);
  }

  /** NewGlobalRef is not one of the calls allowed while an exception is pending. */
  @Test
  public void theCaptureClearsBeforeItAllocates() throws Exception {
    final String capture = body(glue(), "static int capturePendingException(");
    final int clear = capture.indexOf("ExceptionClear");
    final int global = capture.indexOf("NewGlobalRef");
    assertTrue("the capture no longer clears", clear != -1);
    assertTrue("NewGlobalRef runs before the clear", global == -1 || global > clear);
  }

  /** hts_main2 returns 0 on abort, so only the rethrow keeps the run from reporting success. */
  @Test
  public void theCrawlEntryPointRethrows() throws Exception {
    final String main = body(glue(), "jint HTTrackLib_main(");
    assertTrue("a callback exception no longer reaches Java",
        main.contains("Throw(env, t.pendingException)"));
  }

  /* Keeping the throwable can fail under OOM. If the abort hangs off that, the
     whole fix evaporates exactly when memory is short. */
  @Test
  public void theAbortDoesNotHangOnKeepingTheThrowable() throws Exception {
    final String loop = body(glue(), "static int htsshow_loop_internal(");
    assertTrue("the abort must follow capturePendingException's own verdict",
        loop.contains("if (capturePendingException(t)) {"));
    assertFalse("the abort must not hang on NewGlobalRef having succeeded",
        loop.contains("if (t->pendingException != NULL) {\n      code = 0;"));
  }
}
