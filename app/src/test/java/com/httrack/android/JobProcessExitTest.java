package com.httrack.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
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

  /** The count is what tells a job process from one the user is looking at, and it has to survive
   *  a rotation, which creates the replacement activity before destroying the old one. */
  @Test
  public void theProcessHoldsAWindowWhileAnyActivityLives() {
    assertFalse("a process the system started for the job alone",
        HTTrackApplication.hasLiveActivity());
    HTTrackApplication.activityCreated();
    assertTrue(HTTrackApplication.hasLiveActivity());
    HTTrackApplication.activityCreated();
    HTTrackApplication.activityDestroyed();
    assertTrue("the replacement outlives the activity it replaced",
        HTTrackApplication.hasLiveActivity());
    HTTrackApplication.activityDestroyed();
    assertFalse("and the last one leaves nothing behind", HTTrackApplication.hasLiveActivity());
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
    assertTrue("the exit must follow the call that gives the exemptions back",
        TestSources.indexOf(run, "jobFinished(") < TestSources.indexOf(run, "System.exit("));
    assertEquals("HTTrackLib.hasFaulted(), HTTrackApplication.hasLiveActivity()",
        norm(TestSources.arguments(run, "NativeFaultPolicy.exitAfterJob")));
    assertEquals("the exit belongs to the guard inside the finally: an unguarded one, or one a "
        + "throw could skip, kills or strands every run", 2,
        TestSources.depthOf(run, "System.exit("));
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
