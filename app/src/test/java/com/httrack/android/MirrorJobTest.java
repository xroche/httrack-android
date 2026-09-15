package com.httrack.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** The job that owns the crawl from API 34 on. Its lifecycle is device-only, so what is left
 *  here is the wiring a wrong edit would break silently. */
public class MirrorJobTest {
  @Rule
  public final TemporaryFolder tmp = new TemporaryFolder();

  /** A project whose hts-cache exists, as createProfileDirectory leaves it before it asks. */
  private File project(final String name) throws IOException {
    final File target = tmp.newFolder(name);
    assertTrue(new File(target, "hts-cache").mkdirs());
    return target;
  }

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

  /** How often TEXT appears across the whole app, so a second caller in another file counts. */
  private static int occurrencesInApp(final String text) throws IOException {
    int found = 0;
    for (final File file : TestSources.javaSources()) {
      found += TestSources.occurrences(
          TestSources.withoutCommentsAndStrings(TestSources.read(file)), text);
    }
    return found;
  }

  /** How many times REGEX matches SOURCE, so no spelling of a call escapes the count. */
  private static int matches(final String source, final String regex) {
    final Matcher found = Pattern.compile(regex).matcher(source);
    int count = 0;
    while (found.find()) {
      count++;
    }
    return count;
  }

  /** Body of the CrawlRun method NAME, or null when CrawlRun declares no such method. Only a
   *  declaration of the class itself matches, never a call inside another body. */
  private static String crawlRunBody(final String crawl, final String name) {
    final Matcher at = Pattern.compile("(?m)^  [A-Za-z@<][\\w<>\\[\\], ]*\\b" + name
        + "\\s*\\(").matcher(crawl);
    return at.find() ? TestSources.balancedBlock(crawl, at.end()) : null;
  }

  /** Every name BODY calls, keywords that also take parentheses left out. */
  private static List<String> callees(final String body) {
    final List<String> names = new ArrayList<String>();
    final Matcher call = Pattern.compile("([A-Za-z_][A-Za-z0-9_]*)\\s*\\(").matcher(body);
    final List<String> keywords = java.util.Arrays.asList("if", "for", "while", "switch", "catch",
        "synchronized", "return", "new");
    while (call.find()) {
      if (!keywords.contains(call.group(1))) {
        names.add(call.group(1));
      }
    }
    return names;
  }

  /** onStopJob's body plus every CrawlRun body it reaches, so a disk write one call deep is
   *  inside what this certifies rather than outside it. */
  private static String stopPath() throws IOException {
    final String crawl = source("CrawlRun");
    final StringBuilder reached = new StringBuilder(body(source("MirrorJobService"),
        "public boolean onStopJob(final JobParameters params)"));
    final List<String> pending = new ArrayList<String>();
    pending.add("stopMirror");
    final Set<String> seen = new HashSet<String>();
    while (!pending.isEmpty()) {
      final String name = pending.remove(0);
      if (!seen.add(name)) {
        continue;
      }
      final String declared = crawlRunBody(crawl, name);
      if (declared != null) {
        reached.append(declared);
        pending.addAll(callees(declared));
      }
    }
    assertTrue("the closure never reached CrawlRun.stopMirror", seen.size() > 1);
    return reached.toString();
  }

  private static String manifest() throws IOException {
    return TestSources.read(TestSources.mainFile("AndroidManifest.xml"));
  }

  /** The manifest with its XML comments blanked, so a commented-out declaration reads as the
   *  absence it really is. */
  private static String liveManifest() throws IOException {
    return manifest().replaceAll("(?s)<!--.*?-->", " ");
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

  /** Without this permission JobScheduler.schedule throws on the connectivity constraint the job
   *  needs, so every Start from API 34 on died on the main thread. */
  @Test
  public void theManifestDeclaresAccessNetworkState() throws IOException {
    assertEquals("the permission the connectivity constraint requires", 1,
        TestSources.occurrences(liveManifest(),
            "<uses-permission android:name=\"android.permission.ACCESS_NETWORK_STATE\" />"));
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

  /** A job the system starts in a fresh process has loaded no native library, and CrawlRun's
   *  engine field calls native init() the moment it is constructed. */
  @Test
  public void onStartJobLoadsTheEngineBeforeItBuildsTheCrawl() throws IOException {
    final String body = body(source("MirrorJobService"),
        "public boolean onStartJob(final JobParameters params)");
    assertEquals("no load in onStartJob leaves a fresh process to crash on native init()", 1,
        TestSources.occurrences(body, "HTTrackLib.loadLibraries()"));
    assertEquals("one construction, so one ordering to hold", 1,
        TestSources.occurrences(body, "new CrawlRun("));
    assertEquals("a load nested in a branch is a load a fresh process can skip", 0,
        TestSources.depthOf(body, "HTTrackLib.loadLibraries()"));
    assertTrue("the libraries must be loaded before anything constructs a CrawlRun",
        TestSources.indexOf(body, "HTTrackLib.loadLibraries()")
            < TestSources.indexOf(body, "new CrawlRun("));
    assertTrue("a load that fails must end the job, never fall through to the engine",
        norm(body).contains("if (!HTTrackLib.loadLibraries()) {"));
    assertTrue("the notification still comes first, load or no load",
        TestSources.indexOf(body, "setNotification(")
            < TestSources.indexOf(body, "HTTrackLib.loadLibraries()"));
  }

  /** Stop the engine, return the verdict. Nothing else: onStopJob has an 8 second budget and an
   *  unconditional ANR, and the disk write that used to break it sat one call deep. */
  @Test
  public void onStopJobOnlyStopsTheEngine() throws IOException {
    final String body = body(source("MirrorJobService"),
        "public boolean onStopJob(final JobParameters params)");
    assertEquals("the only engine call is the stop", 1,
        TestSources.occurrences(body, "stopMirror("));
    assertEquals("the hard stop is the one that keeps hts-cache for a later Continue", "true",
        norm(TestSources.arguments(body, "run.stopMirror")));
    assertNonePresent("nothing the stop reaches may block the caller or write a file", stopPath(),
        ".join(", ".wait(", "Thread.sleep", "jobFinished", "runMirror", "setNotification",
        "notify(", "setInterruptedProfile", "RandomAccessFile", "FileOutputStream", "mkdirs(",
        "createNewFile", "buildTopIndex");
    assertEquals("a synchronized stop waits on the monitor every engine refresh takes", 0,
        TestSources.occurrences(source("CrawlRun"), "synchronized boolean stopMirror"));
  }

  /** The reason decides, not the code path: a Task Manager stop must stay stopped and a timeout
   *  must come back. */
  @Test
  public void onStopJobReturnsThePolicysVerdict() throws IOException {
    final String body = body(source("MirrorJobService"),
        "public boolean onStopJob(final JobParameters params)");
    assertTrue("the reason is read once, from the parameters the system handed us",
        norm(body).contains("final int stopReason = params.getStopReason();"));
    assertEquals("stopReason", norm(TestSources.arguments(body, "JobStopPolicy.reschedules")));
    assertEquals("the verdict must be the method's own statement, not a branch's", 0,
        TestSources.depthOf(body, "return JobStopPolicy.reschedules("));
    assertEquals("one way out, or a branch could abandon the mirror by itself", 1,
        matches(body, "\\breturn\\b"));
    assertNonePresent("a hard-coded verdict is what the policy replaced", body, "return false;",
        "return true;");
  }

  /** The engine raises HTS_CACHE_PRIORITY on finding its own lock and the command line overrides
   *  it again, so an argv reaching the engine unrewritten downloads the mirror a second time. */
  @Test
  public void theEngineOnlyEverSeesARewrittenArgv() throws IOException {
    final String crawl = source("CrawlRun");
    final String run = body(crawl, "void runMirror()");
    assertEquals("CrawlArgv.build(HTTrackActivity.isIPv6Enabled(), target.getAbsolutePath(), "
        + "options), owner.resumesInterrupted()",
        norm(TestSources.arguments(run, "ResumeArgv.forStart")));
    assertEquals("the rewritten argv is the one the engine is given", "cargs",
        norm(TestSources.arguments(run, "engine.main")));
    assertEquals("one build in the whole app, or a second one skips the rewrite", 1,
        occurrencesInApp("CrawlArgv.build("));
    assertEquals("one rewrite", 1, occurrencesInApp("ResumeArgv.forStart("));
    assertEquals("one way into the engine", 1, occurrencesInApp("engine.main("));
  }

  /** Only a retry rewrites: a first attempt over an interrupted mirror is the user asking for the
   *  Continue or the Update the setup pane offered them. */
  @Test
  public void onlyARetryOverAnUnfinishedMirrorResumes() throws IOException {
    assertEquals("return false;", norm(body(source("HTTrackActivity"),
        "public boolean resumesInterrupted()")));
    assertEquals("return retried && HTTrackActivity.isInterruptedProfile(target);",
        norm(body(source("MirrorJobService"), "public boolean resumesInterrupted()")));
  }

  /** A killed process runs no finally, so only a file on disk can tell the next execution that it
   *  is a retry, and only the start the user asks for may clear it. */
  @Test
  public void everyUserStartClearsTheAttemptStamp() throws IOException {
    final String job = source("MirrorJobService");
    assertEquals("the stamp belongs beside the engine's own locks, out of the served mirror", 1,
        TestSources.occurrences(TestSources.javaSource("MirrorJobService"),
            "return new File(new File(target, \"hts-cache\"), \"job-attempt.lock\");"));
    final String schedule = body(job, "static boolean schedule(final Context context,");
    assertTrue("the stamp must be cleared before the job is handed to the scheduler",
        TestSources.indexOf(schedule, "attemptFile(target).delete()")
            < TestSources.indexOf(schedule, "new JobInfo.Builder("));
    assertEquals("one declaration, one clear and one read; a second clear would hide a retry", 3,
        TestSources.occurrences(job, "attemptFile("));
    assertTrue("the stamp is taken where hts-cache already exists",
        body(job, "public File createProfileDirectory() throws IOException")
            .contains("retried = alreadyAttempted(target);"));
    assertEquals("one declaration and one call: one execution takes one stamp", 2,
        TestSources.occurrences(job, "alreadyAttempted("));
    assertNonePresent("onStartJob has ten seconds and may not read the disk",
        body(job, "public boolean onStartJob(final JobParameters params)"), "alreadyAttempted",
        "attemptFile");
  }

  /** The answer the whole stage turns on: false means the user asked for this start, so their own
   *  Continue or Update stands, and true means replay it as a resume. */
  @Test
  public void theAttemptStampReadsFalseOnceAndTrueAfter() throws IOException {
    final File target = project("stamped");
    final File stamp = MirrorJobService.attemptFile(target);
    assertFalse("nothing has run yet, so this start is the user's own",
        MirrorJobService.alreadyAttempted(target));
    assertTrue("the first read must leave the stamp behind", stamp.exists());
    assertTrue("the next execution is a retry", MirrorJobService.alreadyAttempted(target));
    assertTrue("and so is every one after it", MirrorJobService.alreadyAttempted(target));
  }

  /** A stamp an earlier execution left is read, never rewritten. */
  @Test
  public void anExistingStampIsReadAsARetry() throws IOException {
    final File target = project("existing");
    final File stamp = MirrorJobService.attemptFile(target);
    assertTrue(stamp.createNewFile());
    assertTrue(MirrorJobService.alreadyAttempted(target));
    assertTrue("the stamp must outlive the read", stamp.exists());
  }

  /** schedule() clears the stamp without asking whether one was there, so a delete that finds
   *  nothing must still leave a first attempt reading as one. */
  @Test
  public void clearingTheStampMakesTheNextStartAFirstAttemptAgain() throws IOException {
    final File target = project("cleared");
    final File stamp = MirrorJobService.attemptFile(target);
    assertFalse("nothing to clear yet", stamp.delete());
    assertFalse(MirrorJobService.alreadyAttempted(target));
    assertTrue("the clear the user's next Start performs", stamp.delete());
    assertFalse("their choice must stand again", MirrorJobService.alreadyAttempted(target));
  }

  /** Unstamped has to read as a first attempt: replaying the user's argv copies more than it
   *  needs to, where resuming a mirror they never interrupted loses the choice they made. */
  @Test
  public void aStampThatCannotBeWrittenReadsAsAFirstAttempt() throws IOException {
    final File target = tmp.newFolder("nocache");
    assertFalse("no hts-cache, so createNewFile can only throw",
        MirrorJobService.attemptFile(target).getParentFile().exists());
    assertFalse(MirrorJobService.alreadyAttempted(target));
    assertFalse("and the answer may not drift on the execution after it",
        MirrorJobService.alreadyAttempted(target));
  }

  /** The job's Doze, quota and network exemptions end with this call, so a crawl still running
   *  after it has none of them. */
  @Test
  public void theRunEndsWithJobFinished() throws IOException {
    final String body = runCrawlBody();
    assertTrue("the crawl must run before the job is finished",
        TestSources.indexOf(body, "run.runMirror()") < TestSources.indexOf(body, "jobFinished("));
    assertEquals("only the faulted-process exit may follow the call that gives them back",
        "jobFinished(params, JobStopPolicy.reschedulesRefusedStart(run.wasRefusedInProgress(), "
            + "earlierLive)); endFaultedProcess(); }",
        norm(body.substring(TestSources.indexOf(body, "jobFinished("))));
    assertEquals("one call, or an early one would strand a live crawl", 1,
        TestSources.occurrences(body, "jobFinished("));
  }

  /** A retry that lands while the crawl it is retrying is still winding down is refused by that
   *  wind-down, and finishing there abandons the mirror the retry existed to save. */
  @Test
  public void aRetryRefusedByItsOwnPredecessorComesBack() throws IOException {
    assertEquals("run.wasRefusedInProgress(), earlierLive",
        norm(TestSources.arguments(runCrawlBody(), "JobStopPolicy.reschedulesRefusedStart")));
    final String start = body(source("MirrorJobService"),
        "public boolean onStartJob(final JobParameters params)");
    assertEquals("read from the slot the new run is about to take", 1, TestSources.occurrences(
        start, "final boolean earlierLive = crawl != null && !crawl.isEnded();"));
    assertTrue("read after the assignment it would answer for the new run instead",
        TestSources.indexOf(start, "final boolean earlierLive")
            < TestSources.indexOf(start, "crawl = run;"));
    assertEquals("the only way the flag is set is the refusal itself", 1, TestSources.occurrences(
        source("CrawlRun"), "refusedInProgress = true;"));
  }

  /** runCrawl's body, whose signature the reschedule argument is part of. */
  private static String runCrawlBody() throws IOException {
    return body(source("MirrorJobService"), "private void runCrawl(final JobParameters params, "
        + "final CrawlRun run, final boolean earlierLive,\n      final boolean freshProcess, "
        + "final String rootPath)");
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

  /** JobScheduler.schedule throws when the system refuses the job, uncaught and on the main
   *  thread. CrawlOwnerPolicy.startsInActivity can only hand the crawl back if schedule returns. */
  @Test
  public void aRefusedScheduleReturnsRatherThanThrows() throws IOException {
    final String body = body(source("MirrorJobService"),
        "static boolean schedule(final Context context,");
    assertEquals("one call to the scheduler, or a second one escapes the guard", 1,
        matches(body, "scheduler\\s*\\.\\s*schedule\\s*\\("));
    final int tryAt = body.lastIndexOf("try {", TestSources.indexOf(body, "scheduler.schedule("));
    assertTrue("the scheduler call must sit inside a try", tryAt != -1);
    final String guarded = TestSources.balancedBlock(body, tryAt);
    assertEquals("the guarded block must hold the call and nothing else",
        "verdict = scheduler.schedule(job);", norm(guarded));
    final int closing = body.indexOf(guarded, tryAt) + guarded.length();
    assertTrue("both refusals must be caught, or a device that says no kills the app: "
        + norm(body.substring(closing)), norm(body.substring(closing))
            .startsWith("} catch (final SecurityException | IllegalArgumentException refused)"));
    assertEquals("a caught refusal must report the failure, or the activity never takes over", 1,
        TestSources.occurrences(TestSources.balancedBlock(body, closing), "return false;"));
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
    // Counted by pattern: a second call spelled startRunner( ) escapes a literal count.
    assertEquals("one declaration and one call, or a second hands the activity a job-owned crawl",
        2, matches(activity, "\\bstartRunner\\s*\\("));
    assertEquals("one declaration and one caller of the schedule helper", 2,
        matches(activity, "\\bscheduleMirrorJob\\s*\\("));
    assertEquals("the starter must be the only place a RunnerFragment is created, or the "
        + "fragment's onDestroy stop could fire against a job-owned crawl", 1,
        matches(body(activity, "protected synchronized void startRunner()"),
            "new\\s+RunnerFragment\\s*\\("));
    assertEquals("no fragment is created anywhere else", 1,
        matches(activity, "new\\s+RunnerFragment\\s*\\("));
  }
}
