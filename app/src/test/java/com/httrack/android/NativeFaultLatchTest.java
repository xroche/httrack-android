package com.httrack.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.httrack.android.jni.HTTrackLib;
import java.io.IOException;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.Test;

/** A fault recovered by coffeecatch is a siglongjmp: the engine keeps whatever state, open cache
 *  file and held lock it faulted with. Every entry point back into it has to refuse from then on,
 *  and the app has to end the process rather than run on top of that. */
public class NativeFaultLatchTest {
  /** Entry points that may skip the latch: two constants, and the load hook that runs once
   *  before any of this. */
  private static final Set<String> UNGUARDED = new TreeSet<String>(Arrays.asList(
      "getFeatures", "getVersion", "initStatic"));

  private static final Pattern ENTRY_POINT = Pattern.compile(
      "(?m)^(?:JNICALL\\s+\\w+\\s+)?Java_com_httrack_android_jni_HTTrackLib_(\\w+)\\s*\\(");

  private String jni() throws IOException {
    return TestSources.jniSource("htslibjni.c");
  }

  /** Body of the entry point NAME, braces balanced, the outer pair left out. */
  private static String body(final String source, final String name) {
    final Matcher m = ENTRY_POINT.matcher(source);
    while (m.find()) {
      if (!m.group(1).equals(name)) {
        continue;
      }
      final int from = source.indexOf('{', m.end());
      int depth = 0;
      for (int i = from; i < source.length(); i++) {
        if (source.charAt(i) == '{') {
          depth++;
        } else if (source.charAt(i) == '}' && --depth == 0) {
          return source.substring(from + 1, i);
        }
      }
      throw new IllegalStateException(name + " has no closing brace");
    }
    throw new IllegalStateException("no entry point " + name);
  }

  @Test
  public void theLatchIsSetBeforeTheExceptionIsAllocated() throws IOException {
    final String macro = TestSources.between(jni(),
        "#define COFFEE_TRY_JNI_RECOVER", "#include \"htslibjni.h\"");
    final int latched = macro.indexOf("engineFaulted = 1");
    final int thrown = macro.indexOf("coffeecatch_throw_exception(ENV)");
    assertTrue("the fault must be latched inside COFFEE_CATCH", latched != -1);
    assertTrue("allocating the exception is what the watchdog may kill us over,"
        + " and a fault we did not latch is a fault we would keep running on",
        thrown > latched);
  }

  @Test
  public void everyEntryPointReachingTheEngineReadsTheLatch() throws IOException {
    final String source = jni();
    final Set<String> unguarded = new TreeSet<String>();
    final Set<String> seen = new LinkedHashSet<String>();
    final Matcher m = ENTRY_POINT.matcher(source);
    while (m.find()) {
      seen.add(m.group(1));
    }
    assertTrue("no entry point parsed", seen.size() > 8);
    for (final String name : seen) {
      if (!body(source, name).contains("engineFaulted")
          && !body(source, name).contains("refuseIfFaulted")) {
        unguarded.add(name);
      }
    }
    assertEquals(UNGUARDED, unguarded);
  }

  @Test
  public void theCallsTheUiThreadMakesRefuseWithoutTakingTheEngineLock()
      throws IOException {
    final String source = jni();
    // Stop is a click handler, and free() runs on the finalizer thread: the fault may have
    // happened with that very lock held, so neither may take it, and neither may throw.
    for (final String name : new String[] { "stop", "abortCode", "wasStopped", "free" }) {
      final String body = body(source, name);
      final int latch = body.indexOf("engineFaulted");
      final int locked = body.indexOf("MUTEX_LOCK");
      assertTrue(name + " must read the latch", latch != -1);
      assertTrue(name + " must refuse before it takes the lock",
          locked == -1 || latch < locked);
      assertFalse(name + " must not throw into a click handler",
          body.contains("refuseIfFaulted"));
    }
  }

  @Test
  public void buildingTheTopIndexRefusesWithoutThrowing() throws IOException {
    // Its Java callers dump what they catch, over the dump that describes the fault itself.
    assertFalse(body(jni(), "buildTopIndex").contains("refuseIfFaulted"));
    final String java = TestSources.javaSource("jni/HTTrackLib");
    final String method = TestSources.between(java,
        "public static int buildTopIndex(final File path", "final String p =");
    assertTrue("the Java side has to refuse before it enters JNI at all",
        method.contains("hasFaulted()"));
  }

  @Test
  public void theLatchIsReadableBeforeTheLibrariesAreLoaded() {
    // Called from setPane(), which runs whether or not the native side ever loaded.
    assertFalse(HTTrackLib.hasFaulted());
  }

  @Test
  public void aFaultedEngineIsNeverAskedForAnotherMirror() throws IOException {
    final String source = TestSources.javaSource("HTTrackActivity");
    final String run = TestSources.between(source, "protected void runInternal()",
        "final int code = engine.main(cargs)");
    assertTrue("runInternal must refuse before it starts the engine",
        run.contains("HTTrackLib.hasFaulted()"));
  }

  @Test
  public void leavingTheErrorPanelEndsTheProcess() throws IOException {
    final String source = TestSources.javaSource("HTTrackActivity");
    final String pane = TestSources.between(source, "private void setPane(final int position)",
        "if (pane_id != position)");
    assertTrue("nothing may run past the error panel in this process",
        pane.contains("HTTrackLib.hasFaulted()")
            && pane.contains("exitAfterNativeFault()"));
    final String restart = TestSources.between(source, "protected void restartActivity()",
        "final Intent intent");
    assertTrue("restarting the activity keeps the process the fault poisoned",
        restart.contains("exitAfterNativeFault()"));
    final String exit = TestSources.between(source,
        "private void exitAfterNativeFault()", "\n  }");
    assertTrue("finish() alone leaves the process alive", exit.contains("System.exit(0)"));
  }
}
