package com.httrack.android;

import java.io.File;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.PersistableBundle;
import android.os.SystemClock;
import android.util.Log;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

import com.httrack.android.jni.HTTrackLib;
import com.httrack.android.jni.HTTrackStats;

/**
 * The crawl's owner from API 34 on. A user-initiated data transfer job keeps the engine running
 * once the user leaves the app, and drives the same {@link CrawlRun} the activity drives below 34.
 */
public final class MirrorJobService extends JobService {
  /** One crawl at a time, so one job id. */
  static final int JOB_ID = 0x48545241;

  static final String EXTRA_PROJECT_NAME = "com.httrack.android.job.projectName";
  static final String EXTRA_TARGET = "com.httrack.android.job.target";
  static final String EXTRA_PROJECT_ROOT = "com.httrack.android.job.projectRoot";
  static final String EXTRA_RESOURCES = "com.httrack.android.job.resources";
  static final String EXTRA_OPTIONS = "com.httrack.android.job.options";

  /** The shortest gap between two progress notifications. */
  static final long PROGRESS_INTERVAL_MS = 1000L;

  private final ProgressCoalescer<Frame> coalescer = new ProgressCoalescer<Frame>();
  private volatile CrawlRun crawl;
  private volatile String projectName = "";
  private long lastPostedMs = NotificationRate.NEVER;

  /**
   * Hand a crawl to the job. Needs the app visible, and the profile already written, because the
   * job takes the lock rather than the option map.
   *
   * @param context
   *          any context of this app
   * @param projectName
   *          the mirror's name, shown as the notification's title
   * @param target
   *          the mirror directory
   * @param projectRoot
   *          the directory holding every project
   * @param resources
   *          the unpacked help and template resources
   * @param options
   *          the engine options the option map emitted
   * @return true when the system accepted the job
   */
  static boolean schedule(final Context context, final String projectName, final File target,
      final File projectRoot, final File resources, final List<String> options) {
    if (target == null || projectRoot == null || resources == null) {
      return false;
    }
    final PersistableBundle extras = new PersistableBundle();
    extras.putString(EXTRA_PROJECT_NAME, projectName != null ? projectName : "");
    extras.putString(EXTRA_TARGET, target.getAbsolutePath());
    extras.putString(EXTRA_PROJECT_ROOT, projectRoot.getAbsolutePath());
    extras.putString(EXTRA_RESOURCES, resources.getAbsolutePath());
    extras.putStringArray(EXTRA_OPTIONS, options.toArray(new String[] {}));

    final JobInfo job = new JobInfo.Builder(JOB_ID,
        new ComponentName(context, MirrorJobService.class)).setUserInitiated(true)
        .setPriority(JobInfo.PRIORITY_MAX)
        // Mandatory: the connectivity constraint is what earns the Doze network bypass.
        .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
        // A large estimate on a slow link is what makes the scheduler refuse a job outright.
        .setEstimatedNetworkBytes(JobInfo.NETWORK_BYTES_UNKNOWN, JobInfo.NETWORK_BYTES_UNKNOWN)
        .setExtras(extras).build();

    final JobScheduler scheduler = context.getSystemService(JobScheduler.class);
    if (scheduler == null || scheduler.schedule(job) != JobScheduler.RESULT_SUCCESS) {
      Log.w("MirrorJobService", "the system refused the mirror job; the activity keeps the crawl");
      return false;
    }
    return true;
  }

  /**
   * Does the scheduler already hold our job?
   *
   * @param context
   *          any context of this app
   * @return true when a job is pending or running
   */
  static boolean isPending(final Context context) {
    final JobScheduler scheduler = context.getSystemService(JobScheduler.class);
    return scheduler != null && scheduler.getPendingJob(JOB_ID) != null;
  }

  @Override
  public boolean onStartJob(final JobParameters params) {
    final PersistableBundle extras = params.getExtras();
    projectName = string(extras, EXTRA_PROJECT_NAME);
    HTTrackActivity.createProgressChannel(this);
    setNotification(params, HTTrackActivity.PROGRESS_NOTIFICATION_ID,
        build(new Frame(getString(R.string.starting_mirror), 0, 0)),
        JobService.JOB_END_NOTIFICATION_POLICY_REMOVE);

    final File target = new File(string(extras, EXTRA_TARGET));
    final File projectRoot = new File(string(extras, EXTRA_PROJECT_ROOT));
    final File resources = new File(string(extras, EXTRA_RESOURCES));
    final String[] options = extras.getStringArray(EXTRA_OPTIONS);
    final CrawlRun run = new CrawlRun(getApplicationContext(),
        new JobOwner(target, projectRoot, resources, options),
        HTTrackActivity.crawlMessages(this));
    crawl = run;
    new Thread(new Runnable() {
      @Override
      public void run() {
        runCrawl(params, run, projectRoot.getAbsolutePath());
      }
    }, "httrack-crawl").start();
    return true;
  }

  @Override
  public boolean onStopJob(final JobParameters params) {
    final CrawlRun run = crawl;
    if (run != null) {
      run.stopMirror(true);
    }
    // Always false until the retry argv forces a resume, or a rescheduled job re-downloads it all.
    return false;
  }

  /* The whole run, on its own thread, ending with the call that gives the exemptions back. */
  private void runCrawl(final JobParameters params, final CrawlRun run, final String rootPath) {
    try {
      // Only a fresh process needs the root: initRootPath truncates log.txt, and an activity in
      // this one has already pointed the engine at it.
      final boolean freshProcess = !HTTrackLib.loadedSuccessfully();
      if (HTTrackLib.loadLibraries() && freshProcess) {
        HTTrackLib.initRootPath(rootPath);
      }
      run.runMirror();
    } catch (final Throwable t) {
      HTTrackActivity.emergencyDump(getApplicationContext(), t);
    } finally {
      run.end();
      jobFinished(params, false);
    }
  }

  private static String string(final PersistableBundle extras, final String key) {
    final String value = extras.getString(key);
    return value != null ? value : "";
  }

  /** One notification's worth of progress, kept until the clock lets it be drawn. */
  private static final class Frame {
    final String text;
    final int scanned;
    final int total;

    Frame(final String text, final long scanned, final long total) {
      this.text = text;
      this.scanned = clamp(scanned);
      this.total = clamp(total);
    }

    private static int clamp(final long value) {
      return value < 0 ? 0 : value > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) value;
    }
  }

  /* No extras: a tap restoring them would overwrite the option map the user has since edited. */
  private PendingIntent launcherIntent() {
    final Intent intent = new Intent(getApplicationContext(), HTTrackActivity.class);
    intent.setAction(Intent.ACTION_MAIN);
    intent.addCategory(Intent.CATEGORY_LAUNCHER);
    return PendingIntent.getActivity(this, 0, intent,
        PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
  }

  private Notification build(final Frame frame) {
    return new NotificationCompat.Builder(this, HTTrackActivity.PROGRESS_CHANNEL_ID)
        .setContentTitle(projectName).setContentText(frame.text)
        .setProgress(frame.total, frame.scanned, frame.total == 0).setOnlyAlertOnce(true)
        .setSmallIcon(R.drawable.ic_stat_httrack).setContentIntent(launcherIntent()).build();
  }

  /* Called from the crawl thread on every engine refresh, and drawn at most once per second. */
  private void post(final Frame frame, final boolean finalFrame) {
    coalescer.offerNeedsPost(frame);
    final long now = SystemClock.elapsedRealtime();
    if (!NotificationRate.shouldPost(lastPostedMs, now, PROGRESS_INTERVAL_MS, finalFrame)) {
      return;
    }
    final Frame newest = coalescer.take();
    if (newest == null) {
      return;
    }
    lastPostedMs = now;
    try {
      NotificationManagerCompat.from(this)
          .notify(HTTrackActivity.PROGRESS_NOTIFICATION_ID, build(newest));
    } catch (final SecurityException refused) {
      // The user never granted POST_NOTIFICATIONS; the crawl carries on regardless.
      Log.w(getClass().getSimpleName(), "could not post the progress notification", refused);
    }
  }

  /* The counters the progress pane shows, as one plain line. */
  private String counters(final HTTrackStats stats) {
    final String sep = " • ";
    return getString(R.string.links_scanned) + ": " + stats.linksScanned + "/" + stats.linksTotal
        + sep + getString(R.string.files_written) + ": " + stats.filesWritten + sep
        + getString(R.string.bytes_saved) + ": " + stats.bytesWritten;
  }

  /** The crawl's way back to the user when no window is on screen. */
  private final class JobOwner implements CrawlRun.Owner {
    private final File target;
    private final File projectRoot;
    private final File resources;
    private final List<String> options;

    JobOwner(final File target, final File projectRoot, final File resources,
        final String[] options) {
      this.target = target;
      this.projectRoot = projectRoot;
      this.resources = resources;
      this.options = options != null ? Arrays.asList(options) : new ArrayList<String>();
    }

    @Override
    public void checkAttached() throws IOException {
    }

    @Override
    public File target() {
      return target;
    }

    @Override
    public File projectRoot() {
      return projectRoot;
    }

    @Override
    public File resources() {
      return resources;
    }

    @Override
    public File createProfileDirectory() throws IOException {
      final File profile = HTTrackActivity.getProfileFile(target);
      final File cache = profile.getParentFile();
      if (!HTTrackActivity.mkdirs(target)) {
        throw new IOException("Unable to create " + target.getAbsolutePath());
      }
      if (!HTTrackActivity.mkdirs(cache)) {
        throw new IOException("Unable to create " + cache);
      }
      HTTrackActivity.setFileReadWrite(target);
      HTTrackActivity.setFileReadWrite(cache);
      return profile;
    }

    @Override
    public List<String> options() throws IOException {
      return options;
    }

    @Override
    public void serializeProfile(final FileChannel channel, final File profile)
        throws IOException {
      // Already on disk: the activity wrote it before scheduling, while its widgets were live.
    }

    @Override
    public void onProgress(final String[] lines) {
      post(new Frame(lines.length != 0 ? lines[0] : "", 0, 0), false);
    }

    @Override
    public void onStats(final HTTrackStats stats) {
      post(new Frame(counters(stats), stats.linksScanned, stats.linksTotal), false);
    }

    @Override
    public void onFinished(final String message, final long errorsCount, final File mirrorFolder) {
      post(new Frame(getString(R.string.mirror_finished), 0, 0), true);
    }
  }
}
