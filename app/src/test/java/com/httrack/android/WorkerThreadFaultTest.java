package com.httrack.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.Test;

/** The engine spawns detached threads for itself, a DNS resolver per hostname and an FTP fetch,
 *  and no JNI entry point covers them, so a fault there used to take the process down. The
 *  thread runner catches it on the worker, and the crawl thread is what reports it to Java.
 *
 *  Assertions here compare whole statement lists rather than searching for text, because a
 *  search cannot tell `f()` from `if (0) { f(); }`. */
public class WorkerThreadFaultTest {
  /** htslibjni.c as written. */
  private static String raw() throws IOException {
    return TestSources.jniSource("htslibjni.c");
  }

  /** htslibjni.c with comments and string literals blanked, offsets unchanged, so a
   *  commented-out call cannot pass for one and a raw slice still lines up. */
  private static String blanked() throws IOException {
    return TestSources.withoutCommentsAndStrings(raw());
  }

  /** Offsets of the block starting at the first '{' at or after AT, the braces left out. */
  private static int[] blockAt(final String source, final int at) {
    final int from = source.indexOf('{', at);
    assertTrue("no block at " + at, from != -1);
    int depth = 0;
    for (int i = from; i < source.length(); i++) {
      if (source.charAt(i) == '{') {
        depth++;
      } else if (source.charAt(i) == '}' && --depth == 0) {
        return new int[] { from + 1, i };
      }
    }
    throw new IllegalStateException("block at " + from + " never closes");
  }

  /** Offsets of function NAME's body. */
  private static int[] function(final String source, final String name) {
    final Matcher m = Pattern.compile("(?m)^[\\w *]+\\b" + name + "\\s*\\(").matcher(source);
    assertTrue("no function " + name, m.find());
    return blockAt(source, m.end());
  }

  /** Offsets of CONDITION's own block inside RANGE. */
  private static int[] thenBlock(final String source, final int[] range, final String condition) {
    final int at = source.indexOf(condition, range[0]);
    assertTrue("no " + condition, at != -1 && at < range[1]);
    return blockAt(source, at);
  }

  /** Offsets of the block CONDITION falls through to, which must directly follow its own. */
  private static int[] elseBlock(final String source, final int[] range, final String condition) {
    final int[] taken = thenBlock(source, range, condition);
    final String between = source.substring(taken[1] + 1);
    assertTrue(condition + " has no else", between.trim().startsWith("else"));
    return blockAt(source, taken[1] + 1 + between.indexOf("else"));
  }

  /** Statements of RANGE, in order, stripped of spaces, braces and preprocessor lines. A
   *  statement wrapped in a new block keeps that block's condition, so it reads as one entry
   *  no expected list matches. */
  private static List<String> statements(final String source, final int[] range) {
    final String body = source.substring(range[0], range[1])
        .replaceAll("(?m)^\\s*#.*$", "");
    final List<String> out = new ArrayList<String>();
    for (final String statement : body.split(";")) {
      final String bare = statement.replaceAll("[\\s{}]+", "");
      if (!bare.isEmpty()) {
        out.add(bare);
      }
    }
    return out;
  }

  private static String text(final String source, final int[] range) {
    return source.substring(range[0], range[1]);
  }

  @Test
  public void theRunnerIsInstalledBeforeAnythingCanSpawnAWorker() throws IOException {
    final String source = blanked();
    assertEquals(1, TestSources.occurrences(source, "hts_set_thread_runner("));
    assertTrue("the engine reads the runner unlocked, so it must be set at class load",
        statements(source,
            function(source, "Java_com_httrack_android_jni_HTTrackLib_initStatic"))
            .contains("hts_set_thread_runner(workerThreadRunner)"));
  }

  @Test
  public void onlyASignalCountsAsAFault() throws IOException {
    final String source = blanked();
    final int[] runner = function(source, "workerThreadRunner");
    final String gate = "if (coffeecatch_get_signal() > 0)";
    assertEquals("COFFEE_CATCH is entered on a setup failure too, with the engine intact",
        Arrays.asList("reportWorkerFault()"),
        statements(source, thenBlock(source, runner, gate)));
    assertEquals("a body that faulted has already run, and running it again would fault again",
        Arrays.asList("bodyNeverRan=1"),
        statements(source, elseBlock(source, runner, gate)));
  }

  @Test
  public void aWorkerWhoseBodyNeverRanRunsItUnprotected() throws IOException {
    final String source = blanked();
    final int[] runner = function(source, "workerThreadRunner");
    final int end = source.indexOf("COFFEE_END()", runner[0]);
    assertTrue(end != -1 && end < runner[1]);
    assertEquals("dropping the body would hand the caller a worker that did nothing",
        Arrays.asList("fun(arg)"),
        statements(source, thenBlock(source, new int[] { end, runner[1] }, "if (bodyNeverRan)")));
  }

  @Test
  public void theLatchIsSetBeforeAnythingThatCanWedge() throws IOException {
    final String source = blanked();
    final String report = text(source, function(source, "reportWorkerFault"));
    final int latched = report.indexOf("engineFaulted = 1");
    assertTrue(latched != -1);
    for (final String wedges : new String[] { "error(", "coffeecatch_get_backtrace_info(",
        "MUTEX_LOCK(runningOptLock)" }) {
      assertTrue(wedges + " can block on a lock this thread faulted holding, and every guard"
          + " on the engine reads the flag", report.indexOf(wedges) > latched);
    }
    assertTrue("the crawl thread reads the message once it sees the flag",
        report.indexOf("__ATOMIC_RELEASE") > report.indexOf("snprintf(workerFaultMessage"));
  }

  @Test
  public void theFaultEndsTheMirrorAndKeepsWhatAContinueNeeds() throws IOException {
    final String source = blanked();
    final int[] report = function(source, "reportWorkerFault");
    final String stopping = "if (!alreadyFaulted && runningOpt != NULL)";
    assertEquals("0 would report the mirror as complete and drop the resume metadata",
        Arrays.asList("hts_request_stop(runningOpt,1)"),
        statements(source, thenBlock(source, report, stopping)));
    final String body = text(source, report);
    assertTrue("runningOpt is read under its own lock, which is what keeps the crawl thread"
        + " from retracting it mid-call",
        body.indexOf("MUTEX_LOCK(runningOptLock)") < body.indexOf(stopping)
            && body.indexOf(stopping) < body.indexOf("MUTEX_UNLOCK(runningOptLock)"));
  }

  @Test
  public void aPoisonedOptIsNeverStopped() throws IOException {
    final String source = blanked();
    final String report = text(source, function(source, "reportWorkerFault"));
    assertTrue("any other expression here would stop an opt the crawl thread faulted out of",
        report.contains("const int alreadyFaulted = engineFaulted;"));
    assertTrue("read after the latch, the snapshot would always be true",
        report.indexOf("alreadyFaulted = engineFaulted")
            < report.indexOf("engineFaulted = 1"));
  }

  @Test
  public void theWatchdogIsDisarmedOnceAndLast() throws IOException {
    final String source = blanked();
    assertEquals("a second caller would disarm a watchdog another fault still needs", 1,
        TestSources.occurrences(source, "clearWorkerFaultWatchdog()"));
    final List<String> report = statements(source, function(source, "reportWorkerFault"));
    assertEquals("everything above it can block on a lock this thread faulted holding, and"
        + " the watchdog is what ends the process when one does",
        "clearWorkerFaultWatchdog()", report.get(report.size() - 1));
  }

  @Test
  public void theMirrorEndsAtTheNextTick() throws IOException {
    final String source = blanked();
    assertEquals("the progress callback aborts the mirror by returning 0",
        Arrays.asList("return0"),
        statements(source, thenBlock(source, function(source, "htsshow_loop"),
            "if (hasWorkerFaulted())")));
  }

  @Test
  public void theCrawlThreadReportsTheFault() throws IOException {
    final String source = blanked();
    assertTrue("a plain load would let the crawl thread read the message half-written",
        text(source, function(source, "hasWorkerFaulted")).contains("__ATOMIC_ACQUIRE"));
    final int[] main = function(source, "HTTrackLib_main");
    final int[] reported = thenBlock(source, main, "if (hasWorkerFaulted())");
    assertTrue("an exception is already pending and JNI forbids a second one",
        statements(source, reported).contains("code=-1"));
    final int[] thrown = thenBlock(source, reported, "if (!(*env)->ExceptionCheck(env))");
    assertEquals("getSafeCopy() sizes its buffer from one read and copies on a second, so a"
        + " second faulting worker could grow the string in between",
        Arrays.asList("charreported[sizeof(workerFaultMessage)]",
            "snprintf(reported,sizeof(reported),,workerFaultMessage)",
            "throwException(env,,reported)"),
        statements(source, thrown));
    // The class is a string literal, which the blanked source cannot see, so read the raw
    // text. A commented-out call would satisfy that on its own, hence the count.
    final String slice = text(raw(), thrown);
    assertEquals(1, TestSources.occurrences(slice, "throwException("));
    assertTrue("java.lang.Error is what coffeecatch throws for a fault on this thread",
        slice.contains("\"java/lang/Error\""));
    final String body = text(source, main);
    final int ran = body.indexOf("hts_main2(");
    final int retracted = body.indexOf("runningOpt = NULL");
    assertTrue("the destructor frees the opt, so a worker must not still hold it",
        ran != -1 && retracted > ran && retracted < body.indexOf("hts_get_stats("));
  }
}
