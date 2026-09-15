package com.httrack.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.Before;
import org.junit.Test;

/** What the shade is left holding. A progress notification must die with the crawl behind it, and
 *  a stop the user never asked for has to say so. */
public class NotificationLifecycleTest {
  /** Anything of the shape X.cancel(...) or X.cancelAll(...), whatever X is. */
  private static final Pattern CANCEL = Pattern.compile("\\.cancel(All)?\\s*\\(");

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

  /** Every cancel the app makes, receiver and argument kept and sorted, so a second spelling of
   *  one shows up as an entry of its own rather than passing as the cancel already allowed. */
  private static List<String> cancelCalls() throws IOException {
    final List<String> calls = new ArrayList<String>();
    for (final File file : TestSources.javaSources()) {
      final String source = TestSources.withoutCommentsAndStrings(TestSources.read(file));
      final Matcher call = CANCEL.matcher(source);
      while (call.find()) {
        calls.add(norm(source.substring(statementStart(source, call.start()),
            closingParen(source, call.end() - 1) + 1)));
      }
    }
    Collections.sort(calls);
    return calls;
  }

  /* Offset just past the statement boundary before AT, so the receiver comes along. */
  private static int statementStart(final String source, final int at) {
    for (int i = at; i > 0; i--) {
      final char before = source.charAt(i - 1);
      if (before == ';' || before == '{' || before == '}') {
        return i;
      }
    }
    return 0;
  }

  /* Offset of the ')' matching the '(' at OPEN. */
  private static int closingParen(final String source, final int open) {
    int depth = 0;
    for (int i = open; i < source.length(); i++) {
      if (source.charAt(i) == '(') {
        depth++;
      } else if (source.charAt(i) == ')' && --depth == 0) {
        return i;
      }
    }
    throw new IllegalStateException("unclosed call at " + open);
  }

  /* The count is process-wide, so whatever ran before this case would otherwise seed it. */
  @Before
  public void noExecutionIsHeld() throws Exception {
    final Field field = MirrorJobService.class.getDeclaredField("executions");
    field.setAccessible(true);
    ((AtomicInteger) field.get(null)).set(0);
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
    final int cancel = TestSources.indexOf(create, "cancelStaleProgressNotification();");
    assertEquals("onStart and onResume run again while a crawl is live, where onCreate does not,"
        + " so a cancel nested in a branch is one a relaunch can skip", cancel,
        firstAtDepthZero(create, "cancelStaleProgressNotification();"));
    assertFalse("and one below a return is one the launch that finds the stale frame never reaches",
        Pattern.compile("\\breturn\\b").matcher(create.substring(0, cancel)).find());
  }

  /* Offset of the first TOKEN that is a statement of BODY itself, or BODY's length for none. */
  private static int firstAtDepthZero(final String body, final String token) {
    int depth = 0;
    for (int i = 0; i < body.length(); i++) {
      final char c = body.charAt(i);
      if (c == '{') {
        depth++;
      } else if (c == '}') {
        depth--;
      } else if (depth == 0 && body.startsWith(token, i)) {
        return i;
      }
    }
    return body.length();
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
  }

  /** A cancel takes a notification off the user's screen. The progress id is the only one a crawl
   *  owns; the finished and abort ones carry clock-derived ids and are the user's to dismiss. */
  @Test
  public void nothingCancelsAnyOtherNotification() throws IOException {
    assertEquals("a cancel of any other id, however it is spelled, wipes what the user came back"
        + " to read", "[MirrorJobService.cancel(this), "
        + "NotificationManagerCompat.from(this).cancel(PROGRESS_NOTIFICATION_ID), "
        + "scheduler.cancel(JOB_ID)]", cancelCalls().toString());
  }

  /** The execution outlives its crawl at both ends, and the launch that arrives in between must
   *  find the notification owned rather than stale. */
  @Test
  public void anExecutionOwnsTheNotificationBeforeItsCrawlDoes() throws IOException {
    final String job = source("MirrorJobService");
    assertEquals("one place counts an execution in", 1,
        TestSources.occurrences(job, "executionStarted();"));
    assertEquals("one place counts it back out", 1,
        TestSources.occurrences(job, "executionEnded();"));
    final String start = body(job, "public boolean onStartJob(final JobParameters params)");
    assertTrue("counted in before the thread starts, or the crawl can outrun the count",
        TestSources.indexOf(start, "executionStarted();")
            < TestSources.indexOf(start, "new Thread("));
    assertEquals("an execution that ends in a branch would hold the count for good", 0,
        TestSources.depthOf(start, "executionStarted();"));
    final String run = body(job, "private void runCrawl(final JobParameters params, "
        + "final CrawlRun run, final boolean earlierLive,\n      final boolean freshProcess, "
        + "final String rootPath)");
    assertTrue("counted out before the call that removes the notification with the job",
        TestSources.indexOf(run, "executionEnded();")
            < TestSources.indexOf(run, "jobFinished("));
    assertEquals("one reader, so no second caller can answer the question differently", 2,
        occurrencesInApp("isExecuting()"));
  }

  /** Two executions overlap whenever one starts while its predecessor is still winding down, and
   *  the older one's end must not take the notification off the crawl the newer one is making. */
  @Test
  public void theOlderExecutionEndingLeavesTheNewerHoldingIt() {
    assertFalse("no execution holds a process the job never ran in",
        MirrorJobService.isExecuting());
    MirrorJobService.executionStarted();
    MirrorJobService.executionStarted();
    MirrorJobService.executionEnded();
    assertTrue("the one still crawling owns the notification", MirrorJobService.isExecuting());
    MirrorJobService.executionEnded();
    assertFalse("and the last one leaves nothing behind", MirrorJobService.isExecuting());
  }

  /** A second execution can land on the same service instance, which the system never killed the
   *  process of, and a verdict it never explained must not be suppressed by the first one's. */
  @Test
  public void aSecondExecutionStartsWithNothingToldToTheUser() throws IOException {
    final String start = body(source("MirrorJobService"),
        "public boolean onStartJob(final JobParameters params)");
    for (final String reset : new String[] { "toldTheUser = false;",
        "lastPostedMs = NotificationRate.NEVER;", "coalescer.disarm();" }) {
      assertEquals(reset + " belongs to onStartJob itself, not to a branch of it", 0,
          TestSources.depthOf(start, reset));
    }
    assertTrue("cleared before the crawl thread can set it again",
        TestSources.indexOf(start, "toldTheUser = false;")
            < TestSources.indexOf(start, "new Thread("));
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
