package com.httrack.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import org.junit.Test;

/** What the shade is left holding. A progress notification must die with the crawl behind it, and
 *  a stop the user never asked for has to say so. */
public class NotificationLifecycleTest {
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

  /** A process killed between a frame and the end of its job leaves the last frame on screen, and
   *  only the next launch can clear it. */
  @Test
  public void aLaunchClearsAProgressNotificationNothingIsBehind() throws IOException {
    final String activity = source("HTTrackActivity");
    assertEquals("one declaration and one call, and onCreate is where a relaunch passes", 2,
        occurrencesInApp("cancelStaleProgressNotification()"));
    final String create = body(activity,
        "protected void onCreate(final Bundle savedInstanceState)");
    assertTrue("onStart and onResume run again while a crawl is live, where onCreate does not",
        create.contains("cancelStaleProgressNotification();"));
    assertEquals("a cancel nested in a branch is one a relaunch can skip", 0,
        TestSources.depthOf(create, "cancelStaleProgressNotification();"));
  }

  /** Cancelling one a live crawl is still repainting takes the only sign of that crawl off the
   *  screen, so every reason to believe something is running has to reach the decision. */
  @Test
  public void theCancelAsksBothOwnersFirst() throws IOException {
    final String cancel = body(source("HTTrackActivity"),
        "private void cancelStaleProgressNotification()");
    assertEquals("MirrorSession.get().live() != null, MirrorJobService.isExecuting()",
        norm(TestSources.arguments(cancel, "NotificationRate.cancelsStale")));
    assertEquals("the cancel is the branch's, not the method's", 1,
        TestSources.depthOf(cancel, ".cancel("));
    assertEquals("the progress id is the only one a crawl owns; the finished and abort "
        + "notifications carry clock-derived ids and are the user's to dismiss", 1,
        occurrencesInApp("NotificationManagerCompat.from(this).cancel(PROGRESS_NOTIFICATION_ID)"));
    assertEquals("and no other id may be cancelled anywhere", 1,
        occurrencesInApp("NotificationManagerCompat.from(this).cancel("));
  }

  /** The execution outlives its crawl at both ends, and the launch that arrives in between must
   *  find the notification owned rather than stale. */
  @Test
  public void anExecutionOwnsTheNotificationBeforeItsCrawlDoes() throws IOException {
    final String job = source("MirrorJobService");
    assertEquals("one place sets it", 1, TestSources.occurrences(job, "executing = true;"));
    assertEquals("one place clears it", 1, TestSources.occurrences(job, "executing = false;"));
    final String start = body(job, "public boolean onStartJob(final JobParameters params)");
    assertTrue("set before the thread starts, or the crawl can outrun the flag",
        TestSources.indexOf(start, "executing = true;") < TestSources.indexOf(start, "new Thread("));
    assertEquals("an execution that ends in a branch would leave the flag set for good", 0,
        TestSources.depthOf(start, "executing = true;"));
    final String run = body(job, "private void runCrawl(final JobParameters params, "
        + "final CrawlRun run, final boolean earlierLive,\n      final boolean freshProcess, "
        + "final String rootPath)");
    assertTrue("cleared before the call that removes the notification with the job",
        TestSources.indexOf(run, "executing = false;") < TestSources.indexOf(run, "jobFinished("));
    assertEquals("one reader, so no second caller can answer the question differently", 2,
        occurrencesInApp("isExecuting()"));
  }

  /** Two stop reasons abandon the mirror without the user having asked us to, and the crawl just
   *  stops with nothing on screen to explain it. */
  @Test
  public void theTwoUnaskedAbandonsTellTheUser() throws IOException {
    final String stop = body(source("MirrorJobService"),
        "public boolean onStopJob(final JobParameters params)");
    assertEquals("stopReason", norm(TestSources.arguments(stop, "JobStopPolicy.tellsTheUser")));
    assertEquals("getApplicationContext(), projectName",
        norm(TestSources.arguments(stop, "HTTrackActivity.sendStoppedNotification")));
    assertTrue("a message outside the branch would fire on our own Stop button too",
        TestSources.depthOf(stop, "HTTrackActivity.sendStoppedNotification(") > 0);
    assertTrue("the flag has to be set where the message is, or the verdict says it again",
        TestSources.depthOf(stop, "toldTheUser = true;") > 0);
    assertEquals("one caller of the policy, and one message", 1,
        occurrencesInApp("JobStopPolicy.tellsTheUser("));
    assertEquals(1, occurrencesInApp("HTTrackActivity.sendStoppedNotification("));
  }

  /** Progress is silent by design, so a message the user must read cannot go to that channel. */
  @Test
  public void theStopMessageGoesToTheChannelThatBuzzes() throws IOException {
    final String activity = source("HTTrackActivity");
    final String stopped = body(activity,
        "static void sendStoppedNotification(final Context context, final String projectName)");
    assertEquals("context, context.getString(R.string.mirror_xxx_stopped).replace( , projectName), "
        + "context.getString(R.string.mirror_stopped_will_not_resume)",
        norm(TestSources.arguments(stopped, "postOnMirrorChannel")));
    final String post = body(activity, "private static void postOnMirrorChannel(final Context "
        + "context, final CharSequence title,\n      final CharSequence text)");
    assertEquals("the channel every message the user must read goes to", 1,
        TestSources.occurrences(post, "NOTIFICATION_CHANNEL_ID)"));
    assertEquals("IMPORTANCE_LOW would leave the stop unheard", 0,
        TestSources.occurrences(post, "PROGRESS_CHANNEL_ID"));
    assertEquals("the finished message shares the builder, so both keep the same channel", 1,
        TestSources.occurrences(body(activity, "static void sendFinishedNotification(final Context "
            + "context, final String projectName,\n      final String message)"),
            "postOnMirrorChannel("));
  }

  /** The string is what the user actually reads, and no other locale is checked in. */
  @Test
  public void theStopMessageIsDeclaredOnce() throws IOException {
    final String strings = TestSources.read(TestSources.resFile("values/strings.xml"));
    assertEquals("one declaration", 1,
        TestSources.occurrences(strings, "<string name=\"mirror_stopped_will_not_resume\">"));
    assertEquals("one reader", 1, occurrencesInApp("R.string.mirror_stopped_will_not_resume"));
    assertEquals("the title is the one an abort already used", 1,
        TestSources.occurrences(strings, "<string name=\"mirror_xxx_stopped\">"));
  }
}
