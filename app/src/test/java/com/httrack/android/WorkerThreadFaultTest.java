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
  /** htslibjni.c with comments and string literals blanked, so a commented-out call cannot pass
   *  for one. */
  private String jni() throws IOException {
    return TestSources.withoutCommentsAndStrings(TestSources.jniSource("htslibjni.c"));
  }

  /** Body of the static function NAME, braces balanced and the outer pair left out. */
  private static String function(final String source, final String name) {
    final Matcher m = Pattern.compile("(?m)^[\\w *]+\\b" + name + "\\s*\\(")
        .matcher(source);
    assertTrue("no function " + name, m.find());
    return TestSources.balancedBlock(source, m.end());
  }

  /** Body of the JNI entry point NAME. */
  private static String entryPoint(final String source, final String name) {
    final int at = source.indexOf("Java_com_httrack_android_jni_HTTrackLib_" + name + "(");
    assertTrue("no entry point " + name, at != -1);
    return TestSources.balancedBlock(source, at);
  }

  /** Body of the CONDITION block inside SOURCE. */
  private static String guard(final String source, final String condition) {
    final int at = source.indexOf(condition);
    assertTrue("no " + condition, at != -1);
    return TestSources.balancedBlock(source, at);
  }

  @Test
  public void theRunnerIsInstalledBeforeAnythingCanSpawnAWorker() throws IOException {
    final String source = jni();
    assertEquals(1, TestSources.occurrences(source, "hts_set_thread_runner("));
    assertEquals("workerThreadRunner",
        TestSources.arguments(source, "hts_set_thread_runner").trim());
    assertTrue("the engine reads the runner unlocked, so it must be set at class load",
        entryPoint(source, "initStatic").contains("hts_set_thread_runner("));
  }

  @Test
  public void onlyASignalCountsAsAFault() throws IOException {
    final String runner = function(jni(), "workerThreadRunner");
    assertTrue("COFFEE_CATCH is entered on a setup failure too, with the engine intact",
        guard(runner, "if (coffeecatch_get_signal() > 0)").contains("reportWorkerFault()"));
  }

  @Test
  public void aWorkerWhoseBodyNeverRanRunsItUnprotected() throws IOException {
    final String runner = function(jni(), "workerThreadRunner");
    final int end = runner.indexOf("COFFEE_END()");
    assertTrue(end != -1);
    assertTrue("dropping the body would hand the caller a worker that did nothing",
        guard(runner.substring(end), "if (unprotected)").contains("fun(arg)"));
  }

  @Test
  public void theMessageIsWrittenBeforeTheFlagThatPublishesIt() throws IOException {
    final String report = function(jni(), "reportWorkerFault");
    final int written = report.indexOf("snprintf(workerFaultMessage");
    final int published = report.indexOf("__ATOMIC_RELEASE");
    assertTrue("the crawl thread reads the message once it sees the flag",
        written != -1 && published > written);
    assertTrue("every entry point back into the engine reads this one",
        report.contains("engineFaulted = 1"));
  }

  @Test
  public void theFaultEndsTheMirrorAndKeepsWhatAContinueNeeds() throws IOException {
    final String source = jni();
    assertTrue("0 would report the mirror as complete and drop the resume metadata",
        function(source, "reportWorkerFault").contains("hts_request_stop(runningOpt, 1)"));
    assertTrue("the progress callback aborts the mirror by returning 0",
        guard(function(source, "htsshow_loop"), "if (hasWorkerFaulted())").contains("return 0"));
  }

  @Test
  public void theWorkerLeavesTheWatchdogToTheCrawlThread() throws IOException {
    final String source = jni();
    final String runner = function(source, "workerThreadRunner");
    assertFalse("a worker that unwound nothing may hold a lock the crawl needs, and the"
        + " watchdog is the only thing that would then end the process",
        runner.contains("coffeecatch_cancel_pending_alarm")
            || runner.contains("clearWorkerFaultWatchdog"));
    assertTrue("with no crawl to end, nothing waits on this worker and nothing would disarm it",
        guard(function(source, "reportWorkerFault"), "if (runningOpt != NULL)").length() > 0
            && function(source, "reportWorkerFault").contains("clearWorkerFaultWatchdog()"));
  }

  @Test
  public void theCrawlThreadReportsTheFaultAndDisarmsTheWatchdog() throws IOException {
    final String source = jni();
    final String main = function(source, "HTTrackLib_main");
    final String reported = guard(main, "if (hasWorkerFaulted())");
    assertTrue(reported.contains("workerFaultMessage")
        && reported.contains("clearWorkerFaultWatchdog()"));
    assertTrue("java.lang.Error is what coffeecatch throws for a fault on this thread",
        TestSources.jniSource("htslibjni.c")
            .contains("throwException(env, \"java/lang/Error\", workerFaultMessage)"));
    final int ran = main.indexOf("hts_main2(");
    final int retracted = main.indexOf("runningOpt = NULL");
    final int stats = main.indexOf("hts_get_stats(");
    assertTrue("the destructor frees the opt, so a worker must not still hold it",
        ran != -1 && retracted > ran && retracted < stats);
  }
}
