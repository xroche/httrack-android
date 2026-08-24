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

  /** htslibjni.c with comments and string literals blanked, so a commented-out guard cannot
   *  pass for one. */
  private String jni() throws IOException {
    return TestSources.withoutCommentsAndStrings(TestSources.jniSource("htslibjni.c"));
  }

  /** Body of the entry point NAME, braces balanced, the outer pair left out. */
  private static String body(final String source, final String name) {
    final Matcher m = ENTRY_POINT.matcher(source);
    while (m.find()) {
      if (m.group(1).equals(name)) {
        return TestSources.balancedBlock(source, m.end());
      }
    }
    throw new IllegalStateException("no entry point " + name);
  }

  /** The body of the CONDITION block inside the entry point NAME. */
  private static String blockBody(final String source, final String name,
      final String condition) {
    final String body = body(source, name);
    final int at = body.indexOf(condition);
    assertTrue(name + " must test " + condition, at != -1);
    return TestSources.balancedBlock(body, at);
  }

  /** The body of NAME's `if (engineFaulted)` block, which is what has to refuse. */
  private static String guardBody(final String source, final String name) {
    return blockBody(source, name, "if (engineFaulted)");
  }

  @Test
  public void theLatchIsSetInTheCatchArmBeforeTheExceptionIsAllocated() throws IOException {
    final String macro = TestSources.between(jni(),
        "#define COFFEE_TRY_JNI_RECOVER", "#endif");
    final int caught = macro.indexOf("COFFEE_CATCH()");
    final int latched = macro.indexOf("engineFaulted = 1");
    final int thrown = macro.indexOf("coffeecatch_throw_exception(ENV)");
    assertTrue("latching in the COFFEE_TRY arm would refuse the first call",
        caught != -1 && latched > caught);
    assertTrue("allocating the exception is what the watchdog may kill us over,"
        + " and a fault we did not latch is a fault we would keep running on",
        thrown > latched);
    // COFFEE_CATCH is also entered when coffeecatch_setup() fails, with no signal delivered.
    final int signal = macro.indexOf("coffeecatch_get_signal()");
    assertTrue("a setup failure must not latch an engine that never faulted",
        signal != -1 && signal < latched);
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
    assertTrue("the entry points did not parse: " + seen, seen.containsAll(Arrays.asList(
        "main", "stop", "buildTopIndex", "init", "free", "initRootPath")));
    for (final String name : seen) {
      if (!body(source, name).contains("engineFaulted")
          && !body(source, name).contains("refuseIfFaulted")) {
        unguarded.add(name);
      }
    }
    assertEquals(UNGUARDED, unguarded);
  }

  @Test
  public void theEntryPointsThatCannotThrowRefuseQuietly() throws IOException {
    final String source = jni();
    // stop() is a click handler, free() the finalizer thread, and init() a constructor whose
    // throw would take the activity down before the crawl reports the fault at all.
    for (final String name : new String[] { "stop", "abortCode", "wasStopped", "free", "init" }) {
      final String guard = guardBody(source, name);
      assertFalse(name + " must refuse without an exception", guard.contains("throw"));
      assertTrue(name + " must return from the guard, not fall through it",
          guard.contains("return"));
    }
  }

  @Test
  public void theEngineLockIsNeverTakenAfterAFault() throws IOException {
    final String source = jni();
    // The fault may have happened with that very lock held, and stop() is called from the UI.
    for (final String name : new String[] { "stop", "abortCode", "wasStopped" }) {
      final String body = body(source, name);
      final int latch = body.indexOf("if (engineFaulted)");
      final int locked = body.indexOf("MUTEX_LOCK");
      assertTrue(name + " must refuse on the latch being SET, not cleared", latch != -1);
      assertTrue(name + " must refuse before it takes the lock",
          locked != -1 && latch < locked);
      assertTrue(name + " must return before the lock",
          guardBody(source, name).contains("return"));
    }
  }

  @Test
  public void aNullContextNeverReachesTheLock() throws IOException {
    // Throwing does not return: without one, the next line dereferences the NULL it just refused.
    final String source = jni();
    for (final String name : new String[] { "stop", "abortCode", "wasStopped" }) {
      assertTrue(name + " must return after refusing a null context",
          blockBody(source, name, "if (context == NULL)").contains("return"));
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
        pane.contains("NativeFaultPolicy.closeOnPaneChange(")
            && pane.contains("closeAfterNativeFault()"));
    final String restart = TestSources.between(source, "protected void restartActivity()",
        "final Intent intent");
    assertTrue("restarting the activity keeps the process the fault poisoned",
        restart.contains("closeAfterNativeFault()"));
    // finish() only schedules the destroy, so exiting here would beat onDestroy's serialize().
    final String close = TestSources.between(source,
        "private void closeAfterNativeFault()", "\n  }");
    assertFalse("the exit belongs in onDestroy, after the profile is saved",
        close.contains("System.exit"));
  }

  @Test
  public void everyNativeDeclarationHasItsCDefinition() throws IOException {
    // A name that does not match is an UnsatisfiedLinkError on the first call, and hasFaulted()
    // is now called from setPane() and onDestroy() on an ordinary run.
    final String java = TestSources.javaSource("jni/HTTrackLib");
    final Matcher declared = Pattern.compile(
        "native\\s+(?:\\w+\\s+)*?(\\w+)\\s*\\(").matcher(java);
    final Set<String> names = new TreeSet<String>();
    while (declared.find()) {
      names.add(declared.group(1));
    }
    assertTrue("no native declaration parsed", names.size() >= 8);
    final String source = jni();
    for (final String name : names) {
      assertTrue("no C definition for native " + name,
          source.contains("Java_com_httrack_android_jni_HTTrackLib_" + name + "("));
    }
  }

  @Test
  public void closingTheAppDoesNotLeaveTheLatchForTheNextLaunch() throws IOException {
    // Android hands a relaunch whatever process it kept, so finishing has to end this one.
    // Indented two spaces: the runner fragment overrides onDestroy() as well.
    final String destroy = TestSources.between(
        TestSources.javaSource("HTTrackActivity"), "\n  public void onDestroy()", "\n  }");
    assertTrue("a finished activity leaves the faulted process behind",
        destroy.contains("NativeFaultPolicy.exitOnDestroy(")
            && destroy.contains("System.exit(0)"));
    assertTrue("the exit must follow the profile save, not precede it",
        destroy.indexOf("serialize()") < destroy.indexOf("System.exit(0)"));
  }
}
