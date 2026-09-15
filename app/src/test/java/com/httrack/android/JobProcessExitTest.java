package com.httrack.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Before;
import org.junit.Test;

/** A crawl that faulted in a process no window holds. Nothing else ends such a process, and every
 *  later execution the system hands it would refuse to crawl. */
public class JobProcessExitTest {
  private static String source(final String name) throws IOException {
    return TestSources.withoutCommentsAndStrings(TestSources.javaSource(name));
  }

  private static String body(final String source, final String signature) {
    final int at = source.indexOf(signature);
    assertTrue("no " + signature.trim(), at != -1);
    return TestSources.balancedBlock(source, at + signature.length());
  }

  private static String norm(final String text) {
    return text.replaceAll("\\s+", " ").trim();
  }

  /** How often TEXT appears across the whole app, so a second caller in another file counts. */
  private static int occurrencesInApp(final String text) throws IOException {
    int found = 0;
    for (final File file : TestSources.javaSources()) {
      found += TestSources.occurrences(
          TestSources.withoutCommentsAndStrings(TestSources.read(file)), text);
    }
    return found;
  }

  /* The count is process-wide, so whatever ran before this case would otherwise seed it. */
  @Before
  public void theCountStartsEmpty() throws Exception {
    final Field field = HTTrackApplication.class.getDeclaredField("liveActivities");
    field.setAccessible(true);
    ((AtomicInteger) field.get(null)).set(0);
  }

  /** The count is what tells a job process from one the user is looking at, so a second activity
   *  of ours, such as the options pane, has to keep it up when the first one goes. */
  @Test
  public void theProcessHoldsAWindowWhileAnyActivityLives() {
    assertFalse("a process the system started for the job alone",
        HTTrackApplication.hasLiveActivity());
    HTTrackApplication.activityCreated();
    assertTrue(HTTrackApplication.hasLiveActivity());
    HTTrackApplication.activityCreated();
    HTTrackApplication.activityDestroyed();
    assertTrue("the one still up holds the process", HTTrackApplication.hasLiveActivity());
    HTTrackApplication.activityDestroyed();
    assertFalse("and the last one leaves nothing behind", HTTrackApplication.hasLiveActivity());
  }

  /** A configuration change destroys the old activity before it creates the replacement, so the
   *  count passes through zero with the user still sitting in front of the app. */
  @Test
  public void aRotationTakesTheCountThroughZero() throws IOException {
    HTTrackApplication.activityCreated();
    HTTrackApplication.activityDestroyed();
    assertFalse("the old activity goes first, so a reader landing here sees no window at all",
        HTTrackApplication.hasLiveActivity());
    HTTrackApplication.activityCreated();
    assertTrue("and the replacement arrives after", HTTrackApplication.hasLiveActivity());
    // That zero is out of reach only for a reader on the thread the count is written on.
    final String exit = body(source("MirrorJobService"), "private void endFaultedProcess()");
    assertEquals("the decision has to be posted to the thread the lifecycle callbacks run on",
        "Looper.getMainLooper()", norm(TestSources.arguments(exit, "new Handler")));
    assertTrue("a count read before the post is read off the crawl thread again",
        TestSources.indexOf(exit, ".post(")
            < TestSources.indexOf(exit, "HTTrackApplication.hasLiveActivity()"));
  }

  /** A second activity of ours, such as the options or the cleanup pane, counts as a window: the
   *  user is in the app, and ending the process under them loses what they were editing. */
  @Test
  public void anyActivityCountsNotJustTheMirrorOne() throws IOException {
    final String application = source("HTTrackApplication");
    assertTrue("every activity must be counted, which is what the application-wide callbacks are"
        + " for", norm(body(application, "public void onActivityCreated(final Activity activity, "
        + "final Bundle savedInstanceState)")).startsWith("activityCreated();"));
    assertEquals("activityDestroyed();", norm(body(application,
        "public void onActivityDestroyed(final Activity activity)")));
    assertEquals("one declaration and one call, or a create without its destroy leaks the count",
        2, occurrencesInApp("activityCreated("));
    assertEquals("and a destroy without its create ends the process while a window is up", 2,
        occurrencesInApp("activityDestroyed("));
  }

  /** The exit belongs after jobFinished: the notification and the exemptions are the system's to
   *  take back, and a process ending first leaves the job looking abandoned. */
  @Test
  public void theFaultedProcessEndsAfterTheJobDoes() throws IOException {
    final String job = source("MirrorJobService");
    assertEquals("one exit in the job", 1, TestSources.occurrences(job, "System.exit("));
    final String run = body(job, "private void runCrawl(final JobParameters params, "
        + "final CrawlRun run, final boolean earlierLive,\n      final boolean freshProcess, "
        + "final String rootPath)");
    assertTrue("the exit must be asked for after the call that gives the exemptions back",
        TestSources.indexOf(run, "jobFinished(")
            < TestSources.indexOf(run, "endFaultedProcess()"));
    assertEquals("the ask belongs to the finally itself: one a throw could skip strands the run",
        1, TestSources.depthOf(run, "endFaultedProcess()"));
    final String exit = body(job, "private void endFaultedProcess()");
    assertEquals("HTTrackLib.hasFaulted(), HTTrackApplication.hasLiveActivity()",
        norm(TestSources.arguments(exit, "NativeFaultPolicy.exitAfterJob")));
    assertEquals("the exit belongs to the guard: an unguarded one kills every run", 3,
        TestSources.depthOf(exit, "System.exit("));
    assertEquals("nothing else may ask whether a window is attached", 2,
        occurrencesInApp("hasLiveActivity("));
  }

  /** Both exits stay: the window's own destroy is still the only thing that ends the process the
   *  user was looking at, and neither call site may answer for the other. */
  @Test
  public void theWindowKeepsItsOwnExit() throws IOException {
    assertEquals("one call, from the activity's onDestroy", 1,
        occurrencesInApp("NativeFaultPolicy.exitOnDestroy("));
    assertEquals("one call, from the job's run", 1,
        occurrencesInApp("NativeFaultPolicy.exitAfterJob("));
    assertEquals("isFinishing(), exitWhenDestroyed, HTTrackLib.hasFaulted()", norm(
        TestSources.arguments(source("HTTrackActivity"), "NativeFaultPolicy.exitOnDestroy")));
  }
}
