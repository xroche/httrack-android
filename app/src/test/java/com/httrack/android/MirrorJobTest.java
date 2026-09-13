package com.httrack.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

/** The job that owns the crawl from API 34 on. Its lifecycle is device-only, so what is left
 *  here is the wiring a wrong edit would break silently. */
public class MirrorJobTest {
  private static String source(final String name) throws IOException {
    return TestSources.withoutCommentsAndStrings(TestSources.javaSource(name));
  }

  /** Body of the method whose declaration starts with SIGNATURE, braces balanced. */
  private static String body(final String source, final String signature) {
    final int at = source.indexOf(signature);
    assertTrue("no " + signature.trim(), at != -1);
    return TestSources.balancedBlock(source, at + signature.length());
  }

  private static String norm(final String text) {
    return text.replaceAll("\\s+", " ").trim();
  }

  private static String manifest() throws IOException {
    return TestSources.read(TestSources.mainFile("AndroidManifest.xml"));
  }

  /** The progress branch of onEnterNewPane, where either owner is chosen. */
  private static String progressBranch(final String activity) {
    return TestSources.between(body(activity, "protected void onEnterNewPane()"),
        "case R.layout.activity_mirror_progress:", "case R.layout.activity_mirror_finished:");
  }

  /** Every missing item at once: one failing name is a sample of size one. */
  private static void assertAllPresent(final String what, final String haystack,
      final String... needles) {
    final List<String> missing = new ArrayList<String>();
    for (final String needle : needles) {
      if (!haystack.contains(needle)) {
        missing.add(needle);
      }
    }
    assertEquals(what, "[]", missing.toString());
  }

  private static void assertNonePresent(final String what, final String haystack,
      final String... needles) {
    final List<String> found = new ArrayList<String>();
    for (final String needle : needles) {
      if (haystack.contains(needle)) {
        found.add(needle);
      }
    }
    assertEquals(what, "[]", found.toString());
  }

  @Test
  public void theManifestDeclaresThePermissionAndTheService() throws IOException {
    final String manifest = manifest();
    assertEquals("the permission that lets a job be user-initiated", 1, TestSources.occurrences(
        manifest, "<uses-permission android:name=\"android.permission.RUN_USER_INITIATED_JOBS\""));
    final String service = TestSources.between(manifest, "<service", "/>");
    assertAllPresent("the service entry", service, "android:name=\".MirrorJobService\"",
        "android:exported=\"false\"",
        "android:permission=\"android.permission.BIND_JOB_SERVICE\"");
  }

  /** Play's foreground-service declaration is keyed to a service type on API 34 or later, and a
   *  rejection blocks the whole release. A user-initiated job needs neither. */
  @Test
  public void theManifestDeclaresNoForegroundService() throws IOException {
    final String manifest = manifest();
    assertEquals("a foregroundServiceType would need a Play declaration", 0,
        TestSources.occurrences(manifest, "foregroundServiceType"));
    assertEquals("the FOREGROUND_SERVICE permissions would need one too", 0,
        TestSources.occurrences(manifest, "android.permission.FOREGROUND_SERVICE"));
  }

  /** An app can only ever lower a channel's importance, so both ids and IMPORTANCE_LOW are
   *  permanent for anyone who already installed the app. */
  @Test
  public void theProgressChannelIsItsOwnAndSilent() throws IOException {
    final String raw = TestSources.javaSource("HTTrackActivity");
    assertAllPresent("both channel ids, each declared once", raw,
        "String NOTIFICATION_CHANNEL_ID = \"mirror\";",
        "String PROGRESS_CHANNEL_ID = \"mirror-progress\";");
    assertEquals("one progress notification id", 1,
        TestSources.occurrences(raw, "int PROGRESS_NOTIFICATION_ID = 1;"));
    final String channel = body(TestSources.withoutCommentsAndStrings(raw),
        "static void createProgressChannel(final Context context)");
    assertEquals("PROGRESS_CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_LOW",
        norm(TestSources.arguments(channel, "NotificationChannelCompat.Builder")));
    assertEquals("the buzzing channel must not be reused here", 0,
        TestSources.occurrences(channel, "NOTIFICATION_CHANNEL_ID,"));
  }

  /** The system allows ten seconds and the ANR for missing it is unconditional, so nothing may
   *  precede the call but reading the extras and registering the channel. */
  @Test
  public void onStartJobPostsItsNotificationFirst() throws IOException {
    final String body = body(source("MirrorJobService"),
        "public boolean onStartJob(final JobParameters params)");
    final String[] statements = body.split(";");
    assertTrue("onStartJob is shorter than the four statements it must open with",
        statements.length >= 4);
    assertEquals("final PersistableBundle extras = params.getExtras()", norm(statements[0]));
    assertEquals("projectName = string(extras, EXTRA_PROJECT_NAME)", norm(statements[1]));
    assertEquals("HTTrackActivity.createProgressChannel(this)", norm(statements[2]));
    assertEquals("setNotification(params, HTTrackActivity.PROGRESS_NOTIFICATION_ID, "
        + "build(new Frame(getString(R.string.starting_mirror), 0, 0)), "
        + "JobService.JOB_END_NOTIFICATION_POLICY_REMOVE)", norm(statements[3]));
    assertEquals("the call must be the method's own, not nested in a branch", 0,
        TestSources.depthOf(body, "setNotification("));
    assertTrue("the crawl thread must start after the notification, never before",
        TestSources.indexOf(body, "setNotification(") < TestSources.indexOf(body, "new Thread("));
    assertNonePresent("onStartJob must not block the main thread", body, ".join(", ".wait(",
        "Thread.sleep", "runMirror", "RandomAccessFile", "tryLock", "mkdirs");
  }

  /** Read getStopReason(), stop the engine, return the verdict. Nothing else: a flush or a join
   *  here is an ANR. */
  @Test
  public void onStopJobOnlyStopsTheEngine() throws IOException {
    final String body = body(source("MirrorJobService"),
        "public boolean onStopJob(final JobParameters params)");
    assertNonePresent("onStopJob must not do any of this", body, ".join(", ".wait(",
        "Thread.sleep", "jobFinished", "runMirror", "setNotification", "notify(");
    assertEquals("the only engine call is the stop", 1,
        TestSources.occurrences(body, "stopMirror("));
    assertEquals("the hard stop is the one that keeps hts-cache for a later Continue", "true",
        norm(TestSources.arguments(body, "run.stopMirror")));
  }

  /** Stage 4 is what makes a retry resume. Until the argv forces the resume mode, a rescheduled
   *  job would replay it and download the mirror again. */
  @Test
  public void onStopJobAsksForNoRetryYet() throws IOException {
    final String body = body(source("MirrorJobService"),
        "public boolean onStopJob(final JobParameters params)");
    assertEquals("one verdict", 1, TestSources.occurrences(body, "return false;"));
    assertEquals("no path may ask for a retry", 0, TestSources.occurrences(body, "return true;"));
    assertEquals("JobStopPolicy is stage 4's to wire, not stage 3's", 0,
        TestSources.occurrences(body, "JobStopPolicy"));
    assertEquals("the verdict must be the method's own last statement, not a branch's", 0,
        TestSources.depthOf(body, "return false;"));
  }

  /** The job's Doze, quota and network exemptions end with this call, so a crawl still running
   *  after it has none of them. */
  @Test
  public void theRunEndsWithJobFinished() throws IOException {
    final String body = body(source("MirrorJobService"), "private void runCrawl(final "
        + "JobParameters params, final CrawlRun run, final String rootPath)");
    assertTrue("the crawl must run before the job is finished",
        TestSources.indexOf(body, "run.runMirror()") < TestSources.indexOf(body, "jobFinished("));
    assertEquals("nothing may follow the call that gives the exemptions back",
        "jobFinished(params, false); }",
        norm(body.substring(TestSources.indexOf(body, "jobFinished("))));
    assertEquals("one call, or an early one would strand a live crawl", 1,
        TestSources.occurrences(body, "jobFinished("));
  }

  /** The connectivity constraint is what earns the Doze network bypass, and build() rejects a
   *  user-initiated job that sets a deadline, a latency, idleness or prefetch. */
  @Test
  public void theJobInfoEarnsItsExemptions() throws IOException {
    final String schedule = norm(body(source("MirrorJobService"),
        "static boolean schedule(final Context context,"));
    assertAllPresent("the JobInfo a user-initiated data transfer needs", schedule,
        ".setUserInitiated(true)", ".setPriority(JobInfo.PRIORITY_MAX)",
        ".setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)",
        ".setEstimatedNetworkBytes(JobInfo.NETWORK_BYTES_UNKNOWN, JobInfo.NETWORK_BYTES_UNKNOWN)");
    assertNonePresent("build() rejects a user-initiated job that sets any of these", schedule,
        "setOverrideDeadline", "setMinimumLatency", "setPeriodic", "setPrefetch",
        "setRequiresDeviceIdle", "setRequiresCharging", "setRequiresBatteryNotLow",
        "setRequiresStorageNotLow", "setBackoffCriteria");
    assertTrue("the scheduled job must carry the id the pending check looks for",
        schedule.contains("new JobInfo.Builder(JOB_ID,"));
    assertTrue("the pending check must look for that same id",
        body(source("MirrorJobService"), "static boolean isPending(final Context context)")
            .contains("getPendingJob(JOB_ID)"));
  }

  /** A tap must reach a bare launcher intent: an extras-carrying one would restore its bundle
   *  over the option map the user has since edited. */
  @Test
  public void theProgressNotificationCarriesNoExtras() throws IOException {
    final String job = source("MirrorJobService");
    final String intent = body(job, "private PendingIntent launcherIntent()");
    assertNonePresent("the tap intent must carry nothing", intent, "putExtra", "putExtras",
        "saveInstanceState", "Bundle");
    assertAllPresent("the bare launcher intent", intent, "Intent.ACTION_MAIN",
        "Intent.CATEGORY_LAUNCHER");
    assertEquals("a second intent is how extras would come back", 1,
        TestSources.occurrences(job, "new Intent("));
  }

  @Test
  public void theNotificationShowsTheProjectAndItsCounters() throws IOException {
    final String build = norm(body(source("MirrorJobService"),
        "private Notification build(final Frame frame)"));
    assertAllPresent("the notification's content", build,
        "NotificationCompat.Builder(this, HTTrackActivity.PROGRESS_CHANNEL_ID)",
        ".setContentTitle(projectName)", ".setContentText(frame.text)",
        ".setProgress(frame.total, frame.scanned, frame.total == 0)", ".setOnlyAlertOnce(true)",
        ".setSmallIcon(R.drawable.ic_stat_httrack)");
    assertEquals("the Task Manager already offers Stop, and an action would need extras", 0,
        TestSources.occurrences(build, "addAction"));
  }

  /** One post per engine refresh is the flood #194 took out of the progress pane. */
  @Test
  public void everyFrameGoesThroughTheCoalescerAndTheClock() throws IOException {
    final String job = source("MirrorJobService");
    final String post = body(job, "private void post(final Frame frame, final boolean finalFrame)");
    assertEquals("lastPostedMs, now, PROGRESS_INTERVAL_MS, finalFrame",
        norm(TestSources.arguments(post, "NotificationRate.shouldPost")));
    assertEquals("the newest frame is the one drawn", 1,
        TestSources.occurrences(post, "coalescer.offerNeedsPost(frame)"));
    assertEquals(1, TestSources.occurrences(post, "coalescer.take()"));
    assertTrue("the clock must be read before the notification manager",
        TestSources.indexOf(post, "shouldPost(") < TestSources.indexOf(post, ".notify("));
    assertEquals("one way to the notification manager, or a frame would bypass the gate", 1,
        TestSources.occurrences(job, ".notify("));
    assertEquals("at most one frame per second", 1,
        TestSources.occurrences(job, "PROGRESS_INTERVAL_MS = 1000L"));
  }

  /** The frame that reports the end must never be dropped, and every other one must be. */
  @Test
  public void onlyTheEndingFrameIsFinal() throws IOException {
    final String job = source("MirrorJobService");
    assertEquals("new Frame(getString(R.string.mirror_finished), 0, 0), true",
        norm(TestSources.arguments(body(job, "public void onFinished(final String message, "
            + "final long errorsCount, final File mirrorFolder)"), "post")));
    assertEquals("new Frame(counters(stats), stats.linksScanned, stats.linksTotal), false",
        norm(TestSources.arguments(body(job, "public void onStats(final HTTrackStats stats)"),
            "post")));
    assertEquals("new Frame(lines.length != 0 ? lines[0] : , 0, 0), false",
        norm(TestSources.arguments(body(job, "public void onProgress(final String[] lines)"),
            "post")));
  }

  /** Scheduling over a live job id cancels the execution it is running, so the check has to
   *  precede the call, and the profile has to be on disk before the job takes its lock. */
  @Test
  public void theActivityChecksBeforeItSchedules() throws IOException {
    final String helper = body(source("HTTrackActivity"),
        "protected synchronized boolean scheduleMirrorJob()");
    assertEquals("MirrorSession.get().live() != null, MirrorJobService.isPending(this)",
        norm(TestSources.arguments(helper, "CrawlOwnerPolicy.startsNewCrawl")));
    assertTrue("the two-owners check must precede the call that would cancel a live execution",
        TestSources.indexOf(helper, "startsNewCrawl(")
            < TestSources.indexOf(helper, "MirrorJobService.schedule("));
    assertTrue("winprofile.ini must be written while the widgets are still live",
        TestSources.indexOf(helper, "serialize();")
            < TestSources.indexOf(helper, "MirrorJobService.schedule("));
    assertEquals("this, mapper.getProjectName(), getTargetFile(), getProjectRootFile(), "
        + "getResourceFile(), buildCommandline()",
        norm(TestSources.arguments(helper, "MirrorJobService.schedule")));
  }

  /** The permission prompt runs first, so the job never has to ask for it itself. */
  @Test
  public void theSchedulingFollowsTheNotificationPrompt() throws IOException {
    final String branch = progressBranch(source("HTTrackActivity"));
    assertTrue("the prompt must precede the schedule",
        TestSources.indexOf(branch, "ensureNotificationsAreAllowed();")
            < TestSources.indexOf(branch, "scheduleMirrorJob()"));
    assertEquals("jobOwns, jobOwns && scheduleMirrorJob()",
        norm(TestSources.arguments(branch, "CrawlOwnerPolicy.startsInActivity")));
    assertEquals("android.os.Build.VERSION.SDK_INT",
        norm(TestSources.arguments(branch, "CrawlOwnerPolicy.ownsInJob")));
  }

  /** API 24 to 33 must behave exactly as before: the activity owns the crawl, and the retained
   *  fragment stops it on the way out. */
  @Test
  public void nothingNewRunsBelowThirtyFour() throws IOException {
    final String activity = source("HTTrackActivity");
    final String branch = progressBranch(activity);
    assertTrue("the schedule must be short-circuited, or a pre-34 device writes the profile "
        + "early and asks the scheduler for a job that cannot exist",
        norm(branch).contains("jobOwns && scheduleMirrorJob()"));
    assertEquals("one starter, or a second call hands the activity a job-owned crawl", 1,
        TestSources.occurrences(activity, "startRunner();"));
    assertEquals("one caller of the schedule helper", 1,
        TestSources.occurrences(activity, "&& scheduleMirrorJob()"));
    assertEquals("the starter must be the only place a RunnerFragment is created, or the "
        + "fragment's onDestroy stop could fire against a job-owned crawl", 1,
        TestSources.occurrences(
            body(activity, "protected synchronized void startRunner()"), "new RunnerFragment()"));
    assertEquals("no fragment is created anywhere else", 1,
        TestSources.occurrences(activity, "new RunnerFragment()"));
  }
}
