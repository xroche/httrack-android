package com.httrack.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.Test;

/** The engine spawns detached threads for itself, a DNS resolver per hostname and an FTP fetch,
 *  and no JNI entry point covers them: a fault there used to take the process down. The thread
 *  runner catches it on the worker, and the crawl thread is what reports it to Java. */
public class WorkerThreadFaultTest {
  /** Reads htslibjni.c with comments and string literals blanked, so a commented-out call
   *  cannot pass for one. */
  private String jni() throws IOException {
    return TestSources.withoutCommentsAndStrings(TestSources.jniSource("htslibjni.c"));
  }

  /** Returns function NAME's body, braces balanced, outer pair left out. */
  private static String function(final String source, final String name) {
    final Matcher m = Pattern.compile("(?m)^[\\w *]+\\b" + name + "\\s*\\(").matcher(source);
    assertTrue("no function " + name, m.find());
    return TestSources.balancedBlock(source, m.end());
  }

  /** Returns CONDITION's own block in BODY. */
  private static String blockBody(final String body, final String condition) {
    final int at = body.indexOf(condition);
    assertTrue("no " + condition, at != -1);
    return TestSources.balancedBlock(body, at);
  }

  /** Returns the block CONDITION falls through to in BODY. */
  private static String elseBlock(final String body, final String condition) {
    final int at = body.indexOf(condition);
    assertTrue("no " + condition, at != -1);
    final int fallthrough = body.indexOf("} else {", at);
    assertTrue(condition + " has no else", fallthrough != -1);
    return TestSources.balancedBlock(body, fallthrough + 1);
  }

  @Test
  public void theRunnerIsInstalledBeforeAnythingCanSpawnAWorker() throws IOException {
    final String source = jni();
    assertEquals(1, TestSources.occurrences(source, "hts_set_thread_runner("));
    assertEquals("workerThreadRunner",
        TestSources.arguments(source, "hts_set_thread_runner").trim());
    assertTrue("the engine reads the runner unlocked, so it must be set at class load",
        function(source, "Java_com_httrack_android_jni_HTTrackLib_initStatic")
            .contains("hts_set_thread_runner("));
  }

  @Test
  public void onlyASignalCountsAsAFault() throws IOException {
    final String runner = function(jni(), "workerThreadRunner");
    final String faulted = blockBody(runner, "if (coffeecatch_get_signal() > 0)");
    assertTrue("COFFEE_CATCH is entered on a setup failure too, with the engine intact",
        faulted.contains("reportWorkerFault()"));
    assertFalse("a body that faulted has run, so running it again would fault again",
        faulted.contains("bodyNeverRan = 1"));
    assertTrue(elseBlock(runner, "if (coffeecatch_get_signal() > 0)")
        .contains("bodyNeverRan = 1"));
  }

  @Test
  public void aWorkerWhoseBodyNeverRanRunsItUnprotected() throws IOException {
    final String runner = function(jni(), "workerThreadRunner");
    final int end = runner.indexOf("COFFEE_END()");
    assertTrue(end != -1);
    assertTrue("dropping the body would hand the caller a worker that did nothing",
        blockBody(runner.substring(end), "if (bodyNeverRan)").contains("fun(arg)"));
  }

  @Test
  public void theLatchIsSetBeforeAnythingThatCanWedge() throws IOException {
    final String report = function(jni(), "reportWorkerFault");
    final int latched = report.indexOf("engineFaulted = 1");
    final int logged = report.indexOf("error(");
    final int written = report.indexOf("snprintf(workerFaultMessage");
    final int published = report.indexOf("__ATOMIC_RELEASE");
    assertTrue("logging can wedge on a lock this thread faulted holding, and every guard on"
        + " the engine reads this flag",
        latched != -1 && logged > latched);
    assertTrue("the crawl thread reads the message once it sees the flag",
        written != -1 && published > written);
  }

  @Test
  public void theFaultEndsTheMirrorAndKeepsWhatAContinueNeeds() throws IOException {
    final String report = function(jni(), "reportWorkerFault");
    final String stopping = "if (!alreadyFaulted && runningOpt != NULL)";
    assertTrue("the worker reads runningOpt under its own lock, which is all that keeps the"
        + " crawl thread from retracting it mid-call",
        report.contains("MUTEX_LOCK(runningOptLock)")
            && report.contains("MUTEX_UNLOCK(runningOptLock)"));
    final String stopped = blockBody(report, stopping);
    assertTrue("0 would report the mirror as complete and drop the resume metadata",
        stopped.contains("hts_request_stop(runningOpt, 1"));
    assertFalse("the crawl is what will disarm the watchdog, once it shows it still runs",
        stopped.contains("clearWorkerFaultWatchdog"));
    final String nothingToStop = elseBlock(report, stopping);
    assertTrue("with no crawl to stop, nothing else would ever disarm it",
        nothingToStop.contains("clearWorkerFaultWatchdog()"));
    assertFalse(nothingToStop.contains("hts_request_stop"));
  }

  @Test
  public void aPoisonedOptIsNeverStopped() throws IOException {
    final String report = function(jni(), "reportWorkerFault");
    final int read = report.indexOf("alreadyFaulted = engineFaulted");
    final int latched = report.indexOf("engineFaulted = 1");
    assertTrue("read after the latch, this would always be true",
        read != -1 && latched > read);
  }

  @Test
  public void theMirrorEndsAtTheNextTickAndThatTickDisarmsTheWatchdog() throws IOException {
    final String guard = blockBody(function(jni(), "htsshow_loop"), "if (hasWorkerFaulted())");
    assertTrue("the progress callback aborts the mirror by returning 0",
        guard.contains("return 0"));
    assertTrue("this tick is the first proof no lock the worker held blocks the crawl thread,"
        + " and the wind-down after it has no bound the watchdog could outlast",
        guard.contains("clearWorkerFaultWatchdog()"));
  }

  @Test
  public void theCrawlThreadReportsTheFaultAndDisarmsTheWatchdog() throws IOException {
    final String source = jni();
    assertTrue("a plain load would let the crawl thread read the message half-written",
        function(source, "hasWorkerFaulted").contains("__ATOMIC_ACQUIRE"));
    final String main = function(source, "HTTrackLib_main");
    final String reported = blockBody(main, "if (hasWorkerFaulted())");
    assertTrue("getSafeCopy() sizes its buffer from one read and copies on a second, so a"
        + " second faulting worker could grow the string in between",
        reported.contains("snprintf(reported,") && reported.contains("throwException(env,"));
    assertTrue(reported.contains("clearWorkerFaultWatchdog()")
        && reported.contains("code = -1"));
    assertTrue("java.lang.Error is what coffeecatch throws for a fault on this thread",
        TestSources.jniSource("htslibjni.c")
            .contains("throwException(env, \"java/lang/Error\", reported)"));
    final int ran = main.indexOf("hts_main2(");
    final int retracted = main.indexOf("runningOpt = NULL");
    final int stats = main.indexOf("hts_get_stats(");
    assertTrue("the destructor frees the opt, so a worker must not still hold it",
        ran != -1 && retracted > ran && retracted < stats);
  }
}
